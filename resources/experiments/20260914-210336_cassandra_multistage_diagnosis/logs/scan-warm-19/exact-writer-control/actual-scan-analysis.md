# Actual frozen-state E scan diagnostic

Actual local SinglePartitionReadCommand slices and CQL row limits, same next-partition fallback as workload; warm OS, single thread, fixed warmup and all four ABBA rounds; 5% writes omitted from frozen states

Per-scan SST counts are summed actual merged iterators over all partition commands, not distinct SSTs or physical I/O. Process read_bytes under warm OS can be zero. rchar includes logical syscall bytes and instrumentation. Physical I/O latency not measured.

Data.db eviction requested: False. Optional before each phase: invalidate Cassandra chunk-cache entries and best-effort POSIX_FADV_DONTNEED on this side fixture Data.db files only. Index/key cache unchanged, no global cache drop; /tmp filesystem is not canonical /work device.

| Phase | Side | Scans | Returned rows | Partition commands | Merged SST iterators | Mean µs | p99 µs | Process read bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| measured_1 | native | 947 | 48855 | 952 | 3808 | 97.438 | 163.896 | 0 |
| measured_1 | vcomp | 947 | 48855 | 952 | 3808 | 105.425 | 176.529 | 0 |
| measured_2 | native | 947 | 48855 | 952 | 3808 | 102.186 | 172.672 | 0 |
| measured_2 | vcomp | 947 | 48855 | 952 | 3808 | 101.729 | 184.463 | 0 |
| measured_3 | native | 947 | 48855 | 952 | 3808 | 106.233 | 177.361 | 0 |
| measured_3 | vcomp | 947 | 48855 | 952 | 3808 | 106.435 | 180.266 | 0 |
| measured_4 | native | 947 | 48855 | 952 | 3808 | 97.450 | 169.436 | 0 |
| measured_4 | vcomp | 947 | 48855 | 952 | 3808 | 105.847 | 173.824 | 0 |

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
