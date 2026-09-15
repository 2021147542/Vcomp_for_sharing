# Actual frozen-state E scan diagnostic

Actual local SinglePartitionReadCommand slices and CQL row limits, same next-partition fallback as workload; best-effort Data.db eviction (index/key cache unchanged), single thread, fixed warmup and all four ABBA rounds; 5% writes omitted from frozen states

Per-scan SST counts are summed actual merged iterators over all partition commands, not distinct SSTs or physical I/O. Process read_bytes under warm OS can be zero. rchar includes logical syscall bytes and instrumentation. Physical I/O latency not measured.

Data.db eviction requested: True. Optional before each phase: invalidate Cassandra chunk-cache entries and best-effort POSIX_FADV_DONTNEED on this side fixture Data.db files only. Index/key cache unchanged, no global cache drop; /tmp filesystem is not canonical /work device.

| Phase | Side | Scans | Returned rows | Partition commands | Merged SST iterators | Mean µs | p99 µs | Process read bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| measured_1 | native | 947 | 48855 | 952 | 3808 | 973.941 | 2182.564 | 254574592 |
| measured_1 | vcomp | 947 | 48855 | 952 | 3808 | 973.677 | 2226.115 | 255209472 |
| measured_2 | native | 947 | 48855 | 952 | 3808 | 968.960 | 2186.090 | 254443520 |
| measured_2 | vcomp | 947 | 48855 | 952 | 3808 | 974.794 | 2188.656 | 255275008 |
| measured_3 | native | 947 | 48855 | 952 | 3808 | 976.151 | 2175.791 | 254509056 |
| measured_3 | vcomp | 947 | 48855 | 952 | 3808 | 976.963 | 2270.839 | 255455232 |
| measured_4 | native | 947 | 48855 | 952 | 3808 | 966.481 | 2167.797 | 254377984 |
| measured_4 | vcomp | 947 | 48855 | 952 | 3808 | 987.233 | 2281.960 | 255340544 |

Per-scan field mismatches (same in all four rounds):

```json
{
  "rows": 0,
  "partition_commands": 0,
  "sst_merged_iterators": 0,
  "first_key": 0,
  "last_key": 0,
  "row_keys_hash31": 0
}
```

First/last keys and rolling row-key hash detect differences but a matching hash alone is not a proof of complete row equality.
