# Cassandra 100 GiB: measured baseline vs VComp

- Schema: 10,000 token-ordered partition buckets, one blob clustering key, and one regular value column.
- Input: identical 100 GiB synthetic stream (24 B key + 1,000 B value, seed 20260909).
- Baseline: native CQL writes, 64 MiB explicit flushes, UCS T4 enabled, automatic compaction drain.
- VComp: descriptor-only compaction with native UCS selection, partition-boundary output splitting, then one final materialization/import.
- Approximate-key accuracy: baseline 66,278,498 rows; VComp 66,293,226 rows; delta +14,728 (+0.022%).
- Measurement boundaries: Both systems include final compaction drain; sizes describe final live SST components.

| System | Time (s) | Device write (GiB) | Write amp | Final DB (GiB) | SST count | Largest SST (MiB) |
|---|---:|---:|---:|---:|---:|---:|
| Baseline | 1448.974 | 544.172 | 6.641× | 81.947 | 204 | 687.239 |
| VComp | 152.996 | 81.527 | 1.002× | 81.339 | 204 | 557.065 |
