# Cassandra C/E retained-evidence diagnostics

Source: `/work/vcomp-pebble-1tb/cassandra-organized-100g-20260914-152956/workloads-300s`.

Completion marker present: True. Missing records are unavailable, not zero.

## C

| Metric | Baseline | VComp | Δ VComp / baseline |
|---|---:|---:|---:|
| operations | 9,506,950 | 12,026,678 | +26.50% |
| throughput_ops_per_second | 31,689.616 | 40,088.725 | +26.50% |
| disk_read_bytes | 2,668,645,122,048 | 2,451,061,334,016 | -8.15% |
| disk_read_bytes_per_operation | 280,704.655 | 203,802.025 | -27.40% |
| point_hit_fraction | 0.574 | 0.669 | +16.63% |
| scans | 0 | 0 | unavailable |
| scan_requested_rows | 0 | 0 | unavailable |
| scan_returned_rows | 0 | 0 | unavailable |
| scan_returned_bytes | 0 | 0 | unavailable |
| disk_read_bytes_per_scan | unavailable | unavailable | unavailable |
| returned_rows_per_scan | unavailable | unavailable | unavailable |
| coarse_chunk_misses | 25,519,871 | 26,878,944 | +5.33% |
| coarse_chunk_miss_fraction | 0.329 | 0.290 | -11.86% |
| coarse_chunk_misses_per_operation | 2.684 | 2.235 | -16.74% |
| coarse_daemon_read_bytes | 2,668,644,868,096 | 2,451,061,342,208 | -8.15% |
| coarse_daemon_read_bytes_per_operation | 280,704.629 | 203,802.026 | -27.40% |
| after_sstables_per_read_50pct | 4.000 | 4.000 | +0.00% |
| after_sstables_per_read_95pct | 4.000 | 6.000 | +50.00% |
| after_sstables_per_read_99pct | 4.000 | 6.000 | +50.00% |

## E

| Metric | Baseline | VComp | Δ VComp / baseline |
|---|---:|---:|---:|
| operations | 4,264,471 | 4,910,787 | +15.16% |
| throughput_ops_per_second | 14,214.759 | 16,369.118 | +15.16% |
| disk_read_bytes | 2,172,692,029,440 | 2,004,704,026,624 | -7.73% |
| disk_read_bytes_per_operation | 509,486.881 | 408,224.593 | -19.88% |
| point_hit_fraction | unavailable | unavailable | unavailable |
| scans | 4,050,546 | 4,664,468 | +15.16% |
| scan_requested_rows | 204,504,700 | 235,507,385 | +15.16% |
| scan_returned_rows | 204,504,700 | 235,507,385 | +15.16% |
| scan_returned_bytes | 209,412,812,800 | 241,159,562,240 | +15.16% |
| disk_read_bytes_per_scan | 536,394.854 | 429,781.923 | -19.88% |
| returned_rows_per_scan | 50.488 | 50.490 | +0.00% |
| coarse_chunk_misses | 16,807,092 | 15,747,148 | -6.31% |
| coarse_chunk_miss_fraction | 0.399 | 0.346 | -13.15% |
| coarse_chunk_misses_per_operation | 3.941 | 3.207 | -18.64% |
| coarse_daemon_read_bytes | 2,172,691,992,576 | 2,004,704,006,144 | -7.73% |
| coarse_daemon_read_bytes_per_operation | 509,486.872 | 408,224.589 | -19.88% |
| after_sstables_per_read_50pct | 4.000 | 4.000 | +0.00% |
| after_sstables_per_read_95pct | 4.000 | 6.000 | +50.00% |
| after_sstables_per_read_99pct | 4.000 | 6.000 | +50.00% |

## Interpretation boundaries

- Client disk bytes are shared-device /proc/diskstats deltas over the workload measurement interval; they are neither unique data bytes nor Cassandra-only I/O.
- Daemon /proc/PID/io and node/cache counters bracket a wider interval including client setup. Chunk-cache counters are node-wide. Their per-operation ratios are diagnostic, not exact workload cache-miss rates.
- SSTables-per-read quantiles are table histogram snapshots, not per-request traces. Before/after quantiles cannot be subtracted or attributed exclusively to C/E.
- Returned scan bytes are client payload bytes as defined by the recorded generator; they exclude storage metadata, protocol framing and physical block rounding.
- Cgroup memory.stat file includes page cache and other file-backed charges for the whole daemon/client group. Peaks span the full workload suite and cannot identify one workload without timestamp alignment.
- Native chunk cache plus bounded total memory is not the paper's exact 5% total-cache configuration. Cache miss counts and SST-read histograms can support a mechanism but do not prove causality.
- Missing historical fields remain null/unavailable. This tool does not reconstruct them as zero or infer an unmeasured cause.

## Whole-suite memory

{
  "samples": 927,
  "first_unix_seconds": 1789367505.4818902,
  "last_unix_seconds": 1789372137.2090816,
  "peak_memory_current": 21474988032,
  "peak_file_bytes": 13735075840,
  "peak_anon_bytes": 12437053440,
  "final_memory_events": {
    "low": 0,
    "high": 0,
    "max": 212118726,
    "oom": 0,
    "oom_kill": 0,
    "oom_group_kill": 0
  }
}
