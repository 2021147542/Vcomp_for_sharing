# Actual local read-path diagnostic

All four pre-fixed paired rounds, no selected passes; same-request hit groups computed after execution

Actual engine local read path; one thread, cache policy reported separately, no CQL transport or contention; bounded read-only diagnostic, not full workload campaign SST histogram counts merged SST iterators, not physical disk operations. Bloom is partition-level. Chunk counters are process-global deltas. Linux process read_bytes delta is attributed storage reads under warm OS cache and can be zero; rchar includes logical syscall bytes and instrumentation. Physical I/O latency is not measured.

Data.db cache eviction requested: True. Optional before each phase: invalidate Cassandra chunk-cache entries and best-effort POSIX_FADV_DONTNEED on this side fixture/checkpoint Data.db files only; index/key cache unchanged, no global cache drop

| Round | First | Side | Hits | Misses | SST/read upper mean | Mean command µs | p50 µs | p99 µs | Process read bytes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | native | native | 573 | 427 | 3.5400 | 603.102 | 560.835 | 3025.507 | 569683968 |
| 1 | native | vcomp | 706 | 294 | 3.8400 | 721.742 | 691.528 | 3344.200 | 599363584 |
| 2 | vcomp | native | 573 | 427 | 3.5400 | 595.844 | 556.657 | 3009.046 | 569683968 |
| 2 | vcomp | vcomp | 706 | 294 | 3.8400 | 721.116 | 681.319 | 3261.867 | 599363584 |
| 3 | vcomp | native | 573 | 427 | 3.5400 | 593.413 | 550.025 | 3013.495 | 569683968 |
| 3 | vcomp | vcomp | 706 | 294 | 3.8400 | 703.235 | 684.405 | 3114.983 | 599363584 |
| 4 | native | native | 573 | 427 | 3.5400 | 589.640 | 553.150 | 2980.583 | 569683968 |
| 4 | native | vcomp | 706 | 294 | 3.8400 | 699.640 | 680.027 | 3156.230 | 599363584 |

SSTablesPerReadHistogram records merged SST iterators. Bucket-upper mean is exact for singleton buckets and an upper-bound approximation otherwise; not candidate count or device I/O count.

| Same-request group, all rounds | Native count | VComp count | Native mean µs | VComp mean µs |
|---|---:|---:|---:|---:|
| all | 4000 | 4000 | 595.49969275 | 711.433299 |
| both_hit | 1532 | 1532 | 621.3032754569191 | 759.5039184073107 |
| native_only_hit | 760 | 760 | 588.1511828947368 | 818.9781013157896 |
| vcomp_only_hit | 1292 | 1292 | 527.667290247678 | 533.2297801857585 |
| both_miss | 416 | 416 | 724.5699879807693 | 891.3869230769232 |

| Same-request group, all rounds | Native actual merged SST/read | VComp actual merged SST/read |
|---|---:|---:|
| all | 3.54 | 3.84 |
| both_hit | 3.227154046997389 | 3.772845953002611 |
| native_only_hit | 3.136842105263158 | 4.363157894736842 |
| vcomp_only_hit | 4.0 | 3.4458204334365323 |
| both_miss | 4.0 | 4.355769230769231 |

Same-key timestamp differences per round: 383.
