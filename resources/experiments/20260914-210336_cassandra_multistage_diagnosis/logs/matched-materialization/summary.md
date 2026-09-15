# Matched native/CQL/VComp materialization diagnosis

Fixed seed 20260909. 16,384 inserts, domain 8,192, 64 partitions, 24B key +1000B value, 7,075 final rows, four explicitly forced native output shards. Target64MiB and storage mode NONE/oa are identical in native and offline lanes. Exact key controls are diagnosis only.

| Lane | Data.db bytes | All component bytes | Bloom bytes |
|---|---:|---:|---:|
| native | 7331416 | 7471165 | 128 |
| same_rows_native_header | 7331416 | 7471069 | 144 |
| same_rows_exact_header | 7331412 | 7471063 | 144 |
| flat_timestamps | 7324353 | 7464004 | 144 |
| production_materializer_exact_key_control | 7324353 | 7464004 | 144 |

Native output and CQL with identical rows/versions/values/header have identical Data.db length per shard. Changing only header minimum changes 4 bytes total. Flattening descriptor timestamps changes −7,063 bytes (−0.09634%). Production materializer with exact key control reproduces flattened CQL row hashes and Data.db sizes.

Native writes tokenSpaceCoverage; CQL leaves NaN. UCS fallback computes exactly the same effective first/last partition coverage for all four shards, so missing stored coverage is not a density difference in this full-ring control. Native filter files total128B vs144B in CQL. Native ShardedCompactionWriter estimates per-shard partition cardinality from input overlap/survival, while CQL uses actual output partition count. This is metadata divergence without demonstrated workload impact.

The predetermined nb/CASSANDRA_4 run gives native7,332,120B vs oa7,331,416B (+704B, +0.0096%). Thus prior mixed-format 3% fixture difference cannot be attributed wholesale to nb/oa. Current baseline and VComp runners both force NONE; canonical registered baseline contains204 oa Data.db files.

All three completed physical invocations: JUnit OK(1 test). Initial failed output-directory setup is retained. No canonical baseline opened for writing, no seed selection, no production Java changes.

Sources: run_baseline_20g.sh:102; run_pipeline_100g.sh:134; DatabaseDescriptor.applyCompatibilityMode; BigFormat.BigVersion.current_version; ShardedCompactionWriter.sstableKeyCount/doPrepare; ShardManager.rangeSpanned.

A reusable static test-only materializeNativeControl helper was subsequently added for the parent dense native-vs-exact-CQL read control. Its dense execution results belong to that parent run, not these tiny completed fixtures.

## Dense follow-through in parent fixtures

The same native-control helper was exercised by parent runs 210336 and 210948. For 16 flushes: 8 SSTs, 662,885 physical rows, native and exact CQL both have 687,408,323 Data.db bytes; timestamp-only (native header retained) has 687,414,445 B (+6,122 B). For 19 flushes: 11 SSTs, 853,533 physical rows, exact CQL again matches every native Data.db length; timestamp-only adds 53,025 B. These timestamp-only controls retain header minima, unlike the tiny production-style timestamp plus header-reset lane. Their size signs are not equivalent interventions.

Read-only E continuations: retained-scan 210849 smoke, 210906 sixteen-flush warm, 211123 nineteen-flush warm, and 211146 nineteen-flush per-file Data.db cache eviction. All JUnit tests pass, and all source components retain their paths, sizes, and modification times. Source SST contents are not rewritten; incremental backups are disabled before reattachment. Failed setup 210758 (duplicate existing backup link) is retained and occurred before measurements. Each artifact contains copied helper/runner/runtime inputs and raw phase vectors.
