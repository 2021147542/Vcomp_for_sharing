# Pebble VComp Feasibility Prototype Report

## Native/exact differential debugging and preserved baseline (2026-09-14 17:28 KST)

The seed-20260909 100 GiB native baseline is now a registered reference with 1,632 component hashes, immutable provenance and input/configuration guards. VComp-only rebuilds can reuse its loaded state; changing reader/workload/cache protocols requires new measurements from checkpoints, not another baseline load.

A bounded native diagnostic found the first model-path field difference in job 1: 3,558 native/exact rows versus 3,728 model rows (+4.778%). Exact merge and four fixed-shard outputs match native rows/versions; seven matched-metadata picker event snapshots also agree. The same-job vectors contain 310 native-only keys, 480 model-only keys and 1,773 timestamp differences among 3,248 common keys. Production algorithms were not modified. These small 24 B / 43 B, 64-partition fixtures do not clear production size estimates, asynchronous scheduling, shard-count choices or the CQL materializer, and do not establish the cause of the full 100 GiB performance gap. No new large load or benchmark was launched.

Diagnostic results (bundle deleted at user request, 2026-09-14) · [Differential workflow](../experiments/docs/cassandra-differential-debugging.md) · [Baseline reuse](../experiments/docs/baseline-reference.md).

## Cassandra 100 GiB completed; similarity not achieved (2026-09-14 16:50 KST)

All fourteen 48-worker, 300-second workload cells completed with zero server errors and OOM events. Canonical SST component hashes before/after and the measured source/reader JAR checks passed. Similarity still fails: 30 of 47 primary comparisons fall outside ±10%. A–F throughput is 15.16–26.50% higher, which is still a mismatch; MixGraph is +6.32%. E reads are −7.73% in the fixed-time interval and −19.88% per scan. C diagnostics show different hit/miss populations and key-cache occupancy/miss behavior, without establishing their separate causal contributions. Model membership and native task/flush lifecycle differences remain unresolved.

The original gray/blue 2×2 loading/workload layout is restored in all 17 public bundles; normalized measurements and the independent latency figures were preserved during each style update. The automatic publisher was updated only after measurement verification.

[Results](experiments/20260914-152956_cassandra_100g_organized_resume/results.md) · [Completion evidence and limits](experiments/20260914-152956_cassandra_100g_organized_resume/logs/completion-notes.md).

## Result layout completed; 100 GiB workloads resumed (15:30 KST)

Timestamp bundles now expose only figures/, logs/, results.md; the three SVGs and tables use the same source values. Incomplete attempts were removed from the overview while preserving completed unfavorable cells and required load provenance under logs/. Historical device latency is unavailable. The resumed run adds device-level mean request latency from existing diskstats boundaries; the 47 primary comparisons, seed 20260909 and 48 workers × 300 seconds are unchanged. Canonical loads are reused and all fourteen workload checkpoints restart.

[현재 결과 / current results](experiments/20260914-152956_cassandra_100g_organized_resume/results.md).

## User-requested pause and result layout (2026-09-14 15:11 KST)

The Cassandra workload run was paused at the user's request and has not restarted.
Result bundles now use `figures/loading.svg`, `figures/workload.svg`,
`figures/io_latency.svg`, `logs/`, and `results.md`; figures are being verified.
Latency figures use recorded DB/API measurements with their actual scope. Physical
disk I/O latency and unrecorded write-only latency remain unavailable, not zero or
substituted point-read latency. Failed attempts are retained under the repair
bundle's `logs/attempts/`; completed measurements outside ±10% remain reportable.

## Native chunk-cache repair qualified; 100 GiB workloads restarted (15:01 KST)

Native cache keys omitted reader chunk geometry and growing-tail length. Two actual-file reproducers failed against the old cache and pass after repair; all five regression controls and build/Checkstyle passed. Both 100GiB A pilot cells (48 workers, 60 seconds) passed with zero server errors/OOM. The successful original loads are reused; all 14 workload cells restart with separate load/reader binary provenance and canonical SST byte hashes before/after.

[수정 검증 / repair evidence](experiments/20260914-144957_cassandra_chunk_cache_repair/logs/README.md) · [일시 중단한 재실행 / paused attempt](experiments/20260914-144957_cassandra_chunk_cache_repair/logs/attempts/20260914-150117_cassandra_100g_cachefixed/README.md).

## Cassandra 100 GiB attempt failed during first workload (14:48 KST)

Both complete loads passed full-table value verification. The first A baseline cell failed with Index.db EOF and RandomAccessReader buffer-position errors during native compaction. Zero workload cells completed; this attempt is not performance evidence. Logs and canonical loaded databases are preserved while the root cause is investigated.

[실패 기록 / failed attempt](experiments/20260914-144957_cassandra_chunk_cache_repair/logs/attempts/20260914-141045_cassandra_100g_comprehensive/README.md).

## Cassandra comprehensive qualification and new campaign (2026-09-14 14:10 KST)

The audit corrected complete-KMV replay during materialization, native Data.db
calibration and flush-size rounding, workload E/D/MixGraph semantics, compaction
default handling, symmetric measurement boundaries, and inherited rate/memtable
controls. Core/UCS 77 tests, full Checkstyle, workload regressions and real daemon
pilots passed. An initial schema-assertion failure was retained and corrected.
See [qualification evidence](experiments/20260914-134200_cassandra_comprehensive_preflight/logs/README.md).

