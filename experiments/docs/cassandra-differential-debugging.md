# Cassandra differential debugging

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
