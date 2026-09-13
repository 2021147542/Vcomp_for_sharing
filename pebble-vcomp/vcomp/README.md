# VComp model port

This package contains both the paper-era descriptor path and the later
experimental RocksDB fidelity correction while the Pebble harness retains
Pebble's own compaction picker and output-boundary policy.

The default paper path uses:

- global and range-local KMV sketches with common-theta union estimation;
- continuous PLR merge and logical KV bytes for virtual table metadata;
- a discrete integer CDF certificate added only after the final virtual layout
  is frozen, so materialization preserves the planned key count without
  changing later overlap-based compaction picks;
- strict materialization (count loss or unordered output is an error);
- distinct final Pebble sequence numbers for materialized files.

Set `VCOMP_DISCRETE_CDF=1` to opt into recursive discrete-CDF merge with the
sampled dedup-ratio estimator. Set `VCOMP_SST_SIZE_MODEL=calibrated` to opt into
the affine physical-SST size envelope. These corrections are not the Pebble
paper default: together they crossed Pebble's dynamic-level thresholds in the
September 10 1 TiB campaign and inflated L4/L5 from 138/1,312 baseline tables
to 364/1,588 VComp tables. The default paper path's descriptor-only replay of
the same 1 TiB input produced 123/1,306.

RocksDB source in the repository root remains the reference implementation;
this package does not alter it.
