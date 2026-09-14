# Completed C cells: retained-evidence diagnostic

Completed C cells only; the full organized campaign is still running.

| Metric | Baseline | VComp | Relative difference |
|---|---:|---:|---:|
| throughput_ops_per_second | 31,689.615673 | 40,088.725238 | +26.504% |
| point_lookup_latency_p50_us | 1,268.735000 | 980.479000 | -22.720% |
| point_lookup_latency_p95_us | 3,354.623000 | 2,658.303000 | -20.757% |
| point_lookup_latency_p99_us | 4,550.655000 | 3,788.799000 | -16.742% |
| point_lookup_hit_latency_p50_us | 1,259.519000 | 898.559000 | -28.659% |
| point_lookup_hit_latency_p95_us | 3,282.943000 | 2,533.375000 | -22.832% |
| point_lookup_hit_latency_p99_us | 4,476.927000 | 3,645.439000 | -18.573% |
| point_lookup_miss_latency_p50_us | 1,285.119000 | 1,169.407000 | -9.004% |
| point_lookup_miss_latency_p95_us | 3,442.687000 | 2,856.959000 | -17.014% |
| point_lookup_miss_latency_p99_us | 4,636.671000 | 4,018.175000 | -13.339% |
| disk_read_bytes | 2,668,645,122,048.000000 | 2,451,061,334,016.000000 | -8.153% |
| disk_read_requests | 23,707,652.000000 | 21,958,033.000000 | -7.380% |
| disk_read_latency_avg_ms | 0.112999 | 0.112392 | -0.537% |
| miss_fraction | 0.426188 | 0.330739 | -22.396% |
| device_read_bytes_per_operation | 280,704.655231 | 203,802.025299 | -27.396% |
| device_read_requests_per_operation | 2.493718 | 1.825777 | -26.785% |
| coarse_chunk_miss_fraction | 0.328993 | 0.289961 | -11.864% |
| coarse_chunk_misses_per_operation | 2.684338 | 2.234943 | -16.741% |
| coarse_key_cache_miss_fraction | 0.131379 | 0.000927 | -99.295% |
| coarse_key_cache_misses_per_operation | 0.462043 | 0.003645 | -99.211% |
| key_cache_average_weight_bytes_rounded | 3,173.752232 | 1,742.724656 | -45.089% |
| sstables_per_read_50% | 4.000000 | 4.000000 | +0.000% |
| sstables_per_read_95% | 4.000000 | 6.000000 | +50.000% |
| sstable_count | 204.000000 | 186.000000 | -8.824% |
| live_component_bytes | 87,990,345,878.000000 | 81,913,819,477.000000 | -6.906% |

## Findings

- C hit/miss mix differs by about 9.54 percentage points. Both hit and miss conditional latency distributions also differ, so hit/miss mixture alone does not describe all observed differences.
- With equal 100 MiB capacity, baseline key cache reaches its limit with 33,039 entries and 13.138% counter-delta misses. VComp holds 43,851 entries in 72.88 MiB with 0.0927% misses. This is a concrete difference in the cached index working set.
- Native key cache weighs the key and RowIndexEntry together. RowIndexEntry documentation describes indexed and shallow forms selected according to partition index-sample size. Different partition/index geometry could affect cache occupancy and Index.db work; its contribution has not been measured directly.
- VComp has fewer global SSTs (186 versus 204), but its SSTs-per-read p95/p99 is higher (6 versus 4). The histogram does not support explaining the result simply as fewer SST probes per read.
- VComp reads 27.40% fewer device bytes and issues 26.78% fewer device read requests per operation, while mean completed device-request latency remains almost identical (0.1124 versus 0.1130 ms). This supports investigating read work and cache behavior rather than attributing the gap to substantially faster device service.
- Both C cells record zero write operations, zero memtable switches, and zero pending flush tasks. Compaction counters report less than 1 KiB of total compacted data, providing no evidence of substantial data-table compaction. Both native chunk-cache capacities are exactly 5 GiB; row caches are disabled.
- Final keys are reconstructed from rank models rather than replayed from original keys. Near-equal visible row counts do not guarantee equal Zipf-weighted hit probability. Membership mismatch remains a fidelity issue even when throughput is higher.

## Interpretation limits

- Time mode completes different per-worker stream prefixes. Same seed/generator/distribution is not an identical per-request trace.
- Conditional hit/miss populations contain different keys on the two model-generated databases. Conditional quantile differences are observed costs, not a controlled same-key causal effect.
- Quantiles cannot be linearly reweighted. Raw conditional histograms or request-level matched measurements are unavailable, so the fraction of overall improvement attributable solely to hit/miss mix cannot be computed.
- Native cache/JMX/process I/O snapshots bracket a wider interval than the client clock; cache counters are node-wide. SSTables/read values are histogram snapshots, not exact request traces.
- Device mean completed-request latency is a block-device counter ratio for md0, not client lookup latency and not latency percentiles.
- Cache-entry weight is inferred from human-readable node-info Size/Entries and is rounded. Actual cached RowIndexEntry variants and their distribution were not measured.
- No fresh benchmark, DB query, SST scan, seed/configuration change, or published result edit was performed for this diagnostic.
- The cache correctness repair does not resolve model-generated membership, standalone descriptor scheduling versus native task lifecycle, or logical versus native allocated-heap flush-boundary differences. It is not a fidelity proof.

Complete counters, source notes and evidence hashes: [c-read-diagnostic.json](c-read-diagnostic.json).
