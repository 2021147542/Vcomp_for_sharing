# Actual frozen-state E scan diagnostic

Actual local SinglePartitionReadCommand slices and CQL row limits, same next-partition fallback as workload; warm OS, single thread, fixed warmup and all four ABBA rounds; 5% writes omitted from frozen states

Per-scan SST counts are summed actual merged iterators over all partition commands, not distinct SSTs or physical I/O. Process read_bytes under warm OS can be zero. rchar includes logical syscall bytes and instrumentation. Physical I/O latency not measured.

Data.db eviction requested: False. Optional before each phase: invalidate Cassandra chunk-cache entries and best-effort POSIX_FADV_DONTNEED on this side fixture Data.db files only. Index/key cache unchanged, no global cache drop; /tmp filesystem is not canonical /work device.

| Phase | Side | Scans | Returned rows | Partition commands | Merged SST iterators | Mean µs | p99 µs | Process read bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| measured_1 | native | 947 | 48855 | 952 | 3808 | 176.400 | 348.139 | 0 |
| measured_1 | vcomp | 947 | 48855 | 951 | 3804 | 126.071 | 281.644 | 0 |
| measured_2 | native | 947 | 48855 | 952 | 3808 | 107.895 | 185.756 | 0 |
| measured_2 | vcomp | 947 | 48855 | 951 | 3804 | 110.760 | 193.240 | 0 |
| measured_3 | native | 947 | 48855 | 952 | 3808 | 107.297 | 226.451 | 0 |
| measured_3 | vcomp | 947 | 48855 | 951 | 3804 | 110.307 | 209.350 | 0 |
| measured_4 | native | 947 | 48855 | 952 | 3808 | 99.201 | 169.094 | 0 |
| measured_4 | vcomp | 947 | 48855 | 951 | 3804 | 105.587 | 169.897 | 0 |

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
