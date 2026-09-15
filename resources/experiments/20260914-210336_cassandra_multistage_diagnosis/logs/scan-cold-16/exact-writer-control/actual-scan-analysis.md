# Actual frozen-state E scan diagnostic

Actual local SinglePartitionReadCommand slices and CQL row limits, same next-partition fallback as workload; cache policy reported separately by cache_eviction_requested/eviction_scope; single thread, fixed warmup and all four ABBA rounds; 5% writes omitted from frozen states

Per-scan SST counts are summed actual merged iterators over all partition commands, not distinct SSTs or physical I/O. Process read_bytes under warm OS can be zero. rchar includes logical syscall bytes and instrumentation. Physical I/O latency not measured.

Data.db eviction requested: True. Optional before each phase: invalidate Cassandra chunk-cache entries and best-effort POSIX_FADV_DONTNEED on this side fixture Data.db files only. Index/key cache unchanged, no global cache drop; /tmp filesystem is not canonical /work device.

| Phase | Side | Scans | Returned rows | Partition commands | Merged SST iterators | Mean µs | p99 µs | Process read bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| measured_1 | native | 947 | 48855 | 953 | 953 | 445.040 | 906.057 | 131436544 |
| measured_1 | vcomp | 947 | 48855 | 953 | 953 | 469.436 | 1000.623 | 131567616 |
| measured_2 | native | 947 | 48855 | 953 | 953 | 441.019 | 899.435 | 131764224 |
| measured_2 | vcomp | 947 | 48855 | 953 | 953 | 466.992 | 980.035 | 131239936 |
| measured_3 | native | 947 | 48855 | 953 | 953 | 444.378 | 910.375 | 131698688 |
| measured_3 | vcomp | 947 | 48855 | 953 | 953 | 470.439 | 990.745 | 131436544 |
| measured_4 | native | 947 | 48855 | 953 | 953 | 441.748 | 907.370 | 131239936 |
| measured_4 | vcomp | 947 | 48855 | 953 | 953 | 466.483 | 977.220 | 131567616 |

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
