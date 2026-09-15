# Actual frozen-state E scan diagnostic

Actual local SinglePartitionReadCommand slices and CQL row limits, same next-partition fallback as workload; cache policy reported separately by cache_eviction_requested/eviction_scope; single thread, fixed warmup and all four ABBA rounds; 5% writes omitted from frozen states

Per-scan SST counts are summed actual merged iterators over all partition commands, not distinct SSTs or physical I/O. Process read_bytes under warm OS can be zero. rchar includes logical syscall bytes and instrumentation. Physical I/O latency not measured.

Data.db eviction requested: True. Optional before each phase: invalidate Cassandra chunk-cache entries and best-effort POSIX_FADV_DONTNEED on this side fixture/checkpoint Data.db files only. Index/key cache unchanged, no global cache drop; Physical device is determined by the recorded fixture/checkpoint path.

| Phase | Side | Scans | Returned rows | Partition commands | Merged SST iterators | Mean µs | p99 µs | Process read bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| measured_1 | native | 947 | 48855 | 970 | 3876 | 872.075 | 3296.201 | 915173376 |
| measured_1 | vcomp | 947 | 48855 | 971 | 4211 | 997.447 | 3433.227 | 919109632 |
| measured_2 | native | 947 | 48855 | 970 | 3876 | 859.149 | 3262.307 | 915173376 |
| measured_2 | vcomp | 947 | 48855 | 971 | 4211 | 1002.181 | 3439.359 | 919109632 |
| measured_3 | native | 947 | 48855 | 970 | 3876 | 766.367 | 1220.635 | 915173376 |
| measured_3 | vcomp | 947 | 48855 | 971 | 4211 | 888.959 | 1700.518 | 919109632 |
| measured_4 | native | 947 | 48855 | 970 | 3876 | 784.415 | 1218.249 | 915173376 |
| measured_4 | vcomp | 947 | 48855 | 971 | 4211 | 887.957 | 1700.779 | 919109632 |

Per-scan field mismatches (same in all four rounds):

```json
{
  "rows": 0,
  "partition_commands": 3,
  "sst_merged_iterators": 228,
  "first_key": 518,
  "last_key": 898,
  "row_keys_hash31": 940
}
```

First/last keys and rolling row-key hash detect differences but a matching hash alone is not a proof of complete row equality.
