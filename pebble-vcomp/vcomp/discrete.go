package vcomp

import (
	"fmt"
	"math"
	"math/big"
	"sort"
)

// DiscreteInterval assigns an exact integer mass to an inclusive key range.
// The mass must not exceed the number of integer keys in the range.
type DiscreteInterval struct {
	KeyMin, KeyMax uint64
	NumEntries     uint64
}

// DiscreteCell retains the rational CDF which originally selected its keys.
// OriginRankBegin is important: slicing must preserve the original rounding
// phase instead of fitting a new distribution to the child range.
type DiscreteCell struct {
	KeyMin, KeyMax                    uint64
	NumEntries, PrefixEntries         uint64
	OriginKeyMin, OriginKeyMax        uint64
	OriginNumEntries, OriginRankBegin uint64
}

// DiscreteCDF is an exact certificate for a sorted set of uint64 keys. It is
// the count-preserving counterpart of the approximate piecewise-linear model.
type DiscreteCDF struct {
	cells []DiscreteCell
	count uint64
}

func NewDiscreteCDF(intervals []DiscreteInterval) (*DiscreteCDF, error) {
	cdf := &DiscreteCDF{}
	var previous uint64
	havePrevious := false
	for _, interval := range intervals {
		if interval.KeyMax < interval.KeyMin {
			return nil, fmt.Errorf("discrete CDF: reversed interval")
		}
		if havePrevious && interval.KeyMin <= previous {
			return nil, fmt.Errorf("discrete CDF: intervals overlap or are unordered")
		}
		havePrevious, previous = true, interval.KeyMax
		if new(big.Int).SetUint64(interval.NumEntries).Cmp(intervalCapacity(interval.KeyMin, interval.KeyMax)) > 0 {
			return nil, fmt.Errorf("discrete CDF: mass exceeds interval capacity")
		}
		if interval.NumEntries == 0 {
			continue
		}
		if maxUint64-cdf.count < interval.NumEntries {
			return nil, fmt.Errorf("discrete CDF: total cardinality overflows uint64")
		}
		cell := DiscreteCell{
			NumEntries: interval.NumEntries, PrefixEntries: cdf.count,
			OriginKeyMin: interval.KeyMin, OriginKeyMax: interval.KeyMax,
			OriginNumEntries: interval.NumEntries,
		}
		cell.KeyMin = selectInCell(cell, 0)
		cell.KeyMax = selectInCell(cell, interval.NumEntries-1)
		cdf.cells = append(cdf.cells, cell)
		cdf.count += interval.NumEntries
	}
	return cdf, nil
}

func intervalCapacity(minimum, maximum uint64) *big.Int {
	result := new(big.Int).SetUint64(maximum)
	result.Sub(result, new(big.Int).SetUint64(minimum))
	return result.Add(result, big.NewInt(1))
}

func selectInCell(cell DiscreteCell, localRank uint64) uint64 {
	// min + floor((((origin-rank + local-rank)+1)*width-1)/mass)
	rank := new(big.Int).SetUint64(cell.OriginRankBegin)
	rank.Add(rank, new(big.Int).SetUint64(localRank))
	rank.Add(rank, big.NewInt(1))
	numerator := new(big.Int).Mul(rank, intervalCapacity(cell.OriginKeyMin, cell.OriginKeyMax))
	numerator.Sub(numerator, big.NewInt(1))
	numerator.Quo(numerator, new(big.Int).SetUint64(cell.OriginNumEntries))
	numerator.Add(numerator, new(big.Int).SetUint64(cell.OriginKeyMin))
	return numerator.Uint64()
}

func (d *DiscreteCDF) Count() uint64 {
	if d == nil {
		return 0
	}
	return d.count
}
func (d *DiscreteCDF) Empty() bool { return d == nil || d.count == 0 }
func (d *DiscreteCDF) Cells() []DiscreteCell {
	if d == nil {
		return nil
	}
	return append([]DiscreteCell(nil), d.cells...)
}

func (d *DiscreteCDF) Select(rank uint64) (uint64, error) {
	if d == nil || rank >= d.count {
		return 0, fmt.Errorf("discrete CDF: rank is outside the CDF")
	}
	i := sort.Search(len(d.cells), func(i int) bool {
		return rank < d.cells[i].PrefixEntries+d.cells[i].NumEntries
	})
	cell := d.cells[i]
	return selectInCell(cell, rank-cell.PrefixEntries), nil
}

