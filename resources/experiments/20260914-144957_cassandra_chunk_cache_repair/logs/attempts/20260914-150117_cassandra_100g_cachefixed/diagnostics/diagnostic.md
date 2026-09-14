# Cassandra C/E retained-evidence diagnostics

Source: `/work/vcomp-pebble-1tb/cassandra-cachefixed-100g-20260914-150117/workloads-300s`.

Completion marker present: False. Missing records are unavailable, not zero.

## C

| Metric | Baseline | VComp | Δ VComp / baseline |
|---|---:|---:|---:|
| Pair incomplete | unavailable | unavailable | unavailable |

## E

| Metric | Baseline | VComp | Δ VComp / baseline |
|---|---:|---:|---:|
| Pair incomplete | unavailable | unavailable | unavailable |

## Interpretation boundaries

- Client disk bytes are shared-device /proc/diskstats deltas over the workload measurement interval; they are neither unique data bytes nor Cassandra-only I/O.
- Daemon /proc/PID/io and node/cache counters bracket a wider interval including client setup. Chunk-cache counters are node-wide. Their per-operation ratios are diagnostic, not exact workload cache-miss rates.
- SSTables-per-read quantiles are table histogram snapshots, not per-request traces. Before/after quantiles cannot be subtracted or attributed exclusively to C/E.
- Returned scan bytes are client payload bytes as defined by the recorded generator; they exclude storage metadata, protocol framing and physical block rounding.
- Cgroup memory.stat file includes page cache and other file-backed charges for the whole daemon/client group. Peaks span the full workload suite and cannot identify one workload without timestamp alignment.
- Native chunk cache plus bounded total memory is not the paper's exact 5% total-cache configuration. Cache miss counts and SST-read histograms can support a mechanism but do not prove causality.
- Missing historical fields remain null/unavailable. This tool does not reconstruct them as zero or infer an unmeasured cause.

## Whole-suite memory

Memory trace unavailable.
