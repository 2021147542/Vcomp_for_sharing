# Cassandra YCSB / MixGraph comparison

- Validation: **passed the 1 GiB scale-up gate**. Cardinality error was +4.240%, final DB size was +4.845%, and both systems retained 8 SSTables.
- Throughput delta (VComp vs baseline): A −3.28%, B +3.60%, C −0.53%, D −0.29%, E +2.48%, F +3.76%, MixGraph −1.52%.
- Tested source base: `a035cf6fd84b340efa0a24e525978df3f0f1c1fc` plus the uncommitted implementation patch; runtime JAR SHA-256 is recorded in `runtime-jar.sha256`.
- Raw load runs: `/work/vcomp-pebble-1tb/cassandra-vcomp-faithful-v2-1g-20260913-224000`.
- Raw workload campaign: `/work/vcomp-pebble-1tb/cassandra-faithful-v2-workloads-1g-20260913-224300`.
- Inputs: preserved baseline and VComp DBs; one isolated hard-linked checkpoint per workload
- Workload: 48 client threads, 20,000 operations per client; same seed and operation count for both systems
- Cache start: scoped `POSIX_FADV_DONTNEED` on each checkpoint before Cassandra startup
- Disk I/O: `/proc/diskstats` delta for `md0` over the exact client interval

| Workload | System | Throughput (ops/s) | Point p50 (µs) | Point p95 (µs) | Point p99 (µs) | Scan p99 (µs) | Read misses | Disk read (GB) | Disk write (MB) |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A | Baseline | 156677 | 229 | 650 | 1695 | 0 | 128047 | 0.687 | 12.141 |
| A | VComp | 151530 | 230 | 623 | 1808 | 0 | 119512 | 0.718 | 12.124 |
| B | Baseline | 142342 | 249 | 552 | 1635 | 0 | 277779 | 0.688 | 0.094 |
| B | VComp | 147468 | 239 | 537 | 1571 | 0 | 253894 | 0.721 | 0.098 |
| C | Baseline | 146933 | 252 | 529 | 1461 | 0 | 365633 | 0.688 | 0.131 |
| C | VComp | 146155 | 249 | 537 | 1462 | 0 | 339702 | 0.721 | 0.115 |
| D | Baseline | 168284 | 192 | 478 | 1551 | 0 | 128177 | 0.676 | 0.106 |
| D | VComp | 167795 | 199 | 477 | 1542 | 0 | 130280 | 0.710 | 0.119 |
| E | Baseline | 36814 | 0 | 0 | 0 | 3785 | 0 | 0.688 | 10.879 |
| E | VComp | 37726 | 0 | 0 | 0 | 3760 | 0 | 0.721 | 11.489 |
| F | Baseline | 109939 | 224 | 510 | 1309 | 0 | 167673 | 0.688 | 12.329 |
| F | VComp | 114078 | 218 | 477 | 1241 | 0 | 157754 | 0.721 | 12.374 |
| MixGraph | Baseline | 62081 | 342 | 2466 | 6332 | 28262 | 257597 | 0.576 | 0.119 |
| MixGraph | VComp | 61137 | 341 | 2511 | 6533 | 31932 | 229636 | 0.570 | 0.147 |
