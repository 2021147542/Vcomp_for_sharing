// Package vcomp implements the data-model portion of virtual compaction.
//
// It intentionally models only sorted, unique uint64 user keys. Pebble-specific
// compaction picking and output-boundary policy live outside this package.
package vcomp

import (
	"container/heap"
	"fmt"
	"math"
	"math/bits"
	"sort"
)

const (
	// DefaultKMVSamples is the total KMV sample budget per virtual SST.
	DefaultKMVSamples = 512
	// DefaultKMVRangeBuckets is the number of range-local KMV sketches.
	DefaultKMVRangeBuckets = 8
)

const maxUint64 = ^uint64(0)

// Segment represents position(key) = Slope*key + Intercept over the inclusive
// key range [KeyStart, KeyEnd].
type Segment struct {
	KeyStart  uint64
	KeyEnd    uint64
	Slope     float64
	Intercept float64
}

// Model approximates the CDF of a sorted key sequence. Its segments map a key
// to its zero-based rank.
type Model struct {
	Segments []Segment
	// Discrete is the exact integer count/select certificate used by the
	// current RocksDB VComp design. Segments remain as the compact learned
	// approximation; correctness-sensitive splitting and materialization use
	// the certificate when it is present.
	Discrete *DiscreteCDF
}

// Empty reports whether the model has no segments.
func (m Model) Empty() bool { return len(m.Segments) == 0 }

// KeyMin returns the smallest key covered by the model.
func (m Model) KeyMin() uint64 {
	if len(m.Segments) == 0 {
		return 0
	}
	return m.Segments[0].KeyStart
}

// KeyMax returns the largest key covered by the model.
func (m Model) KeyMax() uint64 {
	if len(m.Segments) == 0 {
		return 0
	}
	return m.Segments[len(m.Segments)-1].KeyEnd
}

// Predict returns the estimated rank of key. Gaps between sparse segments are
// flat, matching CDF semantics.
func (m Model) Predict(key uint64) float64 {
	if m.Discrete != nil {
		return float64(m.Discrete.CountLessThan(key))
	}
	if len(m.Segments) == 0 {
		return 0
	}
	eval := func(seg Segment, k uint64) float64 {
		return seg.Slope*float64(k) + seg.Intercept
	}

	i := sort.Search(len(m.Segments), func(i int) bool {
		return m.Segments[i].KeyEnd >= key
	})
	if i == len(m.Segments) {
		last := m.Segments[len(m.Segments)-1]
		return eval(last, last.KeyEnd)
	}
	seg := m.Segments[i]
	if key < seg.KeyStart {
		if i == 0 {
			return 0
		}
		prev := m.Segments[i-1]
		return eval(prev, prev.KeyEnd)
	}
	return eval(seg, key)
}

// Inverse returns the estimated key at position. It chooses the segment whose
// position range contains position, or the closest segment when position falls
// between segment ranges.
func (m Model) Inverse(position float64) uint64 {
	if m.Discrete != nil && !m.Discrete.Empty() {
		rank := uint64(math.Max(0, math.Round(position)))
		if rank >= m.Discrete.Count() {
			rank = m.Discrete.Count() - 1
		}
		key, _ := m.Discrete.Select(rank)
		return key
	}
	if len(m.Segments) == 0 {
		return 0
	}

	best := 0
	bestDist := math.Inf(1)
	for i, seg := range m.Segments {
		posStart := seg.Slope*float64(seg.KeyStart) + seg.Intercept
		posEnd := seg.Slope*float64(seg.KeyEnd) + seg.Intercept
		lo, hi := math.Min(posStart, posEnd), math.Max(posStart, posEnd)
		if position >= lo && position <= hi {
			best = i
			break
		}
		dist := position - hi
		if position < lo {
			dist = lo - position
		}
		if dist < bestDist {
			bestDist = dist
			best = i
		}
	}

	seg := m.Segments[best]
	if math.Abs(seg.Slope) < 1e-15 {
		return (seg.KeyStart + seg.KeyEnd) / 2
	}
	key := (position - seg.Intercept) / seg.Slope
	key = math.Max(key, float64(seg.KeyStart))
	key = math.Min(key, float64(seg.KeyEnd))
	return uint64(math.Round(key))
}

