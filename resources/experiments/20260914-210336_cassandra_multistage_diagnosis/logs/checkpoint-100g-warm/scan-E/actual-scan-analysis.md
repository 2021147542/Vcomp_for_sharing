# Actual frozen-state E scan diagnostic

Actual local SinglePartitionReadCommand slices and CQL row limits, same next-partition fallback as workload; cache policy reported separately by cache_eviction_requested/eviction_scope; single thread, fixed warmup and all four ABBA rounds; 5% writes omitted from frozen states

Per-scan SST counts are summed actual merged iterators over all partition commands, not distinct SSTs or physical I/O. Process read_bytes under warm OS can be zero. rchar includes logical syscall bytes and instrumentation. Physical I/O latency not measured.

Data.db eviction requested: False. Optional before each phase: invalidate Cassandra chunk-cache entries and best-effort POSIX_FADV_DONTNEED on this side fixture/checkpoint Data.db files only. Index/key cache unchanged, no global cache drop; Physical device is determined by the recorded fixture/checkpoint path.

| Phase | Side | Scans | Returned rows | Partition commands | Merged SST iterators | Mean µs | p99 µs | Process read bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| measured_1 | native | 947 | 48855 | 970 | 3876 | 98.158 | 169.456 | 0 |
| measured_1 | vcomp | 947 | 48855 | 971 | 4211 | 87.656 | 154.898 | 0 |
| measured_2 | native | 947 | 48855 | 970 | 3876 | 100.380 | 159.817 | 0 |
| measured_2 | vcomp | 947 | 48855 | 971 | 4211 | 85.365 | 150.420 | 0 |
| measured_3 | native | 947 | 48855 | 970 | 3876 | 91.932 | 155.680 | 0 |
| measured_3 | vcomp | 947 | 48855 | 971 | 4211 | 85.536 | 152.183 | 0 |
| measured_4 | native | 947 | 48855 | 970 | 3876 | 95.555 | 158.876 | 0 |
| measured_4 | vcomp | 947 | 48855 | 971 | 4211 | 85.098 | 150.891 | 0 |

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