The new [100 GiB campaign](experiments/20260914-144957_cassandra_chunk_cache_repair/logs/attempts/20260914-141045_cassandra_100g_comprehensive/README.md)
started at 14:10:45 KST: fixed seed 20260909, 48 threads, 300 seconds per workload,
native 5 GiB chunk cache and shared 20 GiB memory cgroup without swap. There are
47 preregistered similarity comparisons, including workload device reads/writes.
Additional OS cache and incomplete native virtual-task lifecycle integration are
disclosed limitations. Qualification does not establish absence of every bug or
fidelity success; no completed measurement is claimed at launch.

## Cassandra workload-setting correction and E audit (2026-09-14)

The latest 100 GiB run does not reproduce the paper's five-minute workloads and
dataset-relative 5% block cache. The positive operation limit overrides 300 seconds;
the daemon heap and initial fadvise do not implement that cache budget. E device
reads are 145.796/236.800 decimal GB (+62.42%), with no plotting conversion error.
CPU-only replay finds 26,839/518,805 distinct E scan starts before/after the worker
RNG fix, but cannot attribute the within-pair I/O gap. E also reuses the operation
choice for its scan length, restricting scans to 1..95 rather than the local
RocksDB reference's uniform 1..100. This audit preserves measurements and code;
it does not establish an I/O root cause or a corrected benchmark result.
See the setting and E audit (bundle deleted at user request, 2026-09-14).

## Cassandra 100 GiB rerun completed (2026-09-14 13:09:58 KST)

The paired load and all 14 fixed-operation workload runs completed with exit
status 0; the total campaign took approximately two hours. Fidelity did not
pass: 28 of 33 primary comparisons exceeded the symmetric ±10% criterion.
Throughput deltas for A/B/C/D/E/F/MixGraph were −27.41/−16.65/−24.50/−24.09/
−23.79/−11.54/−10.59%. C point p50/p95/p99 deltas were +27.33/+36.07/+30.16%.
Final visible row counts were close (+0.0621%), but live SST counts were
164 versus 209 (+27.44%), and final component bytes differed by +14.92%.
Both sides' final-state figures use actual files after natural compaction drain.
The unfavorable result, configuration, provenance and original databases are
preserved in the [completed bundle](experiments/20260914-111023_cassandra_100g_fidelity_rerun/results.md).
One repetition and a changed workload generator preclude attributing differences
from historical runs to any individual code fix.

## Cassandra 100 GiB rerun launched (2026-09-14 11:10 KST)

Registered the optional picker seed in CassandraRelevantProperties and updated
both runtime-JAR audits. The full source Checkstyle now passes; Controller's
24 tests and a real seeded 24 B/1000 B smoke pass. VComp's daemon also receives
the shared picker seed. Launched the requested fresh paired 100 GiB load and
fixed-operation A–F/MixGraph campaign in tmux
`cassandra_fidelity_100g_20260914_111023`. Raw databases and logs are preserved at
`/work/vcomp-pebble-1tb/cassandra-fidelity-100g-20260914-111023`; status, preflight
evidence and automatic final publication live in
[the new result bundle](experiments/20260914-111023_cassandra_100g_fidelity_rerun/results.md).
No completed 100 GiB results are claimed at launch.

## Cassandra fidelity audit update (2026-09-14)

The latest review compares `VComp_0913.pdf` sections 4.1–4.4 with the actual
Cassandra source and faithful-v2 results. It fixes partition endpoint semantics
in the UCS adapter, redundant KMV correction during output splitting, missing
INSERT row liveness and encoding minima, and correlated workload worker RNG
streams. Workload timing now starts after preparation and reports hit/miss
latencies separately. The current standalone scheduler is still not integrated
with the native task lifecycle. New 100 GiB workload parity is not established.
See the audit and validation bundle (bundle deleted at user request, 2026-09-14)
for evidence and limits. Earlier sections below describe historical versions.

## Change log

