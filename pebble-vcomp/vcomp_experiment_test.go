package pebble

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"math/rand/v2"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"sort"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/cockroachdb/pebble/internal/base"
	"github.com/cockroachdb/pebble/internal/compact"
	"github.com/cockroachdb/pebble/internal/manifest"
	"github.com/cockroachdb/pebble/internal/randvar"
	"github.com/cockroachdb/pebble/objstorage"
	"github.com/cockroachdb/pebble/objstorage/objstorageprovider"
	"github.com/cockroachdb/pebble/sstable"
	"github.com/cockroachdb/pebble/vcomp"
	"github.com/cockroachdb/pebble/vfs"
)

const (
	vcompKeySize       = 24
	vcompModelError    = 8.0
	vcompFlushEntries  = 64 << 10
	vcompTargetSSTSize = 64 << 20
)

type vcompLevelResult struct {
	Level  int    `json:"level"`
	Tables uint64 `json:"tables"`
	Bytes  uint64 `json:"bytes"`
}

type vcompBaselineResult struct {
	WallSeconds        float64            `json:"wall_seconds"`
	AppBytes           uint64             `json:"app_bytes"`
	ValueBytes         uint64             `json:"value_bytes"`
	FlushBytes         uint64             `json:"flush_bytes"`
	CompactionBytes    uint64             `json:"compaction_bytes"`
	TotalSSTWriteBytes uint64             `json:"total_sst_write_bytes"`
	FinalSSTBytes      uint64             `json:"final_sst_bytes"`
	SSTWriteAmpApp     float64            `json:"sst_write_amp_app"`
	SSTRewriteFactor   float64            `json:"sst_rewrite_factor"`
	LogicalKeys        uint64             `json:"logical_keys"`
	ValueErrors        uint64             `json:"value_errors"`
	Levels             []vcompLevelResult `json:"levels"`
}

type vcompAccuracyResult struct {
	ExpectedKeys             uint64  `json:"expected_keys"`
	MaterializedKeys         uint64  `json:"materialized_keys"`
	IntersectionKeys         uint64  `json:"intersection_keys"`
	MissingKeys              uint64  `json:"missing_keys"`
	ExtraKeys                uint64  `json:"extra_keys"`
	Jaccard                  float64 `json:"jaccard"`
	KMVJobs                  uint64  `json:"kmv_jobs"`
	KMVMeanAbsPercentError   float64 `json:"kmv_mean_abs_percent_error"`
	KMVMedianAbsPercentError float64 `json:"kmv_median_abs_percent_error"`
	KMVP95AbsPercentError    float64 `json:"kmv_p95_abs_percent_error"`
	KMVMaxAbsPercentError    float64 `json:"kmv_max_abs_percent_error"`
}

type vcompVirtualResult struct {
	SimulationSeconds        float64             `json:"simulation_seconds_excluding_accuracy_trace"`
	AccuracyTraceSeconds     float64             `json:"accuracy_trace_seconds"`
	MaterializationSeconds   float64             `json:"materialization_seconds"`
	TotalSeconds             float64             `json:"total_seconds"`
	VirtualCompactions       uint64              `json:"virtual_compactions"`
	VirtualMoves             uint64              `json:"virtual_moves"`
	PredictedCompactionBytes uint64              `json:"predicted_compaction_output_bytes_avoided"`
	CertificationMovedKeys   uint64              `json:"final_certification_redistributed_keys"`
	MaterializedSSTBytes     uint64              `json:"materialized_sst_bytes"`
	FinalSSTBytes            uint64              `json:"final_sst_bytes"`
	SSTRewriteFactor         float64             `json:"sst_rewrite_factor"`
	LogicalKeys              uint64              `json:"logical_keys"`
	ValueErrors              uint64              `json:"value_errors"`
	Levels                   []vcompLevelResult  `json:"levels"`
	Accuracy                 vcompAccuracyResult `json:"accuracy"`
}

type vcompYCSBResult struct {
	Workload          string  `json:"workload"`
	KeyDistribution   string  `json:"key_distribution"`
	RequestedSeconds  float64 `json:"requested_seconds"`
	WallSeconds       float64 `json:"wall_seconds"`
	Concurrency       int     `json:"concurrency"`
	CacheBytes        int64   `json:"cache_bytes"`
	Operations        uint64  `json:"operations"`
	Hits              uint64  `json:"hits"`
	Misses            uint64  `json:"misses"`
	HitRate           float64 `json:"hit_rate"`
	OpsPerSecond      float64 `json:"ops_per_second"`
	LatencyP50Micros  float64 `json:"latency_p50_micros"`
	LatencyP95Micros  float64 `json:"latency_p95_micros"`
	LatencyP99Micros  float64 `json:"latency_p99_micros"`
	DBReadAmp         int     `json:"db_read_amp"`
	BlockCacheHits    int64   `json:"block_cache_hits"`
	BlockCacheMisses  int64   `json:"block_cache_misses"`
	BlockCacheHitRate float64 `json:"block_cache_hit_rate"`
}

type vcompYCSBComparison struct {
	Baseline vcompYCSBResult `json:"baseline"`
	Virtual  vcompYCSBResult `json:"virtual"`
}

type vcompExistingYCSBResult struct {
	DBRoot   string              `json:"db_root"`
	Keyspace uint64              `json:"keyspace"`
	YCSB     vcompYCSBComparison `json:"ycsb_c"`
}

type vcompExperimentResult struct {
	DBRoot         string               `json:"db_root"`
	Writes         uint64               `json:"writes"`
	Keyspace       uint64               `json:"keyspace"`
	ValueSize      int                  `json:"value_size"`
	KeySize        int                  `json:"key_size"`
	FlushEntries   int                  `json:"flush_entries"`
	Baseline       vcompBaselineResult  `json:"baseline"`
	Virtual        vcompVirtualResult   `json:"virtual"`
	YCSB           *vcompYCSBComparison `json:"ycsb_c,omitempty"`
	BaselineReused bool                 `json:"baseline_reused,omitempty"`
}

// vcompPaperLoadResult contains only the loading and state-fidelity metrics
// reported by the paper. Exact-key/value checks remain hard correctness gates,
// but are deliberately not exported as experimental metrics.
type vcompPaperLoadResult struct {
	DBRoot                       string                  `json:"db_root"`
	DatasetBytes                 uint64                  `json:"dataset_bytes"`
	Writes                       uint64                  `json:"writes"`
	KeySize                      int                     `json:"key_size"`
	ValueSize                    int                     `json:"value_size"`
	Configuration                vcompPaperLoadConfig    `json:"configuration"`
	Baseline                     vcompPaperLoadedState   `json:"baseline"`
	Virtual                      vcompPaperLoadedState   `json:"vcomp"`
	Deduplication                vcompPaperDedupAccuracy `json:"deduplication"`
	BaselineReused               bool                    `json:"baseline_reused,omitempty"`
	BaselineLoadMetricsAvailable bool                    `json:"baseline_load_metrics_available"`
}

type vcompPaperLoadConfig struct {
	MemTableBytes     uint64 `json:"memtable_bytes"`
	FlushEntries      int    `json:"flush_entries"`
	TargetSSTBytes    uint64 `json:"target_sst_bytes"`
	MaxBackgroundJobs int    `json:"max_background_jobs"`
	DiscreteCDF       bool   `json:"discrete_cdf"`
	SSTSizeModel      string `json:"sst_size_model"`
	WAL               bool   `json:"wal"`
	Compression       bool   `json:"compression"`
}

type vcompPaperLoadedState struct {
	LoadingSeconds      float64            `json:"loading_seconds"`
	TotalDiskWriteBytes uint64             `json:"total_disk_write_bytes"`
	WriteAmplification  float64            `json:"write_amplification"`
	FinalDBBytes        uint64             `json:"final_db_bytes"`
	SSTCount            uint64             `json:"sst_count"`
	AverageSSTBytes     float64            `json:"average_sst_bytes"`
	Levels              []vcompLevelResult `json:"levels"`
}

type vcompPaperDedupAccuracy struct {
	Jobs                  uint64  `json:"jobs"`
	MeanAbsPercentError   float64 `json:"mean_abs_percent_error"`
	MedianAbsPercentError float64 `json:"median_abs_percent_error"`
	P95AbsPercentError    float64 `json:"p95_abs_percent_error"`
	MaxAbsPercentError    float64 `json:"max_abs_percent_error"`
}

type vcompYCSBConfig struct {
	duration    time.Duration
	concurrency int
	cacheBytes  int64
}

// vcompLatencyHistogram uses 1 us buckets through 1 second. This keeps the
// measurement bounded and permits concurrent recording without retaining one
// latency value per operation during a multi-minute run.
type vcompLatencyHistogram struct {
	buckets []atomic.Uint64
}

const (
	vcompLatencyBucketWidth = time.Microsecond
	vcompLatencyMax         = time.Second
)

type vcompSimStats struct {
	virtualCompactions       uint64
	virtualMoves             uint64
	predictedCompactionBytes uint64
	accuracyDuration         time.Duration
	kmvErrors                []float64
}

type vcompSimState struct {
	opts          *Options
	files         [manifest.NumLevels][]*manifest.TableMetadata
	desc          map[base.TableNum]*vcomp.VirtualSST
	truth         map[base.TableNum][]uint64 // Accuracy trace only; never consulted by the algorithm.
	version       *manifest.Version
	latest        *latestVersionState
	traceAccuracy bool
	discreteCDF   bool
	sizeModel     vcomp.SSTSizeModel
	nextTable     base.TableNum
	nextSeq       base.SeqNum
	stats         vcompSimStats
}

