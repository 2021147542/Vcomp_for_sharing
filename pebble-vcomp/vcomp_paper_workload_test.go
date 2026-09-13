package pebble

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"math/rand/v2"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/cockroachdb/pebble/internal/randvar"
	"golang.org/x/sys/unix"
)

type vcompPaperWorkloadConfig struct {
	duration    time.Duration
	concurrency int
	cacheBytes  int64
	valueSize   int
	keyspace    uint64
}

type vcompPaperWorkloadResult struct {
	DBRoot                  string  `json:"db_root"`
	System                  string  `json:"system"`
	Workload                string  `json:"workload"`
	Definition              string  `json:"definition"`
	KeyDistribution         string  `json:"key_distribution"`
	RequestedSeconds        float64 `json:"requested_seconds"`
	WallSeconds             float64 `json:"wall_seconds"`
	Concurrency             int     `json:"concurrency"`
	BlockCacheBytes         int64   `json:"block_cache_bytes"`
	Operations              uint64  `json:"operations"`
	ThroughputOpsPerSecond  float64 `json:"throughput_ops_per_second"`
	PointLookupLatencyP50US float64 `json:"point_lookup_latency_p50_us"`
	PointLookupLatencyP95US float64 `json:"point_lookup_latency_p95_us"`
	PointLookupLatencyP99US float64 `json:"point_lookup_latency_p99_us"`
	DiskReadBytes           uint64  `json:"disk_read_bytes"`
	DiskWriteBytes          uint64  `json:"disk_write_bytes"`
}

type vcompDiskCounters struct {
	readBytes  uint64
	writeBytes uint64
}

type vcompMixGraphDistribution struct {
	cumulative []float64
	rangeSize  uint64
}

func vcompPaperWorkloadDefinition(name string) (definition, distribution string, ok bool) {
	switch strings.ToUpper(name) {
	case "A":
		return "50% read / 50% update", "scrambled Zipfian theta=0.99", true
	case "B":
		return "95% read / 5% update", "scrambled Zipfian theta=0.99", true
	case "C":
		return "100% read", "scrambled Zipfian theta=0.99", true
	case "D":
		return "95% read / 5% insert", "latest theta=0.99", true
	case "E":
		return "95% scan / 5% insert", "scrambled Zipfian theta=0.99", true
	case "F":
		return "50% read / 50% read-modify-write", "scrambled Zipfian theta=0.99", true
	case "MIXGRAPH":
		return "83% get / 14% put / 3% seek", "MixGraph power + two-term-exponential key ranges", true
	default:
		return "", "", false
	}
}

func vcompReadDiskCounters(device string) (vcompDiskCounters, error) {
	contents, err := os.ReadFile("/proc/diskstats")
	if err != nil {
		return vcompDiskCounters{}, err
	}
	for _, line := range strings.Split(string(contents), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 10 || fields[2] != device {
			continue
		}
		readSectors, readErr := strconv.ParseUint(fields[5], 10, 64)
		writeSectors, writeErr := strconv.ParseUint(fields[9], 10, 64)
		if readErr != nil || writeErr != nil {
			return vcompDiskCounters{}, fmt.Errorf("parse /proc/diskstats entry for %s", device)
		}
		return vcompDiskCounters{readBytes: readSectors * 512, writeBytes: writeSectors * 512}, nil
	}
	return vcompDiskCounters{}, fmt.Errorf("block device %q not found in /proc/diskstats", device)
}

func vcompCreateCheckpoint(source, destination string) error {
	if _, err := os.Stat(destination); !errors.Is(err, os.ErrNotExist) {
		if err == nil {
			return fmt.Errorf("checkpoint %q already exists; refusing to overwrite it", destination)
		}
		return err
	}
	if err := os.MkdirAll(filepath.Dir(destination), 0o755); err != nil {
		return err
	}
	opts := vcompOptions()
	opts.DisableAutomaticCompactions = true
	opts.CacheSize = 8 << 20
	d, err := Open(source, opts)
	if err != nil {
		return err
	}
	checkpointErr := d.Checkpoint(destination)
	if closeErr := d.Close(); checkpointErr == nil {
		checkpointErr = closeErr
	}
	return checkpointErr
}

