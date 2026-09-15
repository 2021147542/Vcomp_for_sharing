# Actual local read-path diagnostic

All four pre-fixed paired rounds, no selected passes; same-request hit groups computed after execution

Actual engine local read path; one thread, warm OS cache after fixture scans, no CQL transport or contention; not 100GiB performance SST histogram counts merged SST iterators, not physical disk operations. Bloom is partition-level. Chunk counters are process-global deltas. Linux process read_bytes delta is attributed storage reads under warm OS cache and can be zero; rchar includes logical syscall bytes and instrumentation. Physical I/O latency is not measured.

| Round | First | Side | Hits | Misses | SST/read upper mean | Mean command µs | p50 µs | p99 µs | Process read bytes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | native | native | 6194 | 3806 | 1.0000 | 10.536 | 10.911 | 16.801 | 0 |
| 1 | native | vcomp | 6432 | 3568 | 1.0000 | 10.892 | 11.241 | 18.745 | 0 |
| 2 | vcomp | native | 6194 | 3806 | 1.0000 | 9.747 | 10.339 | 15.840 | 0 |
| 2 | vcomp | vcomp | 6432 | 3568 | 1.0000 | 9.876 | 10.590 | 16.310 | 0 |
| 3 | vcomp | native | 6194 | 3806 | 1.0000 | 9.899 | 10.550 | 16.080 | 0 |
| 3 | vcomp | vcomp | 6432 | 3568 | 1.0000 | 10.221 | 10.831 | 16.731 | 0 |
| 4 | native | native | 6194 | 3806 | 1.0000 | 9.453 | 10.059 | 15.479 | 0 |
| 4 | native | vcomp | 6432 | 3568 | 1.0000 | 10.307 | 11.010 | 16.952 | 0 |

SSTablesPerReadHistogram records merged SST iterators. Bucket-upper mean is exact for singleton buckets and an upper-bound approximation otherwise; not candidate count or device I/O count.

| Same-request group, all rounds | Native count | VComp count | Native mean µs | VComp mean µs |
|---|---:|---:|---:|---:|
| all | 40000 | 40000 | 9.90882225 | 10.324094225000001 |
| both_hit | 16372 | 16372 | 10.402129245052528 | 10.689100781822624 |
| native_only_hit | 8404 | 8404 | 10.530089600190387 | 9.580634697762969 |
| vcomp_only_hit | 9356 | 9356 | 9.336732898674647 | 11.296174967935015 |
| both_miss | 5868 | 5868 | 8.554854124062713 | 8.820576687116565 |

| Same-request group, all rounds | Native actual merged SST/read | VComp actual merged SST/read |
|---|---:|---:|
| all | 1.0 | 1.0 |
| both_hit | 1.0 | 1.0 |
| native_only_hit | 1.0 | 1.0 |
| vcomp_only_hit | 1.0 | 1.0 |
| both_miss | 1.0 | 1.0 |

Same-key timestamp differences per round: 4093.