type vcompBuiltTable struct {
	entry manifest.NewTableEntry
	size  uint64
	err   error
}

type vcompFinalTable struct {
	descriptor vcomp.VirtualSST
	seqNum     base.SeqNum
}

func vcompOptions() *Options {
	opts := &Options{
		DisableWAL:                  true,
		L0CompactionThreshold:       4,
		LBaseMaxBytes:               64 << 20,
		MemTableSize:                64 << 20,
		MemTableStopWritesThreshold: 4,
		CompactionConcurrencyRange:  func() (int, int) { return 1, 48 },
	}
	for i := range opts.TargetFileSizes {
		opts.TargetFileSizes[i] = vcompTargetSSTSize
	}
	opts.EnsureDefaults()
	for i := range opts.Levels {
		opts.Levels[i].Compression = func() *sstable.CompressionProfile { return sstable.NoCompression }
	}
	return opts
}

func vcompUserKey(key uint64) []byte {
	encoded := make([]byte, vcompKeySize)
	binary.BigEndian.PutUint64(encoded[vcompKeySize-8:], key)
	return encoded
}

func vcompDecodeUserKey(key []byte) (uint64, error) {
	if len(key) != vcompKeySize {
		return 0, fmt.Errorf("key length %d, want %d", len(key), vcompKeySize)
	}
	return binary.BigEndian.Uint64(key[len(key)-8:]), nil
}

func vcompSplitMix64(x uint64) uint64 {
	x += 0x9e3779b97f4a7c15
	x = (x ^ (x >> 30)) * 0xbf58476d1ce4e5b9
	x = (x ^ (x >> 27)) * 0x94d049bb133111eb
	return x ^ (x >> 31)
}

func vcompGeneratedKey(index, keyspace, seed uint64) uint64 {
	return vcompSplitMix64(seed+index) % keyspace
}

func vcompYCSBHashKey(key uint64) uint64 {
	const (
		offset64 = 14695981039346656037
		prime64  = 1099511628211
	)
	hash := uint64(offset64)
	for range 8 {
		hash *= prime64
		hash ^= key & 0xff
		key >>= 8
	}
	return hash
}

func newVCompLatencyHistogram() *vcompLatencyHistogram {
	return &vcompLatencyHistogram{
		buckets: make([]atomic.Uint64, int(vcompLatencyMax/vcompLatencyBucketWidth)+1),
	}
}

func (h *vcompLatencyHistogram) record(duration time.Duration) {
	index := int(duration / vcompLatencyBucketWidth)
	if index >= len(h.buckets) {
		index = len(h.buckets) - 1
	}
	h.buckets[index].Add(1)
}

func (h *vcompLatencyHistogram) percentile(total uint64, quantile float64) float64 {
	if total == 0 {
		return 0
	}
	target := uint64(math.Ceil(float64(total) * quantile))
	var cumulative uint64
	for i := range h.buckets {
		cumulative += h.buckets[i].Load()
		if cumulative >= target {
			return float64((time.Duration(i) * vcompLatencyBucketWidth).Microseconds())
		}
	}
	return float64(vcompLatencyMax.Microseconds())
}

func vcompYCSBConfigFromEnv(writes uint64, valueSize int) (vcompYCSBConfig, error) {
	config := vcompYCSBConfig{
		duration:    5 * time.Minute,
		concurrency: 48,
	}
	// The paper provisions a block cache equal to 5% of the requested dataset.
	// Cap it at 32 GiB on this 62 GiB host so the Go heap and OS have enough
	// headroom during the 1 TiB run.
	requestedBytes := writes * uint64(vcompKeySize+valueSize)
	config.cacheBytes = int64(min(requestedBytes/20, uint64(32<<30)))
	if value := os.Getenv("VCOMP_YCSB_DURATION"); value != "" {
		parsed, err := time.ParseDuration(value)
		if err != nil || parsed <= 0 {
			return vcompYCSBConfig{}, fmt.Errorf("invalid VCOMP_YCSB_DURATION %q", value)
		}
		config.duration = parsed
	}
	if value := os.Getenv("VCOMP_YCSB_CONCURRENCY"); value != "" {
		parsed, err := strconv.Atoi(value)
		if err != nil || parsed <= 0 {
			return vcompYCSBConfig{}, fmt.Errorf("invalid VCOMP_YCSB_CONCURRENCY %q", value)
		}
		config.concurrency = parsed
	}
	if value := os.Getenv("VCOMP_YCSB_CACHE_BYTES"); value != "" {
		parsed, err := strconv.ParseInt(value, 10, 64)
		if err != nil || parsed < 0 {
			return vcompYCSBConfig{}, fmt.Errorf("invalid VCOMP_YCSB_CACHE_BYTES %q", value)
		}
		config.cacheBytes = parsed
	}
	return config, nil
}

func runVCompYCSBC(
	dir string, keyspace uint64, config vcompYCSBConfig, seed uint64,
) (vcompYCSBResult, error) {
	if keyspace == 0 {
		return vcompYCSBResult{}, fmt.Errorf("YCSB keyspace must be non-zero")
	}
	opts := vcompOptions()
	var noCache *Cache
	if config.cacheBytes == 0 {
		// CacheSize=0 means "use Pebble's 8 MiB default". An explicit zero-sized
		// Cache is required to truly disable the Pebble block cache.
		noCache = NewCache(0)
		opts.Cache = noCache
	} else {
		opts.CacheSize = config.cacheBytes
	}
	opts.DisableAutomaticCompactions = true
	d, err := Open(dir, opts)
	if noCache != nil {
		// Open takes its own reference on success.
		noCache.Unref()
	}
	if err != nil {
		return vcompYCSBResult{}, err
	}
	defer d.Close()

	result := vcompYCSBResult{
		Workload:         "C (100% read)",
		KeyDistribution:  "scrambled Zipfian theta=0.99",
		RequestedSeconds: config.duration.Seconds(),
		Concurrency:      config.concurrency,
		CacheBytes:       config.cacheBytes,
		DBReadAmp:        d.Metrics().ReadAmp(),
	}
	histogram := newVCompLatencyHistogram()
	var operations, hits, misses atomic.Uint64
	var firstErr error
	var errOnce sync.Once
	startSignal := make(chan struct{})
	stop := make(chan struct{})
	var workers sync.WaitGroup
	for worker := range config.concurrency {
		workers.Go(func() {
			zipf, zipfErr := randvar.NewDefaultZipf()
			if zipfErr != nil {
				errOnce.Do(func() { firstErr = zipfErr })
				return
			}
			rng := rand.New(rand.NewPCG(seed+uint64(worker), seed^uint64(worker+1)))
			var key [vcompKeySize]byte
			<-startSignal
			for {
				select {
				case <-stop:
					return
				default:
				}
				keyNumber := vcompYCSBHashKey(zipf.Uint64(rng)) % keyspace
				binary.BigEndian.PutUint64(key[vcompKeySize-8:], keyNumber)
				operationStart := time.Now()
				iter, iterErr := d.NewIter(nil)
				if iterErr != nil {
					errOnce.Do(func() { firstErr = iterErr })
					return
				}
				valid := iter.SeekGE(key[:])
				found := valid && bytes.Equal(iter.Key(), key[:])
				if found {
					_ = len(iter.Value())
				}
				if iterErr = iter.Error(); iterErr == nil {
					iterErr = iter.Close()
				} else {
					_ = iter.Close()
				}
				histogram.record(time.Since(operationStart))
				if iterErr != nil {
					errOnce.Do(func() { firstErr = iterErr })
					return
				}
				operations.Add(1)
				if found {
					hits.Add(1)
				} else {
					misses.Add(1)
				}
			}
		})
	}
	start := time.Now()
	close(startSignal)
	timer := time.NewTimer(config.duration)
	<-timer.C
	close(stop)
	workers.Wait()
	result.WallSeconds = time.Since(start).Seconds()
	if firstErr != nil {
		return vcompYCSBResult{}, firstErr
	}
	result.Operations = operations.Load()
	result.Hits = hits.Load()
	result.Misses = misses.Load()
	if result.Operations > 0 {
		result.HitRate = float64(result.Hits) / float64(result.Operations)
		result.OpsPerSecond = float64(result.Operations) / result.WallSeconds
		result.LatencyP50Micros = histogram.percentile(result.Operations, 0.50)
		result.LatencyP95Micros = histogram.percentile(result.Operations, 0.95)
		result.LatencyP99Micros = histogram.percentile(result.Operations, 0.99)
	}
	metrics := d.Metrics()
	result.BlockCacheHits, result.BlockCacheMisses = metrics.BlockCache.HitsAndMisses.Aggregate()
	cacheAccesses := result.BlockCacheHits + result.BlockCacheMisses
	if cacheAccesses > 0 {
		result.BlockCacheHitRate = float64(result.BlockCacheHits) / float64(cacheAccesses)
	}
	return result, nil
}

func vcompFillValue(dst []byte, key uint64) {
	for off, counter := 0, uint64(0); off < len(dst); counter++ {
		x := vcompSplitMix64(key ^ (counter * 0x9e3779b97f4a7c15))
		for shift := 0; shift < 64 && off < len(dst); shift += 8 {
			dst[off] = byte(x >> shift)
			off++
		}
	}
}