- 2026-09-03: Reconfigured the Pebble experiment from its small-scale feasibility settings to the paper's large-scale loading settings: 64 MiB memtables, 65,536 1 KiB writes per explicit 64 MiB flush, and 64 MiB target SSTs. Removed the baseline's per-flush compaction drain so normal Pebble loading can overlap foreground writes with background compactions; the final drain remains. WAL and compression remain disabled, and compaction/materialization concurrency remains dynamically bounded from one to 48 workers. This change was made before restarting the experiment at 100 GiB because extending the 4 MiB scale-down configuration to 1 TiB created excessive tiny SSTs and serialized compaction work.
- 2026-09-03: A 100 GiB run with the 64 MiB configuration completed its work in about 13 minutes but failed the final descriptor-union check: the reopened DB iterator returned 2,521 more positions than the unique descriptor union. Descriptor compactions can propagate the same highest input sequence number to several outputs, and at this scale independently reconstructed tables produced a small number of identical internal keys across levels. Materialization now assigns every final table a unique synthetic sequence number while preserving the relative recency order carried by the virtual metadata. This changes neither reconstructed user keys nor cardinality estimates; it enforces Pebble's internal-key ordering requirement for direct table installation. Added a focused regression test for uniqueness and recency-order preservation.
- 2026-09-03: Re-ran the corrected 100 GiB experiment successfully. The complete test took 933.6 s including independent accuracy tracing and iterator validation; baseline loading took 565.8 s, while measured VComp simulation plus materialization took 35.50 s (15.94x faster). The reopened materialized DB exactly matched the descriptor union and returned zero incorrect values.
- 2026-09-01: Cloned the upstream Pebble repository into `pebble-vcomp/` as the isolated implementation and experiment target. No existing RocksDB/VComp source was modified.
- 2026-09-02: Added `pebble-vcomp/vcomp/model.go`, a Go port of the paper prototype's vSST descriptor operations: piecewise-linear learned-index fitting/merge/inverse, global and eight range-local KMV sketches, KMV union estimation, descriptor slicing, and final key materialization. This module contains no compaction-picking policy; Pebble's existing picker and splitter are used by the integration harness.
- 2026-09-02: Added `pebble-vcomp/vcomp/model_test.go` with focused checks for learned-index rank/inverse behavior, exact and sampled KMV union estimates, output slicing, and bounded/ordered materialization.
- 2026-09-02: Added `pebble-vcomp/vcomp_experiment_test.go`, an opt-in end-to-end feasibility harness. It runs deterministic natural Pebble loading with WAL/compression disabled, drives descriptor-only compactions through Pebble's unmodified score picker and output splitter, records KMV error with a strictly separate truth trace, materializes final vSSTs once, installs them at the predicted levels, validates keys/values, and reports write amplification plus final-state accuracy. The scale-down constants and explicit deterministic flush boundary are experiment controls shared by both paths, not VComp optimizations.
- 2026-09-02: Fixed the experiment harness for the current Pebble APIs by supplying compression as a profile callback and recording table counts with Pebble's unsigned metric type.
- 2026-09-02: Added a fail-fast invariant to the experiment harness that reports any overlapping predicted output ranges before they can create an invalid Pebble level. This is diagnostic validation only and does not alter VComp output.
- 2026-09-02: Expanded the ordering-failure diagnostic to print every simulated table's level, key range, entry estimate, and sequence range; no compaction decision or descriptor is modified.
- 2026-09-02: Fixed fake Pebble table-metadata initialization: `HasPointKeys` is now left unset until `ExtendPointKeyBounds` initializes both bounds and the flag. Setting it early made Pebble preserve an empty smallest key and falsely report otherwise-disjoint vSSTs as overlapping.
- 2026-09-02: Completed the materialization behavior already stated by the source prototype's “cap and deduplicate” comment: after inverse-rank rounding and `key_max` clamping, identical reconstructed integer keys are collapsed so Pebble can build a valid strictly ordered SST. No replacement keys are invented; the lost cardinality remains visible in accuracy metrics. Added a regression test for this boundary case.
- 2026-09-02: Strengthened final-state validation by closing and reopening the materialized Pebble DB before measuring levels, iterating keys, and checking values. This verifies that predicted level placement is persisted in the MANIFEST rather than existing only in memory.
- 2026-09-02: Replaced the remaining shorthand “PLR” in Go comments with the paper's own terminology, “piecewise-linear learned-index model.” Functionality is unchanged.
- 2026-09-02: Review correction: replaced per-pick reconstruction of the entire fake Pebble `Version` with incremental in-memory `BulkVersionEdit` application and persistent `L0Organizer` state. This uses Pebble's existing version-update machinery and removes harness work that is not part of virtual compaction.
- 2026-09-02: Review correction: made exact-key truth propagation conditional. Performance runs no longer allocate, merge, sort, or retain true key arrays; those operations are confined to a separate accuracy-trace run and never influence descriptor output.
- 2026-09-02: Review correction: replaced rank-by-rank calls to Pebble's `OutputSplitter` with descriptor-level event evaluation. The implementation still applies Pebble's target-size, 2x hard-size, grandparent-start, no-split-user-key, and max-grandparent-overlap rules, but obtains boundary ranks from the learned index as required by the paper. The old rank scanner remains as an opt-in `VCOMP_VALIDATE_SPLITTER` oracle for small equivalence runs.
- 2026-09-02: Review correction: split execution into two complete deterministic passes. The reported VComp loading time now comes from a descriptor-only pass with no truth map; KMV job errors come from a second accuracy-only pass and its entire wall time is reported separately.
- 2026-09-02: Review correction: replaced sequential external-SST creation plus one-file-at-a-time ingest with the paper's materialization structure. Final SST objects are now built directly through Pebble's object provider with up to 48 workers, synchronized once, and installed at their predicted levels through one `VersionEdit`. This removes staging copies and intermediate ingest MANIFEST edits; it is the paper-specified parallel materialization and batched final state update, not a new optimization.
- 2026-09-02: Fixed descriptor-level grandparent boundary placement after a 512 MiB invariant failure. `Predict(boundary)` is now used only as the initial rank guess, then aligned to the first inverse-mapped key that reaches the boundary. This reproduces the event Pebble's key scanner observes and prevents adjacent output metadata from sharing a rounded boundary key.
- 2026-09-02: Fixed the event-based splitter after the 512 MiB reference oracle found an early split. The port now carries Pebble's exact dynamic grandparent threshold: 50% at the first observed boundary, increasing by 5 percentage points per crossed boundary up to 90%, with same-rank boundaries counted before one decision. The earlier constant-50% simplification was removed.
- 2026-09-02: Corrected timing and materialization validation. Reported materialization time now stops after object sync and the single MANIFEST install, excluding close/reopen iterator verification. Separately, the union of keys reconstructed directly from all final descriptors is compared byte-for-byte with the reopened DB iterator output; a mismatch fails the experiment.
- 2026-09-02: Fixed a Pebble correctness violation exposed by the descriptor-union check. Final SSTs now use each virtual table metadata record's existing highest sequence number instead of writing every internal key at sequence zero, and the direct-install path advances Pebble's visibility watermarks past those sequence numbers. This preserves the ordering information already carried through the virtual compactions; it is not a new estimator or compaction optimization. Without it, overlapping levels could contain identical internal keys and Pebble's user iterator exposed duplicates.
- 2026-09-02: Corrected the parallel materializer's worker-count expression after renaming its input from descriptors to final tables; behavior is unchanged.
- 2026-09-02: Corrected a benchmark-configuration asymmetry found during final review. Pebble's baseline compaction concurrency now has the paper's maximum of 48 background jobs, and final materialization reads the same configured maximum instead of embedding an independent constant. Pebble retains a normal lower concurrency of one and raises it through its existing debt/L0 heuristics.