// GreedyFit builds a piecewise-linear model using the shrinking-cone
// algorithm. sortedKeys must be in ascending order. The virtual-loading path
// supplies unique keys, although duplicate handling is retained for parity
// with the C++ implementation.
func GreedyFit(sortedKeys []uint64, errorBound float64) Model {
	if len(sortedKeys) == 0 {
		return Model{}
	}

	segments := make([]Segment, 0)
	for segStart := 0; segStart < len(sortedKeys); {
		if segStart == len(sortedKeys)-1 {
			segments = append(segments, Segment{
				KeyStart:  sortedKeys[segStart],
				KeyEnd:    sortedKeys[segStart],
				Slope:     0,
				Intercept: float64(segStart),
			})
			break
		}

		x0 := float64(sortedKeys[segStart])
		y0 := float64(segStart)
		sLo, sHi := math.Inf(-1), math.Inf(1)
		segEnd := segStart

		for i := segStart + 1; i < len(sortedKeys); i++ {
			dx := float64(sortedKeys[i]) - x0
			dy := float64(i) - y0
			if dx <= 0 {
				segEnd = i
				continue
			}

			newSLo := (dy - errorBound) / dx
			newSHi := (dy + errorBound) / dx
			if newSLo > sHi || newSHi < sLo {
				break
			}
			sLo = math.Max(sLo, newSLo)
			sHi = math.Min(sHi, newSHi)
			segEnd = i
		}

		var slope float64
		switch {
		case math.IsInf(sLo, 0) && math.IsInf(sHi, 0):
			slope = 0
		case math.IsInf(sLo, 0):
			slope = sHi
		case math.IsInf(sHi, 0):
			slope = sLo
		default:
			slope = (sLo + sHi) / 2
		}
		segments = append(segments, Segment{
			KeyStart:  sortedKeys[segStart],
			KeyEnd:    sortedKeys[segEnd],
			Slope:     slope,
			Intercept: y0 - slope*x0,
		})
		segStart = segEnd + 1
	}
	return Model{Segments: segments}
}

// KMVSample keeps the original key as well as its hash so unions are
// collision-safe and samples may be filtered by key range.
type KMVSample struct {
	Key  uint64
	Hash uint64
}

// KMVSketch contains the bottom-K hashes. ThetaHash is the largest retained
// hash for an incomplete sketch. A complete sketch contains every key.
type KMVSketch struct {
	Samples   []KMVSample
	ThetaHash uint64
	Complete  bool
}

// KMVRangeSketch is a range-local KMV sketch.
type KMVRangeSketch struct {
	KeyMin              uint64
	KeyMax              uint64
	NumEntries          uint64
	RawEstimatedEntries uint64
	EntriesAreModeled   bool
	Sketch              KMVSketch
}

// VirtualSST is the compact descriptor carried through virtual compactions.
type VirtualSST struct {
	Model      Model
	KMV        KMVSketch
	KMVRanges  []KMVRangeSketch
	KeyMin     uint64
	KeyMax     uint64
	NumEntries uint64
	Level      int
	SizeBytes  uint64
}

func kmvHash(x uint64) uint64 {
	x += 0x9e3779b97f4a7c15
	x = (x ^ (x >> 30)) * 0xbf58476d1ce4e5b9
	x = (x ^ (x >> 27)) * 0x94d049bb133111eb
	return x ^ (x >> 31)
}

func sampleLess(a, b KMVSample) bool {
	if a.Hash != b.Hash {
		return a.Hash < b.Hash
	}
	return a.Key < b.Key
}

type sampleMaxHeap []KMVSample

func (h sampleMaxHeap) Len() int { return len(h) }
func (h sampleMaxHeap) Less(i, j int) bool {
	return sampleLess(h[j], h[i])
}
func (h sampleMaxHeap) Swap(i, j int) { h[i], h[j] = h[j], h[i] }
func (h *sampleMaxHeap) Push(x any)   { *h = append(*h, x.(KMVSample)) }
func (h *sampleMaxHeap) Pop() any {
	old := *h
	n := len(old)
	x := old[n-1]
	*h = old[:n-1]
	return x
}

func sortUniqueByKey(samples []KMVSample) []KMVSample {
	if len(samples) == 0 {
		return samples
	}
	sort.Slice(samples, func(i, j int) bool {
		if samples[i].Key != samples[j].Key {
			return samples[i].Key < samples[j].Key
		}
		return samples[i].Hash < samples[j].Hash
	})
	w := 1
	for i := 1; i < len(samples); i++ {
		if samples[i].Key == samples[w-1].Key {
			continue
		}
		samples[w] = samples[i]
		w++
	}
	return samples[:w]
}

func sortByHashAndTrim(samples []KMVSample, maxSamples int) []KMVSample {
	sort.Slice(samples, func(i, j int) bool {
		return sampleLess(samples[i], samples[j])
	})
	if maxSamples > 0 && len(samples) > maxSamples {
		return samples[:maxSamples]
	}
	return samples
}

func buildKMV(sortedKeys []uint64, maxSamples int) KMVSketch {
	sketch := KMVSketch{ThetaHash: maxUint64}
	if len(sortedKeys) == 0 {
		return sketch
	}
	if maxSamples == 0 {
		maxSamples = DefaultKMVSamples
	}

	h := make(sampleMaxHeap, 0, min(len(sortedKeys), maxSamples))
	uniqueCount := uint64(0)
	var last uint64
	haveLast := false
	for _, key := range sortedKeys {
		if haveLast && key == last {
			continue
		}
		haveLast, last = true, key
		uniqueCount++
		sample := KMVSample{Key: key, Hash: kmvHash(key)}
		if len(h) < maxSamples {
			heap.Push(&h, sample)
		} else if sampleLess(sample, h[0]) {
			h[0] = sample
			heap.Fix(&h, 0)
		}
	}

	sketch.Complete = uniqueCount <= uint64(maxSamples)
	sketch.Samples = sortByHashAndTrim([]KMVSample(h), maxSamples)
	if !sketch.Complete && len(sketch.Samples) > 0 {
		sketch.ThetaHash = sketch.Samples[len(sketch.Samples)-1].Hash
	}
	return sketch
}

