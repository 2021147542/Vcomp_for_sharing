# Actual local read-path diagnostic

All four pre-fixed paired rounds, no selected passes; same-request hit groups computed after execution

Actual engine local read path; one thread, warm OS cache after fixture scans, no CQL transport or contention; not 100GiB performance SST histogram counts merged SST iterators, not physical disk operations. Bloom is partition-level. Chunk counters are process-global deltas. Linux process read_bytes delta is attributed storage reads under warm OS cache and can be zero; rchar includes logical syscall bytes and instrumentation. Physical I/O latency is not measured.

| Round | First | Side | Hits | Misses | SST/read upper mean | Mean command µs | p50 µs | p99 µs | Process read bytes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | native | native | 6802 | 3198 | 3.6925 | 29.965 | 33.673 | 45.295 | 0 |
| 1 | native | vcomp | 6802 | 3198 | 3.6925 | 34.333 | 38.522 | 55.273 | 0 |
| 2 | vcomp | native | 6802 | 3198 | 3.6925 | 29.690 | 33.923 | 44.042 | 0 |
| 2 | vcomp | vcomp | 6802 | 3198 | 3.6925 | 33.750 | 38.291 | 52.308 | 0 |
| 3 | vcomp | native | 6802 | 3198 | 3.6925 | 30.293 | 33.512 | 69.950 | 0 |
| 3 | vcomp | vcomp | 6802 | 3198 | 3.6925 | 34.677 | 37.510 | 86.682 | 0 |
| 4 | native | native | 6802 | 3198 | 3.6925 | 28.940 | 33.041 | 42.629 | 0 |
| 4 | native | vcomp | 6802 | 3198 | 3.6925 | 33.879 | 37.480 | 61.475 | 0 |

SSTablesPerReadHistogram records merged SST iterators. Bucket-upper mean is exact for singleton buckets and an upper-bound approximation otherwise; not candidate count or device I/O count.

| Same-request group, all rounds | Native count | VComp count | Native mean µs | VComp mean µs |
|---|---:|---:|---:|---:|
| all | 40000 | 40000 | 29.721984375 | 34.159717575 |
| both_hit | 27208 | 27208 | 29.428621140840928 | 33.686319097324315 |
| native_only_hit | 0 | 0 | None | None |
| vcomp_only_hit | 0 | 0 | None | None |
| both_miss | 12792 | 12792 | 30.345954580988117 | 35.16661452470294 |

| Same-request group, all rounds | Native actual merged SST/read | VComp actual merged SST/read |
|---|---:|---:|
| all | 3.6925 | 3.6925 |
| both_hit | 3.5480740958541603 | 3.5480740958541603 |
| native_only_hit | None | None |
| vcomp_only_hit | None | None |
| both_miss | 3.999687304565353 | 3.999687304565353 |

Same-key timestamp differences per round: 6802.