func vcompRemoveWorkloadCheckpoint(root, workload, system, runDB string) error {
	expected, err := filepath.Abs(filepath.Join(root, "paper-workloads", "db", workload, system))
	if err != nil {
		return err
	}
	actual, err := filepath.Abs(runDB)
	if err != nil {
		return err
	}
	if actual != expected {
		return fmt.Errorf("refusing to remove unexpected workload checkpoint %q; want %q", actual, expected)
	}
	if actual == root || actual == filepath.Join(root, "baseline") || actual == filepath.Join(root, "virtual") {
		return fmt.Errorf("refusing to remove canonical DB path %q", actual)
	}
	return os.RemoveAll(actual)
}

// vcompDropSSTPageCache starts each workload with cold SST data without
// requiring a privileged, system-wide /proc/sys/vm/drop_caches operation.
func vcompDropSSTPageCache(dir string) error {
	return filepath.WalkDir(dir, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() || filepath.Ext(path) != ".sst" {
			return nil
		}
		file, err := os.Open(path)
		if err != nil {
			return err
		}
		err = unix.Fadvise(int(file.Fd()), 0, 0, unix.FADV_DONTNEED)
		if closeErr := file.Close(); err == nil {
			err = closeErr
		}
		return err
	})
}

func vcompNewMixGraphDistribution(keyspace uint64) vcompMixGraphDistribution {
	const ranges = 30
	weights := make([]float64, ranges)
	for i := range weights {
		pfx := float64(ranges - i)
		weights[i] = 14.18*math.Exp(-2.917*pfx) + 0.0164*math.Exp(-0.08082*pfx)
	}
	shuffle := rand.New(rand.NewPCG(0x6d69786772617068, 30))
	shuffle.Shuffle(len(weights), func(i, j int) { weights[i], weights[j] = weights[j], weights[i] })
	var sum float64
	for _, weight := range weights {
		sum += weight
	}
	var running float64
	for i, weight := range weights {
		running += weight / sum
		weights[i] = running
	}
	weights[len(weights)-1] = 1
	return vcompMixGraphDistribution{cumulative: weights, rangeSize: max(uint64(1), keyspace/ranges)}
}

func (d vcompMixGraphDistribution) key(random uint64) uint64 {
	u := (float64(random>>11) + 1) / (float64(uint64(1)<<53) + 1)
	rangeID := 0
	for rangeID < len(d.cumulative)-1 && u >= d.cumulative[rangeID] {
		rangeID++
	}
	within := float64(random%d.rangeSize) / float64(d.rangeSize)
	keySeed := uint64(math.Ceil(math.Pow(within/0.002312, 1/0.3467)))
	offset := vcompSplitMix64(keySeed) % d.rangeSize
	return uint64(rangeID)*d.rangeSize + offset
}

func vcompPareto(random uint64, k, sigma float64) uint64 {
	u := (float64(random>>11) + 1) / (float64(uint64(1)<<53) + 1)
	return uint64(math.Ceil(sigma * (math.Pow(u, -k) - 1) / k))
}

func vcompPaperGet(
	d *DB, key []byte, copyValue bool,
	histogram *vcompLatencyHistogram, reads *atomic.Uint64,
) ([]byte, bool, error) {
	started := time.Now()
	value, closer, err := d.Get(key)
	histogram.record(time.Since(started))
	reads.Add(1)
	if errors.Is(err, ErrNotFound) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	var result []byte
	if copyValue {
		result = append([]byte(nil), value...)
	} else {
		_ = len(value)
	}
	if closeErr := closer.Close(); closeErr != nil {
		return nil, false, closeErr
	}
	return result, true, nil
}

