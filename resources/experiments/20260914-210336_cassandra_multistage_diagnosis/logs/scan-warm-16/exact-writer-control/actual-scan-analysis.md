# Actual frozen-state E scan diagnostic

Actual local SinglePartitionReadCommand slices and CQL row limits, same next-partition fallback as workload; warm OS, single thread, fixed warmup and all four ABBA rounds; 5% writes omitted from frozen states

Per-scan SST counts are summed actual merged iterators over all partition commands, not distinct SSTs or physical I/O. Process read_bytes under warm OS can be zero. rchar includes logical syscall bytes and instrumentation. Physical I/O latency not measured.

Data.db eviction requested: False. No explicit Data.db cache eviction

| Phase | Side | Scans | Returned rows | Partition commands | Merged SST iterators | Mean µs | p99 µs | Process read bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| measured_1 | native | 947 | 48855 | 953 | 953 | 59.445 | 109.994 | 0 |
| measured_1 | vcomp | 947 | 48855 | 953 | 953 | 62.963 | 118.130 | 0 |
| measured_2 | native | 947 | 48855 | 953 | 953 | 58.294 | 106.759 | 0 |
| measured_2 | vcomp | 947 | 48855 | 953 | 953 | 61.289 | 112.610 | 0 |
| measured_3 | native | 947 | 48855 | 953 | 953 | 56.742 | 104.043 | 0 |
| measured_3 | vcomp | 947 | 48855 | 953 | 953 | 61.765 | 111.788 | 0 |
| measured_4 | native | 947 | 48855 | 953 | 953 | 55.626 | 103.874 | 0 |
| measured_4 | vcomp | 947 | 48855 | 953 | 953 | 59.690 | 109.845 | 0 |

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