func (d *DiscreteCDF) countBeforeEdge(edge *big.Int) uint64 {
	if d == nil || len(d.cells) == 0 {
		return 0
	}
	i := sort.Search(len(d.cells), func(i int) bool {
		return new(big.Int).SetUint64(d.cells[i].KeyMax).Cmp(edge) >= 0
	})
	if i == len(d.cells) {
		return d.count
	}
	cell := d.cells[i]
	if edge.Cmp(new(big.Int).SetUint64(cell.KeyMin)) <= 0 {
		return cell.PrefixEntries
	}
	delta := new(big.Int).Sub(new(big.Int).Set(edge), new(big.Int).SetUint64(cell.OriginKeyMin))
	originCount := new(big.Int).Mul(new(big.Int).SetUint64(cell.OriginNumEntries), delta)
	originCount.Quo(originCount, intervalCapacity(cell.OriginKeyMin, cell.OriginKeyMax))
	begin := new(big.Int).SetUint64(cell.OriginRankBegin)
	end := new(big.Int).Add(new(big.Int).Set(begin), new(big.Int).SetUint64(cell.NumEntries))
	if originCount.Cmp(begin) < 0 {
		originCount.Set(begin)
	}
	if originCount.Cmp(end) > 0 {
		originCount.Set(end)
	}
	originCount.Sub(originCount, begin)
	return cell.PrefixEntries + originCount.Uint64()
}

func (d *DiscreteCDF) CountLessThan(key uint64) uint64 {
	return d.countBeforeEdge(new(big.Int).SetUint64(key))
}
func (d *DiscreteCDF) CountThrough(key uint64) uint64 {
	edge := new(big.Int).SetUint64(key)
	edge.Add(edge, big.NewInt(1))
	return d.countBeforeEdge(edge)
}

func (d *DiscreteCDF) Slice(firstRank, count uint64) (*DiscreteCDF, error) {
	if d == nil || firstRank > d.count || count > d.count-firstRank {
		return nil, fmt.Errorf("discrete CDF: slice is outside the CDF")
	}
	result := &DiscreteCDF{count: count}
	if count == 0 {
		return result, nil
	}
	endRank, prefix := firstRank+count, uint64(0)
	i := sort.Search(len(d.cells), func(i int) bool {
		return d.cells[i].PrefixEntries+d.cells[i].NumEntries > firstRank
	})
	for ; i < len(d.cells); i++ {
		cell := d.cells[i]
		if cell.PrefixEntries >= endRank {
			break
		}
		begin := max(firstRank, cell.PrefixEntries)
		end := min(endRank, cell.PrefixEntries+cell.NumEntries)
		child := cell
		child.OriginRankBegin += begin - cell.PrefixEntries
		child.NumEntries = end - begin
		child.PrefixEntries = prefix
		child.KeyMin = selectInCell(child, 0)
		child.KeyMax = selectInCell(child, child.NumEntries-1)
		result.cells = append(result.cells, child)
		prefix += child.NumEntries
	}
	if prefix != count {
		return nil, fmt.Errorf("discrete CDF: slice count mismatch")
	}
	return result, nil
}

// DiscreteCursor streams a CDF using division only once per cell. Per-key work
// is quotient/remainder addition, so materializing a large descriptor stays O(n).
type DiscreteCursor struct {
	cdf                      *DiscreteCDF
	cellIndex                int
	emitted                  uint64
	current                  uint64
	stepQuotient             uint64
	stepRemainder, remainder uint64
	initialized              bool
}

func (d *DiscreteCDF) NewCursor() *DiscreteCursor { return &DiscreteCursor{cdf: d} }

func (c *DiscreteCursor) initialize() {
	cell := c.cdf.cells[c.cellIndex]
	width := intervalCapacity(cell.OriginKeyMin, cell.OriginKeyMax)
	numerator := new(big.Int).SetUint64(cell.OriginRankBegin + 1)
	numerator.Mul(numerator, width)
	numerator.Sub(numerator, big.NewInt(1))
	denominator := new(big.Int).SetUint64(cell.OriginNumEntries)
	quotient, remainder := new(big.Int), new(big.Int)
	quotient.QuoRem(numerator, denominator, remainder)
	c.current = new(big.Int).Add(new(big.Int).SetUint64(cell.OriginKeyMin), quotient).Uint64()
	c.remainder = remainder.Uint64()
	stepRemainder := new(big.Int)
	stepQuotient := new(big.Int)
	stepQuotient.QuoRem(width, denominator, stepRemainder)
	c.stepQuotient, c.stepRemainder = stepQuotient.Uint64(), stepRemainder.Uint64()
	c.emitted, c.initialized = 0, true
}

