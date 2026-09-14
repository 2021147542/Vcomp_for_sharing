# Cassandra 1 GiB: measured baseline vs VComp

- Schema: 100 token-ordered partition buckets, one blob clustering key, and one regular value column.
- Input: identical 1 GiB synthetic stream (24 B key + 1,000 B value, seed 20260909).
- Baseline: native CQL writes, 64 MiB explicit flushes, UCS T4 enabled, automatic compaction drain.
- VComp: descriptor-only compaction with native UCS selection, partition-boundary output splitting, then one final materialization/import.
- Approximate-key accuracy: baseline 662,885 rows; VComp 690,994 rows; delta +28,109 (+4.240%).

| System | Time (s) | Device write (GiB) | Write amp | Final DB (GiB) | SST count | Largest SST (MiB) |
|---|---:|---:|---:|---:|---:|---:|
| Baseline | 50.434 | 2.545 | 3.970× | 0.641 | 8 | 91.941 |
| VComp | 2.339 | 0.689 | 1.025× | 0.672 | 8 | 97.381 |