## Scope and fidelity rules

This is a feasibility prototype against upstream Pebble commit `8ca7bf36e171f1f158a71f0f1ab5679daafdc988`. It intentionally implements only mechanisms stated in the F2Load paper:

- in-memory vSST metadata (key range, entry count, estimated size, and level);
- piecewise-linear learned-index fitting, model-level merging, splitting, and inverse materialization;
- one global KMV sketch and eight key-range-local KMV sketches per vSST;
- the original DB's compaction trigger/input selection and lower-level-overlap split behavior; and
- one-time materialization after the virtual LSM state reaches quiescence.

No new compaction policy, estimator, sketch correction, or performance optimization was added. Pebble-specific code is limited to encoding integer keys, adapting descriptors to `TableMetadata`, calling Pebble's existing score picker, applying the same output-split events as `internal/compact.OutputSplitter`, writing final SSTs, and installing their predicted levels through a manifest edit. The exact-key arrays retained by the experiment are accuracy tracing only and are never read by the virtual algorithm.

The large-scale experiment configuration uses 24-byte keys, 1000-byte values, WAL and compression disabled, 64 MiB memtables and target SSTs, 65,536 writes per explicit 64 MiB flush, up to 48 background compactions/materialization workers, and `SplitMix64(seed+i) % writes`. The explicit flush boundary is shared by baseline and VComp so the two paths see identical initial batches, but the baseline waits for compactions only after loading completes so foreground writes and normal background compactions overlap. With `keyspace = writes`, about 63.2% of generated keys are unique, matching the paper's random-load shape.

## Implementation map

- `pebble-vcomp/vcomp/model.go`: paper descriptor algorithms only.
- `pebble-vcomp/vcomp/model_test.go`: learned-index, KMV, slicing, and materialization unit tests.
- `pebble-vcomp/vcomp_experiment_test.go`: Pebble integration, natural-load baseline, virtual picker loop, one-time materialization, durable level installation, and measurements.

The experiment is opt-in so it does not run during ordinary Pebble tests:

```sh
VCOMP_EXPERIMENT=1 VCOMP_WRITES=1048576 go test . \
  -run '^TestVCompExperiment$' -count=1 -v -timeout=30m
```

## Verification performed

- `go test ./vcomp -count=1`: passed.
- Paper-scale 64 MiB configuration smoke run at 512 MiB, including baseline, VComp, close/reopen validation, and descriptor-union validation: passed in 4.30 s. Baseline loading took 2.92 s and the measured VComp simulation-plus-materialization path took 0.243 s.
- Corrected paper-scale 64 MiB configuration run at 100 GiB: passed in 933.6 s, including baseline validation, independent accuracy tracing, parallel materialization, close/reopen validation, descriptor-union equality, and value validation.
- Root Pebble test package compilation with the experiment skipped: passed.
- 64 MiB smoke experiment, including close/reopen validation: passed.
- 512 MiB experiment, including close/reopen validation: passed.
- 1 GiB experiment, including close/reopen validation: passed.
- Descriptor-level split positions versus Pebble's record-by-record `OutputSplitter` oracle at 64 MiB and 512 MiB: exact match.
- Reopened DB iterator versus the independently reconstructed union of all final vSST descriptors at every measured scale: exact match.
- All baseline and VComp iterator value checks: zero errors.

## Results

### 100 GiB paper-scale configuration

The corrected 100 GiB run used 24-byte keys, 1000-byte values, 64 MiB memtables and target SSTs, 65,536 writes per explicit flush, normal overlapping foreground/background baseline execution with only a final compaction drain, no WAL or compression, and up to 48 background/materialization workers.

| Metric | Pebble baseline | Pebble VComp |
|---|---:|---:|
| Measured loading time | 565.751 s | 35.495 s |
| Descriptor simulation | — | 23.455 s |
| Final materialization | — | 12.040 s |
| Total SST writes | 1.511 TB | 72.689 GB |
| Final SST bytes | 73.062 GB | 72.689 GB |
| SST rewrite factor | 20.679x | 1.000x |
| Logical keys | 66,284,772 | 65,903,886 |
| Incorrect values | 0 | 0 |

The measured VComp path was 15.94x faster and reduced SST writes by 95.19%. Final SST bytes differed from baseline by -0.51%, and logical key count differed by -0.57%. The complete test took 933.6 s because it additionally performed baseline/VComp iterator validation and a separate 138.9 s exact-key accuracy trace; those costs are excluded from both loading-time columns. VComp executed 7,442 virtual compactions and 223 virtual moves. Its final level counts were L0/L4/L5/L6 = 1/13/127/1,094, compared with baseline L0/L3/L4/L5/L6 = 6/1/16/129/1,095.

KMV mean, median, p95, and maximum absolute cardinality errors over virtual compaction jobs were 4.29%, 3.63%, 10.67%, and 47.18%. The exact reconstructed-key Jaccard similarity was 0.4605, consistent with the previously documented limitation of inverse materialization from an approximate learned index.

### Earlier scale-down configuration

