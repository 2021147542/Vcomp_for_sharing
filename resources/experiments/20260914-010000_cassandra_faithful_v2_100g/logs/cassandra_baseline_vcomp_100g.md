# Cassandra 100 GiB: measured baseline vs VComp

- Schema: 10,000 token-ordered partition buckets, one blob clustering key, and one regular value column.
- Input: identical 100 GiB synthetic stream (24 B key + 1,000 B value, seed 20260909).
- Baseline: native CQL writes, 64 MiB explicit flushes, UCS T4 enabled, automatic compaction drain.
- VComp: descriptor-only compaction with native UCS selection, partition-boundary output splitting, then one final materialization/import.
- Approximate-key accuracy: baseline 66,278,498 rows; VComp 66,292,349 rows; delta +13,851 (+0.021%).

| System | Time (s) | Device write (GiB) | Write amp | Final DB (GiB) | SST count | Largest SST (MiB) |
|---|---:|---:|---:|---:|---:|---:|
| Baseline | 5524.010 | 420.349 | 5.818× | 72.255 | 181 | 589.757 |
| VComp | 122.312 | 82.528 | 1.002× | 82.404 | 210 | 565.291 |
