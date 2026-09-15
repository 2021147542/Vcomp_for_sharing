# Actual local read-path diagnostic

All four pre-fixed paired rounds, no selected passes; same-request hit groups computed after execution

Actual engine local read path; one thread, warm OS cache after fixture scans, no CQL transport or contention; not 100GiB performance SST histogram counts merged SST iterators, not physical disk operations. Bloom is partition-level. Chunk counters are process-global deltas. Linux process read_bytes delta is attributed storage reads under warm OS cache and can be zero; rchar includes logical syscall bytes and instrumentation. Physical I/O latency is not measured.

| Round | First | Side | Hits | Misses | SST/read upper mean | Mean command µs | p50 µs | p99 µs | Process read bytes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | native | native | 6802 | 3198 | 3.6925 | 33.331 | 38.662 | 49.442 | 0 |
| 1 | native | vcomp | 6956 | 3044 | 3.6783 | 34.691 | 40.566 | 53.519 | 0 |
| 2 | vcomp | native | 6802 | 3198 | 3.6925 | 33.037 | 37.721 | 57.798 | 0 |
| 2 | vcomp | vcomp | 6956 | 3044 | 3.6783 | 33.154 | 39.083 | 50.604 | 0 |
| 3 | vcomp | native | 6802 | 3198 | 3.6925 | 32.737 | 38.231 | 48.610 | 0 |
| 3 | vcomp | vcomp | 6956 | 3044 | 3.6783 | 34.308 | 40.125 | 51.576 | 0 |
| 4 | native | native | 6802 | 3198 | 3.6925 | 32.235 | 37.760 | 48.150 | 0 |
| 4 | native | vcomp | 6956 | 3044 | 3.6783 | 34.401 | 39.233 | 79.448 | 0 |

SSTablesPerReadHistogram records merged SST iterators. Bucket-upper mean is exact for singleton buckets and an upper-bound approximation otherwise; not candidate count or device I/O count.

| Same-request group, all rounds | Native count | VComp count | Native mean µs | VComp mean µs |
|---|---:|---:|---:|---:|
| all | 40000 | 40000 | 32.83504255 | 34.138531225 |
| both_hit | 19428 | 19428 | 32.57137934939263 | 34.060743720403536 |
| native_only_hit | 7780 | 7780 | 32.200668123393314 | 36.401431876606686 |
| vcomp_only_hit | 8396 | 8396 | 35.56900857551215 | 34.12159957122439 |
| both_miss | 4396 | 4396 | 29.901353503184712 | 30.509788444040037 |

| Same-request group, all rounds | Native actual merged SST/read | VComp actual merged SST/read |
|---|---:|---:|
| all | 3.6925 | 3.6783 |
| both_hit | 3.5383981881820055 | 3.5425159563516573 |
| native_only_hit | 3.572236503856041 | 4.0 |
| vcomp_only_hit | 3.9995235826584086 | 3.525964745116722 |
| both_miss | 4.0 | 4.0 |

Same-key timestamp differences per round: 4857.
