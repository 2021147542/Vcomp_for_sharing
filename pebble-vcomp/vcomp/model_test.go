package vcomp

import (
	"math"
	"testing"
)

func keyRange(begin, end uint64) []uint64 {
	keys := make([]uint64, 0, end-begin)
	for key := begin; key < end; key++ {
		keys = append(keys, key)
	}
	return keys
}

func makeVirtualSST(keys []uint64, level int) VirtualSST {
	v := VirtualSST{
		Model:      GreedyFit(keys, 0),
		KMV:        BuildKMV(keys),
		KMVRanges:  BuildRangeKMV(keys),
		KeyMin:     keys[0],
		KeyMax:     keys[len(keys)-1],
		NumEntries: uint64(len(keys)),
		Level:      level,
		SizeBytes:  uint64(len(keys)) * 100,
	}
	if err := CertifyVirtualSST(&v); err != nil {
		panic(err)
	}
	return v
}

func TestGreedyFitPredictAndInverse(t *testing.T) {
	keys := []uint64{10, 20, 30, 40, 50}
	model := GreedyFit(keys, 0)
	for rank, key := range keys {
		if got := model.Predict(key); math.Abs(got-float64(rank)) > 1e-9 {
			t.Fatalf("Predict(%d)=%f, want %d", key, got, rank)
		}
		if got := model.Inverse(float64(rank)); got != key {
			t.Fatalf("Inverse(%d)=%d, want %d", rank, got, key)
		}
	}
}

func TestCompleteKMVDeduplicatesExactUnion(t *testing.T) {
	left := makeVirtualSST(keyRange(0, 32), 0)
	right := makeVirtualSST(keyRange(16, 48), 0)
	inputs := []*VirtualSST{&left, &right}

	if got := EstimateUnion(inputs, 64); got != 48 {
		t.Fatalf("union estimate=%d, want 48", got)
	}
	merged, entries, err := MergeRangeAware(inputs)
	if err != nil {
		t.Fatal(err)
	}
	if entries != 48 {
		t.Fatalf("merged entries=%d, want 48", entries)
	}
	if merged.Empty() || merged.KeyMin() != 0 || merged.KeyMax() != 47 {
		t.Fatalf("unexpected merged model range [%d,%d]", merged.KeyMin(), merged.KeyMax())
	}
}

func TestContinuousMergeDoesNotAttachDiscreteCertificate(t *testing.T) {
	left := makeVirtualSST(keyRange(0, 32), 0)
	right := makeVirtualSST(keyRange(16, 48), 0)
	merged, entries, err := MergeRangeAwareContinuous([]*VirtualSST{&left, &right})
	if err != nil {
		t.Fatal(err)
	}
	if entries != 48 {
		t.Fatalf("merged entries=%d, want 48", entries)
	}
	if merged.Discrete != nil {
		t.Fatal("continuous merge unexpectedly attached a discrete certificate")
	}
}

func TestPaperMergeUsesExactCompleteKMVUnion(t *testing.T) {
	left := makeVirtualSST(keyRange(0, 32), 0)
	right := makeVirtualSST(keyRange(16, 48), 0)
	merged, entries, err := MergeRangeAwarePaper([]*VirtualSST{&left, &right})
	if err != nil {
		t.Fatal(err)
	}
	if entries != 48 {
		t.Fatalf("merged entries=%d, want 48", entries)
	}
	if merged.Discrete != nil {
		t.Fatal("paper merge unexpectedly attached a discrete certificate")
	}
}

func TestIncompleteKMVEstimateIsBounded(t *testing.T) {
	leftKeys := keyRange(0, 1000)
	rightKeys := keyRange(500, 1500)
	left := makeVirtualSST(leftKeys, 0)
	right := makeVirtualSST(rightKeys, 0)
	left.KMV = buildKMV(leftKeys, 64)
	right.KMV = buildKMV(rightKeys, 64)
	inputs := []*VirtualSST{&left, &right}

	got := EstimateUnion(inputs, 2000)
	if got == 0 || got > 2000 {
		t.Fatalf("union estimate=%d, want 1..2000", got)
	}
}