func (c *DiscreteCursor) Next() (uint64, bool) {
	if c == nil || c.cdf == nil || c.cellIndex >= len(c.cdf.cells) {
		return 0, false
	}
	if !c.initialized {
		c.initialize()
	}
	cell := c.cdf.cells[c.cellIndex]
	key := c.current
	c.emitted++
	if c.emitted == cell.NumEntries {
		c.cellIndex++
		c.initialized = false
	} else {
		carry := c.stepRemainder != 0 && c.remainder >= cell.OriginNumEntries-c.stepRemainder
		if carry {
			c.remainder -= cell.OriginNumEntries - c.stepRemainder
		} else {
			c.remainder += c.stepRemainder
		}
		c.current += c.stepQuotient
		if carry {
			c.current++
		}
	}
	return key, true
}

type mergeBase struct {
	minimum, maximum uint64
	weight           float64
}

type mergePart struct {
	minimum, maximum uint64
	weight           float64
	mass             uint64
	witness          bool
}

func capacity64(minimum, maximum uint64) uint64 {
	capacity := intervalCapacity(minimum, maximum)
	if capacity.BitLen() > 64 {
		return maxUint64
	}
	return capacity.Uint64()
}

func sortedUnique(values []uint64) []uint64 {
	sort.Slice(values, func(i, j int) bool { return values[i] < values[j] })
	if len(values) == 0 {
		return values
	}
	w := 1
	for i := 1; i < len(values); i++ {
		if values[i] != values[w-1] {
			values[w], w = values[i], w+1
		}
	}
	return values[:w]
}

