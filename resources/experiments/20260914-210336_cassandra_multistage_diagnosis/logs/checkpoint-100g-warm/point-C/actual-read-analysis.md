# Actual local read-path diagnostic

All four pre-fixed paired rounds, no selected passes; same-request hit groups computed after execution

Actual engine local read path; one thread, cache policy reported separately, no CQL transport or contention; bounded read-only diagnostic, not full workload campaign SST histogram counts merged SST iterators, not physical disk operations. Bloom is partition-level. Chunk counters are process-global deltas. Linux process read_bytes delta is attributed storage reads under warm OS cache and can be zero; rchar includes logical syscall bytes and instrumentation. Physical I/O latency is not measured.

Data.db cache eviction requested: False. Optional before each phase: invalidate Cassandra chunk-cache entries and best-effort POSIX_FADV_DONTNEED on this side fixture/checkpoint Data.db files only; index/key cache unchanged, no global cache drop

| Round | First | Side | Hits | Misses | SST/read upper mean | Mean command µs | p50 µs | p99 µs | Process read bytes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | native | native | 573 | 427 | 3.5400 | 27.938 | 28.543 | 49.021 | 0 |
| 1 | native | vcomp | 706 | 294 | 3.8400 | 25.171 | 25.898 | 40.145 | 0 |
| 2 | vcomp | native | 573 | 427 | 3.5400 | 24.783 | 25.458 | 42.359 | 0 |
| 2 | vcomp | vcomp | 706 | 294 | 3.8400 | 23.738 | 24.285 | 38.862 | 0 |
| 3 | vcomp | native | 573 | 427 | 3.5400 | 24.872 | 25.557 | 42.469 | 0 |
| 3 | vcomp | vcomp | 706 | 294 | 3.8400 | 23.249 | 23.544 | 42.269 | 0 |
| 4 | native | native | 573 | 427 | 3.5400 | 22.883 | 23.294 | 39.193 | 0 |
| 4 | native | vcomp | 706 | 294 | 3.8400 | 22.674 | 23.083 | 37.169 | 0 |

SSTablesPerReadHistogram records merged SST iterators. Bucket-upper mean is exact for singleton buckets and an upper-bound approximation otherwise; not candidate count or device I/O count.

| Same-request group, all rounds | Native count | VComp count | Native mean µs | VComp mean µs |
|---|---:|---:|---:|---:|
| all | 4000 | 4000 | 25.11900275 | 23.70804925 |
| both_hit | 1532 | 1532 | 26.148261096605744 | 24.8215091383812 |
| native_only_hit | 760 | 760 | 25.117469736842104 | 23.86289210526316 |
| vcomp_only_hit | 1292 | 1292 | 23.04383746130031 | 21.87192182662539 |
| both_miss | 416 | 416 | 27.776346153846152 | 25.027221153846153 |

| Same-request group, all rounds | Native actual merged SST/read | VComp actual merged SST/read |
|---|---:|---:|
| all | 3.54 | 3.84 |
| both_hit | 3.227154046997389 | 3.772845953002611 |
| native_only_hit | 3.136842105263158 | 4.363157894736842 |
| vcomp_only_hit | 4.0 | 3.4458204334365323 |
| both_miss | 4.0 | 4.355769230769231 |

Same-key timestamp differences per round: 383.