func vcompSortedUnique(keys []uint64) []uint64 {
	sort.Slice(keys, func(i, j int) bool { return keys[i] < keys[j] })
	if len(keys) == 0 {
		return keys
	}
	w := 1
	for i := 1; i < len(keys); i++ {
		if keys[i] != keys[w-1] {
			keys[w] = keys[i]
			w++
		}
	}
	return keys[:w]
}

func vcompExpectedKeys(writes, keyspace, seed uint64) []uint64 {
	keys := make([]uint64, writes)
	for i := uint64(0); i < writes; i++ {
		keys[i] = vcompGeneratedKey(i, keyspace, seed)
	}
	return vcompSortedUnique(keys)
}

func vcompDrainCompactions(d *DB) {
	d.mu.Lock()
	d.maybeScheduleCompaction()
	for d.mu.compact.compactingCount > 0 {
		d.mu.compact.cond.Wait()
	}
	d.mu.Unlock()
}

func vcompCollectKeys(d *DB, valueSize int) ([]uint64, uint64, error) {
	iter, err := d.NewIter(nil)
	if err != nil {
		return nil, 0, err
	}
	defer iter.Close()
	keys := make([]uint64, 0)
	wantValue := make([]byte, valueSize)
	var valueErrors uint64
	for valid := iter.First(); valid; valid = iter.Next() {
		key, err := vcompDecodeUserKey(iter.Key())
		if err != nil {
			return nil, valueErrors, err
		}
		keys = append(keys, key)
		vcompFillValue(wantValue, key)
		if !bytes.Equal(iter.Value(), wantValue) {
			valueErrors++
		}
	}
	if err := iter.Error(); err != nil {
		return nil, valueErrors, err
	}
	return keys, valueErrors, nil
}

func vcompMetricsLevels(metrics *Metrics) ([]vcompLevelResult, uint64) {
	levels := make([]vcompLevelResult, 0, manifest.NumLevels)
	var total uint64
	for level := 0; level < manifest.NumLevels; level++ {
		m := metrics.Levels[level]
		levels = append(levels, vcompLevelResult{Level: level, Tables: m.Tables.Count, Bytes: m.Tables.Bytes})
		total += m.Tables.Bytes
	}
	return levels, total
}

func runVCompBaseline(
	dir string, writes, keyspace, seed uint64, valueSize int,
) (vcompBaselineResult, []uint64, error) {
	opts := vcompOptions()
	start := time.Now()
	d, err := Open(dir, opts)
	if err != nil {
		return vcompBaselineResult{}, nil, err
	}
	value := make([]byte, valueSize)
	for i := uint64(0); i < writes; i++ {
		key := vcompGeneratedKey(i, keyspace, seed)
		vcompFillValue(value, key)
		if err := d.Set(vcompUserKey(key), value, NoSync); err != nil {
			_ = d.Close()
			return vcompBaselineResult{}, nil, err
		}
		if (i+1)%vcompFlushEntries == 0 {
			if err := d.Flush(); err != nil {
				_ = d.Close()
				return vcompBaselineResult{}, nil, err
			}
		}
	}
	if writes%vcompFlushEntries != 0 {
		if err := d.Flush(); err != nil {
			_ = d.Close()
			return vcompBaselineResult{}, nil, err
		}
	}
	vcompDrainCompactions(d)
	wall := time.Since(start)
	metrics := d.Metrics()
	levels, finalBytes := vcompMetricsLevels(metrics)
	keys, valueErrors, err := vcompCollectKeys(d, valueSize)
	if err != nil {
		_ = d.Close()
		return vcompBaselineResult{}, nil, err
	}
	var flushBytes, compactionBytes uint64
	for level := range metrics.Levels {
		flushBytes += metrics.Levels[level].TablesFlushed.Bytes
		compactionBytes += metrics.Levels[level].TablesCompacted.Bytes
	}
	if err := d.Close(); err != nil {
		return vcompBaselineResult{}, nil, err
	}
	appBytes := writes * uint64(vcompKeySize+valueSize)
	totalWrites := flushBytes + compactionBytes
	result := vcompBaselineResult{
		WallSeconds:        wall.Seconds(),
		AppBytes:           appBytes,
		ValueBytes:         writes * uint64(valueSize),
		FlushBytes:         flushBytes,
		CompactionBytes:    compactionBytes,
		TotalSSTWriteBytes: totalWrites,
		FinalSSTBytes:      finalBytes,
		LogicalKeys:        uint64(len(keys)),
		ValueErrors:        valueErrors,
		Levels:             levels,
	}
	if appBytes > 0 {
		result.SSTWriteAmpApp = float64(totalWrites) / float64(appBytes)
	}
	if finalBytes > 0 {
		result.SSTRewriteFactor = float64(totalWrites) / float64(finalBytes)
	}
	return result, keys, nil
}

// reuseVCompBaselineState recovers the durable final tree without replaying
// the expensive load. A prior TestVCompExperiment invocation reached the
// virtual phase only after exact baseline key/value validation had passed, so
// resume mode deliberately does not rescan hundreds of GiB of values.
func reuseVCompBaselineState(
	dir string, writes uint64, valueSize int, logicalKeys uint64,
) (vcompBaselineResult, error) {
	opts := vcompOptions()
	opts.DisableAutomaticCompactions = true
	d, err := Open(dir, opts)
	if err != nil {
		return vcompBaselineResult{}, err
	}
	metrics := d.Metrics()
	levels, finalBytes := vcompMetricsLevels(metrics)
	if err := d.Close(); err != nil {
		return vcompBaselineResult{}, err
	}
	if finalBytes == 0 {
		return vcompBaselineResult{}, fmt.Errorf("reused baseline has no SST data")
	}
	return vcompBaselineResult{
		AppBytes:      writes * uint64(vcompKeySize+valueSize),
		ValueBytes:    writes * uint64(valueSize),
		FinalSSTBytes: finalBytes,
		LogicalKeys:   logicalKeys,
		Levels:        levels,
	}, nil
}

func newVCompSimState(opts *Options, traceAccuracy bool) *vcompSimState {
	l0Organizer := manifest.NewL0Organizer(opts.Comparer, opts.FlushSplitBytes)
	version := manifest.NewInitialVersion(opts.Comparer)
	l0Organizer.ResetForTesting(version)
	latest := &latestVersionState{
		l0Organizer:     l0Organizer,
		virtualBackings: manifest.MakeVirtualBackings(),
	}
	latest.blobFiles.Init(nil, manifest.BlobRewriteHeuristic{CurrentTime: time.Now})
	state := &vcompSimState{
		opts:          opts,
		desc:          make(map[base.TableNum]*vcomp.VirtualSST),
		version:       version,
		latest:        latest,
		traceAccuracy: traceAccuracy,
		// The discrete-CDF correction is a post-paper experimental path. It
		// remains opt-in for Pebble until its scale-sensitive tree shape is
		// qualified against the continuous PLR baseline.
		discreteCDF: os.Getenv("VCOMP_DISCRETE_CDF") == "1",
		nextTable:   1,
		nextSeq:     base.SeqNumStart,
	}
	if traceAccuracy {
		state.truth = make(map[base.TableNum][]uint64)
	}
	return state
}

func (s *vcompSimState) newMetadata(v *vcomp.VirtualSST, seqLo, seqHi base.SeqNum) *manifest.TableMetadata {
	meta := &manifest.TableMetadata{
		TableNum:              s.nextTable,
		Size:                  max(v.SizeBytes, uint64(1)),
		SeqNums:               base.SeqNumRange{Low: seqLo, High: seqHi},
		LargestSeqNumAbsolute: seqHi,
	}
	s.nextTable++
	meta.ExtendPointKeyBounds(
		s.opts.Comparer.Compare,
		base.MakeInternalKey(vcompUserKey(v.KeyMin), seqHi, base.InternalKeyKindSet),
		base.MakeInternalKey(vcompUserKey(v.KeyMax), seqLo, base.InternalKeyKindSet),
	)
	meta.InitPhysicalBacking()
	return meta
}

func (s *vcompSimState) addInitial(keys []uint64, valueSize int) error {
	if len(keys) == 0 {
		return nil
	}
	seqLo := s.nextSeq
	seqHi := seqLo + base.SeqNum(len(keys)-1)
	s.nextSeq = seqHi + 1
	v := &vcomp.VirtualSST{
		Model:      vcomp.GreedyFit(keys, vcompModelError),
		KMV:        vcomp.BuildKMV(keys),
		KMVRanges:  vcomp.BuildRangeKMV(keys),
		KeyMin:     keys[0],
		KeyMax:     keys[len(keys)-1],
		NumEntries: uint64(len(keys)),
		Level:      0,
		SizeBytes:  s.sizeModel.Estimate(uint64(len(keys))),
	}
	if s.discreteCDF {
		if err := vcomp.CertifyVirtualSST(v); err != nil {
			return err
		}
	}
	meta := s.newMetadata(v, seqLo, seqHi)
	s.files[0] = append(s.files[0], meta)
	s.desc[meta.TableNum] = v
	if s.traceAccuracy {
		s.truth[meta.TableNum] = append([]uint64(nil), keys...)
	}
	if err := s.applyVersionEdit(&manifest.VersionEdit{
		NewTables: []manifest.NewTableEntry{{Level: 0, Meta: meta}},
	}); err != nil {
		return err
	}
	return s.compactUntilQuiescent()
}

