# Actual local read-path diagnostic

All four pre-fixed paired rounds, no selected passes; same-request hit groups computed after execution

Actual engine local read path; one thread, warm OS cache after fixture scans, no CQL transport or contention; not 100GiB performance SST histogram counts merged SST iterators, not physical disk operations. Bloom is partition-level. Chunk counters are process-global deltas. Linux process read_bytes delta is attributed storage reads under warm OS cache and can be zero; rchar includes logical syscall bytes and instrumentation. Physical I/O latency is not measured.

| Round | First | Side | Hits | Misses | SST/read upper mean | Mean command µs | p50 µs | p99 µs | Process read bytes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | native | native | 6194 | 3806 | 1.0000 | 10.505 | 10.930 | 16.530 | 0 |
| 1 | native | vcomp | 6194 | 3806 | 1.0000 | 11.222 | 11.531 | 19.466 | 0 |
| 2 | vcomp | native | 6194 | 3806 | 1.0000 | 10.123 | 10.709 | 16.281 | 0 |
| 2 | vcomp | vcomp | 6194 | 3806 | 1.0000 | 10.234 | 10.870 | 16.812 | 0 |
| 3 | vcomp | native | 6194 | 3806 | 1.0000 | 10.024 | 10.579 | 16.050 | 0 |
| 3 | vcomp | vcomp | 6194 | 3806 | 1.0000 | 10.394 | 11.041 | 17.182 | 0 |
| 4 | native | native | 6194 | 3806 | 1.0000 | 9.653 | 10.280 | 15.719 | 0 |
| 4 | native | vcomp | 6194 | 3806 | 1.0000 | 10.486 | 11.080 | 17.092 | 0 |

SSTablesPerReadHistogram records merged SST iterators. Bucket-upper mean is exact for singleton buckets and an upper-bound approximation otherwise; not candidate count or device I/O count.

| Same-request group, all rounds | Native count | VComp count | Native mean µs | VComp mean µs |
|---|---:|---:|---:|---:|
| all | 40000 | 40000 | 10.07630485 | 10.584146324999999 |
| both_hit | 24776 | 24776 | 10.587819099128188 | 11.104271149499516 |
| native_only_hit | 0 | 0 | None | None |
| vcomp_only_hit | 0 | 0 | None | None |
| both_miss | 15224 | 15224 | 9.243851024697845 | 9.737679387808722 |

| Same-request group, all rounds | Native actual merged SST/read | VComp actual merged SST/read |
|---|---:|---:|
| all | 1.0 | 1.0 |
| both_hit | 1.0 | 1.0 |
| native_only_hit | None | None |
| vcomp_only_hit | None | None |
| both_miss | 1.0 | 1.0 |

Same-key timestamp differences per round: 0.
