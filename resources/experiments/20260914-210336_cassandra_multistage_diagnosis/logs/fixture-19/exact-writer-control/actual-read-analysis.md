# Actual local read-path diagnostic

All four pre-fixed paired rounds, no selected passes; same-request hit groups computed after execution

Actual engine local read path; one thread, warm OS cache after fixture scans, no CQL transport or contention; not 100GiB performance SST histogram counts merged SST iterators, not physical disk operations. Bloom is partition-level. Chunk counters are process-global deltas. Linux process read_bytes delta is attributed storage reads under warm OS cache and can be zero; rchar includes logical syscall bytes and instrumentation. Physical I/O latency is not measured.

| Round | First | Side | Hits | Misses | SST/read upper mean | Mean command µs | p50 µs | p99 µs | Process read bytes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | native | native | 6802 | 3198 | 3.6925 | 30.462 | 34.213 | 57.387 | 0 |
| 1 | native | vcomp | 6802 | 3198 | 3.6925 | 33.340 | 37.630 | 52.658 | 0 |
| 2 | vcomp | native | 6802 | 3198 | 3.6925 | 30.358 | 34.774 | 44.523 | 0 |
| 2 | vcomp | vcomp | 6802 | 3198 | 3.6925 | 32.684 | 37.259 | 50.834 | 0 |
| 3 | vcomp | native | 6802 | 3198 | 3.6925 | 28.882 | 32.871 | 42.770 | 0 |
| 3 | vcomp | vcomp | 6802 | 3198 | 3.6925 | 31.748 | 35.907 | 48.019 | 0 |
| 4 | native | native | 6802 | 3198 | 3.6925 | 30.040 | 34.535 | 44.182 | 0 |
| 4 | native | vcomp | 6802 | 3198 | 3.6925 | 31.868 | 36.087 | 49.452 | 0 |

SSTablesPerReadHistogram records merged SST iterators. Bucket-upper mean is exact for singleton buckets and an upper-bound approximation otherwise; not candidate count or device I/O count.

| Same-request group, all rounds | Native count | VComp count | Native mean µs | VComp mean µs |
|---|---:|---:|---:|---:|
| all | 40000 | 40000 | 29.9353773 | 32.41015335 |
| both_hit | 27208 | 27208 | 29.586039069391354 | 31.98407968244634 |
| native_only_hit | 0 | 0 | None | None |
| vcomp_only_hit | 0 | 0 | None | None |
| both_miss | 12792 | 12792 | 30.678403767979987 | 33.3163925891182 |

| Same-request group, all rounds | Native actual merged SST/read | VComp actual merged SST/read |
|---|---:|---:|
| all | 3.6925 | 3.6925 |
| both_hit | 3.5480740958541603 | 3.5480740958541603 |
| native_only_hit | None | None |
| vcomp_only_hit | None | None |
| both_miss | 3.999687304565353 | 3.999687304565353 |

Same-key timestamp differences per round: 0.