These are single reviewed scale-down runs on the local host, not a reproduction of the paper's multi-TB hardware evaluation. They supersede the earlier harness timings: the earlier implementation rebuilt full Pebble versions per pick, enumerated every predicted rank through the splitter, performed exact-key tracing in the measured path, and used a sequential ingest path. Those were prototype artifacts rather than F2Load work. “SST rewrite factor” is `(flush output + compaction output) / final live SST bytes` for baseline and `materialization output / final live SST bytes` for VComp. Manifest traffic is excluded from both.

| Requested input | Baseline SST writes | Baseline final SST | Baseline rewrite factor | VComp materialization | VComp rewrite factor | SST-write reduction | Baseline time | VComp time | Speedup |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 64 MiB | 269.4 MB | 45.3 MB | 5.94x | 43.1 MB | 1.00x | 84.0% | 0.348 s | 0.032 s | 10.71x |
| 512 MiB | 5.225 GB | 381.3 MB | 13.70x | 378.6 MB | 1.00x | 92.8% | 6.451 s | 0.292 s | 22.10x |
| 1 GiB | 12.970 GB | 727.4 MB | 17.83x | 727.7 MB | 1.00x | 94.4% | 15.356 s | 0.588 s | 26.13x |

VComp time is descriptor simulation plus the paper-specified parallel, one-time materialization and one batched manifest install. It excludes the wholly separate exact-key accuracy pass and post-timing close/reopen iterator validation. Baseline time likewise stops before its iterator validation. At 1 GiB, descriptor simulation took 0.519 s and materialization took 0.068 s.

At 1 GiB, baseline ended with 2/20/177 files in L0/L5/L6; VComp ended with 0/19/177. Final SST bytes differed by +0.039%, and logical key counts differed by +0.337%. At 512 MiB, final bytes differed by -0.688% and logical key counts by -0.496%. At 64 MiB the final-byte difference was larger (-4.88%) because this very small run has only 11 final VComp tables and is sensitive to the two baseline L0 tables.

KMV cardinality accuracy over virtual compaction jobs was:

| Requested input | Jobs | Mean absolute error | Median | p95 | Maximum |
|---:|---:|---:|---:|---:|---:|
| 64 MiB | 8 | 1.03% | 0.78% | 2.80% | 2.80% |
| 512 MiB | 154 | 3.36% | 1.66% | 10.95% | 18.30% |
| 1 GiB | 391 | 3.72% | 2.81% | 10.80% | 16.01% |

The exact-key-set Jaccard similarity was only 0.467, 0.462, and 0.466 respectively. This does not contradict the close cardinality and level-size results: the learned index approximates ranks and its inverse often reconstructs a nearby integer rather than the original integer. The paper evaluates output sizes and subsequent workload/state behavior, not exact equality with the originally generated key set. Any application that requires byte-for-byte reproduction of the original generated keys would therefore need a stronger guarantee than this design provides.

## Conclusions and limits

The core claim is implementable on Pebble: its normal compaction picker can operate on synthetic `TableMetadata`, its output-splitting rules can be evaluated from descriptor boundary events, and final SSTs can be materialized once and durably installed at the predicted levels. At 1 GiB the reviewed prototype reduced measured SST writes by 94.4%, ran 26.13x faster than natural loading, and reproduced final size and lower-level file counts closely. The corrected result does not support a Pebble-specific inability to remove loading delay.

The bad earlier latency result came from the harness, not Pebble: it rebuilt a complete version for every pick, ran exact-key truth propagation inside the timed pass, invoked the learned-index inverse once per predicted record through `OutputSplitter`, and staged then sequentially ingested every output SST. The reviewed path instead applies virtual version edits incrementally, evaluates only the splitter events named by Pebble's existing policy, isolates accuracy tracing in a second run, and follows the paper's parallel write-once materialization plus batched state update. An opt-in oracle compared the event-based split positions with Pebble's record-by-record `OutputSplitter` and passed.

One real Pebble integration difference is internal sequence-number validity. Directly installed SSTs in overlapping levels cannot all contain sequence-zero internal keys: identical user key plus identical sequence number produces identical internal keys, and Pebble may expose duplicates. The fix does not change VComp's estimate; before materialization it stable-sorts final tables by their virtual metadata sequence number, assigns every table a distinct synthetic sequence number while preserving relative recency, and advances Pebble's visibility watermark past that range. The reopened DB's iterator is then exactly equal to the independently materialized descriptor union at all tested scales, with zero value errors. This synthetic experiment uses a deterministic value derived from the key. A general loader whose value changes on repeated writes would need to retain enough version information to reconstruct the winning value; neither this descriptor nor the paper's descriptor specifies that information.

This remains an opt-in feasibility harness rather than a production Pebble loading mode. The remaining fidelity gaps are visible rather than corrected: VComp left no L0 tables where baseline left two, KMV tail error was materially higher than the paper's aggregate, and exact reconstructed key identity was poor even though cardinality and final bytes were close. The low key-set Jaccard follows from inverse materialization of an approximate learned index; no unreported correction keys were invented. Larger repeated runs and post-load YCSB behavior would be required before claiming paper-level final-state fidelity on Pebble.

## YCSB-C integration for the 1 TiB run

The end-to-end harness now reopens both the naturally loaded baseline DB and the VComp-materialized DB and runs a post-load YCSB-C-equivalent workload against each. The workload is 100% point reads, uses 48 workers and a scrambled Zipfian distribution with theta 0.99, and defaults to five minutes per DB. It reports operation count, throughput, p50/p95/p99 latency, exact key hits and misses, the DB's structural read amplification, and block-cache hits, misses, and hit rate. Baseline and VComp workers use identical deterministic PRNG seeds, so corresponding workers consume the same query-stream prefixes; a time-based benchmark may execute different prefix lengths because throughput differs.

