# Resumed workload runner audit

Runner: `experiments/scripts/cassandra/run_resumed_fidelity_100g_campaign.sh`.

The original 100 GiB load pair completed successfully under `/work/vcomp-pebble-1tb/cassandra-comprehensive-100g-20260914-141045`; its workload phase failed during A. This runner reuses those canonical loads and restarts all fourteen workload cells in fresh paths. It performs no build or load. The original failed campaign and its measurements are preserved.

## Protocol and provenance

- Fixed seed 20260909, 48 clients, 300-second time mode, all A–F/MixGraph baseline/VComp pairs. No failed workload cell or checkpoint is reused.
- The existing bounded workload harness supplies the same cache, memtable, compaction, and server-error checks. A complete run requires its success, the unchanged 47-comparison publication checks, and canonical byte verification. Fidelity failure is reported without discarding measured results.
- Before workloads, validate both canonical SUCCESS markers, original final-state paths/inventories, 100 GiB input configuration, target SST size, partition count, seeds, disabled compression/durable writes, and symmetric loading-metric boundary.
- Preserve original load configuration, metrics, source provenance, final-state inventory path/SHA256, and recorded load-runtime SHA256. Loading metrics are reused, not remeasured.
- **The original load JAR file was rebuilt before it could be archived. Its original binary file is not retained; its recorded hash and source provenance remain available.** The runner records this explicitly and does not claim that the repaired reader produced these loads.
- The repaired reader JAR must differ from the known failed runtime hash. Retain an exact copy in the new raw root's `runtime/`, verify its SHA256 against the qualified build JAR, and retain `runtime-archive.sha256`. Keep the original build-path hash checks to detect mutation of the binary actually launched.
- Source freezing includes the resumed runner, current harness, wrapper, memory monitor, analysis/plot scripts, Cassandra configuration/build inputs, and cache regression test sources. Untracked test source archival includes `cassandra_vcomp/test/unit/org/apache/cassandra/io/util/ChunkCacheFileHandleTest.java`.
- Compute complete SHA256 manifests of both canonical application-table SST components before and after workloads, outside timed intervals. Compare bytes and filenames with the original live inventory. On workload failure, attempt the post-run byte check before archiving failure evidence.
- New raw/result paths are mandatory; existing paths are refused. The runner does not modify old result bundles or canonical SST components.

## Checks performed without launching a benchmark

- Shell syntax and every embedded Python block compile.
- Actual canonical load metadata passes the input/metric provenance validator, and both current live-file name/size inventories match the original `final_state.json`.
- A temporary small-file fixture passes before/after SHA256 comparison and rejects a same-length byte mutation.
- The complete numeric fidelity/publication calculation block matches the original campaign's 47-comparison implementation exactly; only reuse/provenance explanatory text differs.
- Runtime archival checks compare both hashes before measurement and recheck the retained binary after the workload suite.

Temporary validation fixtures were stored under `/tmp/cassandra-resume-runner-test-*` and `/tmp/cassandra-resume-byteproof-test-*`. No database contents were hashed during these validation checks, and this audit agent launched no build, database daemon, or benchmark. The coordinator must finish reader repair qualification before running the resumed campaign.