func (s *vcompSimState) applyVersionEdit(ve *manifest.VersionEdit) error {
	var bulk manifest.BulkVersionEdit
	if err := bulk.Accumulate(ve); err != nil {
		return err
	}
	version, err := bulk.Apply(s.version, s.opts.ReadCompactionRate)
	if err != nil {
		return err
	}
	update := s.latest.l0Organizer.PrepareUpdate(&bulk, version)
	s.latest.l0Organizer.PerformUpdate(update, version)
	if err := version.CheckOrdering(); err != nil {
		return err
	}
	s.version = version
	return nil
}

func (s *vcompSimState) compactUntilQuiescent() error {
	for jobs := 0; ; jobs++ {
		if jobs > 100000 {
			return fmt.Errorf("virtual compaction did not quiesce")
		}
		picker := newCompactionPickerByScore(s.version, s.latest, s.opts, nil)
		picked := picker.pickAutoScore(compactionEnv{
			diskAvailBytes:          math.MaxUint64,
			earliestUnflushedSeqNum: base.SeqNumMax,
		})
		if picked == nil {
			return nil
		}
		pc, ok := picked.(*pickedTableCompaction)
		if !ok {
			return fmt.Errorf("unexpected compaction type %T", picked)
		}
		c := newCompaction(context.Background(), pc, s.opts, time.Now(), nil, noopGrantHandle{}, noSharedStorage, neverSeparateValues)
		if err := s.applyCompaction(c); err != nil {
			return err
		}
	}
}

func (s *vcompSimState) selectedInputs(c *tableCompaction) ([]base.TableNum, []*vcomp.VirtualSST, []uint64, error) {
	seen := make(map[base.TableNum]struct{})
	nums := make([]base.TableNum, 0)
	inputs := make([]*vcomp.VirtualSST, 0)
	truthInputs := make([][]uint64, 0)
	for _, inputLevel := range c.inputs {
		for meta := range inputLevel.files.All() {
			if _, exists := seen[meta.TableNum]; exists {
				continue
			}
			seen[meta.TableNum] = struct{}{}
			desc := s.desc[meta.TableNum]
			if desc == nil {
				return nil, nil, nil, fmt.Errorf("missing vSST for table %s", meta.TableNum)
			}
			nums = append(nums, meta.TableNum)
			inputs = append(inputs, desc)
			if s.traceAccuracy {
				truthInputs = append(truthInputs, s.truth[meta.TableNum])
			}
		}
	}
	if !s.traceAccuracy {
		return nums, inputs, nil, nil
	}
	traceStart := time.Now()
	truth := vcompMergeExact(truthInputs)
	s.stats.accuracyDuration += time.Since(traceStart)
	return nums, inputs, truth, nil
}

func vcompMergeExact(inputs [][]uint64) []uint64 {
	var size int
	for _, input := range inputs {
		size += len(input)
	}
	keys := make([]uint64, 0, size)
	for _, input := range inputs {
		keys = append(keys, input...)
	}
	return vcompSortedUnique(keys)
}

func (s *vcompSimState) removeTables(selected map[base.TableNum]struct{}) {
	for level := range s.files {
		out := s.files[level][:0]
		for _, meta := range s.files[level] {
			if _, remove := selected[meta.TableNum]; !remove {
				out = append(out, meta)
			}
		}
		s.files[level] = out
	}
}

func vcompTableSplitLimit(
	cmp base.Compare, grandparents manifest.LevelSlice, startKey []byte, maxOverlap uint64,
) []byte {
	iter := grandparents.Iter()
	var overlap uint64
	file := iter.SeekGE(cmp, startKey)
	if file != nil && cmp(file.Smallest().UserKey, startKey) <= 0 {
		overlap += file.Size
		file = iter.Next()
	}
	for ; file != nil; file = iter.Next() {
		overlap += file.Size
		if overlap > maxOverlap {
			return append([]byte(nil), file.Smallest().UserKey...)
		}
	}
	return nil
}

// vcompSplitPositionsReference feeds every predicted key through Pebble's
// OutputSplitter. It is retained only as a small-run oracle for the event-based
// descriptor implementation below.
func vcompSplitPositionsReference(model vcomp.Model, total uint64, sizeModel vcomp.SSTSizeModel, c *tableCompaction) []uint64 {
	if total == 0 {
		return nil
	}
	positions := make([]uint64, 0)
	for start := uint64(0); start < total; {
		startKey := vcompUserKey(model.Inverse(float64(start)))
		limit := vcompTableSplitLimit(c.comparer.Compare, c.grandparents, startKey, c.maxOverlapBytes)
		var frontiers compact.Frontiers
		frontiers.Init(c.comparer.Compare)
		splitter := compact.NewOutputSplitter(
			c.comparer.Compare, startKey, limit, c.maxOutputFileSize, c.grandparents.Iter(), &frontiers,
		)
		previous := startKey
		split := total
		for pos := start; pos < total; pos++ {
			key := vcompUserKey(model.Inverse(float64(pos)))
			frontiers.Advance(key)
			estimatedSize := sizeModel.Estimate(pos - start)
			if splitter.ShouldSplitBefore(key, estimatedSize, func(candidate []byte) bool {
				return bytes.Equal(previous, candidate)
			}) == compact.SplitNow {
				split = pos
				break
			}
			previous = key
		}
		if split >= total || split <= start {
			break
		}
		positions = append(positions, split)
		start = split
	}
	return positions
}

func vcompSizeSplitPosition(
	model vcomp.Model, start, total, byteThreshold uint64, sizeModel vcomp.SSTSizeModel,
) uint64 {
	delta := max(uint64(1), sizeModel.MaxEntries(byteThreshold))
	position := start + delta
	if position >= total {
		return total
	}
	// Pebble never splits a user key. The learned-index inverse may map adjacent
	// ranks to the same integer, so advance only across that equal-key run.
	for position < total && model.Inverse(float64(position)) == model.Inverse(float64(position-1)) {
		position++
	}
	return position
}

func vcompBoundaryPosition(model vcomp.Model, start, total, boundary uint64) uint64 {
	position := uint64(math.Round(model.Predict(boundary)))
	position = max(position, start+1)
	if position >= total {
		position = total - 1
	}
	// Predict gives an approximate rank. Align it to the first predicted key
	// that actually reaches the boundary, which is the event observed by
	// Pebble's key-by-key OutputSplitter.
	for position > start+1 && model.Inverse(float64(position-1)) >= boundary {
		position--
	}
	for position < total && model.Inverse(float64(position)) < boundary {
		position++
	}
	if position <= start {
		return total
	}
	return position
}

// vcompSplitPositions applies Pebble's existing OutputSplitter policy at the
// descriptor level. Instead of enumerating every missing KV pair, it evaluates
// only the same events that can cause OutputSplitter to cut: target size,
// 2*target hard size, grandparent starts, and the max-overlap limit.
func vcompSplitPositions(
	model vcomp.Model, total uint64, sizeModel vcomp.SSTSizeModel, c *tableCompaction,
) ([]uint64, error) {
	if total == 0 || !sizeModel.Valid() {
		return nil, nil
	}
	positions := make([]uint64, 0)
	for start := uint64(0); start < total; {
		startKeyNumber := model.Inverse(float64(start))
		startKey := vcompUserKey(startKeyNumber)
		limitKey := vcompTableSplitLimit(c.comparer.Compare, c.grandparents, startKey, c.maxOverlapBytes)
		limitPosition := total
		if len(limitKey) > 0 {
			limitNumber, err := vcompDecodeUserKey(limitKey)
			if err != nil {
				return nil, err
			}
			limitPosition = vcompBoundaryPosition(model, start, total, limitNumber)
		}

		targetPosition := vcompSizeSplitPosition(model, start, total, c.maxOutputFileSize, sizeModel)
		hardPosition := vcompSizeSplitPosition(model, start, total, 2*c.maxOutputFileSize, sizeModel)
		candidate := hardPosition

		// OutputSplitter considers only grandparent start keys strictly after the
		// output start and before the hard max-overlap limit.
		grandparentCandidate := total
		boundariesObserved := uint64(0)
		lastBoundaryPosition := total
		iter := c.grandparents.Iter()
		for grandparent := iter.SeekGE(c.comparer.Compare, startKey); grandparent != nil; grandparent = iter.Next() {
			boundaryKey := grandparent.Smallest().UserKey
			if c.comparer.Compare(boundaryKey, startKey) <= 0 {
				continue
			}
			if len(limitKey) > 0 && c.comparer.Compare(boundaryKey, limitKey) >= 0 {
				break
			}
			boundary, err := vcompDecodeUserKey(boundaryKey)
			if err != nil {
				return nil, err
			}
			position := vcompBoundaryPosition(model, start, total, boundary)
			if position >= total {
				break
			}
			boundariesObserved++
			// Pebble evaluates once after Advance has crossed every boundary at
			// this key, so defer the threshold check until the position changes.
			if lastBoundaryPosition != total && position != lastBoundaryPosition {
				estimatedBytes := sizeModel.Estimate(lastBoundaryPosition - start)
				minimumPct := uint64(50) + 5*min(boundariesObserved-2, uint64(8))
				if estimatedBytes >= (minimumPct*c.maxOutputFileSize)/100 {
					grandparentCandidate = lastBoundaryPosition
					break
				}
			}
			lastBoundaryPosition = position
		}
		if grandparentCandidate == total && lastBoundaryPosition != total {
			estimatedBytes := sizeModel.Estimate(lastBoundaryPosition - start)
			minimumPct := uint64(50) + 5*min(boundariesObserved-1, uint64(8))
			if estimatedBytes >= (minimumPct*c.maxOutputFileSize)/100 {
				grandparentCandidate = lastBoundaryPosition
			}
		}
		if grandparentCandidate < candidate {
			candidate = grandparentCandidate
		} else if grandparentCandidate == total && targetPosition < candidate {
			// With no grandparent boundary ahead, Pebble cuts at target size.
			candidate = targetPosition
		}
		if limitPosition < candidate {
			candidate = limitPosition
		}
		if candidate >= total || candidate <= start {
			break
		}
		positions = append(positions, candidate)
		start = candidate
	}
	return positions, nil
}