Pebble's stock `pebble bench ycsb` command was not used directly because it assumes CockroachDB MVCC-formatted keys and the Cockroach comparer, whereas this experiment creates fixed 24-byte numeric keys with Pebble's default byte comparer. Its read path also accepts the first key at or after the requested key, which would hide missing reconstructed keys. The integrated runner retains YCSB-C semantics but encodes queries in the experiment's key format and requires exact key equality, making hit-rate divergence visible.

The paper provisions block cache at 5% of requested dataset size. The harness follows that rule up to a 32 GiB cap; the cap is required on the current 62 GiB machine so the 1 TiB experiment retains memory headroom for Pebble, Go, and the OS. `VCOMP_YCSB_DURATION`, `VCOMP_YCSB_CONCURRENCY`, and `VCOMP_YCSB_CACHE_BYTES` can override the defaults without changing the loading configuration. Before the workload starts, large exact-validation key arrays are released and garbage collection is requested. The 64 MiB smoke validation used a 64 MiB cache and 0.5 seconds per DB; both baseline and VComp YCSB phases completed and emitted all configured metrics.

For the follow-up 1 TiB single-thread YCSB-C run, `VCOMP_YCSB_CACHE_BYTES=0` is supported as a true Pebble block-cache disable setting. A plain `Options.CacheSize=0` would silently select Pebble's 8 MiB default, so the runner instead constructs and supplies an explicit `NewCache(0)`. This disables only Pebble's block cache; the Linux filesystem page cache is not dropped. The loading configuration remains unchanged, while `VCOMP_YCSB_CONCURRENCY=1` changes only the post-load YCSB worker count.

The experiment no longer uses `testing.T.TempDir`, because that deleted the roughly 747 GB baseline and 743 GB VComp databases at the end of each successful 1 TiB run and forced a complete reload for every YCSB variant. The default is now a persistent directory created with `os.MkdirTemp`; `VCOMP_DB_ROOT` may select an explicit persistent location. The chosen path is printed before loading begins and included in the final JSON as `db_root`. An explicitly configured directory must be empty, otherwise the harness fails rather than overwriting or mixing with retained data. No automatic cleanup is registered, on success or failure, so later read workloads may reopen `baseline/` and `virtual/` directly.

`TestVCompYCSBExisting` is the post-load-only entry point for retained databases. It requires `VCOMP_EXISTING_DB_ROOT` and `VCOMP_WRITES`, reopens that root's `baseline/` and `virtual/` directories, and runs only the two YCSB-C phases. Cache size, concurrency, duration, and value size retain the same environment overrides as the full experiment. This turns subsequent YCSB configuration changes into minutes-long runs rather than repeating the multi-hour 1 TiB load.

## Cassandra iteration graph export (2026-09-13)

Added `resources/plot_cassandra_iteration_versions.py` and exported nine independent Cassandra implementation-version comparisons to `resources/experiments/20260913-124257_cassandra_iteration_versions/`. The original per-version SVG/PNG files are now archived under that bundle’s `logs/`; its current entry points are `results.md` and `figures/`. Every original graph shows load time, device writes, write amplification, final physical size, final SST count, and exact-version workload throughput deltas when such workload results exist. The accompanying `logs/metrics.csv` and `logs/metrics.json` retain the raw source paths and derived deltas.

Workload measurements were not copied between versions: versions without an exact matching workload run are explicitly marked as having no workload result. No 1 TiB Cassandra graph was produced because no completed 1 TiB Cassandra result exists. The seeded 1 GiB and 100 GiB figures also disclose that the baseline daemon loaded the pre-seed JAR, even though the requested seed was recorded, so those runs are not presented as a fully seeded baseline/VComp pair.

## Cassandra 100 GiB single-partition rerun (2026-09-13)

Added `cassandra_check/run_single_partition_100g_campaign.sh` to execute the user-requested giant-partition control as one guarded sequence: rebuild the Cassandra runtime JAR, verify that its `Controller` bytecode contains the seeded UCS picker hook, run a fresh 100 GiB native baseline with one partition, run the matching one-partition VComp load, and then run YCSB A-F plus MixGraph for five minutes per system/workload. Both loads use 24 B keys, 1,000 B values, UCS T4, 48 compactors, and picker seed 20260909. The workload runner is given the exact newly produced baseline and VComp DB paths rather than historical defaults.

The campaign was launched at 2026-09-13 13:00 KST in tmux session `cassandra_100g_single_20260913_125952`, with raw root `/work/vcomp-pebble-1tb/cassandra-100g-single-partition-20260913-125952`. At launch `/work` had approximately 6.3 TiB available and no other Cassandra benchmark was active. The rebuilt runtime JAR has SHA-256 `eb808c227f5a5dfc4b6e1aef3c36af6827b1a64c51104e2b2b35f085a71890a7`; `javap` confirmed that the daemon-loaded JAR contains `cassandra.ucs.picker_seed`, fixing the provenance defect in the preceding seeded runs. The baseline configuration records `partition_keys=1`, and at the 2026-09-13 13:02 KST status check it was actively loading, at 4,194,304 of 104,857,600 writes (64 explicit flushes). The campaign remains in progress; no result values should be reported until its `SUCCESS` marker and all fourteen workload JSON files exist.

## Repository publication preparation (2026-09-13)

