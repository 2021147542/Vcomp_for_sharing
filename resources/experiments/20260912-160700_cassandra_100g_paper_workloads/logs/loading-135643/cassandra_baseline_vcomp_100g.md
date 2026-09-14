# Cassandra 100 GiB: measured baseline vs VComp

- Schema: one partition key (`all`), one blob clustering key, one regular value column.
- Input: identical 100 GiB synthetic stream (24 B key + 1,000 B value, seed 20260909).
- Baseline: native CQL writes, 64 MiB explicit flushes, UCS T4 enabled, automatic compaction drain.
- VComp: descriptor simulation then materialization; no size-driven output split in this one-partition control.
- Approximate-key accuracy: baseline 66,278,498 rows; VComp 67,768,639 rows; delta +1,490,141 (+2.248%).

| System | Time (s) | Device write (GiB) | Write amp | Final DB (GiB) | SST count | Largest SST (MiB) |
|---|---:|---:|---:|---:|---:|---:|
| Baseline | 6009.799 | 454.169 | 5.915× | 76.789 | 4 | 54551.786 |
| VComp | 112.630 | 84.596 | 1.000× | 84.586 | 4 | 51326.747 |
