# Actual local read-path diagnostic

All four pre-fixed paired rounds, no selected passes; same-request hit groups computed after execution

Actual engine local read path; one thread, warm OS cache after fixture scans, no CQL transport or contention; not 100GiB performance SST histogram counts merged SST iterators, not physical disk operations. Bloom is partition-level. Chunk counters are process-global deltas. Linux process read_bytes delta is attributed storage reads under warm OS cache and can be zero; rchar includes logical syscall bytes and instrumentation. Physical I/O latency is not measured.

| Round | First | Side | Hits | Misses | SST/read upper mean | Mean command µs | p50 µs | p99 µs | Process read bytes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | native | native | 6194 | 3806 | 1.0000 | 10.154 | 10.770 | 16.180 | 0 |
| 1 | native | vcomp | 6194 | 3806 | 1.0000 | 11.091 | 11.341 | 18.795 | 0 |
| 2 | vcomp | native | 6194 | 3806 | 1.0000 | 9.930 | 10.540 | 16.060 | 0 |
| 2 | vcomp | vcomp | 6194 | 3806 | 1.0000 | 9.998 | 10.730 | 16.521 | 0 |
| 3 | vcomp | native | 6194 | 3806 | 1.0000 | 9.791 | 10.409 | 15.820 | 0 |
| 3 | vcomp | vcomp | 6194 | 3806 | 1.0000 | 10.290 | 10.910 | 16.811 | 0 |
| 4 | native | native | 6194 | 3806 | 1.0000 | 9.602 | 10.179 | 15.599 | 0 |
| 4 | native | vcomp | 6194 | 3806 | 1.0000 | 10.232 | 10.920 | 16.771 | 0 |

SSTablesPerReadHistogram records merged SST iterators. Bucket-upper mean is exact for singleton buckets and an upper-bound approximation otherwise; not candidate count or device I/O count.

| Same-request group, all rounds | Native count | VComp count | Native mean µs | VComp mean µs |
|---|---:|---:|---:|---:|
| all | 40000 | 40000 | 9.869094525000001 | 10.402741075 |
| both_hit | 24776 | 24776 | 10.359230545689377 | 10.852224128188569 |
| native_only_hit | 0 | 0 | None | None |
| vcomp_only_hit | 0 | 0 | None | None |
| both_miss | 15224 | 15224 | 9.071432277982135 | 9.671238702049395 |

| Same-request group, all rounds | Native actual merged SST/read | VComp actual merged SST/read |
|---|---:|---:|
| all | 1.0 | 1.0 |
| both_hit | 1.0 | 1.0 |
| native_only_hit | None | None |
| vcomp_only_hit | None | None |
| both_miss | 1.0 | 1.0 |

Same-key timestamp differences per round: 6194.
