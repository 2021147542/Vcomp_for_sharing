# Actual frozen-state E scan diagnostic

Actual local SinglePartitionReadCommand slices and CQL row limits, same next-partition fallback as workload; warm OS, single thread, fixed warmup and all four ABBA rounds; 5% writes omitted from frozen states

Per-scan SST counts are summed actual merged iterators over all partition commands, not distinct SSTs or physical I/O. Process read_bytes under warm OS can be zero. rchar includes logical syscall bytes and instrumentation. Physical I/O latency not measured.

Data.db eviction requested: False. No explicit Data.db cache eviction

| Phase | Side | Scans | Returned rows | Partition commands | Merged SST iterators | Mean µs | p99 µs | Process read bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| measured_1 | native | 947 | 48855 | 953 | 953 | 59.061 | 108.292 | 0 |
| measured_1 | vcomp | 947 | 48855 | 953 | 953 | 63.521 | 117.199 | 0 |
| measured_2 | native | 947 | 48855 | 953 | 953 | 57.612 | 105.687 | 0 |
| measured_2 | vcomp | 947 | 48855 | 953 | 953 | 64.946 | 118.721 | 0 |
| measured_3 | native | 947 | 48855 | 953 | 953 | 53.460 | 106.648 | 0 |
| measured_3 | vcomp | 947 | 48855 | 953 | 953 | 60.245 | 111.407 | 0 |
| measured_4 | native | 947 | 48855 | 953 | 953 | 58.656 | 104.895 | 0 |
| measured_4 | vcomp | 947 | 48855 | 953 | 953 | 60.498 | 113.071 | 0 |

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