// BuildKMV constructs the default 512-sample global KMV sketch.
func BuildKMV(sortedKeys []uint64) KMVSketch {
	return buildKMV(sortedKeys, DefaultKMVSamples)
}

// BuildRangeKMV constructs eight equal-entry-count range-local sketches and
// divides the global sample budget among them.
func BuildRangeKMV(sortedKeys []uint64) []KMVRangeSketch {
	return buildRangeKMV(sortedKeys, DefaultKMVSamples, DefaultKMVRangeBuckets)
}

func buildRangeKMV(sortedKeys []uint64, maxSamples, maxRanges int) []KMVRangeSketch {
	if len(sortedKeys) == 0 {
		return nil
	}
	if maxSamples == 0 {
		maxSamples = DefaultKMVSamples
	}
	if maxRanges == 0 {
		maxRanges = DefaultKMVRangeBuckets
	}
	rangeCount := min(maxRanges, len(sortedKeys))
	if rangeCount == 0 {
		return nil
	}
	samplesPerRange := max(1, maxSamples/rangeCount)
	ranges := make([]KMVRangeSketch, 0, rangeCount)
	for i := 0; i < rangeCount; i++ {
		begin := len(sortedKeys) * i / rangeCount
		end := len(sortedKeys) * (i + 1) / rangeCount
		if begin >= end {
			continue
		}
		ranges = append(ranges, KMVRangeSketch{
			KeyMin:              sortedKeys[begin],
			KeyMax:              sortedKeys[end-1],
			NumEntries:          uint64(end - begin),
			RawEstimatedEntries: uint64(end - begin),
			Sketch:              buildKMV(sortedKeys[begin:end], samplesPerRange),
		})
	}
	return ranges
}

// EstimateUnion estimates the union cardinality of inputs using their global
// KMV sketches. Missing sketches conservatively return naiveEntries.
func EstimateUnion(inputs []*VirtualSST, naiveEntries uint64) uint64 {
	if len(inputs) == 0 || naiveEntries == 0 {
		return naiveEntries
	}
	complete := true
	thetaHash := maxUint64
	reserve := 0
	for _, input := range inputs {
		if input == nil || (len(input.KMV.Samples) == 0 && !input.KMV.Complete) {
			return naiveEntries
		}
		complete = complete && input.KMV.Complete
		thetaHash = min(thetaHash, input.KMV.ThetaHash)
		reserve += len(input.KMV.Samples)
	}

	merged := make([]KMVSample, 0, reserve)
	for _, input := range inputs {
		for _, sample := range input.KMV.Samples {
			if sample.Hash <= thetaHash {
				merged = append(merged, sample)
			}
		}
	}
	sampledEntries := uint64(len(merged))
	merged = sortUniqueByKey(merged)
	sampledUnique := uint64(len(merged))
	if sampledEntries == 0 || sampledUnique >= sampledEntries {
		return naiveEntries
	}
	estimate := float64(naiveEntries) * float64(sampledUnique) / float64(sampledEntries)
	if complete {
		return max(uint64(1), uint64(math.Round(estimate)))
	}
	return max(uint64(1), uint64(math.Ceil(estimate)))
}

// estimateUnionTheta is the KMV cardinality estimator used by the paper-era
// Pebble port. It estimates the absolute union cardinality from the number of
// distinct samples under the inputs' common theta. The later dedup-ratio
// estimator is retained in EstimateUnion for discrete-CDF experiments, but it
// changes the accumulated tree shape at TiB scale and must be qualified
// separately.
func estimateUnionTheta(inputs []*VirtualSST, naiveEntries uint64) uint64 {
	if len(inputs) == 0 || naiveEntries == 0 {
		return naiveEntries
	}
	complete := true
	thetaHash := maxUint64
	reserve := 0
	for _, input := range inputs {
		if input == nil || (len(input.KMV.Samples) == 0 && !input.KMV.Complete) {
			return naiveEntries
		}
		complete = complete && input.KMV.Complete
		thetaHash = min(thetaHash, input.KMV.ThetaHash)
		reserve += len(input.KMV.Samples)
	}
	merged := make([]KMVSample, 0, reserve)
	for _, input := range inputs {
		for _, sample := range input.KMV.Samples {
			if sample.Hash <= thetaHash {
				merged = append(merged, sample)
			}
		}
	}
	merged = sortUniqueByKey(merged)
	if complete {
		return min(naiveEntries, uint64(len(merged)))
	}
	if len(merged) == 0 {
		return naiveEntries
	}
	theta := math.Ldexp(float64(thetaHash)+1, -64)
	if !(theta > 0) {
		return naiveEntries
	}
	estimate := float64(len(merged)) / theta
	if estimate >= float64(naiveEntries) {
		return naiveEntries
	}
	return max(uint64(1), uint64(math.Ceil(estimate)))
}

