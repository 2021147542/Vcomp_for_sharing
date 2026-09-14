# Cassandra 100 GiB: measured baseline vs VComp

- Schema: 10,000 token-ordered partition buckets, one blob clustering key, and one regular value column.
- Input: identical 100 GiB synthetic stream (24 B key + 1,000 B value, seed 20260909).
- Baseline: native CQL writes, 64 MiB explicit flushes, UCS T4 enabled, automatic compaction drain.
- VComp: descriptor-only compaction with native UCS selection, partition-boundary output splitting, then one final materialization/import.
- Approximate-key accuracy: baseline 66,278,498 rows; VComp 66,319,628 rows; delta +41,130 (+0.062%).

| System | Time (s) | Device write (GiB) | Write amp | Final DB (GiB) | SST count | Largest SST (MiB) |
|---|---:|---:|---:|---:|---:|---:|
| Baseline | 5750.138 | 432.186 | 6.205× | 69.647 | 164 | 601.802 |
| VComp | 121.132 | 80.161 | 1.002× | 80.037 | 209 | 553.764 |