Prepared the top-level `vcomp` tree for publication as one Git repository. The
repository-local ignore rules now exclude the downloaded Go toolchain, Maven
cache, plotting virtual environment, local agent metadata, nested-Git backup,
and raw Cassandra pipeline runs. These are local or reproducible artifacts and
are not required to build the source from a fresh clone. The separately checked
out `/home/dongju/go` directory remains outside this repository; it is a Go
module cache rather than required Pebble source.

The embedded `pebble-vcomp` checkout was flattened so its modified Go source is
tracked as normal files instead of an unresolved gitlink. Its upstream URL and
base commit are recorded in `pebble-vcomp/UPSTREAM_PROVENANCE.md`, while its
original nested Git metadata is retained only as an ignored local backup. This
repository preparation does not alter the active Cassandra campaign or its
database under `/work`.

## Paper porting-evaluation draft (2026-09-13)

Added `resources/paper_porting_evaluation_draft.md` as a manuscript insertion
draft. It places the Pebble/Cassandra portability study after Evaluation
Section 5.4 (Final State Fidelity) and before Memory Overhead, includes a short
implementation bridge and conclusion sentence, uses the completed Pebble 1 TiB
measurements, and leaves explicit tokens and graph placeholders for the ongoing
Cassandra single-partition campaign. The draft distinguishes paired
within-engine comparisons from cross-engine comparisons and preserves the
Cassandra partition-atomicity and approximate-key-membership limitations.
Added `resources/paper_porting_evaluation_draft_kor.md` as a separate Korean
version with the same section structure, measured Pebble values, Cassandra
tokens, graph placeholders, limitations, and post-campaign replacement
checklist. The English draft remains unchanged.
Revised both language versions to avoid implying that the Cassandra port solves
general partitioned ordering. The introduction and summary now state directly
that the implementation fixes one partition key and reduces the dataset to one
clustering-key order; the result therefore does not establish general
multi-partition Cassandra support.

## Cassandra 100 GiB single-partition campaign rejected (2026-09-13)

Stopped tmux campaign `cassandra_100g_single_20260913_125952` during the VComp
side of workload E after the already completed results established a fidelity
failure. Native baseline and VComp ended with 66,278,498 versus 68,890,595 live
rows (+3.94%), 83.940 versus 87.597 GB of physical data (+4.36%), and 6 versus
3 SSTables. The partial A-D workload throughput differences were +1.31%,
+29.28%, +55.13%, and +30.85%; those numbers are not accepted as performance
results because the final key populations/layouts differ and the time-based
runs execute different-length operation prefixes.

Archived the small configuration, binary provenance, logs, fingerprints,
metrics, SST TOC files, and nine completed workload JSON files under
`resources/experiments/20260913-124257_cassandra_iteration_versions/logs/partial-attempts/20260913-125952_cassandra_100g_single_partition_failed_fidelity/`.
The corresponding `/work` database/checkpoint tree was then removed at the
user's request. The next iteration starts again at 1 GiB and treats deterministic
equal-trace workload replay plus final-state/physical-shape fidelity as gates
before another 100 GiB campaign.

## Cassandra fixed-operation workload mode (2026-09-13)

Added an optional `operations-per-thread` argument to
`CassandraPaperWorkload` and the matching `OPERATIONS_PER_THREAD` setting to
`run_existing_100g_paper_workloads.sh`. A positive value makes every worker in
both systems consume the same finite PRNG prefix; zero preserves the existing
time-based mode. This removes the previous confound where the faster system
performed more operations and mutations during a nominally equal 300-second
interval. The fixed-operation mode is a measurement-control change only; it
does not alter the F2Load descriptor, picker, merge, split, or materialization
algorithms.

Hardened `run_baseline_vcomp_20g_compare.sh` to build the Cassandra runtime JAR
once before either side starts, record its SHA-256, disassemble the packaged
UCS `Controller`, and refuse the campaign unless the packaged daemon code
contains the seeded-picker hook. This closes the provenance hole in the prior
"seeded" comparison where the baseline daemon silently loaded an older JAR.

Generalized `resources/plot_cassandra_paper_workloads.py` so graph titles and
summaries are derived from the campaign configuration instead of claiming that
every input is a 100 GiB, five-minute run. New exports use the neutral
`cassandra_workloads.svg` name and disclose whether the campaign is time- or
fixed-operation based.

## Cassandra 1 GiB ordered-partition fixed-trace qualification (2026-09-13)

Completed a fresh paired 1 GiB load after rebuilding and auditing the actual
Cassandra daemon JAR. Both paths used 100 Murmur3-token-ordered partitions,
disjoint scalar clustering-key ranges, UCS T4, and picker seed 20260909. Native
baseline versus VComp results were 48.747 versus 2.530 seconds, 2.541 versus
0.676 GiB of device writes, 0.641 versus 0.672 GiB final physical size, 662,885
versus 690,994 live rows, and exactly 8 versus 8 final SSTables.

Ran A-F and MixGraph against independent hard-link checkpoints with 48 workers
and exactly 20,000 operations per worker on both systems. Throughput deltas
were A +1.48%, B +3.86%, C +1.95%, D +0.61%, E -0.00%, F -0.54%, and MixGraph
+2.61%. Archived graphs, configurations, load metrics, runtime-JAR hash, all 14
workload JSON files, and interpretation limits under
`resources/experiments/20260913-160100_cassandra_1g_fixed_operations_q1/`.
The raw DB/checkpoint root remains under `/work/vcomp-pebble-1tb/` for follow-up
validation and is not part of the Git-facing artifact.

## Cassandra 100 GiB ordered-partition scale gate launched (2026-09-13)