func vcompRandomValuePool(rng *rand.Rand) []byte {
	pool := make([]byte, 1<<20)
	for offset := 0; offset < len(pool); offset += 8 {
		binary.LittleEndian.PutUint64(pool[offset:], rng.Uint64())
	}
	return pool
}

func vcompValueFromPool(pool []byte, random uint64, size int) []byte {
	if size > len(pool) {
		size = len(pool)
	}
	start := int(random % uint64(len(pool)-size+1))
	return pool[start : start+size]
}

func runVCompPaperWorkload(
	dir, root, system, workload string, config vcompPaperWorkloadConfig, seed uint64,
) (result vcompPaperWorkloadResult, err error) {
	definition, distribution, ok := vcompPaperWorkloadDefinition(workload)
	if !ok {
		return vcompPaperWorkloadResult{}, fmt.Errorf("unknown paper workload %q", workload)
	}
	opts := vcompOptions()
	opts.CacheSize = config.cacheBytes
	d, err := Open(dir, opts)
	if err != nil {
		return vcompPaperWorkloadResult{}, err
	}
	defer func() {
		if closeErr := d.Close(); err == nil {
			err = closeErr
		}
	}()

	result = vcompPaperWorkloadResult{
		DBRoot:           root,
		System:           system,
		Workload:         workload,
		Definition:       definition,
		KeyDistribution:  distribution,
		RequestedSeconds: config.duration.Seconds(),
		Concurrency:      config.concurrency,
		BlockCacheBytes:  config.cacheBytes,
	}
	histogram := newVCompLatencyHistogram()
	var operations, pointReads, nextInsert atomic.Uint64
	nextInsert.Store(config.keyspace)
	latest, err := randvar.NewSkewedLatest(0, config.keyspace-1, 0.99)
	if err != nil {
		return vcompPaperWorkloadResult{}, err
	}
	mixDist := vcompNewMixGraphDistribution(config.keyspace)
	var firstErr error
	var errOnce sync.Once
	startSignal := make(chan struct{})
	stop := make(chan struct{})
	var workers sync.WaitGroup

	for worker := range config.concurrency {
		workers.Go(func() {
			rng := rand.New(rand.NewPCG(seed+uint64(worker), seed^uint64(worker+1)))
			zipf, zipfErr := randvar.NewDefaultZipf()
			if zipfErr != nil {
				errOnce.Do(func() { firstErr = zipfErr })
				return
			}
			pool := vcompRandomValuePool(rng)
			var key [vcompKeySize]byte
			<-startSignal
			for {
				select {
				case <-stop:
					return
				default:
				}
				random := rng.Uint64()
				choice := random % 100
				keyNumber := vcompYCSBHashKey(zipf.Uint64(rng)) % config.keyspace
				operation := strings.ToUpper(workload)
				if operation == "MIXGRAPH" {
					keyNumber = mixDist.key(random)
				} else if operation == "D" && choice < 95 {
					keyNumber = latest.Uint64(rng)
				}
				binary.BigEndian.PutUint64(key[vcompKeySize-8:], keyNumber)
				var operationErr error
				switch operation {
				case "A":
					if choice < 50 {
						_, _, operationErr = vcompPaperGet(d, key[:], false, histogram, &pointReads)
					} else {
						operationErr = d.Set(key[:], vcompValueFromPool(pool, random, config.valueSize), NoSync)
					}
				case "B":
					if choice < 95 {
						_, _, operationErr = vcompPaperGet(d, key[:], false, histogram, &pointReads)
					} else {
						operationErr = d.Set(key[:], vcompValueFromPool(pool, random, config.valueSize), NoSync)
					}
				case "C":
					_, _, operationErr = vcompPaperGet(d, key[:], false, histogram, &pointReads)
				case "D":
					if choice < 95 {
						_, _, operationErr = vcompPaperGet(d, key[:], false, histogram, &pointReads)
					} else {
						keyNumber = nextInsert.Add(1) - 1
						binary.BigEndian.PutUint64(key[vcompKeySize-8:], keyNumber)
						operationErr = d.Set(key[:], vcompValueFromPool(pool, random, config.valueSize), NoSync)
						latest.IncMax(1)
					}
				case "E":
					if choice < 95 {
						iter, iterErr := d.NewIter(nil)
						if iterErr == nil {
							for valid, remaining := iter.SeekGE(key[:]), 1+int(random%100); valid && remaining > 0; valid, remaining = iter.Next(), remaining-1 {
								_ = len(iter.Value())
							}
							iterErr = iter.Error()
							if closeErr := iter.Close(); iterErr == nil {
								iterErr = closeErr
							}
						}
						operationErr = iterErr
					} else {
						keyNumber = nextInsert.Add(1) - 1
						binary.BigEndian.PutUint64(key[vcompKeySize-8:], keyNumber)
						operationErr = d.Set(key[:], vcompValueFromPool(pool, random, config.valueSize), NoSync)
					}
				case "F":
					if choice < 50 {
						_, _, operationErr = vcompPaperGet(d, key[:], false, histogram, &pointReads)
					} else {
						value, found, getErr := vcompPaperGet(d, key[:], true, histogram, &pointReads)
						if getErr != nil {
							operationErr = getErr
						} else {
							if !found {
								value = append([]byte(nil), vcompValueFromPool(pool, random, config.valueSize)...)
							}
							if len(value) > 0 {
								value[random%uint64(len(value))]++
							}
							operationErr = d.Set(key[:], value, NoSync)
						}
					}
				case "MIXGRAPH":
					mixChoice := random % 1000
					if mixChoice < 830 {
						_, _, operationErr = vcompPaperGet(d, key[:], false, histogram, &pointReads)
					} else if mixChoice < 970 {
						valueSize := int(vcompPareto(random, 0.2615, 25.45))
						if valueSize < 10 {
							valueSize = 10
						} else if valueSize > 1024 {
							valueSize %= 1024
						}
						operationErr = d.Set(key[:], vcompValueFromPool(pool, random, valueSize), NoSync)
					} else {
						iter, iterErr := d.NewIter(nil)
						if iterErr == nil {
							scanLength := int(vcompPareto(random, 2.517, 14.236) % 10000)
							for valid, remaining := iter.SeekGE(key[:]), scanLength; valid && remaining > 0; valid, remaining = iter.Next(), remaining-1 {
								_ = len(iter.Value())
							}
							iterErr = iter.Error()
							if closeErr := iter.Close(); iterErr == nil {
								iterErr = closeErr
							}
						}
						operationErr = iterErr
					}
				}
				if operationErr != nil {
					errOnce.Do(func() { firstErr = operationErr })
					return
				}
				operations.Add(1)
			}
		})
	}

	diskDevice := os.Getenv("VCOMP_DISK_DEVICE")
	if diskDevice == "" {
		diskDevice = "md0"
	}
	diskStart, err := vcompReadDiskCounters(diskDevice)
	if err != nil {
		return vcompPaperWorkloadResult{}, err
	}
	started := time.Now()
	close(startSignal)
	timer := time.NewTimer(config.duration)
	<-timer.C
	close(stop)
	workers.Wait()
	result.WallSeconds = time.Since(started).Seconds()
	diskEnd, err := vcompReadDiskCounters(diskDevice)
	if err != nil {
		return vcompPaperWorkloadResult{}, err
	}
	if firstErr != nil {
		return vcompPaperWorkloadResult{}, firstErr
	}
	result.Operations = operations.Load()
	if result.WallSeconds > 0 {
		result.ThroughputOpsPerSecond = float64(result.Operations) / result.WallSeconds
	}
	readCount := pointReads.Load()
	if readCount > 0 {
		result.PointLookupLatencyP50US = histogram.percentile(readCount, 0.50)
		result.PointLookupLatencyP95US = histogram.percentile(readCount, 0.95)
		result.PointLookupLatencyP99US = histogram.percentile(readCount, 0.99)
	}
	result.DiskReadBytes = diskEnd.readBytes - diskStart.readBytes
	result.DiskWriteBytes = diskEnd.writeBytes - diskStart.writeBytes
	return result, nil
}