func TestIncompleteKMVUsesDedupRatio(t *testing.T) {
	keys := keyRange(0, 1000)
	left, right := makeVirtualSST(keys, 0), makeVirtualSST(keys, 0)
	left.KMV, right.KMV = buildKMV(keys, 64), buildKMV(keys, 64)
	if got := EstimateUnion([]*VirtualSST{&left, &right}, 2000); got != 1000 {
		t.Fatalf("identical-input ratio estimate=%d, want 1000", got)
	}
}

func TestRangeEstimatePreservesCertifiedDistribution(t *testing.T) {
	cdf, err := NewDiscreteCDF([]DiscreteInterval{
		{KeyMin: 0, KeyMax: 99, NumEntries: 100},
		{KeyMin: 100, KeyMax: 999, NumEntries: 10},
	})
	if err != nil {
		t.Fatal(err)
	}
	input := VirtualSST{
		Model:      Model{Segments: []Segment{{KeyStart: 0, KeyEnd: 999}}, Discrete: cdf},
		KMV:        KMVSketch{ThetaHash: maxUint64},
		KMVRanges:  []KMVRangeSketch{{KeyMin: 0, KeyMax: 999, NumEntries: 110, RawEstimatedEntries: 110, EntriesAreModeled: true, Sketch: KMVSketch{ThetaHash: maxUint64}}},
		KeyMin:     0,
		KeyMax:     999,
		NumEntries: 110,
	}

	// The coarse range bucket is only 10% covered by [0,99], but the
	// certificate says that 100 of 110 entries are there. A later merge must
	// retain the certified shape instead of flattening it to 11 entries.
	if got := estimateUnionForRange([]*VirtualSST{&input}, 0, 99); got != 100 {
		t.Fatalf("range estimate=%d, want certified mass 100", got)
	}
}

func TestSlicePreservesCountAndOrderedRanges(t *testing.T) {
	source := makeVirtualSST(keyRange(0, 100), 0)
	outputs := SliceInto(source.Model, source.NumEntries, []uint64{25, 50, 75}, 1, LogicalSSTSizeModel(100), []*VirtualSST{&source})
	if len(outputs) != 4 {
		t.Fatalf("output count=%d, want 4", len(outputs))
	}
	var total uint64
	for i := range outputs {
		total += outputs[i].NumEntries
		if i > 0 && outputs[i-1].KeyMax >= outputs[i].KeyMin {
			t.Fatalf("ranges overlap or are unordered: [%d,%d], [%d,%d]",
				outputs[i-1].KeyMin, outputs[i-1].KeyMax,
				outputs[i].KeyMin, outputs[i].KeyMax)
		}
	}
	if total != source.NumEntries {
		t.Fatalf("output entries=%d, want %d", total, source.NumEntries)
	}
}

func TestMaterializedKeysAreIncreasingAndBounded(t *testing.T) {
	source := makeVirtualSST(keyRange(100, 200), 0)
	keys, err := Materialize(source)
	if err != nil {
		t.Fatal(err)
	}
	if uint64(len(keys)) != source.NumEntries {
		t.Fatalf("materialized %d keys, want %d", len(keys), source.NumEntries)
	}
	for i, key := range keys {
		if key < source.KeyMin || key > source.KeyMax {
			t.Fatalf("key %d outside [%d,%d]", key, source.KeyMin, source.KeyMax)
		}
		if i > 0 && key <= keys[i-1] {
			t.Fatalf("keys are not strictly increasing at %d: %d then %d", i, keys[i-1], key)
		}
	}
}

func TestMaterializeRejectsCountLoss(t *testing.T) {
	v := VirtualSST{
		Model:  Model{Segments: []Segment{{KeyStart: 0, KeyEnd: 1, Slope: 0}}},
		KeyMin: 0, KeyMax: 1, NumEntries: 4,
	}
	if _, err := Materialize(v); err == nil {
		t.Fatal("expected impossible descriptor cardinality to be rejected")
	}
}

