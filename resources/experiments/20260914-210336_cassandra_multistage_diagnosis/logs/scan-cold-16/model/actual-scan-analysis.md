# Actual frozen-state E scan diagnostic

Actual local SinglePartitionReadCommand slices and CQL row limits, same next-partition fallback as workload; cache policy reported separately by cache_eviction_requested/eviction_scope; single thread, fixed warmup and all four ABBA rounds; 5% writes omitted from frozen states

Per-scan SST counts are summed actual merged iterators over all partition commands, not distinct SSTs or physical I/O. Process read_bytes under warm OS can be zero. rchar includes logical syscall bytes and instrumentation. Physical I/O latency not measured.

Data.db eviction requested: True. Optional before each phase: invalidate Cassandra chunk-cache entries and best-effort POSIX_FADV_DONTNEED on this side fixture Data.db files only. Index/key cache unchanged, no global cache drop; /tmp filesystem is not canonical /work device.

| Phase | Side | Scans | Returned rows | Partition commands | Merged SST iterators | Mean µs | p99 µs | Process read bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| measured_1 | native | 947 | 48855 | 953 | 953 | 521.223 | 1012.526 | 131567616 |
| measured_1 | vcomp | 947 | 48855 | 952 | 952 | 504.575 | 1015.051 | 130199552 |
| measured_2 | native | 947 | 48855 | 953 | 953 | 455.627 | 926.626 | 131567616 |
| measured_2 | vcomp | 947 | 48855 | 952 | 952 | 469.704 | 934.731 | 129937408 |
| measured_3 | native | 947 | 48855 | 953 | 953 | 451.111 | 913.641 | 131567616 |
| measured_3 | vcomp | 947 | 48855 | 952 | 952 | 467.564 | 959.045 | 130134016 |
| measured_4 | native | 947 | 48855 | 953 | 953 | 447.769 | 923.880 | 131239936 |
| measured_4 | vcomp | 947 | 48855 | 952 | 952 | 454.295 | 929.371 | 130199552 |

Per-scan field mismatches (same in all four rounds):

```json
{
  "rows": 0,
  "partition_commands": 3,
  "sst_merged_iterators": 3,
  "first_key": 460,
  "last_key": 892,
  "row_keys_hash31": 941
}
```

First/last keys and rolling row-key hash detect differences but a matching hash alone is not a proof of complete row equality.
