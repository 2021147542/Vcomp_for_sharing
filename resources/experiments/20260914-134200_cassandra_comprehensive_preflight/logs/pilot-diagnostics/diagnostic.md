# Cassandra C/E retained-evidence diagnostics

Source: `/work/vcomp-pebble-1tb/cassandra-comprehensive-preflight-20260914-134500/workloads-pilot-default-fix`.

Completion marker present: True. Missing records are unavailable, not zero.

## C

| Metric | Baseline | VComp | Δ VComp / baseline |
|---|---:|---:|---:|
| operations | 161,202 | 155,143 | -3.76% |
| throughput_ops_per_second | 32,239.355 | 31,027.861 | -3.76% |
| disk_read_bytes | 688,123,904 | 715,915,264 | +4.04% |
| disk_read_bytes_per_operation | 4,268.706 | 4,614.551 | +8.10% |
| point_hit_fraction | 0.623 | 0.644 | +3.37% |
| scans | 0 | 0 | unavailable |
| scan_requested_rows | 0 | 0 | unavailable |
| scan_returned_rows | 0 | 0 | unavailable |
| scan_returned_bytes | 0 | 0 | unavailable |
| disk_read_bytes_per_scan | unavailable | unavailable | unavailable |
| returned_rows_per_scan | unavailable | unavailable | unavailable |
| coarse_chunk_misses | 182,268 | 170,274 | -6.58% |
| coarse_chunk_miss_fraction | 0.295 | 0.320 | +8.60% |
| coarse_chunk_misses_per_operation | 1.131 | 1.098 | -2.93% |
| coarse_daemon_read_bytes | 688,123,904 | 715,915,264 | +4.04% |
| coarse_daemon_read_bytes_per_operation | 4,268.706 | 4,614.551 | +8.10% |
| after_sstables_per_read_50pct | 1.000 | 1.000 | +0.00% |
| after_sstables_per_read_95pct | 1.000 | 1.000 | +0.00% |
| after_sstables_per_read_99pct | 1.000 | 1.000 | +0.00% |

## E

| Metric | Baseline | VComp | Δ VComp / baseline |
|---|---:|---:|---:|
| operations | 79,800 | 81,549 | +2.19% |
| throughput_ops_per_second | 15,959.271 | 16,309.030 | +2.19% |
| disk_read_bytes | 688,123,904 | 715,915,264 | +4.04% |
| disk_read_bytes_per_operation | 8,623.107 | 8,778.958 | +1.81% |
| point_hit_fraction | unavailable | unavailable | unavailable |
| scans | 75,755 | 77,414 | +2.19% |
| scan_requested_rows | 3,820,822 | 3,904,226 | +2.18% |
| scan_returned_rows | 3,820,822 | 3,904,226 | +2.18% |
| scan_returned_bytes | 3,912,521,728 | 3,997,927,424 | +2.18% |
| disk_read_bytes_per_scan | 9,083.544 | 9,247.878 | +1.81% |
| returned_rows_per_scan | 50.437 | 50.433 | -0.01% |
| coarse_chunk_misses | 136,186 | 136,392 | +0.15% |
| coarse_chunk_miss_fraction | 0.414 | 0.438 | +5.65% |
| coarse_chunk_misses_per_operation | 1.707 | 1.673 | -2.00% |
| coarse_daemon_read_bytes | 688,123,904 | 715,915,264 | +4.04% |
| coarse_daemon_read_bytes_per_operation | 8,623.107 | 8,778.958 | +1.81% |
| after_sstables_per_read_50pct | 1.000 | 1.000 | +0.00% |
| after_sstables_per_read_95pct | 1.000 | 1.000 | +0.00% |
| after_sstables_per_read_99pct | 1.000 | 1.000 | +0.00% |

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
  "samples": 93,
  "first_unix_seconds": 1789361340.7000384,
  "last_unix_seconds": 1789361800.7311363,
  "peak_memory_current": 7602110464,
  "peak_file_bytes": 1680801792,
  "peak_anon_bytes": 5922689024,
  "final_memory_events": {
    "low": 0,
    "high": 0,
    "max": 0,
    "oom": 0,
    "oom_kill": 0,
    "oom_group_kill": 0
  }
}
