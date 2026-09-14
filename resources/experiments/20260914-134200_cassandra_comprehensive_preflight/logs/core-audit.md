# Cassandra core audit — 2026-09-14

This is a source audit and regression rationale, not a completed 100 GiB result.
Baseline similarity is evaluated in both directions; a faster VComp result is not
preferred to a slower result of the same relative magnitude. No seed search,
exact baseline-key replay, or post-hoc selection was introduced.

## Confirmed defects corrected

1. **Complete KMV sample replay bypassed the learned model.**
   `VCompMaterializedKeyIterator` previously sorted and returned sketch keys whenever
   `isComplete()` was true. Paper §4.3 requires independent inverse-rank
   reconstruction and states that KMV is not reapplied at materialization. A complete
   sketch can make the cardinality exact during compaction; it does not authorize
   replacing the corrected model at materialization. The shortcut is removed.
   This changes small controls and any final descriptor with a complete sketch;
   it is not a claim that this shortcut caused the historical 100 GiB E gap.

2. **Picker sizing used the wrong physical byte definition.**
   Native `ShardManager.density()` divides `SSTableReader.onDiskLength()` by token
   coverage. The reader method returns `dfile.onDiskLength`, i.e. the Data.db
   component, not the sum of all SST components. `writeCalibration()` previously
   counted all component files. Calibration now counts Data.db only. All files
   remain on disk for inspection. Reported total storage may still legitimately
   include every component, but that metric is distinct from UCS density bytes.

3. **Calibrated sizing retained an unrelated logical lower bound.**
   The old calibrated model initialized an upper envelope with logical KV bytes,
   then added the measured affine line. This can reject physically smaller
   compressed encodings by definition. Calibrated mode now uses its measured
   line; explicit logical mode remains available. The planned comparison disables
   compression in both systems, so no compressed-table performance claim follows.

4. **Offline flush-size threshold omitted native MiB rounding.**
   Native `Controller.getFlushSizeBytes()` rounds observed flush bytes up to a
   whole MiB before deriving the base density. `VCompBulkLoad` previously passed
   the raw estimated byte count as an offline override. It now applies the same
   upward rounding through `VCompUcsPlanner.roundObservedFlushSize()`, and emits
   both raw estimated flush bytes and the picker threshold in `VCOMP_SIZING`.
   `size_basis=data_component` identifies calibrated estimates; explicit logical
   mode reports `size_basis=logical_kv`. Both fields are bytes; the picker value
   is rounded, while `estimated_flush_bytes` is the unrounded model output.
   This aligns rounding, not the full native dynamic observation mechanism.

## Changed files and verification intent

Source:

- `cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/VCompMaterializedKeyIterator.java`
- `cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/VCompCqlSstableMaterializer.java`
- `cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/VCompUcsPlanner.java`
- `cassandra_vcomp/src/java/org/apache/cassandra/tools/VCompBulkLoad.java`

Tests:

- `DefaultVirtualCompactionTest`: a complete sketch containing `{0, 1, 20}` with
  a rank model whose inverse is `{0, 10, 20}` must materialize the latter. This
  fails the old shortcut. The existing overlapping-input test now expects the
  analytically inverted normalized masses, `{0, 9, 18, 25, 32, 41}`, instead of
  demanding the original union `{0, 10, 20, 30, 40, 50}`.
- `VCompCqlSstableMaterializerTest`: writes two real calibration SSTables and
  verifies that the estimates recover Data.db byte sizes while other components
  also exist. Its existing row-liveness/header/value round trip uses error-zero
  fitting to isolate serialization from model approximation.
- `VCompUcsPlannerTest`: checks below/exactly/above a whole-MiB boundary, including
  the 64 MiB + 1 byte case that must become 65 MiB.

`git diff --check` passed for these owned files before the consolidated build.
The parent-coordinated consolidated build and Checkstyle completed successfully
(`build-checkstyle.log`: 2,550 checked files), and the isolated core suite passed
53 tests (`core-tests.log`), including these new regressions. The separate
Controller suite passed 24 tests (`controller-tests.log`). The subsequent
VCOMP_SIZING label-only correction requires the final JAR rebuild before use.
Daemon and scale qualification are coordinated separately; this audit does not
assert their completion.

## Reviewed behavior retained

- The ordered key layout uses signed Murmur3 token order, then byte-order
  partition-key ties; scalar ranges therefore agree with partition/clustering
  order. The UCS candidate adapter compares partition identities, not clustering
  scalar endpoints. Distinct clustering ranges inside one partition overlap.
- Native UCS selects output shard count from combined **input** density. The
  virtual splitter likewise uses combined input bytes/token coverage and the
  Controller shard formula, rather than sizing from deduplicated output bytes.
- Outputs split at whole partition boundaries and become separate picker
  candidates, retaining common lineage only in descriptor IDs.
- The corrected continuous parent rank is sliced and rebased. Output sketch
  propagation does not trigger a second density correction in the splitter.
- CQL materialization supplies row liveness, value-cell timestamps and encoding
  minima. SSTs are imported before ordinary Cassandra workload execution.
- The production ordered pipeline stays on continuous PLR reconstruction. The
  discrete-certificate helper retained for direct test/legacy entry points is
  not activated by `VCompBulkLoad`'s ordered pipeline.

## Remaining limitations and interpretation boundaries

**Scheduling/lifecycle integration is incomplete.** `VCompPipeline.runVirtualLoad`
registers one virtual flush, then calls `compactToQuiescence` synchronously before
reading the next flush. Native UCS reserves readers through
`Tracker.tryModify(..., COMPACTION)`, creates a `UnifiedCompactionTask`, and allows
flush and compaction tasks to overlap. Sharing a picker does not equate their
candidate visibility schedules. Even T4 can differ: eight overlapping candidates
visible together can form one eight-input pick; observing four and completing
that pick before four more arrive produces two four-input picks. That is a
mechanism demonstration, not an attribution of the measured 15% storage gap.

A real native integration would require descriptor-backed reader registration,
virtual dispatch after the native pick, lifecycle commit/rollback for virtual
outputs, flush integration, and final atomic replacement with real readers.
Wrapping the current loop in an executor would not implement those contracts and
was not presented as such. Native scheduling itself is timing-dependent; final
layout equality is not guaranteed merely by a common seed.

**Flush and physical-size metadata remain estimates.** The corrected calibration
still uses two bounded contiguous probes. Actual random flushes touch more
partitions, carry a distribution of timestamps, and incur different per-partition
framing. The offline controller uses a fixed rounded estimate, whereas native
Controller observes real flushes and updates after a sufficiently large change.
These remaining estimation errors must be measured in the qualified load; they
are not fixed by removing the component-byte mismatch.

**Timestamp and key identity reconstruction remain approximate.** A virtual
output inherits the maximum timestamp of its inputs and materializes generated
rows with that timestamp. It does not preserve each original winning timestamp
or exact hot-key membership. Native UCS uses maximum timestamp for candidate
ordering; equal inherited timestamps can leave ties that real outputs do not
share. Exact timestamp/key replay was not added to force comparable outcomes.

**Writer metadata is not proven identical.** CQL SST writing uses the native SST
serialization implementation, but it is not the native sharded compaction task.
For this full-ring one-node case, native coverage fallback and virtual endpoint
coverage have matching intended definitions; all header/statistics fields have
not been proven equal across native and materialized outputs.

These constraints must accompany the new 100 GiB results. Passing regression
checks means the tested failures were corrected; it does not prove an absence of
all bugs or establish ±10% workload fidelity in advance.