func estimateUnionForRange(inputs []*VirtualSST, keyMin, keyMax uint64) uint64 {
	if len(inputs) == 0 || keyMax < keyMin {
		return 0
	}
	type sketchPiece struct {
		sketch             *KMVSketch
		pieceMin, pieceMax uint64
		entries            uint64
	}

	pieces := make([]sketchPiece, 0)
	// densityEstimate is the sum of the input cardinalities in the requested
	// range before deduplication. Once a descriptor has a discrete certificate,
	// that certificate is the authoritative description of its reconstructed
	// distribution. Do not flatten it back into a uniform distribution within
	// the much coarser range-KMV buckets: doing so on every merge compounds CDF
	// error and eventually changes overlap-based compaction picks.
	densityEstimate := float64(0)
	for _, input := range inputs {
		if input == nil || input.KeyMax < keyMin || input.KeyMin > keyMax {
			continue
		}
		overlapMin := max(input.KeyMin, keyMin)
		overlapMax := min(input.KeyMax, keyMax)
		if input.Model.Discrete != nil {
			densityEstimate += float64(input.Model.Discrete.CountThrough(overlapMax) -
				input.Model.Discrete.CountLessThan(overlapMin))
		}
		if len(input.KMVRanges) > 0 {
			for i := range input.KMVRanges {
				r := &input.KMVRanges[i]
				if r.KeyMax < keyMin || r.KeyMin > keyMax {
					continue
				}
				entries := r.NumEntries
				if r.EntriesAreModeled {
					entries = r.RawEstimatedEntries
				}
				pieces = append(pieces, sketchPiece{&r.Sketch, r.KeyMin, r.KeyMax, entries})
				if input.Model.Discrete == nil {
					rangeMin := max(r.KeyMin, keyMin)
					rangeMax := min(r.KeyMax, keyMax)
					if rangeMax >= rangeMin && entries > 0 {
						pieceSpan := float64(r.KeyMax-r.KeyMin) + 1
						overlapSpan := float64(rangeMax-rangeMin) + 1
						if pieceSpan > 0 {
							densityEstimate += float64(entries) * overlapSpan / pieceSpan
						}
					}
				}
			}
		} else {
			pieces = append(pieces, sketchPiece{&input.KMV, input.KeyMin, input.KeyMax, input.NumEntries})
			if input.Model.Discrete == nil {
				pieceSpan := float64(input.KeyMax-input.KeyMin) + 1
				overlapSpan := float64(overlapMax-overlapMin) + 1
				if pieceSpan > 0 {
					densityEstimate += float64(input.NumEntries) * overlapSpan / pieceSpan
				}
			}
		}
	}
	if len(pieces) == 0 {
		return 0
	}

	thetaHash := maxUint64
	reserve := 0
	for _, piece := range pieces {
		thetaHash = min(thetaHash, piece.sketch.ThetaHash)
		reserve += len(piece.sketch.Samples)
	}
	if !(densityEstimate > 0) {
		return 0
	}

	merged := make([]KMVSample, 0, reserve)
	for _, piece := range pieces {
		for _, sample := range piece.sketch.Samples {
			if sample.Key >= keyMin && sample.Key <= keyMax && sample.Hash <= thetaHash {
				merged = append(merged, sample)
			}
		}
	}
	sampledEntries := uint64(len(merged))
	merged = sortUniqueByKey(merged)
	sampledUnique := uint64(len(merged))
	inputEntriesCap := uint64(math.Ceil(densityEstimate))
	inRange := densityEstimate
	if sampledEntries == 0 || sampledUnique >= sampledEntries {
		return min(inputEntriesCap, uint64(math.Ceil(inRange)))
	}
	estimate := inRange * float64(sampledUnique) / float64(sampledEntries)
	return min(inputEntriesCap, max(uint64(1), uint64(math.Ceil(estimate))))
}

