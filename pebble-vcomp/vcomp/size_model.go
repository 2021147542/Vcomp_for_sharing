package vcomp

import "math/big"

type sizeLine struct{ numerator, denominator, fixed uint64 }

// SSTSizeModel is the upper envelope of rational affine physical-size fits.
// It mirrors RocksDB's current model and avoids treating logical KV bytes as
// identical to the encoded SST size.
type SSTSizeModel struct{ lines []sizeLine }

func LogicalSSTSizeModel(bytesPerEntry uint64) SSTSizeModel {
	var m SSTSizeModel
	if bytesPerEntry != 0 {
		m.lines = append(m.lines, sizeLine{bytesPerEntry, 1, 0})
	}
	return m
}

func (m *SSTSizeModel) AddCalibration(n1, bytes1, n2, bytes2 uint64) bool {
	if n1 == 0 || n2 <= n1 || bytes2 <= bytes1 {
		return false
	}
	numerator, denominator := bytes2-bytes1, n2-n1
	left := new(big.Int).Mul(new(big.Int).SetUint64(bytes1), new(big.Int).SetUint64(denominator))
	right := new(big.Int).Mul(new(big.Int).SetUint64(n1), new(big.Int).SetUint64(numerator))
	fixed := uint64(0)
	if left.Cmp(right) > 0 {
		difference := new(big.Int).Sub(left, right)
		q, r := new(big.Int), new(big.Int)
		q.QuoRem(difference, new(big.Int).SetUint64(denominator), r)
		fixed = q.Uint64()
		if r.Sign() != 0 {
			fixed++
		}
	}
	m.lines = append(m.lines, sizeLine{numerator, denominator, fixed})
	return true
}

func (m SSTSizeModel) Valid() bool { return len(m.lines) != 0 }

func (m SSTSizeModel) Estimate(entries uint64) uint64 {
	if entries == 0 {
		return 0
	}
	result := uint64(0)
	for _, line := range m.lines {
		product := new(big.Int).Mul(new(big.Int).SetUint64(entries), new(big.Int).SetUint64(line.numerator))
		q, r := new(big.Int), new(big.Int)
		q.QuoRem(product, new(big.Int).SetUint64(line.denominator), r)
		if r.Sign() != 0 {
			q.Add(q, big.NewInt(1))
		}
		q.Add(q, new(big.Int).SetUint64(line.fixed))
		estimate := maxUint64
		if q.BitLen() <= 64 {
			estimate = q.Uint64()
		}
		if estimate > result {
			result = estimate
		}
	}
	return result
}

func (m SSTSizeModel) MaxEntries(target uint64) uint64 {
	if !m.Valid() {
		return 0
	}
	result := maxUint64
	for _, line := range m.lines {
		if target < line.fixed {
			return 0
		}
		product := new(big.Int).Mul(new(big.Int).SetUint64(target-line.fixed), new(big.Int).SetUint64(line.denominator))
		product.Quo(product, new(big.Int).SetUint64(line.numerator))
		bounded := maxUint64
		if product.BitLen() <= 64 {
			bounded = product.Uint64()
		}
		if bounded < result {
			result = bounded
		}
	}
	return result
}