func (s *vcompSimState) applyCompaction(c *tableCompaction) error {
	nums, inputs, truth, err := s.selectedInputs(c)
	if err != nil {
		return err
	}
	selected := make(map[base.TableNum]struct{}, len(nums))
	for _, num := range nums {
		selected[num] = struct{}{}
	}
	if c.kind == compactionKindMove {
		if len(nums) != 1 {
			return fmt.Errorf("move selected %d tables", len(nums))
		}
		var moved *manifest.TableMetadata
		for _, meta := range s.files[c.startLevel.level] {
			if meta.TableNum == nums[0] {
				moved = meta
				break
			}
		}
		if moved == nil {
			return fmt.Errorf("move input %s not found", nums[0])
		}
		s.removeTables(selected)
		s.files[c.outputLevel.level] = append(s.files[c.outputLevel.level], moved)
		s.desc[moved.TableNum].Level = c.outputLevel.level
		s.stats.virtualMoves++
		return s.applyVersionEdit(&manifest.VersionEdit{
			DeletedTables: map[manifest.DeletedTableEntry]*manifest.TableMetadata{
				{Level: c.startLevel.level, FileNum: moved.TableNum}: moved,
			},
			NewTables: []manifest.NewTableEntry{{Level: c.outputLevel.level, Meta: moved}},
		})
	}

	var merged vcomp.Model
	var total uint64
	if s.discreteCDF {
		merged, total, err = vcomp.MergeRangeAware(inputs)
	} else {
		merged, total, err = vcomp.MergeRangeAwarePaper(inputs)
	}
	if err != nil {
		return err
	}
	if total == 0 || merged.Empty() {
		return fmt.Errorf("empty output from non-empty compaction")
	}
	if s.traceAccuracy {
		traceStart := time.Now()
		if len(truth) > 0 {
			errPct := math.Abs(float64(total)-float64(len(truth))) / float64(len(truth)) * 100
			s.stats.kmvErrors = append(s.stats.kmvErrors, errPct)
		}
		s.stats.accuracyDuration += time.Since(traceStart)
	}

	seqLo, seqHi := base.SeqNumMax, base.SeqNumZero
	for _, inputLevel := range c.inputs {
		for meta := range inputLevel.files.All() {
			seqLo = min(seqLo, meta.SeqNums.Low)
			seqHi = max(seqHi, meta.SeqNums.High)
		}
	}
	splits, err := vcompSplitPositions(merged, total, s.sizeModel, c)
	if err != nil {
		return err
	}
	if os.Getenv("VCOMP_VALIDATE_SPLITTER") != "" {
		reference := vcompSplitPositionsReference(merged, total, s.sizeModel, c)
		if !slices.Equal(splits, reference) {
			return fmt.Errorf("descriptor splitter mismatch: fast=%v reference=%v", splits, reference)
		}
	}
	outputs := vcomp.SliceInto(merged, total, splits, c.outputLevel.level, s.sizeModel, inputs)
	if len(outputs) == 0 {
		return fmt.Errorf("compaction produced no vSSTs")
	}
	for i := 1; i < len(outputs); i++ {
		if outputs[i-1].KeyMax >= outputs[i].KeyMin {
			return fmt.Errorf(
				"predicted output ranges overlap: output %d [%d,%d], output %d [%d,%d], total=%d splits=%v model=[%d,%d] segments=%d",
				i-1, outputs[i-1].KeyMin, outputs[i-1].KeyMax,
				i, outputs[i].KeyMin, outputs[i].KeyMax,
				total, splits, merged.KeyMin(), merged.KeyMax(), len(merged.Segments),
			)
		}
	}

	ve := &manifest.VersionEdit{
		DeletedTables: make(map[manifest.DeletedTableEntry]*manifest.TableMetadata),
	}
	for _, inputLevel := range c.inputs {
		for meta := range inputLevel.files.All() {
			ve.DeletedTables[manifest.DeletedTableEntry{
				Level: inputLevel.level, FileNum: meta.TableNum,
			}] = meta
		}
	}
	s.removeTables(selected)
	for _, num := range nums {
		delete(s.desc, num)
		if s.traceAccuracy {
			delete(s.truth, num)
		}
	}
	for i := range outputs {
		output := outputs[i]
		meta := s.newMetadata(&output, seqLo, seqHi)
		s.files[c.outputLevel.level] = append(s.files[c.outputLevel.level], meta)
		s.desc[meta.TableNum] = &output
		ve.NewTables = append(ve.NewTables, manifest.NewTableEntry{Level: c.outputLevel.level, Meta: meta})
		if s.traceAccuracy {
			traceStart := time.Now()
			begin := sort.Search(len(truth), func(j int) bool { return truth[j] >= output.KeyMin })
			end := sort.Search(len(truth), func(j int) bool { return truth[j] > output.KeyMax })
			s.truth[meta.TableNum] = append([]uint64(nil), truth[begin:end]...)
			s.stats.accuracyDuration += time.Since(traceStart)
		}
		s.stats.predictedCompactionBytes += output.SizeBytes
	}
	s.stats.virtualCompactions++
	return s.applyVersionEdit(ve)
}

func (s *vcompSimState) finalTables() []vcompFinalTable {
	result := make([]vcompFinalTable, 0, len(s.desc))
	for level := 0; level < manifest.NumLevels; level++ {
		for _, meta := range s.files[level] {
			v := *s.desc[meta.TableNum]
			v.Level = level
			result = append(result, vcompFinalTable{
				descriptor: v,
				seqNum:     meta.SeqNums.High,
			})
		}
	}
	return result
}

// vcompCertifyFinalTables adds the count/select certificate only after the
// paper-path compaction graph has reached its final layout. Applying the
// discrete reconstruction recursively during virtual compaction changes key
// ranges and Pebble's later overlap picks at TiB scale. Final-only
// certification preserves that qualified tree shape. If local KMV error made
// a descriptor denser than its integer key domain permits, excess cardinality
// is moved only within the same level before certificates are attached.
func vcompCertifyFinalTables(tables []vcompFinalTable, sizeModel vcomp.SSTSizeModel) (uint64, error) {
	byLevel := make([][]*vcomp.VirtualSST, manifest.NumLevels)
	for i := range tables {
		level := tables[i].descriptor.Level
		if level < 0 || level >= manifest.NumLevels {
			return 0, fmt.Errorf("certify final table %d: invalid level %d", i, level)
		}
		byLevel[level] = append(byLevel[level], &tables[i].descriptor)
	}
	var moved uint64
	for level := range byLevel {
		levelMoved, err := vcomp.CertifyVirtualSSTGroup(byLevel[level])
		if err != nil {
			return 0, fmt.Errorf("certify final level %d: %w", level, err)
		}
		if math.MaxUint64-moved < levelMoved {
			return 0, fmt.Errorf("certify final tables: redistributed count overflows uint64")
		}
		moved += levelMoved
	}
	for i := range tables {
		tables[i].descriptor.SizeBytes = sizeModel.Estimate(tables[i].descriptor.NumEntries)
	}
	return moved, nil
}

// vcompAssignUniqueMaterializationSeqNums preserves the virtual metadata's
// relative recency order while ensuring that independently materialized tables
// cannot contain identical internal keys. Descriptor compactions propagate an
// input range's highest sequence number to every output, so distinct final
// tables may otherwise share both a reconstructed user key and sequence number.
func vcompAssignUniqueMaterializationSeqNums(tables []vcompFinalTable) {
	indices := make([]int, len(tables))
	for i := range indices {
		indices[i] = i
	}
	sort.SliceStable(indices, func(i, j int) bool {
		return tables[indices[i]].seqNum < tables[indices[j]].seqNum
	})
	for rank, index := range indices {
		tables[index].seqNum = base.SeqNumStart + base.SeqNum(rank)
	}
}