func estimateUnionForRangeTheta(inputs []*VirtualSST, keyMin, keyMax uint64) uint64 {
	if len(inputs) == 0 || keyMax < keyMin {
		return 0
	}
	type sketchPiece struct {
		sketch             *KMVSketch
		pieceMin, pieceMax uint64
		entries            uint64
	}
	pieces := make([]sketchPiece, 0)
	for _, input := range inputs {
		if input == nil || input.KeyMax < keyMin || input.KeyMin > keyMax {
			continue
		}
		if len(input.KMVRanges) > 0 {
			for i := range input.KMVRanges {
				r := &input.KMVRanges[i]
				if r.KeyMax < keyMin || r.KeyMin > keyMax {
					continue
				}
				pieces = append(pieces, sketchPiece{&r.Sketch, r.KeyMin, r.KeyMax, r.NumEntries})
			}
		} else {
			pieces = append(pieces, sketchPiece{&input.KMV, input.KeyMin, input.KeyMax, input.NumEntries})
		}
	}
	if len(pieces) == 0 {
		return 0
	}
	complete := true
	thetaHash := maxUint64
	inputEntriesCap := uint64(0)
	densityEstimate := float64(0)
	reserve := 0
	for _, piece := range pieces {
		complete = complete && piece.sketch.Complete
		thetaHash = min(thetaHash, piece.sketch.ThetaHash)
		inputEntriesCap += piece.entries
		reserve += len(piece.sketch.Samples)
		overlapMin := max(piece.pieceMin, keyMin)
		overlapMax := min(piece.pieceMax, keyMax)
		if overlapMax >= overlapMin && piece.entries > 0 {
			pieceSpan := float64(piece.pieceMax-piece.pieceMin) + 1
			overlapSpan := float64(overlapMax-overlapMin) + 1
			if pieceSpan > 0 {
				densityEstimate += float64(piece.entries) * overlapSpan / pieceSpan
			}
		}
	}
	if inputEntriesCap == 0 {
		return 0
	}
	merged := make([]KMVSample, 0, reserve)
	for _, piece := range pieces {
		for _, sample := range piece.sketch.Samples {
			if sample.Key >= keyMin && sample.Key <= keyMax && sample.Hash <= thetaHash {
				merged = append(merged, sample)
			}
		}
	}
	merged = sortUniqueByKey(merged)
	if complete {
		return min(inputEntriesCap, uint64(len(merged)))
	}
	if len(merged) == 0 {
		return min(inputEntriesCap, uint64(math.Ceil(densityEstimate)))
	}
	theta := math.Ldexp(float64(thetaHash)+1, -64)
	if !(theta > 0) {
		return 0
	}
	estimate := float64(len(merged)) / theta
	return min(inputEntriesCap, max(uint64(1), uint64(math.Ceil(estimate))))
}

// MergeRangeAware merges learned-index shapes while using global KMV for total unique
// entries and range-local KMV to distribute that total over key intervals. It
// enables the experimental discrete count/select certificate.
func MergeRangeAware(inputs []*VirtualSST) (Model, uint64, error) {
	return mergeRangeAware(inputs, true, EstimateUnion, estimateUnionForRange)
}

// MergeRangeAwareContinuous runs the paper's continuous PLR merge without the
// later experimental discrete-CDF correction. Keeping this path explicit lets
// the Pebble harness qualify the correction independently instead of silently
// changing the tree shape used by paper-style experiments.
func MergeRangeAwareContinuous(inputs []*VirtualSST) (Model, uint64, error) {
	return mergeRangeAware(inputs, false, EstimateUnion, estimateUnionForRange)
}

// MergeRangeAwarePaper runs the original paper-path combination: continuous
// PLR merge plus common-theta KMV cardinality estimation.
func MergeRangeAwarePaper(inputs []*VirtualSST) (Model, uint64, error) {
	return mergeRangeAware(inputs, false, estimateUnionTheta, estimateUnionForRangeTheta)
}