func TestCertifyVirtualSSTGroupMovesInfeasibleCountToNearestCapacity(t *testing.T) {
	left := VirtualSST{
		Model:  Model{Segments: []Segment{{KeyStart: 0, KeyEnd: 1}}},
		KMV:    KMVSketch{ThetaHash: maxUint64},
		KeyMin: 0, KeyMax: 1, NumEntries: 4,
	}
	right := VirtualSST{
		Model:  Model{Segments: []Segment{{KeyStart: 2, KeyEnd: 9}}},
		KMV:    KMVSketch{ThetaHash: maxUint64},
		KeyMin: 2, KeyMax: 9, NumEntries: 2,
	}
	moved, err := CertifyVirtualSSTGroup([]*VirtualSST{&left, &right})
	if err != nil {
		t.Fatal(err)
	}
	if moved != 2 || left.NumEntries != 2 || right.NumEntries != 4 {
		t.Fatalf("moved=%d counts=(%d,%d), want 2 and (2,4)", moved, left.NumEntries, right.NumEntries)
	}
	for i, descriptor := range []*VirtualSST{&left, &right} {
		keys, err := Materialize(*descriptor)
		if err != nil {
			t.Fatalf("descriptor %d: %v", i, err)
		}
		if uint64(len(keys)) != descriptor.NumEntries {
			t.Fatalf("descriptor %d materialized %d keys, want %d", i, len(keys), descriptor.NumEntries)
		}
	}
}

func TestCertifyVirtualSSTGroupRejectsInsufficientCapacity(t *testing.T) {
	one := VirtualSST{
		Model:  Model{Segments: []Segment{{KeyStart: 0, KeyEnd: 1}}},
		KMV:    KMVSketch{ThetaHash: maxUint64},
		KeyMin: 0, KeyMax: 1, NumEntries: 3,
	}
	two := VirtualSST{
		Model:  Model{Segments: []Segment{{KeyStart: 2, KeyEnd: 3}}},
		KMV:    KMVSketch{ThetaHash: maxUint64},
		KeyMin: 2, KeyMax: 3, NumEntries: 3,
	}
	if _, err := CertifyVirtualSSTGroup([]*VirtualSST{&one, &two}); err == nil {
		t.Fatal("expected insufficient group capacity to be rejected")
	}
}

func TestDiscreteCDFSliceAndCursorPreservePhase(t *testing.T) {
	cdf, err := NewDiscreteCDF([]DiscreteInterval{{KeyMin: 10, KeyMax: 19, NumEntries: 4}, {KeyMin: 30, KeyMax: 39, NumEntries: 3}})
	if err != nil {
		t.Fatal(err)
	}
	child, err := cdf.Slice(2, 4)
	if err != nil {
		t.Fatal(err)
	}
	cursor := child.NewCursor()
	for rank := uint64(0); rank < child.Count(); rank++ {
		got, ok := cursor.Next()
		if !ok {
			t.Fatalf("cursor ended at rank %d", rank)
		}
		want, _ := cdf.Select(rank + 2)
		if got != want {
			t.Fatalf("rank %d: got %d, want %d", rank, got, want)
		}
	}
	if _, ok := cursor.Next(); ok {
		t.Fatal("cursor emitted too many keys")
	}
}

func TestSSTSizeCalibrationAndInverse(t *testing.T) {
	model := LogicalSSTSizeModel(100)
	if !model.AddCalibration(10, 1200, 20, 2200) {
		t.Fatal("calibration rejected")
	}
	if got := model.Estimate(15); got != 1700 {
		t.Fatalf("Estimate(15)=%d, want 1700", got)
	}
	if got := model.MaxEntries(1700); got != 15 {
		t.Fatalf("MaxEntries(1700)=%d, want 15", got)
	}
}
