# Actual frozen-state E scan diagnostic

Actual local SinglePartitionReadCommand slices and CQL row limits, same next-partition fallback as workload; warm OS, single thread, fixed warmup and all four ABBA rounds; 5% writes omitted from frozen states

Per-scan SST counts are summed actual merged iterators over all partition commands, not distinct SSTs or physical I/O. Process read_bytes under warm OS can be zero. rchar includes logical syscall bytes and instrumentation. Physical I/O latency not measured.

Data.db eviction requested: False. No explicit Data.db cache eviction

| Phase | Side | Scans | Returned rows | Partition commands | Merged SST iterators | Mean µs | p99 µs | Process read bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| measured_1 | native | 947 | 48855 | 953 | 953 | 75.812 | 144.439 | 0 |
| measured_1 | vcomp | 947 | 48855 | 952 | 952 | 89.038 | 221.362 | 0 |
| measured_2 | native | 947 | 48855 | 953 | 953 | 85.437 | 212.014 | 0 |
| measured_2 | vcomp | 947 | 48855 | 952 | 952 | 63.769 | 115.175 | 0 |
| measured_3 | native | 947 | 48855 | 953 | 953 | 115.772 | 335.756 | 0 |
| measured_3 | vcomp | 947 | 48855 | 952 | 952 | 119.498 | 347.056 | 0 |
| measured_4 | native | 947 | 48855 | 953 | 953 | 57.636 | 108.182 | 0 |
| measured_4 | vcomp | 947 | 48855 | 952 | 952 | 61.292 | 117.318 | 0 |

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
