# Cassandra YCSB / MixGraph comparison

- Inputs: preserved baseline and VComp DBs; one isolated hard-linked checkpoint per workload
- Workload: 48 client threads, 20,000 operations per client; same seed and operation count for both systems
- Cache start: scoped `POSIX_FADV_DONTNEED` on each checkpoint before Cassandra startup
- Disk I/O: `/proc/diskstats` delta for `md0` over the exact client interval

| Workload | System | Throughput (ops/s) | Point p50 (µs) | Point p95 (µs) | Point p99 (µs) | Scan p99 (µs) | Read misses | Disk read (GB) | Disk write (MB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A | Baseline | 88098 | 514 | 1382 | 3820 | 0 | 105080 | 57.097 | 196.502 |
| A | VComp | 63951 | 669 | 2161 | 5349 | 0 | 105047 | 91.405 | 197.014 |
| B | Baseline | 56018 | 695 | 1697 | 3998 | 0 | 231859 | 99.346 | 3.699 |
| B | VComp | 46693 | 803 | 2286 | 4489 | 0 | 231957 | 126.752 | 4.108 |
| C | Baseline | 48583 | 798 | 1845 | 3824 | 0 | 408765 | 106.198 | 4.137 |
| C | VComp | 36678 | 1016 | 2511 | 4977 | 0 | 328858 | 131.206 | 4.354 |
| D | Baseline | 88279 | 408 | 1197 | 2978 | 0 | 179001 | 44.009 | 3.490 |
| D | VComp | 67016 | 571 | 1584 | 3467 | 0 | 173245 | 47.217 | 3.584 |
| E | Baseline | 22925 | 0 | 0 | 0 | 6115 | 0 | 145.796 | 15.426 |
| E | VComp | 17472 | 0 | 0 | 0 | 8905 | 0 | 236.800 | 15.761 |
| F | Baseline | 47272 | 585 | 1558 | 3736 | 0 | 210176 | 104.825 | 197.501 |
| F | VComp | 41818 | 641 | 1999 | 3971 | 0 | 210148 | 126.193 | 197.460 |
| MixGraph | Baseline | 36952 | 895 | 3283 | 6001 | 29737 | 280213 | 42.038 | 7.369 |
| MixGraph | VComp | 33038 | 1005 | 3592 | 6615 | 36307 | 276386 | 47.636 | 7.430 |