func vcompMaterialize(
	dir string, tables []vcompFinalTable, valueSize int, measuredDuration *time.Duration,
) (uint64, []vcompLevelResult, uint64, []uint64, uint64, error) {
	start := time.Now()
	vcompAssignUniqueMaterializationSeqNums(tables)
	opts := vcompOptions()
	opts.DisableAutomaticCompactions = true
	d, err := Open(dir, opts)
	if err != nil {
		return 0, nil, 0, nil, 0, err
	}
	diskFileNums := make([]base.DiskFileNum, len(tables))
	d.mu.Lock()
	for i := range diskFileNums {
		diskFileNums[i] = d.mu.versions.getNextDiskFileNum()
	}
	d.mu.Unlock()

	built := make([]vcompBuiltTable, len(tables))
	jobs := make(chan int)
	_, maxBackgroundJobs := opts.CompactionConcurrencyRange()
	workers := min(len(tables), min(maxBackgroundJobs, runtime.GOMAXPROCS(0)))
	var wg sync.WaitGroup
	for range workers {
		wg.Go(func() {
			value := make([]byte, valueSize)
			for i := range jobs {
				table := tables[i]
				descriptor := table.descriptor
				writable, objectMeta, err := d.objProvider.Create(
					context.Background(), base.FileTypeTable, diskFileNums[i],
					objstorage.CreateOptions{WriteCategory: "vcomp-materialize"},
				)
				if err != nil {
					built[i].err = err
					continue
				}
				writer := sstable.NewWriter(
					writable, opts.MakeWriterOptions(descriptor.Level, d.TableFormat()),
				)
				keys, err := vcomp.Materialize(descriptor)
				if err != nil {
					built[i].err = err
					_ = writer.Close()
					continue
				}
				for j, key := range keys {
					if j > 0 && key <= keys[j-1] {
						built[i].err = fmt.Errorf(
							"materialization produced non-increasing keys in vSST %d at rank %d", i, j,
						)
						break
					}
					vcompFillValue(value, key)
					if err := writer.Raw().Add(
						base.MakeInternalKey(vcompUserKey(key), table.seqNum, base.InternalKeyKindSet),
						value, false, base.KVMeta{},
					); err != nil {
						built[i].err = err
						break
					}
				}
				if err := writer.Close(); built[i].err == nil && err != nil {
					built[i].err = err
				}
				if built[i].err != nil {
					continue
				}
				writerMeta, err := writer.Metadata()
				if err != nil {
					built[i].err = err
					continue
				}
				fileMeta := &manifest.TableMetadata{
					TableNum:              base.PhysicalTableFileNum(objectMeta.DiskFileNum),
					CreationTime:          time.Now().Unix(),
					Size:                  writerMeta.Size,
					SeqNums:               writerMeta.SeqNums,
					LargestSeqNumAbsolute: writerMeta.SeqNums.High,
				}
				fileMeta.InitPhysicalBacking()
				maybeSetStatsFromProperties(fileMeta.PhysicalMeta(), &writerMeta.Properties)
				if writerMeta.HasPointKeys {
					fileMeta.ExtendPointKeyBounds(
						opts.Comparer.Compare, writerMeta.SmallestPoint, writerMeta.LargestPoint,
					)
				}
				built[i] = vcompBuiltTable{
					entry: manifest.NewTableEntry{Level: descriptor.Level, Meta: fileMeta},
					size:  writerMeta.Size,
				}
			}
		})
	}
	for i := range tables {
		jobs <- i
	}
	close(jobs)
	wg.Wait()
	if err := d.objProvider.Sync(); err != nil {
		_ = d.Close()
		return 0, nil, 0, nil, 0, err
	}

	ve := &manifest.VersionEdit{}
	var materializedBytes uint64
	for i := range built {
		if built[i].err != nil {
			_ = d.Close()
			return 0, nil, 0, nil, 0, built[i].err
		}
		materializedBytes += built[i].size
		ve.NewTables = append(ve.NewTables, built[i].entry)
	}
	d.mu.Lock()
	// The materialized keys bypassed Pebble's commit pipeline. Ratchet both
	// sequence-number watermarks past the largest sequence number installed,
	// just as Pebble's data-driven table installation helper does.
	largestSeqNum := d.mu.versions.logSeqNum.Load()
	for i := range tables {
		if largestSeqNum <= tables[i].seqNum {
			largestSeqNum = tables[i].seqNum + 1
		}
	}
	if d.mu.versions.logSeqNum.Load() < largestSeqNum {
		d.mu.versions.logSeqNum.Store(largestSeqNum)
	}
	if d.mu.versions.visibleSeqNum.Load() < largestSeqNum {
		d.mu.versions.visibleSeqNum.Store(largestSeqNum)
	}
	_, err = d.mu.versions.UpdateVersionLocked(func() (versionUpdate, error) {
		return versionUpdate{
			VE:                      ve,
			JobID:                   d.newJobIDLocked(),
			InProgressCompactionsFn: func() []compactionInfo { return d.getInProgressCompactionInfoLocked(nil) },
		}, nil
	})
	if err == nil {
		d.updateReadStateLocked(nil)
	}
	d.mu.Unlock()
	if err != nil {
		_ = d.Close()
		return 0, nil, 0, nil, 0, err
	}
	*measuredDuration = time.Since(start)
	// Reopen before validation so the experiment also verifies that the exact
	// predicted level placement was durably recorded in Pebble's manifest.
	if err := d.Close(); err != nil {
		return 0, nil, 0, nil, 0, err
	}
	d, err = Open(dir, opts)
	if err != nil {
		return 0, nil, 0, nil, 0, err
	}
	metrics := d.Metrics()
	levels, finalBytes := vcompMetricsLevels(metrics)
	keys, valueErrors, err := vcompCollectKeys(d, valueSize)
	if err != nil {
		_ = d.Close()
		return 0, nil, 0, nil, 0, err
	}
	if err := d.Close(); err != nil {
		return 0, nil, 0, nil, 0, err
	}
	return materializedBytes, levels, finalBytes, keys, valueErrors, nil
}

func TestVCompFinalCertificationPreservesPlannedCount(t *testing.T) {
	tables := []vcompFinalTable{{descriptor: vcomp.VirtualSST{
		Model:      vcomp.Model{Segments: []vcomp.Segment{{KeyStart: 0, KeyEnd: 999}}},
		KMV:        vcomp.KMVSketch{ThetaHash: math.MaxUint64},
		KeyMin:     0,
		KeyMax:     999,
		NumEntries: 100,
		SizeBytes:  100 << 10,
	}}}
	if _, err := vcompCertifyFinalTables(tables, vcomp.LogicalSSTSizeModel(1<<10)); err != nil {
		t.Fatal(err)
	}
	keys, err := vcomp.Materialize(tables[0].descriptor)
	if err != nil {
		t.Fatal(err)
	}
	if len(keys) != 100 {
		t.Fatalf("materialized %d keys, want 100", len(keys))
	}
}

func TestVCompAssignUniqueMaterializationSeqNums(t *testing.T) {
	tables := []vcompFinalTable{
		{seqNum: 20},
		{seqNum: 10},
		{seqNum: 20},
		{seqNum: 5},
	}
	original := []base.SeqNum{20, 10, 20, 5}
	vcompAssignUniqueMaterializationSeqNums(tables)
	seen := make(map[base.SeqNum]struct{}, len(tables))
	for i := range tables {
		if _, exists := seen[tables[i].seqNum]; exists {
			t.Fatalf("duplicate assigned sequence number %d", tables[i].seqNum)
		}
		seen[tables[i].seqNum] = struct{}{}
		for j := range tables {
			if original[i] < original[j] && tables[i].seqNum >= tables[j].seqNum {
				t.Fatalf("recency order reversed: old %d < %d, new %d >= %d",
					original[i], original[j], tables[i].seqNum, tables[j].seqNum)
			}
		}
	}
}

func vcompMaterializedUnion(tables []vcompFinalTable) ([]uint64, error) {
	var count uint64
	for i := range tables {
		count += tables[i].descriptor.NumEntries
	}
	keys := make([]uint64, 0, count)
	for i := range tables {
		materialized, err := vcomp.Materialize(tables[i].descriptor)
		if err != nil {
			return nil, err
		}
		keys = append(keys, materialized...)
	}
	return vcompSortedUnique(keys), nil
}

func vcompCompareKeys(expected, actual []uint64) vcompAccuracyResult {
	var i, j, intersection uint64
	for i < uint64(len(expected)) && j < uint64(len(actual)) {
		switch {
		case expected[i] < actual[j]:
			i++
		case expected[i] > actual[j]:
			j++
		default:
			intersection++
			i++
			j++
		}
	}
	union := uint64(len(expected)+len(actual)) - intersection
	result := vcompAccuracyResult{
		ExpectedKeys:     uint64(len(expected)),
		MaterializedKeys: uint64(len(actual)),
		IntersectionKeys: intersection,
		MissingKeys:      uint64(len(expected)) - intersection,
		ExtraKeys:        uint64(len(actual)) - intersection,
	}
	if union > 0 {
		result.Jaccard = float64(intersection) / float64(union)
	}
	return result
}

func vcompPercentile(sortedValues []float64, quantile float64) float64 {
	if len(sortedValues) == 0 {
		return 0
	}
	index := int(math.Ceil(quantile*float64(len(sortedValues)))) - 1
	index = max(0, min(index, len(sortedValues)-1))
	return sortedValues[index]
}

func vcompAddKMVAccuracy(result *vcompAccuracyResult, errors []float64) {
	if len(errors) == 0 {
		return
	}
	errors = append([]float64(nil), errors...)
	sort.Float64s(errors)
	var sum float64
	for _, value := range errors {
		sum += value
	}
	result.KMVJobs = uint64(len(errors))
	result.KMVMeanAbsPercentError = sum / float64(len(errors))
	result.KMVMedianAbsPercentError = vcompPercentile(errors, 0.50)
	result.KMVP95AbsPercentError = vcompPercentile(errors, 0.95)
	result.KMVMaxAbsPercentError = errors[len(errors)-1]
}

func runVCompSimulation(
	writes, keyspace, seed uint64, valueSize int, traceAccuracy bool,
) (*vcompSimState, time.Duration, error) {
	opts := vcompOptions()
	state := newVCompSimState(opts, traceAccuracy)
	if os.Getenv("VCOMP_SST_SIZE_MODEL") == "calibrated" {
		sizeModel, err := vcompCalibrateSSTSizeModel(opts, valueSize)
		if err != nil {
			return nil, 0, err
		}
		state.sizeModel = sizeModel
	} else {
		// Paper vSST metadata uses logical KV bytes. Physical calibration is a
		// later experiment and remains opt-in because it changes Pebble's
		// dynamic-level scores enough to regress the qualified TiB tree shape.
		state.sizeModel = vcomp.LogicalSSTSizeModel(uint64(vcompKeySize + valueSize))
	}
	start := time.Now()
	for begin := uint64(0); begin < writes; begin += vcompFlushEntries {
		end := min(writes, begin+vcompFlushEntries)
		batch := make([]uint64, end-begin)
		for i := begin; i < end; i++ {
			batch[i-begin] = vcompGeneratedKey(i, keyspace, seed)
		}
		batch = vcompSortedUnique(batch)
		if err := state.addInitial(batch, valueSize); err != nil {
			return nil, 0, err
		}
	}
	return state, time.Since(start), nil
}

