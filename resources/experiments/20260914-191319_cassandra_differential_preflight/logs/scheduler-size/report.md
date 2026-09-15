# Bounded picker, sizing, and scheduling diagnosis

Diagnostic only, no production edits, no Cassandra DB opened and no physical SST writes. Seed remains 20260909. Commands: `bash run.sh`. Requires already compiled current Cassandra main classes; their hashes are retained. Seven assertions passed. All tests stop at first picker decision; no synthetic later compaction history is presented as native.

## Findings

1. **Measured input-shape mismatch, not measured physical-size error.** Actual production `SyntheticVCompLoadSource` with the retained baseline input contract (100 GiB domain 104857600, 65536 writes per flush, 10000 partitions) yields first-flush 65519 unique keys occupying 9983 partitions. Actual calibration key coordinates (4096 consecutive keys starting 0, 8192 consecutive keys starting 16384) occupy only 1 and 2 partitions respectively. `VCompBulkLoad.java:119` calibrates these sizes, then estimates 65536 rows for fixed planner flush metadata at `:121`. `VCompCqlSstableMaterializer.java:183`/`:213` constructs contiguous samples. `VCompSSTSizeModel.java:36` estimates bytes solely by row count; no partition-count or key/value-distribution predictor is used. Thus the calibration shapes do not represent the random input's partition overhead or locality. Magnitude and direction of actual Data.db error remain UNMEASURED. For 1GiB/small domains the exact calibration partition counts differ; do not transfer these counts there.

2. **Size error has discontinuous effects in actual production picker.** Synthetic metadata control, all same endpoints/timestamps and fixed 64 MiB planner flush reference: candidate densities 190/190/190/205 MiB, level-0 upper density 198.4 MiB. Multiplying all candidate sizes by 0.9 selects all four at level 0; nominal gives no selection (3+1 split across density levels); multiplying by 1.1 selects all four at level 1. This isolates candidate-size error relative to a held-fixed flush reference. It does NOT show that a uniform error scaling both candidate sizes AND the flush reference would do the same. Formula and threshold from actual `VCompUcsPlanner.java:49`, `:188`, `UnifiedCompactionPicker.java:143`. Synthetic metadata, NOT observed 10% production error.

3. **Size error can change actual shared shard count.** `Controller.calculateNumShards`, target64MiB/base1/growth0.333: combined density90/100/110MiB ->1/1/2 shards. The positive and negative 10% controls were both retained; no seed selection. Production uses this in `DefaultVirtualCompaction.java:192`. Exact row-count equality therefore does not alone establish identical physical SST topology.

4. **Immediate quiescence changes available history.** Same exact descriptors and planner, first call immediately at fourth flush selects [4,3,2,1]; delayed first eligible call after fifth flush selects [5,4,3,2,1]. Holding flush1 reserved at the fourth-flush snapshot leaves three and gives no pick. This is a controlled scheduling/availability intervention, not a measured native timing replay. Production `VCompPipeline.java:247` drains compactions to completion after each flush before admitting the next, while native `UnifiedCompactionStrategy.java:225`/`:239` reserves asynchronous work and `:326`/`:535` filters currently compacting files. No synthetic service time should be introduced as a fix without paper-algorithm review.

5. **No evidence of intrinsic tiered-policy mismatch in this check.** Both lanes use the same threshold/fanout picker and same density/sharding policy. It is the inputs and event availability that can differ. This test does not rule out other adapters/integration bugs or identify the full 100GiB latency/throughput cause.

## Remaining concrete checks

- Write small native and calibration/materializer SSTs from the identical key vectors and measure Data.db bytes per input/output; hold timestamp/value/schema identical; compare predicted and actual bytes. Record occupied partitions and compression separately.
- Feed those observed vs predicted descriptors into the same picker, stopping at first selected input/level difference.
- Replay a bounded native flush/reserve/complete event trace using exact metadata to isolate schedule, then run production schedule separately. Do not tune fake execution time to match performance.
- `VCompUcsPlanner` fixes rounded predicted initial flush size; native `Controller.java:381` follows observed flush sizes with >50% update hysteresis. Existing fixed-size experiments may never cross it; still needs event metadata logging.

A dormant helper issue found while reading: `cassandra_check/src/VCompSimulation.java:44` references undefined `sizeModel`; that helper was not used for these diagnoses and was not changed. This is not evidence explaining past benchmarks.
