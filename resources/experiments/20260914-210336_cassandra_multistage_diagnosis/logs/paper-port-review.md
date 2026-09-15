# Cassandra port: paper conformance and causal boundaries

Read-only production audit, 2026-09-14. This review reads the actual
`resources/VComp_0913.pdf` (fresh `mutool draw -F txt` extraction), current
`README.md`, RocksDB implementation, Cassandra port, and existing diagnostic
evidence. The only executable addition is a CPU diagnostic under `test/unit`.
No production implementation, input seed, canonical baseline, or benchmark
configuration was changed. This document is one component of the broader
native-event/materialization/read-path investigation, not a claim that every
100 GiB performance difference has been attributed.

## 1. The reference versions are different

The paper §4.2 explicitly specifies absolute common-threshold cardinality
`min(N_sum, ceil(m / theta))`, range-local correction and normalization. Section
4.3 specifies independent continuous-PLR inverse generation, monotonization,
and termination at a descriptor's upper bound. It explicitly does not promise
original key membership or exact emitted count.

Current RocksDB HEAD is a later implementation. Its
[README](../../README.md#discrete-cdf-and-kmv-merge-candidate) documents the
default discrete-CDF certificate and a sampled deduplication-ratio estimator.
[EstimateKMVUnionEntries](../../db/virtual_compaction/virtual_sst.cc#L217) now
multiplies `sampled_unique / sampled_entries` by the naive entry count; the
range estimator similarly multiplies proportional range mass by that ratio.
The adjacent code comments explain why this differs from an absolute estimator.
These are real specification differences, not alternate names for one formula.

Consequently, silently adopting the current RocksDB estimator or discrete
materialization to improve Cassandra's results would change the paper target.
The current Cassandra continuous/common-theta path follows the supplied PDF on
these points. A comparison against RocksDB HEAD must identify the chosen
revision and feature settings, rather than label every difference a port bug.

## 2. Established approximations are not automatically coding defects

| Stage | Current code | Paper assessment |
|---|---|---|
| Flush | Sort/deduplicate actual batch keys, fit error-8 PLR, 512 global samples and up to eight local sketches sharing another 512 samples | Matches §4.1 |
| Global union | Common theta; preserve original sample identities; complete union exact, otherwise `ceil(m/theta)` with capacity/count bounds | Core estimator matches §4.2 |
| Local merge | Inclusive breakpoint spans; local estimate changes slope; global normalization makes total mass equal the global estimate | Matches the explicit inclusive-boundary/normalization description in §4.2 |
| Split | Split corrected rank mass and propagate original-key sketches | Required dependency in §4.2; Cassandra-specific partition/shard adaptation must be checked separately |
| Final generation | Inverse PLR, clamp, increase duplicate/decreasing keys, stop contributing beyond upper range | Matches §4.3's approximate independent generation |

Source anchors: [DefaultFlushVirtualizer](../../cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/DefaultFlushVirtualizer.java#L66),
[mergeModelsRangeAware](../../cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/DefaultVirtualCompaction.java#L294),
[estimateCommonThetaCardinality](../../cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/DefaultVirtualCompaction.java#L574),
[VCompMaterializedKeyIterator](../../cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/VCompMaterializedKeyIterator.java#L107).

The prior first-job decomposition (3,558 exact union, 3,731 estimated,
3,728 emitted) therefore demonstrates approximation at known stages. The
bottom-K union matched a sketch built directly from exact input union keys.
Even error-zero input fits and a complete sketch need not reconstruct the
exact union after the range-local merge: adjacent inclusive spans can share
boundary mass and normalization redistributes that mass. Error-zero direct
fitting of the final union is a different operation. It is a diagnostic oracle,
not a replacement production algorithm.

Allowed approximation still has to meet the user's performance-fidelity
criterion. Being allowed by the paper does not prove that its effect is small
on Cassandra's partitioned read path.

## 3. A concrete reproducible invariant defect

`VirtualSSTable` currently rejects a complete original-key sketch whose count
differs from the descriptor's modeled count:
[constructor check](../../cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/VCompPipeline.java#L660).
But ordered output splitting allocates rows from the approximate parent rank
function, while `mergeSketches` filters original sampled keys into the same
key range:
[output construction](../../cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/DefaultVirtualCompaction.java#L207).
Those two counts can validly differ under the paper algorithm. A complete
sketch of original keys is not a certificate of the reconstructed model.

The new [CPU probe](../../cassandra_vcomp/test/unit/org/apache/cassandra/db/compaction/vcomp/VCompCompleteSketchSplitProbe.java)
calls the actual production merge/split classes without writing SSTs. Cases
are declared before execution: seed 20260909, four batches of 128 attempted
writes, domain 4096, 64 partitions, KMV 512/ranges 8, 64 MiB target, and PLR
errors 8 and 0. A second metadata-only 1 MiB/entry case exercises sharding;
it does not claim that real 1 MiB values or a large database were written.

| Metadata bytes/entry | PLR error | Shards | Global exact/estimated count | Actual production split |
|---:|---:|---:|---:|---|
| 1024 | 8 | 1 | 488 / 488 | succeeds |
| 1024 | 0 | 1 | 488 / 488 | succeeds |
| 1048576 | 8 | 4 | 488 / 488 | rejects complete-sketch/count mismatch |
| 1048576 | 0 | 4 | 488 / 488 | rejects complete-sketch/count mismatch |

For error 8, modeled shard counts are 145/108/140/95 while exact original
counts are 127/109/143/109. Error 0 still gives 127/110/143/108. The actual
production split exception, rather than the independently reported allocation
arithmetic, establishes the failure. The latter arithmetic records which
contract is inconsistent; it is not a native split oracle.

This is a latent correctness defect to address by distinguishing original
sketch cardinality from modeled output cardinality. Copying original keys,
changing the seed, or forcing all children to exact original counts would not
be a justified paper-conforming fix. The preserved 100 GiB run completed with
incomplete sketches in its large files; this exception is **not evidence that
it caused that run's 20–30% performance difference**.

Raw output: `/tmp/vcomp-complete-sketch-probe/validated-report.json`; execution
log: `/tmp/vcomp-complete-sketch-probe/run.log`. A first execution omitted
`build/test/classes` and logged missing test logging classes, but completed the
calculation. The second execution includes that classpath and preserves the
same four outcomes. The first JSON is `report.json`; no cases/seeds were
selected after observing the outcomes.

Reproduce from `cassandra_vcomp`, using existing compiled production classes:

```bash
mkdir -p /tmp/vcomp-complete-sketch-probe/classes
/usr/lib/jvm/java-11-openjdk-amd64/bin/javac \
  -cp 'build/classes/main:build/lib/jars/*:build/test/lib/jars/*' \
  -d /tmp/vcomp-complete-sketch-probe/classes \
  test/unit/org/apache/cassandra/db/compaction/vcomp/VCompCompleteSketchSplitProbe.java
/usr/lib/jvm/java-11-openjdk-amd64/bin/java \
  -cp '/tmp/vcomp-complete-sketch-probe/classes:build/classes/main:build/test/classes:build/lib/jars/*:build/test/lib/jars/*:test/conf' \
  -Dcassandra.logdir=/tmp/vcomp-complete-sketch-probe/logs \
  org.apache.cassandra.db.compaction.vcomp.VCompCompleteSketchSplitProbe \
  /tmp/vcomp-complete-sketch-probe/validated-report.json
```

## 4. Scheduling: an architectural deviation, not yet an attributed percentage

Paper §4.4 registers vSST metadata into the engine's ordinary state and runs
virtual compaction through its original background compaction path. RocksDB
still dispatches to `RunVirtualCompaction` inside
[native background compaction](../../db/db_impl/db_impl_compaction_flush.cc#L4451).
Cassandra instead has a standalone
[per-flush quiescence loop](../../cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/VCompPipeline.java#L239),
its own state, and immediate sequential reserve/complete operations. It shares
the picker policy but not the native lifecycle/strategy/state path.

This falls short of the paper's architectural reuse requirement. It must not
be represented as an already faithful reuse of the entire compaction path.
However, eliminating data I/O also changes completion times in the original
RocksDB design. Native and virtual wall-clock histories need not be identical;
adding sleeps or replaying baseline-derived timing to force matching output
would not establish correct generic compaction semantics.

The existing 20 MiB native-event fixture found different eligibility at F5,
but both paths selected F1–F4 once and ended with J1+F5 lineage. That particular
availability difference did not demonstrate different final grouping. Only
a multi-job trace can establish where differing availability leads to an
actual different job or final layout. Shared-picker agreement under identical
metadata does not clear the surrounding scheduling integration.

## 5. Metadata and materialization distinctions

- **Timestamp:** [SyntheticVCompLoadSource](../../cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/SyntheticVCompLoadSource.java#L88)
  discards key-level timestamps at flush creation. Merge takes input maxima;
  [materializer](../../cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/VCompCqlSstableMaterializer.java#L136)
  applies one maximum to the descriptor. This loses native version history.
  The paper preserves approximate key distributions rather than original
  identities, and current RocksDB materialization likewise assigns file-global
  precedence. Exact per-key timestamps therefore cannot simply be required
  as a new production oracle. For the restricted deterministic key-to-value
  load, this does not by itself prove wrong returned values. Prior 1000 B
  value control measured only −0.0946% Data.db size effect; read-path pruning
  effects require direct evidence.
- **Sizes:** calibration uses contiguous 4096/8192 keys, whereas random
  64 MiB flushes occupy almost all 10,000 partitions. A prior physical first
  flush showed approximately −0.4% estimated-byte bias. Repeated compaction
  metadata remains a separate question because UCS tier/shard thresholds
  are discontinuous. Do not turn the known small first-flush error into an
  unsupported claim of 20–30% latency contribution.
- **Shards:** the ordered path calls `Controller.calculateNumShards`, with
  base shards 1, growth .333 and target from the loader; the current native
  baseline schema uses those same base/growth options. Native combined density
  uses total input bytes and the covering endpoint interval, as does the
  adapter. This is not a separate STCS implementation. One-node/full-ring and
  no-TTL/no-deletion restrictions are substantive; general repair/topology
  equivalence is not provided.
- **On-disk format:** the 20 MiB old diagnostic mixed nb native with oa
  offline files and did not match target options. That diagnostic confound
  must be removed in a matched native/materializer experiment. The preserved
  100 GiB campaign's `final_state.json` contains **oa on both arms**, so the
  nb/oa mismatch does not explain that campaign.
- **Verification:** [VCompFilesystemVerifier](../../cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/VCompFilesystemVerifier.java#L50)
  currently checks nonempty components and an aggregate count bound. It does
  not read back every file and certify per-file bounds, row distributions,
  metadata, or read behavior. Successful import is not equivalent to those
  checks. This is a verification gap, not evidence of file corruption.

## 6. Why comparable plots require comparable request semantics

The current [CassandraPaperWorkload](../../cassandra_check/src/CassandraPaperWorkload.java#L201)
separates payload randomness, acknowledges insert progression, and derives
MixGraph query type/key/size from the intended shared key-space draw. These
current paths must not be confused with historical generator revisions.
Its [scan](../../cassandra_check/src/CassandraPaperWorkload.java#L355) can issue
multiple CQL queries to traverse ordered partitions, and a zero-length
MixGraph seek reads one row as a proxy for RocksDB's cursor-only seek.
These are explicit cross-engine adaptations. Both Cassandra arms use them,
but partition occupancy changes can alter the number of underlying CQL/SST
accesses even when requested logical rows are equal.

Paper §5 uses 48 workers, five-minute workloads and a RocksDB block cache
equal to 5% of dataset size. A Cassandra chunk cache at the same percentage
plus OS page cache is not the same cache architecture. In time-limited runs,
raw device bytes mix per-operation cost with differing operation counts.
Therefore fixed request traces, hit/miss, partition/SST candidates, actual
read counters, and bytes per operation are necessary for causal diagnosis;
none of those should be relabeled as device I/O merely from set membership.

The historical 152956 final inventory is already different: baseline 204 SSTs
and 87,895,956,310 Data.db bytes, VComp 186 SSTs and 81,825,525,072 bytes.
These describe final state, not an attribution among scheduling, estimated
cardinality, partition density, and physical writing. Earlier experiments
whose plots looked closer used different duration/generator/cache conditions;
reverting to whichever plot is closest would not identify a correct algorithm.

## 7. Two-generation native-DAG control and partition-shape attribution

New root fixture `20260914-210336` records sixteen 64 MiB flushes (1 GiB
logical attempted input), six actual native jobs, two generations, and eight
final SSTs. Domain 1048576, 100 ordered partitions, seed 20260909, 24 B key /
1000 B value, 64 MiB target, matched oa format. Its root native/aligned/read
reports are separate from this CPU intervention.

[VCompNativeScheduleModelReplay](../../cassandra_vcomp/test/unit/org/apache/cassandra/db/compaction/vcomp/VCompNativeScheduleModelReplay.java)
imposes the recorded native job DAG on the production model/sketch/split
operations, carrying modeled outputs forward instead of refitting them to
the next job's native keys. It uses original keys only for the initial flush
models and for explicit diagnostic comparisons. This is a test-only schedule
intervention; it bypasses the picker and does not simulate service times.

The replay only maps a native output to a carried model output when both
calculations give the same full-ring shard count, each output falls wholly
in one shard, and the occupied shard IDs have a unique one-to-one match. It
does not zip output arrays or silently force different boundaries together.
If this mapping fails, it reports a partial frontier and stops. All six jobs
in this fixture admit the verified mapping.

The first global-cardinality difference is native event 23: 231923 native
rows versus 254244 modeled rows. Carried final estimated/emitted rows total
690994 versus native 662885. Crucially, the default replay's eight final
emitted-key counts and sorted-key SHA256 hashes **match all eight actual
production VComp materialized SSTs**. The matching candidate in each case is
unique by containing descriptor range; it is not selected by hash similarity.
The final descriptor ranges/counts/bytes also agree. Therefore imposing the
native DAG does not change final generated keys or the observed descriptor
layout in this fixture. The earlier availability-order difference is not a
demonstrated cause of this fixture's final key/layout error.

Root's independent native-aligned controls refit actual input rows separately
at each job. At generation two, the global counts equal the carried path,
but per-shard counts differ: event 105 carried rows
92217/94987/64185/64255 versus refit 94163/95215/63625/62641. This establishes
propagated distribution error even when global union estimates agree.

Four CPU lanes were declared together before execution, all using the same
native DAG and original flush vectors. Larger sketches and error-zero fits
are diagnostic interventions only; no production setting was selected.

| Lane | Emitted rows | Mean rows/partition | Population SD across 100 partitions | Minimum | Maximum |
|---|---:|---:|---:|---:|---:|
| Native | 662885 | 6628.85 | 49.63 | See native vectors | See native vectors |
| KMV 512 / PLR error 8 (production default) | 690994 | 6909.94 | 456.42 | 5616 | 7939 |
| KMV 512 / PLR error 0 | 690994 | 6909.94 | 185.42 | 6626 | 7136 |
| KMV 4096 / PLR error 8 | 690590 | 6905.90 | 389.97 | 5889 | 7788 |
| KMV 4096 / PLR error 0 | 690594 | 6905.94 | 76.83 | 6790 | 7021 |

At fixed KMV size, changing only the initial fit error reduces partition
unevenness substantially without materially changing total cardinality.
Larger sketches reduce the remaining unevenness further in the error-zero
lane. This identifies modeled local geometry as a source of the distorted
partition distribution; it does not show that a larger sample budget repairs
the algorithm or that error-zero fits are a suitable final implementation.
In fact, global count remains approximately 4.2% high in all four lanes.

The two error-8 lanes were additionally checked against a direct bottom-K
constructed from each job's exact native input union. **All six jobs match
exactly at both sample sizes:** retained key identities, theta, and completeness
are equal, and the estimator returns the same count. At 4096 samples the last
two native unions contain 311422 and 351463 keys but direct estimates are
320878 and 369716 (sum 690594; the error-8 generator later loses four rows).
Thus the remaining count error in this fixed fixture is the specified
threshold estimator's error on these key/hash samples. It is not introduced
by child sketch propagation or PLR rank normalization. Upward rounding adds
less than one row per estimated union and cannot explain these thousands of
rows. No hash/seed or estimator was changed to reduce this observed error.
The direct controls are `carry-direct-512-8.json` and
`carry-direct-4096-8.json`, with theta and sample counts recorded per job.

Raw CPU outputs and logs are `/tmp/vcomp-complete-sketch-probe/carry-512-8.*`,
`carry-512-0.*`, `carry-4096-8.*`, `carry-4096-0.*`. The predeclared lane plan
is `carry-lanes-plan.json`. The earlier default-only key fingerprint report
is `native-schedule-replay-with-fingerprints.json`. No physical SSTs or
workload measurements were created by this helper.

## 8. Timestamp read-pruning mechanism and what the new small fixture can show

For the supported point lookup, [SinglePartitionReadCommand](../../cassandra_vcomp/src/java/org/apache/cassandra/db/SinglePartitionReadCommand.java#L703)
selects the names-filter optimization. It sorts candidate SSTs by decreasing
maximum timestamp, then calls `reduceFilter` before each next SST.
[isRowComplete](../../cassandra_vcomp/src/java/org/apache/cassandra/db/SinglePartitionReadCommand.java#L1168)
requires both row liveness and all requested cells to have timestamps strictly
greater than the next SST's maximum. If so, the read can stop early.

Thus, with the same keys/files/file maxima, replacing an older row timestamp
by that file's maximum can make a hit terminate earlier. However, the actual
port also propagates the maximum across all sibling output shards, potentially
raising a candidate file's maximum above its native value. These changes
must be separated by a timestamp-only same-key/file control; their net effect
cannot be inferred from smaller serialized timestamp bytes. The generic
slice/scan path used by E does not apply this per-row names-filter shortcut
in the insert-only, no-tombstone fixture.

The eight final SSTs of the 1 GiB fixture cover disjoint ordered partition
ranges. Few or no extra candidate SSTs may remain to prune, so a null effect
in its exact-versus-flat-timestamp read control cannot rule out an effect in
the 100 GiB layout with overlapping tiers. This is a scope limitation of the
fixture, not grounds for manufacturing overlap or picking favorable requests.

The preserved 100 GiB C results provide context, not a controlled attribution:
VComp throughput +26.50%, hit p50 −28.66%, miss p50 −9.00%, and read bytes per
operation −27.40%. Its observed hit fraction changes from about 57.38% to
66.93%. Time-limited runs consumed different-length streams, so those aggregate
hit fractions do not substitute for identical requests. E read bytes per
operation also differ (−19.88%) despite lacking the names-filter shortcut.
Timestamp collapse alone therefore cannot explain every workload difference.

## 9. Original 100 GiB topology: more overlap despite fewer files

The later checkpoint-read diagnostic opened independent copies of the original
100 GiB SSTs and exported their reader metadata. This section reads only that
JSON and the original small load logs. It does not scan SST rows, mutate a
database, or execute a storage benchmark. Primary metadata source:
`experiments/artifacts/cassandra-checkpoint-read-20260914-212215/opened-sst-metadata.json`.
The checkpoint retains baseline 204 and VComp 186 files, all oa format, with
the same Data.db byte totals as the original published final inventory.

The exact configured 10000-partition token ordering was exported with
`VCompOrderedPartitionLayout`. Every SST contributes to the inclusive range
between its first and last partition keys. These are candidate-range counts,
not assertions that every covered partition exists in the SST or is read.

| Candidate-range topology | Native | VComp |
|---|---:|---:|
| File count | 204 | 186 |
| Depth over all 10000 configured partitions | exactly 4 everywhere | 4–6 |
| Partitions at depth 4 | 10000 | 7465 |
| Partitions at depth 5 | 0 | 1240 |
| Partitions at depth 6 | 0 | 1295 |
| Mean partition depth | 4.000 | 4.383 |
| Same fixed 1000 C requests: mean candidate depth | 4.000 | 4.333 |

The VComp extra-overlap regions are ordinal 5063–6357 (depth 6) and
8760–9999 (depth 5). All other ordinals have depth 4. Native maximum-timestamp
cohorts ending near flushes 1024, 1280, 1536 and 1600 each cover the full
partition domain once. VComp has partially covering cohorts ending at many
different flush indices, including 1380, 1428, 1452, 1464, 1488, 1524, 1548,
1572, 1584 and 1596. Timestamp buckets are descriptive age groupings, not a
reconstruction of the unrecorded original compaction DAG.

The original VComp log records an estimated flush size of 67830192 B and a
rounded picker flush size of 68157440 B (65 MiB). The original native log has
1600 application flush records, all between 65.015 and 65.046 MiB, with first
flush 65.037 MiB. Since this run is uncompressed and Controller rounds the
observed Data.db size upward to whole MiB, native 66 MiB is an inference from
the data-writer logs and code; the Controller state itself was not archived.

Applying the shared T4 density bucket formula to the opened metadata gives:

| Derived density bucket | Native: inferred 66 MiB flush | VComp: recorded 65 MiB flush |
|---|---:|---:|
| 1 | 0 | 2 |
| 2 | 0 | 4 |
| 3 | 16 | 20 |
| 4 | 142 | 73 |
| 5 | 46 | 87 |

The calculation uses `Data.db bytes / effective token coverage`, base density
`flush_bytes * (1 - .9/4)`, and fanout 4/survival factor 1. These buckets are
**not** persisted SST level numbers or exact generations. Reported token
coverage is used when valid; otherwise the full-ring endpoint fallback is
used. Repeating the classification at both 65 and 66 MiB changes some bucket
4/5 assignments but leaves VComp's six low-density files in buckets 1/2.
Those files cover broad partition ranges, adding read candidates even though
total file count is smaller. This final topology is consistent with uneven
regional progress through compaction; the metadata does not identify the
first original 100 GiB job that created the divergence.

An actual shared-picker CPU check on all final opened descriptors returns
**no compaction pick in all four conditions**, native/VComp × 65/66 MiB.
All files are assumed eligible in this insert-only, no-expiration control.
Thus the observed additional overlap is not simply pending eligible T4 work
that a final drain or the one-MiB threshold adjustment immediately resolves.

There is also a stronger timestamp bound than in the earlier small fixture:
among all files whose endpoint ranges cover the same partition, timestamp
intervals **never intersect on either arm** (0/60000 pair-slots native,
0/76615 VComp). Therefore, for the frozen insert-only single-cell lookup, a
row found in a newer candidate already has timestamp strictly greater than
all older candidate maxima. Given these metadata bounds, changing only its
within-file timestamp to that file's maximum cannot make the names-filter
early-stop test newly succeed. This rules out timestamp flattening alone as
the source of extra candidate traversal in this particular frozen layout.
It does not equate layouts or returned key membership between arms.

Actual-read agent's independent join of these depth labels with all fixed
cold C rounds locates the performance gap in the same regions: depth-4
queries average 576.50→619.84 microseconds (+7.52%), depth-5 queries
644.46→926.39 (+43.75%), depth-6 queries 676.11→1128.06 (+66.85%). The
233 queries in depth-5/6 regions contribute about 71.3% of the total mean
paired latency increase. The both-hit subset also shows the larger gap in
the higher-depth regions. These are diagnostic local reads of a fixed request
set with their recorded cold-cache procedure; they are not a rerun of the
historical five-minute workload, and the grouping is observational rather
than a topology-changing intervention.

This connects a concrete location in the original final state to actual read
work. The small four-lane replay separately identifies PLR/local estimation
as a source of partition-density distortion. Together they provide a much
narrower explanation than total SST count or a generic “tiered compaction”
claim, while leaving the original 100 GiB first divergent load job unobserved.

CPU evidence under `/tmp/vcomp-complete-sketch-probe/`:
`original-100g-topology.json`, `original-100g-request-depths.json`,
`original-100g-density-tiers.json`, `original-100g-flush-size-evidence.json`,
`original-100g-final-picker.json`. Reproduction sources are
`analyze_topology.py`, `classify_density.py`, `ExportPartitionLayout.java`,
`FinalTopologyPicker.java`. Actual depth/latency attribution is preserved in
`experiments/artifacts/cassandra-checkpoint-read-20260914-212300/point-C/actual-read-depth-attribution.json`
and its paired Markdown report. Estimated partition-key metadata was never
reported as actual live-row cardinality.