func mergeRangeAware(
	inputs []*VirtualSST,
	useDiscrete bool,
	estimateTotal func([]*VirtualSST, uint64) uint64,
	estimateRange func([]*VirtualSST, uint64, uint64) uint64,
) (Model, uint64, error) {
	if len(inputs) == 0 {
		return Model{}, 0, nil
	}

	naiveEntries := uint64(0)
	breakpoints := make([]uint64, 0)
	for _, input := range inputs {
		if input == nil {
			continue
		}
		naiveEntries += input.NumEntries
		breakpoints = append(breakpoints, input.KeyMin, input.KeyMax)
		for _, seg := range input.Model.Segments {
			breakpoints = append(breakpoints, seg.KeyStart, seg.KeyEnd)
		}
	}
	kmvTotal := estimateTotal(inputs, naiveEntries)
	if useDiscrete {
		discrete, accepted, err := BuildDiscreteMergeModel(inputs, kmvTotal)
		if err != nil {
			return Model{}, 0, err
		}
		if !discrete.Empty() {
			return discrete, accepted, nil
		}
	}
	if len(breakpoints) == 0 {
		return Model{}, 0, nil
	}
	sort.Slice(breakpoints, func(i, j int) bool { return breakpoints[i] < breakpoints[j] })
	w := 1
	for i := 1; i < len(breakpoints); i++ {
		if breakpoints[i] != breakpoints[w-1] {
			breakpoints[w] = breakpoints[i]
			w++
		}
	}
	breakpoints = breakpoints[:w]
	if len(breakpoints) == 1 {
		return Model{Segments: []Segment{{
			KeyStart:  breakpoints[0],
			KeyEnd:    breakpoints[0],
			Slope:     0,
			Intercept: float64(kmvTotal) / 2,
		}}}, kmvTotal, nil
	}

	order := make([]int, len(inputs))
	for i := range order {
		order[i] = i
	}
	sort.SliceStable(order, func(a, b int) bool {
		left, right := inputs[order[a]], inputs[order[b]]
		if left == nil {
			return false
		}
		if right == nil {
			return true
		}
		return left.KeyMin < right.KeyMin
	})

	segIdx := make([]int, len(inputs))
	active := make([]int, 0, len(inputs))
	merged := make([]Segment, 0, len(breakpoints)-1)
	nextModel := 0
	rawTotal := float64(0)

	for i := 0; i+1 < len(breakpoints); i++ {
		keyStart, keyEnd := breakpoints[i], breakpoints[i+1]
		keyMid := keyStart + (keyEnd-keyStart)/2

		for nextModel < len(inputs) {
			j := order[nextModel]
			if inputs[j] == nil {
				nextModel++
				continue
			}
			if inputs[j].KeyMin > keyMid {
				break
			}
			active = append(active, j)
			nextModel++
		}

		write := 0
		for _, j := range active {
			if inputs[j] == nil || inputs[j].KeyMax < keyMid {
				continue
			}
			active[write] = j
			write++
		}
		active = active[:write]

		slopeSum := float64(0)
		intervalInputs := make([]*VirtualSST, 0, len(active))
		for _, j := range active {
			segments := inputs[j].Model.Segments
			for segIdx[j]+1 < len(segments) && segments[segIdx[j]].KeyEnd < keyMid {
				segIdx[j]++
			}
			if len(segments) == 0 {
				continue
			}
			seg := segments[segIdx[j]]
			if keyMid < seg.KeyStart || keyMid > seg.KeyEnd {
				continue
			}
			slopeSum += math.Max(seg.Slope, 0)
			intervalInputs = append(intervalInputs, inputs[j])
		}

		intervalEntries := estimateRange(intervalInputs, keyStart, keyEnd)
		rawTotal += float64(intervalEntries)
		width := math.Max(1, float64(keyEnd-keyStart))
		naiveMass := math.Max(0, slopeSum*width)
		slope := float64(0)
		if intervalEntries > 0 {
			if slopeSum > 0 && naiveMass > 0 {
				slope = slopeSum * (float64(intervalEntries) / naiveMass)
			} else {
				slope = float64(intervalEntries) / width
			}
		}
		merged = append(merged, Segment{KeyStart: keyStart, KeyEnd: keyEnd, Slope: slope})
	}

	if kmvTotal > 0 && rawTotal > 0 {
		scale := float64(kmvTotal) / rawTotal
		for i := range merged {
			merged[i].Slope *= scale
		}
	} else if kmvTotal > 0 && len(merged) > 0 {
		width := math.Max(1, float64(breakpoints[len(breakpoints)-1]-breakpoints[0]))
		slope := float64(kmvTotal) / width
		for i := range merged {
			merged[i].Slope = slope
		}
	}

	cumulativePos := float64(0)
	for i := range merged {
		merged[i].Intercept = cumulativePos - merged[i].Slope*float64(merged[i].KeyStart)
		cumulativePos += merged[i].Slope * float64(merged[i].KeyEnd-merged[i].KeyStart)
	}
	merged = compactSegments(merged)
	return Model{Segments: merged}, kmvTotal, nil
}

func compactSegments(segments []Segment) []Segment {
	if len(segments) < 2 {
		return segments
	}
	compacted := make([]Segment, 1, len(segments))
	compacted[0] = segments[0]
	for _, cur := range segments[1:] {
		prev := &compacted[len(compacted)-1]
		if math.Abs(prev.Slope-cur.Slope) < 1e-12 &&
			math.Abs(prev.Intercept-cur.Intercept) < 1e-9 {
			prev.KeyEnd = cur.KeyEnd
			continue
		}
		compacted = append(compacted, cur)
	}
	return compacted
}

// MergeKMVForRange merges input sketches and retains samples in the inclusive
// range [keyMin, keyMax].
func MergeKMVForRange(inputs []*VirtualSST, keyMin, keyMax uint64) KMVSketch {
	return mergeKMVForRange(inputs, keyMin, keyMax, DefaultKMVSamples)
}