// BuildDiscreteMergeModel constructs the count/select certificate introduced
// by the current RocksDB implementation. Input extrema and KMV samples are
// mandatory witnesses; the remaining integer mass is distributed over the
// input-supported key space using range-KMV density estimates.
func BuildDiscreteMergeModel(inputs []*VirtualSST, requested uint64) (Model, uint64, error) {
	var nonempty []*VirtualSST
	var starts, witnesses []uint64
	inputSum := uint64(0)
	globalMax := uint64(0)
	for _, input := range inputs {
		if input == nil {
			return Model{}, 0, fmt.Errorf("discrete merge: nil input")
		}
		if input.NumEntries == 0 {
			continue
		}
		if input.KeyMax < input.KeyMin {
			return Model{}, 0, fmt.Errorf("discrete merge: reversed input bounds")
		}
		if maxUint64-inputSum < input.NumEntries {
			return Model{}, 0, fmt.Errorf("discrete merge: input count overflow")
		}
		inputSum += input.NumEntries
		nonempty = append(nonempty, input)
		starts = append(starts, input.KeyMin)
		if input.KeyMax != maxUint64 {
			starts = append(starts, input.KeyMax+1)
		}
		if len(nonempty) == 1 || input.KeyMax > globalMax {
			globalMax = input.KeyMax
		}
		witnesses = append(witnesses, input.KeyMin, input.KeyMax)
		for _, sample := range input.KMV.Samples {
			witnesses = append(witnesses, sample.Key)
		}
		for _, r := range input.KMVRanges {
			starts = append(starts, r.KeyMin)
			if r.KeyMax != maxUint64 {
				starts = append(starts, r.KeyMax+1)
			}
			for _, sample := range r.Sketch.Samples {
				witnesses = append(witnesses, sample.Key)
			}
		}
		if input.Model.Discrete == nil {
			for _, segment := range input.Model.Segments {
				begin, end := max(segment.KeyStart, input.KeyMin), min(segment.KeyEnd, input.KeyMax)
				if begin <= end {
					starts = append(starts, begin)
					if end != maxUint64 {
						starts = append(starts, end+1)
					}
				}
			}
		}
	}
	if len(nonempty) == 0 {
		return Model{}, 0, nil
	}
	starts, witnesses = sortedUnique(starts), sortedUnique(witnesses)
	var bases []mergeBase
	totalCapacity := new(big.Int)
	for i, begin := range starts {
		if begin > globalMax {
			break
		}
		end := globalMax
		if i+1 < len(starts) && starts[i+1] != 0 {
			end = starts[i+1] - 1
		}
		var active []*VirtualSST
		for _, input := range nonempty {
			if input.KeyMin <= begin && input.KeyMax >= begin {
				active = append(active, input)
			}
		}
		if len(active) == 0 {
			continue
		}
		totalCapacity.Add(totalCapacity, intervalCapacity(begin, end))
		bases = append(bases, mergeBase{begin, end, float64(estimateUnionForRange(active, begin, end))})
	}
	if new(big.Int).SetUint64(uint64(len(witnesses))).Cmp(totalCapacity) > 0 {
		return Model{}, 0, fmt.Errorf("discrete merge: witnesses exceed support capacity")
	}
	target := requested
	if totalCapacity.BitLen() <= 64 && target > totalCapacity.Uint64() {
		target = totalCapacity.Uint64()
	}
	if target < uint64(len(witnesses)) {
		target = uint64(len(witnesses))
	}
	if target > inputSum {
		return Model{}, 0, fmt.Errorf("discrete merge: projected count exceeds input sum")
	}

	parts := make([]mergePart, 0, len(bases)+2*len(witnesses))
	wi := 0
	for _, base := range bases {
		begin := base.minimum
		span := math.Max(1, float64(base.maximum-base.minimum)+1)
		for wi < len(witnesses) && witnesses[wi] < base.minimum {
			return Model{}, 0, fmt.Errorf("discrete merge: witness outside support")
		}
		for wi < len(witnesses) && witnesses[wi] <= base.maximum {
			key := witnesses[wi]
			if begin < key {
				parts = append(parts, mergePart{begin, key - 1, base.weight * float64(key-begin) / span, 0, false})
			}
			parts = append(parts, mergePart{key, key, 0, 1, true})
			wi++
			if key == maxUint64 {
				begin = key
				break
			}
			begin = key + 1
		}
		if begin <= base.maximum && (len(parts) == 0 || !parts[len(parts)-1].witness || parts[len(parts)-1].maximum != base.maximum) {
			parts = append(parts, mergePart{begin, base.maximum, base.weight * float64(base.maximum-begin+1) / span, 0, false})
		}
	}
	if wi != len(witnesses) {
		return Model{}, 0, fmt.Errorf("discrete merge: unassigned witness")
	}
	remaining := target - uint64(len(witnesses))
	active := make([]int, 0, len(parts))
	for i := range parts {
		if !parts[i].witness {
			active = append(active, i)
		}
	}
	for remaining > 0 {
		if len(active) == 0 {
			return Model{}, 0, fmt.Errorf("discrete merge: insufficient free capacity")
		}
		weightSum := float64(0)
		for _, i := range active {
			weightSum += parts[i].weight
		}
		if !(weightSum > 0) || math.IsInf(weightSum, 0) || math.IsNaN(weightSum) {
			weightSum = 0
			for _, i := range active {
				parts[i].weight = float64(capacity64(parts[i].minimum, parts[i].maximum))
				weightSum += parts[i].weight
			}
		}
		var saturating []int
		saturationMass := new(big.Int)
		for _, i := range active {
			capacity := capacity64(parts[i].minimum, parts[i].maximum)
			quota := float64(remaining) * parts[i].weight / weightSum
			if capacity <= remaining && quota >= float64(capacity) {
				saturating = append(saturating, i)
				saturationMass.Add(saturationMass, new(big.Int).SetUint64(capacity))
			}
		}
		if len(saturating) > 0 && saturationMass.Cmp(new(big.Int).SetUint64(remaining)) <= 0 {
			removed := make(map[int]struct{}, len(saturating))
			for _, i := range saturating {
				parts[i].mass = capacity64(parts[i].minimum, parts[i].maximum)
				removed[i] = struct{}{}
			}
			remaining -= saturationMass.Uint64()
			kept := active[:0]
			for _, i := range active {
				if _, ok := removed[i]; !ok {
					kept = append(kept, i)
				}
			}
			active = kept
			continue
		}
		allocated := uint64(0)
		type rem struct {
			index    int
			fraction float64
		}
		remainders := make([]rem, 0, len(active))
		for _, i := range active {
			capacity := capacity64(parts[i].minimum, parts[i].maximum)
			quota := float64(remaining) * parts[i].weight / weightSum
			mass := uint64(math.Floor(math.Min(float64(capacity), quota)))
			parts[i].mass = mass
			allocated += mass
			remainders = append(remainders, rem{i, quota - float64(mass)})
		}
		if allocated > remaining {
			return Model{}, 0, fmt.Errorf("discrete merge: invalid allocation")
		}
		residual := remaining - allocated
		sort.SliceStable(remainders, func(i, j int) bool { return remainders[i].fraction > remainders[j].fraction })
		for _, r := range remainders {
			if residual == 0 {
				break
			}
			capacity := capacity64(parts[r.index].minimum, parts[r.index].maximum)
			available := capacity - parts[r.index].mass
			amount := min(residual, available)
			parts[r.index].mass += amount
			residual -= amount
		}
		if residual != 0 {
			return Model{}, 0, fmt.Errorf("discrete merge: integer residual is infeasible")
		}
		remaining = 0
	}
	intervals := make([]DiscreteInterval, 0, len(parts))
	for _, part := range parts {
		intervals = append(intervals, DiscreteInterval{part.minimum, part.maximum, part.mass})
	}
	cdf, err := NewDiscreteCDF(intervals)
	if err != nil {
		return Model{}, 0, err
	}
	segments := make([]Segment, 0, len(bases))
	for _, base := range bases {
		yBegin, yEnd := cdf.CountLessThan(base.minimum), cdf.CountLessThan(base.maximum)
		slope := float64(0)
		if base.minimum != base.maximum {
			slope = float64(yEnd-yBegin) / float64(base.maximum-base.minimum)
		}
		segments = append(segments, Segment{base.minimum, base.maximum, slope, float64(yBegin) - slope*float64(base.minimum)})
	}
	return Model{Segments: compactSegments(segments), Discrete: cdf}, target, nil
}

