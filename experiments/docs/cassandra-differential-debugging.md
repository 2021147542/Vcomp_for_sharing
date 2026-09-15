# Cassandra differential debugging

## Follow-up bounded diagnosis, 2026-09-14 19:13–19:20

[Results and scope](../../resources/experiments/20260914-191319_cassandra_differential_preflight/results.md). Production unchanged. Same-job decomposition: native3558 → KMV3731 → inverse3728; merged bottom-K matches direct exact-union bottom-K. Exact global count alone does not restore key membership. Paper §4.2–4.3 explicitly allows approximate membership and inverse count loss.

Offline CQL writer controls locate timestamp loss in the initial FlushBatch, before any compaction; 1000B values yield a −0.0946% Data.db size delta in the bounded case. Production first-flush materialization for the 100GiB key domain (only65536 writes) has −0.392% size-prediction bias, with65519 rows but occupied partitions changing9983→9897. These are not measured20–30% performance causes.

CPU interventions demonstrate candidate-size/availability sensitivity, not actual native asynchronous history. The next unresolved comparison is native live/in-flight events versus virtual snapshots, followed by multi-job SST membership/shape and fixed-request hit/miss/SST-access diagnostics. Preserve the baseline and fixed seed; exact/debug controls remain test-only.

The reproducible native runner now includes the first-job CPU model probe. Offline writer controls run with `bash experiments/scripts/cassandra/run_timestamp_materialization_diagnostic.sh`; they require current main classes and write only a fresh `/tmp` tree.

Freeze the retained 100 GiB baseline and seed 20260909. Pause large end-to-end
reruns while identifying the first differing field in small native/exact/model
observations. Do not modify the production approximation algorithm to make an
exact diagnostic pass.

Run the bounded diagnostic:

```bash
bash experiments/scripts/cassandra/run_differential_oracle.sh
```

This builds current main classes, compiles two new test-only oracles, and runs
real CQL flushes and native compaction. New test data lives under the unique
`/tmp/vcomp-cassandra-differential-<timestamp>/storage` path from a copied YAML.
The retained baseline is never opened. Cassandra needs host-local network
interface discovery during test initialization; a sandbox that denies it can
fail before any comparison. Such failure is not a model divergence.

The exact reference retains bounded full primary keys, row/cell timestamps and
values. A key list alone would conceal reconciliation errors. The supported
fixture is insert-only, with no TTL, tombstones, null cells or conflicting
equal-timestamp values. It rejects unsupported cases instead of inventing
Cassandra reconciliation rules. Exact rows are never materialized as the
production VComp benchmark result.

| Stage | Current observation | Still unresolved |
|---|---|---|
| Picker/eligibility | Seven native event snapshots, matched measured SST metadata and availability | Production estimated metadata and asynchronous event history |
| Merge | Native CompactionIterator versus independent exact primary-key/version merge | Other schema features and full campaign history |
| Split | Native ShardedCompactionWriter versus independent token arithmetic for four fixed shards | Production shard-count decision and virtual splitter |
| Physical bytes/materialization | Native Data.db sizes recorded | Exact byte predictor and production CQL writer round trip |
| Model path | Production-default PLR/KMV union compared on the same four input SSTs | Effects of subsequent jobs and contribution to 100 GiB performance |

“Same event” means the same eligible inputs and observed state, not equal wall
clock time. The native and accelerated virtual paths have different service
times. A separate injected eligibility negative control validates the detector;
it is not counted as an observed production defect.

The first isolated result is recorded in
the diagnostic bundle (bundle deleted at user request, 2026-09-14).
Native and exact merge/split agree at 3,558 rows. In job 1 the model path predicts
3,728 rows, a +4.778% cardinality difference; the analyzer stops at
`approximation.$.row_count`. The already-recorded same-job vectors also contain
310 native-only keys, 480 model-only keys and 1,773 timestamp differences among
3,248 common keys. These observations are not a proof of the cause of the
100 GiB throughput gap, nor are they all automatically KMV estimation bugs.

`cassandra_first_divergence.py` reports the first unequal field within each
trace. It preserves unchecked stages, and never emits a production-fidelity
certificate. JUnit success means the diagnostic executed, not that all fields
matched. Picker and merge traces are separate controlled fixtures, not a
stitched native job history.

The next investigation should stay on the recorded job: inspect the model
cardinality inputs/estimate and timestamp propagation separately, then add the
missing physical materialization and production-state replay checks. Do not
patch later jobs or choose a favorable seed before this divergence is understood.

See [baseline reuse](baseline-reference.md) for rebuilding only VComp when input
semantics remain compatible. Reader/workload/configuration changes may require
new baseline measurements on a fresh checkpoint, without repeating its load.

## Full-path follow-through (2026-09-14)

The completed bounded analysis now covers matched format/target native jobs, exact writer and timestamp controls, carried native-DAG replay, actual C/E commands, and warm/Data.db-cold reads on independent copies of the retained original 100GiB states. See [the multistage diagnosis](../../resources/experiments/20260914-210336_cassandra_multistage_diagnosis/results.md). Extra partition overlap and hot-key membership are observed in the original state; do not equate total SST count with read amplification, or claim a historical first100GiB job was reconstructed. Production algorithms and the registered seed/reference remain unchanged.