func mergeKMVForRange(inputs []*VirtualSST, keyMin, keyMax uint64, maxSamples int) KMVSketch {
	result := KMVSketch{ThetaHash: maxUint64}
	if len(inputs) == 0 || keyMax < keyMin {
		return result
	}
	pieces := make([]*KMVSketch, 0)
	for _, input := range inputs {
		if input == nil || input.KeyMax < keyMin || input.KeyMin > keyMax {
			continue
		}
		if len(input.KMVRanges) > 0 {
			for i := range input.KMVRanges {
				r := &input.KMVRanges[i]
				if r.KeyMax < keyMin || r.KeyMin > keyMax {
					continue
				}
				pieces = append(pieces, &r.Sketch)
			}
		} else {
			pieces = append(pieces, &input.KMV)
		}
	}
	if len(pieces) == 0 {
		return result
	}

	complete := true
	thetaHash := maxUint64
	reserve := 0
	for _, piece := range pieces {
		complete = complete && piece.Complete
		thetaHash = min(thetaHash, piece.ThetaHash)
		reserve += len(piece.Samples)
	}
	samples := make([]KMVSample, 0, reserve)
	for _, piece := range pieces {
		for _, sample := range piece.Samples {
			if sample.Key >= keyMin && sample.Key <= keyMax && sample.Hash <= thetaHash {
				samples = append(samples, sample)
			}
		}
	}
	samples = sortUniqueByKey(samples)
	trimmed := maxSamples > 0 && len(samples) > maxSamples
	if complete && trimmed {
		complete = false
	}
	samples = sortByHashAndTrim(samples, maxSamples)
	result.Samples = samples
	result.Complete = complete
	if complete {
		result.ThetaHash = maxUint64
	} else if trimmed && len(samples) > 0 {
		result.ThetaHash = samples[len(samples)-1].Hash
	} else {
		result.ThetaHash = thetaHash
	}
	return result
}

func mergeRangeKMVForOutput(inputs []*VirtualSST, keyMin, keyMax, numEntries uint64, model *DiscreteCDF) []KMVRangeSketch {
	if len(inputs) == 0 || numEntries == 0 || keyMax < keyMin {
		return nil
	}
	rangeCount := int(min(uint64(DefaultKMVRangeBuckets), numEntries))
	if rangeCount == 0 {
		return nil
	}
	samplesPerRange := max(1, DefaultKMVSamples/rangeCount)
	ranges := make([]KMVRangeSketch, 0, rangeCount)

	spanLo := keyMax - keyMin + 1
	spanHi := uint64(0)
	if spanLo == 0 {
		spanHi = 1
	}
	for i := 0; i < rangeCount; i++ {
		startOff, _ := scaledOffset(spanHi, spanLo, uint64(i), uint64(rangeCount))
		endOff, endOverflow := scaledOffset(spanHi, spanLo, uint64(i+1), uint64(rangeCount))
		rangeMin := keyMin + startOff
		var rangeMax uint64
		if endOverflow {
			rangeMax = maxUint64
		} else if endOff == 0 {
			rangeMax = keyMin
		} else {
			rangeMax = keyMin + endOff - 1
		}
		if rangeMax < rangeMin {
			rangeMax = rangeMin
		}

		raw := estimateUnionForRange(inputs, rangeMin, rangeMax)
		modeled := raw
		if model != nil {
			modeled = model.CountThrough(rangeMax) - model.CountLessThan(rangeMin)
		}
		r := KMVRangeSketch{
			KeyMin:              rangeMin,
			KeyMax:              rangeMax,
			NumEntries:          modeled,
			RawEstimatedEntries: raw,
			EntriesAreModeled:   model != nil,
			Sketch:              mergeKMVForRange(inputs, rangeMin, rangeMax, samplesPerRange),
		}
		if r.NumEntries > 0 || len(r.Sketch.Samples) > 0 {
			ranges = append(ranges, r)
		}
	}
	return ranges
}

// scaledOffset computes floor((span*i)/count). span is represented by the
// two-word value (spanHi, spanLo), and may equal 2^64.
func scaledOffset(spanHi, spanLo, i, count uint64) (offset uint64, overflow bool) {
	if i == count {
		if spanHi != 0 {
			return 0, true
		}
		return spanLo, false
	}
	hi, lo := bits.Mul64(spanLo, i)
	hi += spanHi * i
	q, _ := bits.Div64(hi, lo, count)
	return q, false
}

