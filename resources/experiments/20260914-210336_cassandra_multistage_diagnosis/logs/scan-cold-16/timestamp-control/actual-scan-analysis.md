# Actual frozen-state E scan diagnostic

Actual local SinglePartitionReadCommand slices and CQL row limits, same next-partition fallback as workload; cache policy reported separately by cache_eviction_requested/eviction_scope; single thread, fixed warmup and all four ABBA rounds; 5% writes omitted from frozen states

Per-scan SST counts are summed actual merged iterators over all partition commands, not distinct SSTs or physical I/O. Process read_bytes under warm OS can be zero. rchar includes logical syscall bytes and instrumentation. Physical I/O latency not measured.

Data.db eviction requested: True. Optional before each phase: invalidate Cassandra chunk-cache entries and best-effort POSIX_FADV_DONTNEED on this side fixture Data.db files only. Index/key cache unchanged, no global cache drop; /tmp filesystem is not canonical /work device.

| Phase | Side | Scans | Returned rows | Partition commands | Merged SST iterators | Mean µs | p99 µs | Process read bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| measured_1 | native | 947 | 48855 | 953 | 953 | 439.473 | 903.633 | 131436544 |
| measured_1 | vcomp | 947 | 48855 | 953 | 953 | 467.124 | 987.860 | 131469312 |
| measured_2 | native | 947 | 48855 | 953 | 953 | 440.107 | 907.891 | 131633152 |
| measured_2 | vcomp | 947 | 48855 | 953 | 953 | 468.991 | 988.551 | 131403776 |
| measured_3 | native | 947 | 48855 | 953 | 953 | 439.632 | 905.957 | 131502080 |
| measured_3 | vcomp | 947 | 48855 | 953 | 953 | 467.546 | 992.559 | 131272704 |
| measured_4 | native | 947 | 48855 | 953 | 953 | 442.362 | 904.675 | 131305472 |
| measured_4 | vcomp | 947 | 48855 | 953 | 953 | 470.036 | 1000.814 | 131534848 |

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
