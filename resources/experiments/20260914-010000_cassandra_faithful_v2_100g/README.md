# Cassandra YCSB / MixGraph comparison

- Inputs: preserved baseline and VComp DBs; one isolated hard-linked checkpoint per workload
- Workload: 48 client threads, 20,000 operations per client; same seed and operation count for both systems
- Cache start: scoped `POSIX_FADV_DONTNEED` on each checkpoint before Cassandra startup
- Disk I/O: `/proc/diskstats` delta for `md0` over the exact client interval

| Workload | System | Throughput (ops/s) | Point p50 (µs) | Point p95 (µs) | Point p99 (µs) | Scan p99 (µs) | Read misses | Disk read (GB) | Disk write (MB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A | Baseline | 77961 | 765 | 2094 | 3559 | 0 | 130973 | 9.424 | 13.828 |
| A | VComp | 70222 | 1037 | 2134 | 3553 | 0 | 127279 | 10.592 | 13.914 |
| B | Baseline | 57990 | 696 | 1830 | 2947 | 0 | 280475 | 14.957 | 0.610 |
| B | VComp | 49122 | 934 | 1957 | 2990 | 0 | 271159 | 18.151 | 0.627 |
| C | Baseline | 53305 | 727 | 1821 | 2922 | 0 | 408858 | 15.427 | 0.602 |
| C | VComp | 46117 | 924 | 1951 | 2961 | 0 | 320535 | 18.933 | 2.081 |
| D | Baseline | 71153 | 390 | 2171 | 3279 | 0 | 165087 | 3.991 | 0.729 |
| D | VComp | 61281 | 452 | 2318 | 3426 | 0 | 154446 | 4.759 | 0.741 |
| E | Baseline | 25730 | 0 | 0 | 0 | 5595 | 0 | 22.531 | 9.884 |
| E | VComp | 23907 | 0 | 0 | 0 | 5751 | 0 | 25.633 | 9.765 |
| F | Baseline | 51143 | 636 | 1801 | 2824 | 0 | 251114 | 15.412 | 14.111 |
| F | VComp | 43547 | 909 | 1935 | 2943 | 0 | 249198 | 18.930 | 15.659 |
| MixGraph | Baseline | 38758 | 654 | 2521 | 8741 | 100794 | 290021 | 7.530 | 2.220 |
| MixGraph | VComp | 38458 | 601 | 2281 | 9257 | 114688 | 288109 | 8.496 | 9.007 |
