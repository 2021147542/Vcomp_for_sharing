# Cassandra YCSB / MixGraph comparison

- Inputs: preserved baseline and VComp DBs; one isolated hard-linked checkpoint per workload
- Workload: 48 client threads, 300 seconds; same seed and operation count for both systems
- Cache start: scoped `POSIX_FADV_DONTNEED` on each checkpoint before Cassandra startup
- Disk I/O: `/proc/diskstats` delta for `md0` over the exact client interval

| Workload | System | Throughput (ops/s) | Point p50 (µs) | Point p95 (µs) | Point p99 (µs) | Scan p99 (µs) | Read misses | Disk read (GB) | Disk write (MB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A | Baseline | 57585 | 756 | 3031 | 5583 | 0 | 1481623 | 2123.077 | 21544.428 |
| A | VComp | 68815 | 644 | 2456 | 5272 | 0 | 1730220 | 1922.461 | 32191.300 |
| B | Baseline | 36233 | 1070 | 3142 | 4297 | 0 | 2244196 | 2577.734 | 669.925 |
| B | VComp | 45635 | 846 | 2474 | 3537 | 0 | 2770718 | 2376.231 | 1268.789 |
| C | Baseline | 31690 | 1269 | 3355 | 4551 | 0 | 4051748 | 2668.645 | 14.447 |
| C | VComp | 40089 | 980 | 2658 | 3789 | 0 | 3977694 | 2451.061 | 14.909 |
| D | Baseline | 83889 | 399 | 1675 | 2566 | 0 | 2643083 | 1966.544 | 3317.211 |
| D | VComp | 103799 | 354 | 1155 | 1981 | 0 | 3057786 | 1593.310 | 4654.600 |
| E | Baseline | 14215 | 0 | 0 | 0 | 9208 | 0 | 2172.692 | 471.278 |
| E | VComp | 16369 | 0 | 0 | 0 | 7823 | 0 | 2004.704 | 485.134 |
| F | Baseline | 31718 | 843 | 3056 | 4669 | 0 | 1732661 | 2275.236 | 10868.920 |
| F | VComp | 37692 | 726 | 2513 | 4033 | 0 | 2019737 | 2125.730 | 14219.215 |
| MixGraph | Baseline | 42450 | 693 | 3076 | 5534 | 33112 | 2908675 | 894.924 | 296.686 |
| MixGraph | VComp | 45134 | 664 | 2894 | 5345 | 29983 | 2919861 | 735.148 | 298.930 |
