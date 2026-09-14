# Cassandra 100 GiB comprehensive campaign — failed during workload A

Started 2026-09-14 14:10:45 KST; stopped 14:48:05 KST with exit status 1.
Both fresh 100 GiB loads completed and full-table deterministic value verification passed.
Baseline/VComp visible rows: 66,278,498 / 66,389,271 (+0.1671%).
The first A baseline workload failed about 11 seconds after measurement started;
no complete workload cell exists and this is **not a completed performance comparison**.

The daemon reported `newPosition > limit` (7523 > 4096, then 7779 > 4096)
and an Index.db EOF during concurrent native compaction early opening.
See [server failure evidence](failed-a-baseline-system.log), [campaign tail](campaign-tail.log),
[terminal status](status.env), and [final load inventory](final_state.json).
Root-cause investigation and a separately identified rerun follow; this attempt is preserved.
No seed or performance result selected the retry.

Raw logs, failed workload checkpoint, and verified canonical loaded databases:
`/work/vcomp-pebble-1tb/cassandra-comprehensive-100g-20260914-141045`.
Original frozen settings and source/build hashes remain in this bundle.