func vcompCalibrateSSTSizeModel(opts *Options, valueSize int) (vcomp.SSTSizeModel, error) {
	measure := func(entries uint64, name string) (uint64, error) {
		mem := vfs.NewMem()
		file, err := mem.Create(name, vfs.WriteCategoryUnspecified)
		if err != nil {
			return 0, err
		}
		writer := sstable.NewWriter(
			objstorageprovider.NewFileWritable(file),
			opts.MakeWriterOptions(0, sstable.TableFormatMax),
		)
		value := make([]byte, valueSize)
		for key := uint64(0); key < entries; key++ {
			vcompFillValue(value, key)
			if err := writer.Raw().Add(
				base.MakeInternalKey(vcompUserKey(key), base.SeqNumZero, base.InternalKeyKindSet),
				value, false, base.KVMeta{},
			); err != nil {
				_ = writer.Close()
				return 0, err
			}
		}
		if err := writer.Close(); err != nil {
			return 0, err
		}
		metadata, err := writer.Metadata()
		if err != nil {
			return 0, err
		}
		return metadata.Size, nil
	}
	const first, second = uint64(4096), uint64(8192)
	bytes1, err := measure(first, "calibration-1.sst")
	if err != nil {
		return vcomp.SSTSizeModel{}, err
	}
	bytes2, err := measure(second, "calibration-2.sst")
	if err != nil {
		return vcomp.SSTSizeModel{}, err
	}
	model := vcomp.LogicalSSTSizeModel(uint64(vcompKeySize + valueSize))
	if !model.AddCalibration(first, bytes1, second, bytes2) {
		return vcomp.SSTSizeModel{}, fmt.Errorf("invalid physical SST calibration: (%d,%d), (%d,%d)", first, bytes1, second, bytes2)
	}
	return model, nil
}

func runVCompVirtual(
	dir string, writes, keyspace, seed uint64, valueSize int, expected []uint64,
) (vcompVirtualResult, error) {
	state, simulationTotal, err := runVCompSimulation(writes, keyspace, seed, valueSize, false)
	if err != nil {
		return vcompVirtualResult{}, err
	}
	tables := state.finalTables()
	certificationMovedKeys, err := vcompCertifyFinalTables(tables, state.sizeModel)
	if err != nil {
		return vcompVirtualResult{}, err
	}
	var materializeDuration time.Duration
	materializedBytes, levels, finalBytes, actual, valueErrors, err := vcompMaterialize(
		dir, tables, valueSize, &materializeDuration,
	)
	if err != nil {
		return vcompVirtualResult{}, err
	}
	descriptorUnion, err := vcompMaterializedUnion(tables)
	if err != nil {
		return vcompVirtualResult{}, err
	}
	if !slices.Equal(descriptorUnion, actual) {
		return vcompVirtualResult{}, fmt.Errorf(
			"materialized DB differs from descriptor union: descriptors=%d DB=%d",
			len(descriptorUnion), len(actual),
		)
	}
	accuracyState, accuracyDuration, err := runVCompSimulation(writes, keyspace, seed, valueSize, true)
	if err != nil {
		return vcompVirtualResult{}, err
	}
	accuracy := vcompCompareKeys(expected, actual)
	vcompAddKMVAccuracy(&accuracy, accuracyState.stats.kmvErrors)
	result := vcompVirtualResult{
		SimulationSeconds:        simulationTotal.Seconds(),
		AccuracyTraceSeconds:     accuracyDuration.Seconds(),
		MaterializationSeconds:   materializeDuration.Seconds(),
		TotalSeconds:             simulationTotal.Seconds() + materializeDuration.Seconds(),
		VirtualCompactions:       state.stats.virtualCompactions,
		VirtualMoves:             state.stats.virtualMoves,
		PredictedCompactionBytes: state.stats.predictedCompactionBytes,
		CertificationMovedKeys:   certificationMovedKeys,
		MaterializedSSTBytes:     materializedBytes,
		FinalSSTBytes:            finalBytes,
		LogicalKeys:              uint64(len(actual)),
		ValueErrors:              valueErrors,
		Levels:                   levels,
		Accuracy:                 accuracy,
	}
	if finalBytes > 0 {
		result.SSTRewriteFactor = float64(materializedBytes) / float64(finalBytes)
	}
	return result, nil
}

func vcompPersistentExperimentRoot(reuseBaseline bool) (string, error) {
	if configured := os.Getenv("VCOMP_DB_ROOT"); configured != "" {
		root, err := filepath.Abs(configured)
		if err != nil {
			return "", err
		}
		if err := os.MkdirAll(root, 0o755); err != nil {
			return "", err
		}
		entries, err := os.ReadDir(root)
		if err != nil {
			return "", err
		}
		if len(entries) != 0 && !reuseBaseline {
			return "", fmt.Errorf("VCOMP_DB_ROOT %q is not empty; refusing to overwrite a retained experiment", root)
		}
		if reuseBaseline {
			baseline := filepath.Join(root, "baseline")
			if info, statErr := os.Stat(baseline); statErr != nil || !info.IsDir() {
				return "", fmt.Errorf("VCOMP_DB_ROOT %q has no reusable baseline directory", root)
			}
			virtual := filepath.Join(root, "virtual")
			if _, statErr := os.Stat(virtual); !errors.Is(statErr, os.ErrNotExist) {
				if statErr == nil {
					return "", fmt.Errorf("VCOMP_DB_ROOT %q already has a virtual directory", root)
				}
				return "", statErr
			}
		}
		return root, nil
	}
	// Do not use testing.T.TempDir here. These databases are expensive
	// experimental artifacts and must survive both successful and failed tests
	// so additional post-load workloads can reuse them.
	return os.MkdirTemp("", "pebble-vcomp-experiment-")
}

func vcompPaperState(
	seconds float64, diskWriteBytes uint64, writeAmp float64,
	finalBytes uint64, levels []vcompLevelResult,
) vcompPaperLoadedState {
	var tables uint64
	for _, level := range levels {
		tables += level.Tables
	}
	state := vcompPaperLoadedState{
		LoadingSeconds:      seconds,
		TotalDiskWriteBytes: diskWriteBytes,
		WriteAmplification:  writeAmp,
		FinalDBBytes:        finalBytes,
		SSTCount:            tables,
		Levels:              levels,
	}
	if tables != 0 {
		state.AverageSSTBytes = float64(finalBytes) / float64(tables)
	}
	return state
}

func vcompWritePaperLoadResult(
	root string, writes uint64, valueSize int,
	baseline vcompBaselineResult, virtual vcompVirtualResult, baselineReused bool,
) (vcompPaperLoadResult, []byte, error) {
	sizeModelName := "logical"
	if os.Getenv("VCOMP_SST_SIZE_MODEL") == "calibrated" {
		sizeModelName = "calibrated"
	}
	result := vcompPaperLoadResult{
		DBRoot:                       root,
		DatasetBytes:                 writes * uint64(vcompKeySize+valueSize),
		Writes:                       writes,
		KeySize:                      vcompKeySize,
		ValueSize:                    valueSize,
		BaselineReused:               baselineReused,
		BaselineLoadMetricsAvailable: !baselineReused,
		Configuration: vcompPaperLoadConfig{
			MemTableBytes:     64 << 20,
			FlushEntries:      vcompFlushEntries,
			TargetSSTBytes:    vcompTargetSSTSize,
			MaxBackgroundJobs: 48,
			DiscreteCDF:       os.Getenv("VCOMP_DISCRETE_CDF") == "1",
			SSTSizeModel:      sizeModelName,
			WAL:               false,
			Compression:       false,
		},
		Baseline: vcompPaperState(
			baseline.WallSeconds, baseline.TotalSSTWriteBytes,
			baseline.SSTRewriteFactor, baseline.FinalSSTBytes, baseline.Levels,
		),
		Virtual: vcompPaperState(
			virtual.TotalSeconds, virtual.MaterializedSSTBytes,
			virtual.SSTRewriteFactor, virtual.FinalSSTBytes, virtual.Levels,
		),
		Deduplication: vcompPaperDedupAccuracy{
			Jobs:                  virtual.Accuracy.KMVJobs,
			MeanAbsPercentError:   virtual.Accuracy.KMVMeanAbsPercentError,
			MedianAbsPercentError: virtual.Accuracy.KMVMedianAbsPercentError,
			P95AbsPercentError:    virtual.Accuracy.KMVP95AbsPercentError,
			MaxAbsPercentError:    virtual.Accuracy.KMVMaxAbsPercentError,
		},
	}
	encoded, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		return vcompPaperLoadResult{}, nil, err
	}
	encoded = append(encoded, '\n')
	if err := os.WriteFile(filepath.Join(root, "paper_load_result.json"), encoded, 0o644); err != nil {
		return vcompPaperLoadResult{}, nil, err
	}
	return result, encoded, nil
}

