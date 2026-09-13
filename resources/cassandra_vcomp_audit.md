# Cassandra VComp implementation audit (2026-09-10)

## Audited scope

- All classes under `cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/`
- `VCompBulkLoad`, the two streaming-SSTable writer entry points, the CQL verification client,
  and the smoke/large-scale run scripts
- The restricted planner was compared directly with Cassandra 5.0.9's
  `UnifiedCompactionStrategy` and `Controller` implementation for the one-partition T4 case.

## Corrections made before the new load

- Replaced the old zero-padded key representation with one reversible codec that accepts exactly
  24-byte and 48-byte keys. The first eight bytes preserve numeric sort order; every remaining
  byte is a deterministic expansion of the coordinate. Decode/encode validates the entire key,
  so malformed or incorrectly sized keys fail immediately.
- Removed the unused bigint codec and the old fixed-width suffix codec, leaving one experiment key
  definition shared by materialization, flush tracking, and CQL verification.
- Corrected virtual compaction ordering so the global KMV is merged first, union cardinality is
  estimated once, and that same estimate is used by model merge and output construction.
- Corrected global union merging to use each vSST's global KMV. Range KMVs are now used only for
  range-local estimates and model-shape distribution.
- Capped global and range cardinality estimates by the represented integer-key domain, preventing
  descriptors that request more distinct keys than can exist in their key range.
- Added validation for flush ordering, IDs, key ranges, sizes, timestamps, model ranges, KMV sample
  ranges, complete-sketch cardinalities, range ordering, run ordering, duplicate compaction inputs,
  and duplicate run/vSST/materialized IDs.
- Removed metadata-only placeholder constructors and the unused `LoadSource` argument from final
  materialization.
- Moved generated-component verification before `nodetool import`. Verification now requires one
  non-empty `Data.db` per final descriptor and unique materialization directories.
- Renamed `installAtomically` to `install`: one `nodetool import` command is used, but Cassandra does
  not promise transactional rollback for a partially failed import.
- Added table/reverse-order validation to the streaming partition API added to
  `CQLSSTableWriter`.
- Parameterized both experiment sizes: 24-byte key + 1000-byte value (1024-byte KV) and 48-byte key
  + 43-byte value (91-byte KV). Run scripts reject every other key width and preserve failed as
  well as successful run directories.

## Verification completed

- Clean Cassandra build: passed.
- Cassandra checkstyle over 2,543 Java files: passed.
- VComp unit tests: 19 tests passed (0 failures, 0 errors), including a regression test proving
  that global KMV union is not contaminated by range-sketch theta thresholds.
- 24-byte/1000-byte end-to-end smoke: 64 writes, 43 reconstructed rows, SST import and full CQL
  verification passed.
- 48-byte/43-byte end-to-end smoke: 64 writes, 43 reconstructed rows, SST import and full CQL
  verification passed.

## Deliberate restrictions that remain

These are architectural experiment boundaries rather than silently accepted implementation gaps.

- This is a standalone synthetic-load path, not a registered online Cassandra compaction strategy.
  `VCompTrackingSSTableMultiWriter` is a tested future integration building block but is not wired
  into live Cassandra flushes.
- All rows use one partition key. Cassandra can split SSTables only at partition boundaries, so a
  final descriptor becomes one giant physical SSTable. Multiple partitions are required to test
  normal UCS sharding; that schema change is currently deferred.
- Reconstructed keys are learned-model approximations, as in the VComp design; the descriptor does
  not retain the original exact key set.
- Values are deterministic pseudorandom bytes derived from the key. This makes repeated writes to
  one key value-idempotent. Arbitrary per-write value changes cannot be reconstructed because the
  current descriptor does not retain per-key winning values or timestamps.
- UCS density uses estimated logical bytes because intermediate physical SSTables intentionally do
  not exist. With compression disabled, the 1 KiB smoke produced 44,855 physical Data.db bytes for
  44,032 logical bytes (1.9% difference). The 91-byte shape has proportionally larger Cassandra row
  overhead and will need explicit density calibration before claiming baseline-equivalent picker
  decisions at that size.
- Post-import correctness is checked by the external CQL client. Filesystem component validation
  alone is intentionally not described as verification of the live Cassandra table.