// CertifyVirtualSST attaches a discrete certificate to a flush descriptor.
func CertifyVirtualSST(v *VirtualSST) error {
	if v == nil || v.NumEntries == 0 || v.Model.Discrete != nil {
		return nil
	}
	model, accepted, err := BuildDiscreteMergeModel([]*VirtualSST{v}, v.NumEntries)
	if err != nil {
		return err
	}
	if accepted != v.NumEntries {
		return fmt.Errorf("discrete flush changed exact count: %d -> %d", v.NumEntries, accepted)
	}
	v.Model = model
	return nil
}

// CertifyVirtualSSTGroup attaches final count/select certificates while
// preserving the group's total planned cardinality. Continuous KMV estimates
// can occasionally assign more entries to a narrow output range than that
// range has distinct integer keys. Once the compaction layout is frozen, move
// only that infeasible excess to the nearest descriptors with spare key-domain
// capacity. This leaves table bounds, levels, and the picker-visible tree shape
// unchanged.
//
// Descriptors that already carry a discrete certificate are treated as fixed.
// The return value is the number of entries moved out of infeasible ranges.
func CertifyVirtualSSTGroup(group []*VirtualSST) (uint64, error) {
	type entry struct {
		originalIndex int
		value         VirtualSST
		capacity      uint64
	}
	entries := make([]entry, 0, len(group))
	for i, input := range group {
		if input == nil {
			return 0, fmt.Errorf("discrete group: nil input")
		}
		if input.NumEntries == 0 || input.Model.Discrete != nil {
			continue
		}
		if input.KeyMax < input.KeyMin {
			return 0, fmt.Errorf("discrete group: reversed input bounds")
		}
		entries = append(entries, entry{i, *input, capacity64(input.KeyMin, input.KeyMax)})
	}
	if len(entries) == 0 {
		return 0, nil
	}
	sort.SliceStable(entries, func(i, j int) bool {
		if entries[i].value.KeyMin != entries[j].value.KeyMin {
			return entries[i].value.KeyMin < entries[j].value.KeyMin
		}
		return entries[i].value.KeyMax < entries[j].value.KeyMax
	})

	overflow := make([]uint64, len(entries))
	var moved uint64
	for i := range entries {
		if entries[i].value.NumEntries > entries[i].capacity {
			overflow[i] = entries[i].value.NumEntries - entries[i].capacity
			entries[i].value.NumEntries = entries[i].capacity
			if maxUint64-moved < overflow[i] {
				return 0, fmt.Errorf("discrete group: redistributed count overflows uint64")
			}
			moved += overflow[i]
		}
	}

	for source, remaining := range overflow {
		for distance := 1; remaining > 0 && distance < len(entries); distance++ {
			for _, destination := range []int{source - distance, source + distance} {
				if destination < 0 || destination >= len(entries) {
					continue
				}
				spare := entries[destination].capacity - entries[destination].value.NumEntries
				amount := min(remaining, spare)
				entries[destination].value.NumEntries += amount
				remaining -= amount
				if remaining == 0 {
					break
				}
			}
		}
		if remaining != 0 {
			return 0, fmt.Errorf("discrete group: insufficient key-domain capacity for %d entries", remaining)
		}
	}

	for i := range entries {
		entries[i].value.Model.Discrete = nil
		if err := CertifyVirtualSST(&entries[i].value); err != nil {
			return 0, fmt.Errorf("discrete group descriptor %d: %w", entries[i].originalIndex, err)
		}
	}
	for i := range entries {
		*group[entries[i].originalIndex] = entries[i].value
	}
	return moved, nil
}