func TestVCompYCSBExisting(t *testing.T) {
	configuredRoot := os.Getenv("VCOMP_EXISTING_DB_ROOT")
	if configuredRoot == "" {
		t.Skip("set VCOMP_EXISTING_DB_ROOT and VCOMP_WRITES to benchmark retained databases")
	}
	root, err := filepath.Abs(configuredRoot)
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"baseline", "virtual"} {
		info, statErr := os.Stat(filepath.Join(root, name))
		if statErr != nil {
			t.Fatalf("retained %s DB: %v", name, statErr)
		}
		if !info.IsDir() {
			t.Fatalf("retained %s DB path is not a directory", name)
		}
	}
	writesText := os.Getenv("VCOMP_WRITES")
	if writesText == "" {
		t.Fatal("VCOMP_WRITES is required to define the retained DB keyspace")
	}
	keyspace, err := strconv.ParseUint(writesText, 10, 64)
	if err != nil || keyspace == 0 {
		t.Fatalf("invalid VCOMP_WRITES %q", writesText)
	}
	valueSize := 1000
	if value := os.Getenv("VCOMP_VALUE_SIZE"); value != "" {
		valueSize, err = strconv.Atoi(value)
		if err != nil || valueSize <= 0 {
			t.Fatalf("invalid VCOMP_VALUE_SIZE %q", value)
		}
	}
	config, err := vcompYCSBConfigFromEnv(keyspace, valueSize)
	if err != nil {
		t.Fatal(err)
	}
	seed := uint64(0x5eed1234) ^ 0x79637362
	baseline, err := runVCompYCSBC(filepath.Join(root, "baseline"), keyspace, config, seed)
	if err != nil {
		t.Fatal(err)
	}
	runtime.GC()
	virtual, err := runVCompYCSBC(filepath.Join(root, "virtual"), keyspace, config, seed)
	if err != nil {
		t.Fatal(err)
	}
	result := vcompExistingYCSBResult{
		DBRoot:   root,
		Keyspace: keyspace,
		YCSB: vcompYCSBComparison{
			Baseline: baseline,
			Virtual:  virtual,
		},
	}
	encoded, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("VCOMP_EXISTING_YCSB_RESULT\n%s", encoded)
}

// TestVCompSimulationOnly runs the descriptor path without creating baseline
// or materialized databases. It is intended for scale-sensitive tree-shape
// qualification before committing storage and hours to a full campaign.
func TestVCompSimulationOnly(t *testing.T) {
	if os.Getenv("VCOMP_SIMULATION_ONLY") == "" {
		t.Skip("set VCOMP_SIMULATION_ONLY=1 and VCOMP_WRITES to run descriptor-only qualification")
	}
	writes, err := strconv.ParseUint(os.Getenv("VCOMP_WRITES"), 10, 64)
	if err != nil || writes == 0 {
		t.Fatalf("invalid VCOMP_WRITES %q", os.Getenv("VCOMP_WRITES"))
	}
	valueSize := 1000
	if value := os.Getenv("VCOMP_VALUE_SIZE"); value != "" {
		valueSize, err = strconv.Atoi(value)
		if err != nil || valueSize <= 0 {
			t.Fatalf("invalid VCOMP_VALUE_SIZE %q", value)
		}
	}
	state, duration, err := runVCompSimulation(writes, writes, 0x5eed1234, valueSize, false)
	if err != nil {
		t.Fatal(err)
	}
	tables := state.finalTables()
	certificationMovedKeys, err := vcompCertifyFinalTables(tables, state.sizeModel)
	if err != nil {
		t.Fatal(err)
	}
	levels := make([]vcompLevelResult, manifest.NumLevels)
	for level := range levels {
		levels[level].Level = level
		for _, meta := range state.files[level] {
			levels[level].Tables++
			levels[level].Bytes += meta.Size
		}
	}
	sizeModelName := "logical"
	if os.Getenv("VCOMP_SST_SIZE_MODEL") == "calibrated" {
		sizeModelName = "calibrated"
	}
	result := struct {
		Writes             uint64             `json:"writes"`
		DiscreteCDF        bool               `json:"discrete_cdf"`
		SSTSizeModel       string             `json:"sst_size_model"`
		SimulationSeconds  float64            `json:"simulation_seconds"`
		VirtualCompactions uint64             `json:"virtual_compactions"`
		VirtualMoves       uint64             `json:"virtual_moves"`
		CertificationMoved uint64             `json:"final_certification_redistributed_keys"`
		Levels             []vcompLevelResult `json:"levels"`
	}{
		Writes:             writes,
		DiscreteCDF:        state.discreteCDF,
		SSTSizeModel:       sizeModelName,
		SimulationSeconds:  duration.Seconds(),
		VirtualCompactions: state.stats.virtualCompactions,
		VirtualMoves:       state.stats.virtualMoves,
		CertificationMoved: certificationMovedKeys,
		Levels:             levels,
	}
	encoded, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("VCOMP_SIMULATION_RESULT\n%s", encoded)
}

func TestVCompExperiment(t *testing.T) {
	if os.Getenv("VCOMP_EXPERIMENT") == "" {
		t.Skip("set VCOMP_EXPERIMENT=1 to run the end-to-end experiment")
	}
	writes := uint64(64 << 20 / (vcompKeySize + 1000))
	if value := os.Getenv("VCOMP_WRITES"); value != "" {
		parsed, err := strconv.ParseUint(value, 10, 64)
		if err != nil {
			t.Fatal(err)
		}
		writes = parsed
	}
	valueSize := 1000
	if value := os.Getenv("VCOMP_VALUE_SIZE"); value != "" {
		parsed, err := strconv.Atoi(value)
		if err != nil || parsed <= 0 {
			t.Fatalf("invalid VCOMP_VALUE_SIZE %q", value)
		}
		valueSize = parsed
	}
	keyspace := writes
	seed := uint64(0x5eed1234)
	reuseBaseline := os.Getenv("VCOMP_REUSE_BASELINE") == "1"
	root, err := vcompPersistentExperimentRoot(reuseBaseline)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("VCOMP_DB_ROOT=%s (retained after test)", root)
	skipYCSB := os.Getenv("VCOMP_SKIP_YCSB") != ""
	var ycsbConfig vcompYCSBConfig
	if !skipYCSB {
		ycsbConfig, err = vcompYCSBConfigFromEnv(writes, valueSize)
		if err != nil {
			t.Fatal(err)
		}
	}
	expected := vcompExpectedKeys(writes, keyspace, seed)
	baselineDir := filepath.Join(root, "baseline")
	virtualDir := filepath.Join(root, "virtual")
	var baseline vcompBaselineResult
	if reuseBaseline {
		baseline, err = reuseVCompBaselineState(baselineDir, writes, valueSize, uint64(len(expected)))
		if err != nil {
			t.Fatal(err)
		}
		t.Log("reusing previously validated baseline; historical load time and write amplification are unavailable")
	} else {
		var baselineKeys []uint64
		baseline, baselineKeys, err = runVCompBaseline(
			baselineDir, writes, keyspace, seed, valueSize,
		)
		if err != nil {
			t.Fatal(err)
		}
		baselineAccuracy := vcompCompareKeys(expected, baselineKeys)
		if baselineAccuracy.MissingKeys != 0 || baselineAccuracy.ExtraKeys != 0 || baseline.ValueErrors != 0 {
			t.Fatalf("baseline validation failed: %+v, value errors=%d", baselineAccuracy, baseline.ValueErrors)
		}
		baselineKeys = nil
	}
	virtual, err := runVCompVirtual(
		virtualDir, writes, keyspace, seed, valueSize, expected,
	)
	if err != nil {
		t.Fatal(err)
	}
	// Exact-key validation retains several large key arrays. They are no longer
	// needed once both loading paths have passed validation; release them before
	// provisioning the post-load block cache.
	expected = nil
	runtime.GC()
	var ycsb *vcompYCSBComparison
	if !skipYCSB {
		baselineYCSB, runErr := runVCompYCSBC(baselineDir, keyspace, ycsbConfig, seed^0x79637362)
		if runErr != nil {
			t.Fatal(runErr)
		}
		runtime.GC()
		virtualYCSB, runErr := runVCompYCSBC(virtualDir, keyspace, ycsbConfig, seed^0x79637362)
		if runErr != nil {
			t.Fatal(runErr)
		}
		ycsb = &vcompYCSBComparison{Baseline: baselineYCSB, Virtual: virtualYCSB}
	}
	result := vcompExperimentResult{
		DBRoot:         root,
		Writes:         writes,
		Keyspace:       keyspace,
		ValueSize:      valueSize,
		KeySize:        vcompKeySize,
		FlushEntries:   vcompFlushEntries,
		Baseline:       baseline,
		Virtual:        virtual,
		YCSB:           ycsb,
		BaselineReused: reuseBaseline,
	}
	if os.Getenv("VCOMP_PAPER_MODE") != "" {
		_, encoded, writeErr := vcompWritePaperLoadResult(root, writes, valueSize, baseline, virtual, reuseBaseline)
		if writeErr != nil {
			t.Fatal(writeErr)
		}
		t.Logf("VCOMP_PAPER_LOAD_RESULT\n%s", encoded)
	} else {
		encoded, marshalErr := json.MarshalIndent(result, "", "  ")
		if marshalErr != nil {
			t.Fatal(marshalErr)
		}
		t.Logf("VCOMP_RESULT\n%s", encoded)
	}
	if virtual.ValueErrors != 0 {
		t.Fatalf("vcomp materialization returned %d incorrect values", virtual.ValueErrors)
	}
}