func TestVCompPaperWorkloadExisting(t *testing.T) {
	if os.Getenv("VCOMP_PAPER_WORKLOAD_EXPERIMENT") == "" {
		t.Skip("set VCOMP_PAPER_WORKLOAD_EXPERIMENT=1 to run a paper workload")
	}
	root, err := filepath.Abs(os.Getenv("VCOMP_EXISTING_DB_ROOT"))
	if err != nil || root == "" {
		t.Fatalf("invalid VCOMP_EXISTING_DB_ROOT: %v", err)
	}
	system := strings.ToLower(os.Getenv("VCOMP_PAPER_SYSTEM"))
	if system != "baseline" && system != "virtual" {
		t.Fatalf("VCOMP_PAPER_SYSTEM must be baseline or virtual, got %q", system)
	}
	workload := strings.ToUpper(os.Getenv("VCOMP_PAPER_WORKLOAD"))
	if _, _, ok := vcompPaperWorkloadDefinition(workload); !ok {
		t.Fatalf("VCOMP_PAPER_WORKLOAD must be A-F or MixGraph, got %q", workload)
	}
	keyspace, err := strconv.ParseUint(os.Getenv("VCOMP_WRITES"), 10, 64)
	if err != nil || keyspace == 0 {
		t.Fatalf("invalid VCOMP_WRITES %q", os.Getenv("VCOMP_WRITES"))
	}
	valueSize := 1000
	if text := os.Getenv("VCOMP_VALUE_SIZE"); text != "" {
		valueSize, err = strconv.Atoi(text)
		if err != nil || valueSize <= 0 {
			t.Fatalf("invalid VCOMP_VALUE_SIZE %q", text)
		}
	}
	baseConfig, err := vcompYCSBConfigFromEnv(keyspace, valueSize)
	if err != nil {
		t.Fatal(err)
	}
	config := vcompPaperWorkloadConfig{
		duration: baseConfig.duration, concurrency: baseConfig.concurrency,
		cacheBytes: baseConfig.cacheBytes, valueSize: valueSize, keyspace: keyspace,
	}
	source := filepath.Join(root, system)
	if info, statErr := os.Stat(source); statErr != nil || !info.IsDir() {
		t.Fatalf("source DB %q is unavailable: %v", source, statErr)
	}
	runDB := filepath.Join(root, "paper-workloads", "db", workload, system)
	if err := vcompCreateCheckpoint(source, runDB); err != nil {
		t.Fatal(err)
	}
	if err := vcompDropSSTPageCache(runDB); err != nil {
		t.Fatal(err)
	}
	runtime.GC()
	result, err := runVCompPaperWorkload(
		runDB, root, system, workload, config, uint64(0x5eed1234)^uint64(len(workload)),
	)
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	encoded = append(encoded, '\n')
	resultDir := filepath.Join(root, "paper-workloads", "results")
	if err := os.MkdirAll(resultDir, 0o755); err != nil {
		t.Fatal(err)
	}
	resultPath := filepath.Join(resultDir, fmt.Sprintf("%s_%s.json", workload, system))
	if _, err := os.Stat(resultPath); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("result %q already exists; refusing to overwrite it", resultPath)
	}
	if err := os.WriteFile(resultPath, encoded, 0o644); err != nil {
		t.Fatal(err)
	}
	t.Logf("VCOMP_PAPER_WORKLOAD_RESULT\n%s", encoded)
	if os.Getenv("VCOMP_KEEP_WORKLOAD_DB") == "" {
		if err := vcompRemoveWorkloadCheckpoint(root, workload, system, runDB); err != nil {
			t.Fatal(err)
		}
		t.Logf("removed completed workload checkpoint %s; canonical DB retained", runDB)
	}
}
