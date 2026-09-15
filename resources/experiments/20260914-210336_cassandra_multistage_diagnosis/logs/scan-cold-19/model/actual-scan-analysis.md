# Actual frozen-state E scan diagnostic

Actual local SinglePartitionReadCommand slices and CQL row limits, same next-partition fallback as workload; best-effort Data.db eviction (index/key cache unchanged), single thread, fixed warmup and all four ABBA rounds; 5% writes omitted from frozen states

Per-scan SST counts are summed actual merged iterators over all partition commands, not distinct SSTs or physical I/O. Process read_bytes under warm OS can be zero. rchar includes logical syscall bytes and instrumentation. Physical I/O latency not measured.

Data.db eviction requested: True. Optional before each phase: invalidate Cassandra chunk-cache entries and best-effort POSIX_FADV_DONTNEED on this side fixture Data.db files only. Index/key cache unchanged, no global cache drop; /tmp filesystem is not canonical /work device.

| Phase | Side | Scans | Returned rows | Partition commands | Merged SST iterators | Mean µs | p99 µs | Process read bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| measured_1 | native | 947 | 48855 | 952 | 3808 | 1047.717 | 2284.024 | 254377984 |
| measured_1 | vcomp | 947 | 48855 | 951 | 3804 | 986.630 | 2264.617 | 251650048 |
| measured_2 | native | 947 | 48855 | 952 | 3808 | 981.712 | 2223.621 | 254689280 |
| measured_2 | vcomp | 947 | 48855 | 951 | 3804 | 972.685 | 2181.663 | 251600896 |
| measured_3 | native | 947 | 48855 | 952 | 3808 | 969.266 | 2190.950 | 254640128 |
| measured_3 | vcomp | 947 | 48855 | 951 | 3804 | 964.065 | 2154.942 | 251846656 |
| measured_4 | native | 947 | 48855 | 952 | 3808 | 972.018 | 2193.614 | 254574592 |
| measured_4 | vcomp | 947 | 48855 | 951 | 3804 | 972.556 | 2221.116 | 251781120 |

Per-scan field mismatches (same in all four rounds):

```json
{
  "rows": 0,
  "partition_commands": 1,
  "sst_merged_iterators": 1,
  "first_key": 391,
  "last_key": 900,
  "row_keys_hash31": 937
}
```

First/last keys and rolling row-key hash detect differences but a matching hash alone is not a proof of complete row equality.