// SliceInto partitions model at caller-provided rank positions. splitPositions
// must be sorted and identify half-open position ranges. The function performs
// no Pebble-specific output-boundary selection.
func SliceInto(
	model Model,
	totalEntries uint64,
	splitPositions []uint64,
	level int,
	sizeModel SSTSizeModel,
	inputs []*VirtualSST,
) []VirtualSST {
	if totalEntries == 0 || model.Empty() {
		return nil
	}
	globalMin, globalMax := model.KeyMin(), model.KeyMax()
	result := make([]VirtualSST, 0, len(splitPositions)+1)

	for i := 0; i <= len(splitPositions); i++ {
		posStart := uint64(0)
		if i > 0 {
			posStart = splitPositions[i-1]
		}
		posEnd := totalEntries
		if i < len(splitPositions) {
			posEnd = splitPositions[i]
		}
		if posStart >= totalEntries || posEnd <= posStart {
			continue
		}

		keyStart := globalMin
		if i > 0 {
			keyStart = model.Inverse(float64(posStart))
		}
		keyEnd := globalMax
		if i < len(splitPositions) {
			keyEnd = model.Inverse(float64(posEnd - 1))
		}
		if keyEnd < keyStart {
			keyEnd = keyStart
		}

		subSegments := make([]Segment, 0)
		for _, seg := range model.Segments {
			if seg.KeyEnd < keyStart {
				continue
			}
			if seg.KeyStart > keyEnd {
				break
			}
			seg.KeyStart = max(seg.KeyStart, keyStart)
			seg.KeyEnd = min(seg.KeyEnd, keyEnd)
			seg.Intercept -= float64(posStart)
			subSegments = append(subSegments, seg)
		}

		n := posEnd - posStart
		vsst := VirtualSST{
			Model:      Model{Segments: subSegments},
			KeyMin:     keyStart,
			KeyMax:     keyEnd,
			NumEntries: n,
			Level:      level,
			SizeBytes:  sizeModel.Estimate(n),
			KMV:        KMVSketch{ThetaHash: maxUint64},
		}
		if model.Discrete != nil {
			child, err := model.Discrete.Slice(posStart, n)
			if err != nil {
				return nil
			}
			vsst.Model.Discrete = child
			keyStart, _ = child.Select(0)
			keyEnd, _ = child.Select(n - 1)
			vsst.KeyMin, vsst.KeyMax = keyStart, keyEnd
		}
		if inputs != nil {
			vsst.KMV = mergeKMVForRange(inputs, keyStart, keyEnd, DefaultKMVSamples)
			vsst.KMVRanges = mergeRangeKMVForOutput(inputs, keyStart, keyEnd, n, vsst.Model.Discrete)
		}
		result = append(result, vsst)
	}
	return result
}

// Materialize reconstructs sorted keys by walking a virtual SST's learned-index model.
func Materialize(v VirtualSST) ([]uint64, error) {
	if v.NumEntries == 0 || v.Model.Empty() {
		return nil, nil
	}
	if v.Model.Discrete != nil {
		if v.Model.Discrete.Count() != v.NumEntries {
			return nil, fmt.Errorf("materialize: certificate count %d differs from descriptor count %d", v.Model.Discrete.Count(), v.NumEntries)
		}
		keys := make([]uint64, 0, v.NumEntries)
		cursor := v.Model.Discrete.NewCursor()
		for {
			key, ok := cursor.Next()
			if !ok {
				break
			}
			if len(keys) > 0 && key <= keys[len(keys)-1] {
				return nil, fmt.Errorf("materialize: certificate emitted unordered key")
			}
			keys = append(keys, key)
		}
		if uint64(len(keys)) != v.NumEntries {
			return nil, fmt.Errorf("materialize: emitted %d keys, expected %d", len(keys), v.NumEntries)
		}
		return keys, nil
	}
	keys := make([]uint64, 0, v.NumEntries)
	segments := v.Model.Segments
	segIdx := 0
	for pos := uint64(0); pos < v.NumEntries; pos++ {
		position := float64(pos)
		for segIdx+1 < len(segments) {
			seg := segments[segIdx]
			posEnd := seg.Slope*float64(seg.KeyEnd) + seg.Intercept
			if position <= posEnd {
				break
			}
			segIdx++
		}

		seg := segments[segIdx]
		var key uint64
		if math.Abs(seg.Slope) < 1e-15 {
			key = (seg.KeyStart + seg.KeyEnd) / 2
		} else {
			keyFloat := (position - seg.Intercept) / seg.Slope
			keyFloat = math.Max(keyFloat, float64(seg.KeyStart))
			keyFloat = math.Min(keyFloat, float64(seg.KeyEnd))
			key = uint64(math.Round(keyFloat))
		}
		key = max(key, v.KeyMin)
		key = min(key, v.KeyMax)
		keys = append(keys, key)
	}

	for i := 1; i < len(keys); i++ {
		if keys[i] <= keys[i-1] {
			keys[i] = keys[i-1] + 1
		}
	}
	if len(keys) > 0 && keys[len(keys)-1] > v.KeyMax {
		for i := len(keys); i > 0; i-- {
			if keys[i-1] > v.KeyMax {
				keys[i-1] = v.KeyMax
			} else {
				break
			}
		}
	}

	// Rounding followed by the key_max clamp can collapse adjacent ranks onto
	// the same integer key. A real SST requires strictly increasing user keys,
	// so retain one copy of each reconstructed key. Do not synthesize replacement
	// keys: any resulting cardinality loss must remain visible to accuracy tests.
	write := 0
	for _, key := range keys {
		if write > 0 && key == keys[write-1] {
			continue
		}
		keys[write] = key
		write++
	}
	if uint64(write) != v.NumEntries {
		return nil, fmt.Errorf("materialize: continuous model emitted %d distinct keys, expected %d", write, v.NumEntries)
	}
	return keys[:write], nil
}