Launched a fresh 100 GiB paired load in tmux session
`cassandra_100g_fixed_q1_20260913_160245`, with raw root
`/work/vcomp-pebble-1tb/cassandra-vcomp-100g-fixedtrace-q1-20260913-160245`.
It uses the qualified 10,000-partition token-ordered layout, 24-byte keys,
1,000-byte values, UCS T4, 64 MiB flush/target size, common seed 20260909, and
the pre-run packaged-JAR audit. No other Cassandra benchmark was active and
`/work` had approximately 6.3 TiB available at launch. The planned gate is load
row/size/SST-shape fidelity followed by fixed-operation A-F/MixGraph; no
100 GiB result should be reported until the paired load completes.

## Cassandra 100 GiB ordered-partition fixed-trace result (2026-09-13)

Completed the fresh 10,000-partition paired load with an audited runtime JAR.
Native baseline versus VComp measured 5,558.277 versus 116.419 seconds, 422.153
versus 71.066 GiB of device writes, 71.906 versus 70.979 GiB final physical
size, 66,278,498 versus 66,549,593 live rows, and 184 versus 165 final SSTables.
Thus final bytes differed by -1.29% and visible rows by +0.409%, while SST count
remained 10.33% lower on VComp.

Completed fixed-operation A-F and MixGraph with 48 workers and exactly 20,000
operations per worker on each side. VComp throughput deltas were A +9.33%, B
+10.63%, C +12.40%, D +10.90%, E +3.70%, F +10.24%, and MixGraph -2.44%.
Archived both graphs, all 14 JSON records, load metrics, configuration, and
runtime provenance at
`resources/experiments/20260913-180400_cassandra_100g_fixed_operations_q1/`.
The result improves substantially on the rejected single-partition +55.13%
read-only delta, but the read-heavy deltas and 184/165 SST mismatch require
per-token/shard layout analysis before a 1 TiB campaign.

Regenerated the workload figure as `paper_workloads.svg` using the same 2×2
paper layout as the Pebble result: throughput, layered point-lookup
p50/p95/p99 latency, disk reads, and disk writes. Corrected the I/O panel
captions from the inapplicable five-minute label to the actual 960,000 fixed
operations per system. `cassandra_workloads.svg` remains as an identical
compatibility alias for older links.

## Cassandra 1 TiB ordered-partition campaign launched (2026-09-13)

At the user's request, removed the database payloads from the completed 100
GiB ordered-partition campaign while retaining its logs, configuration, and
the published `resources` bundle. This reduced the raw campaign root from 145
GiB to 202 MiB and restored `/work` free space from 6.1 TiB to 6.3 TiB.

Added `cassandra_check/run_ordered_partition_1tib_campaign.sh` and launched it
in tmux session `cassandra_1tib_fixed_q1_20260913_194834`. The raw root is
`/work/vcomp-pebble-1tb/cassandra-vcomp-1tib-fixedtrace-q1-20260913-194834`.
The user stopped this run during baseline loading. Its incomplete small-result
folder was removed during the September 14 reorganization; the raw path is
retained as the historical source reference.
The campaign uses 1,024 GiB, 1,073,741,824 logical key slots, 102,400
Murmur3-token-ordered partitions, 24-byte keys, 1,000-byte values, UCS T4, and
picker seed 20260909. It runs the native baseline and VComp serially, then A-F
and MixGraph with exactly 20,000 operations on each of 48 clients per system.
On successful completion it automatically archives the metrics and all 14
JSON records and renders loading plus paper-style workload graphs. The runtime
JAR audit passed, the native Cassandra node reached `Startup complete`, and
the baseline entered its explicit 64 MiB flush/write loop; no result is claimed
while `campaign.env` says `running`.

## Cassandra 1 TiB campaign stopped and architecture audit (2026-09-13)

Stopped the 1 TiB campaign at the user's request during the native baseline
load. At exit status 130, the loader had completed 564,723,712 of 1,073,741,824
writes (52.59%) and 8,617 explicit flushes. Confirmed that the wrapper, loader,
Cassandra daemon, and tmux session had all exited. The approximately 634 GiB
partial raw tree and its logs remain under `/work`; neither the VComp load nor
the workload phase started, and no result or graph is reported.

The audit found that the current Cassandra port does not yet implement the
intended narrow substitution boundary of retaining Cassandra's native UCS
selection, task lifecycle, scheduling, and sharded writer while replacing only
the key/value compaction I/O with vSST descriptor operations. `VCompPipeline`
explicitly runs as a standalone offline synthetic-load path. Although native
UCS and `VCompUcsPlanner` call the same extracted `UnifiedCompactionPicker`
kernel, VComp supplies a separate candidate adapter and policy, advances a
synthetic shared-work clock with a fixed concurrency limit, and independently
reconstructs native shard splitting before materializing each descriptor with
`CQLSSTableWriter`. The latter creates simple SSTable writers with expected key
count zero, so even final Bloom/filter metadata is not produced through the
same path as a native compaction.

This architecture explains why equal picker code and close final byte/row
counts did not preserve the 100 GiB physical layout: native baseline selected
329 compactions and ended with 184 SSTables, whereas VComp simulated 307
compactions and ended with 165 SSTables. VComp then issued roughly 16--20% less
disk read traffic in the read-heavy fixed-operation workloads, yielding the
9--12% throughput separation. These figures are evidence of a layout confound,
not a validated F2Load read-performance benefit. The Cassandra port should be
restructured around native task scheduling and native output-boundary logic
before another large campaign; learned-model/KMV descriptor merging and the
ordered-partition representation remain legitimate paper mechanisms, but a
VComp-only picker scheduler or corrective layout heuristic should not be used
to tune the comparison.
