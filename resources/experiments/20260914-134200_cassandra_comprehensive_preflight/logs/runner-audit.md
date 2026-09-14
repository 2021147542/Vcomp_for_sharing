# Cassandra runner audit — before the new measurement

The new campaign is a 100 GiB Cassandra porting measurement with explicit differences from the paper. It is not an assertion that the paper's full hardware, engine, scale, or cache implementation has been reproduced.

## Corrections

- The campaign executes each A–F/MixGraph cell for 300 seconds with 48 workers, operations_per_thread=0, and the pre-existing seed 20260909. Time-mode operation counts are allowed to differ. Seed search and fidelity-based result selection are absent.
- The campaign requires reference-streams-v3 and verifies measurement mode, requested duration, thread count, seed, positive operation count, operation accounting, hit/miss accounting, and E scan-length bounds. Similarity is reported separately from execution validity. Unfavorable performance remains a completed measurement.
- E's summary uses scan latency. Derived bytes per operation and bytes per scan accompany raw shared-device byte counters, because time-mode runs complete different operation counts. Scan requested/returned rows, returned bytes, CQL request count, and empty scans are retained.
- Both loaders wait for zero pending tasks and no active compaction table for three consecutive observations. Both primary loading time/device-write measurements include natural compaction drain. VComp materialization-only and post-import drain evidence remain separately available.
- Both loading summaries count only live table-level files, excluding directory allocation and nested snapshots. The campaign independently inventories final live files after the daemon exits.
- Workload clients compile and run against the qualified daemon JAR. The campaign freezes source hashes and checks the runtime JAR before/after measurement. Newly introduced untracked helper source is archived explicitly. Memory-monitor Python and JSONL output are included in provenance/archival.
- Existing output paths and listeners are refused. Failure cleanup writes markers only to directories created by that invocation. Existing databases/results remain preserved.
- Application SSTables use independent hardlinked checkpoints; small system keyspaces are copied separately. Cache eviction advice errors now fail visibly instead of being silently ignored.

## Cache protocol and diagnostics

The workload configuration explicitly enables Cassandra's native chunk cache and sets disk_access_mode=standard. Cassandra reserves 32 MiB of file_cache_size for pooling, so the 100 GiB run uses 5152 MiB, giving 5120 MiB (5 GiB) chunk capacity. Pilot capacities round down to a whole MiB. The runner verifies that nodetool reports an active Chunk Cache before measurement.

The daemon and all workload clients run together in the parent's bounded systemd user service with memory.max=20 GiB and memory.swap.max=0. The runner verifies those actual kernel settings. The parent monitor records memory.stat and memory.events and rejects OOM events.

This is **not the paper's exact 5% total-cache configuration**: Cassandra standard I/O additionally uses the OS page cache, and the cgroup constrains total memory rather than only file cache. No custom direct-I/O modification was introduced. Scoped POSIX_FADV_DONTNEED establishes a best-effort common cache start, not guaranteed cold-cache equivalence.

Before/after diagnostics include nodetool table histograms, table statistics, compaction statistics, node/cache statistics, and daemon /proc/PID/io. These diagnostic snapshots have a coarser boundary than the workload client's device counters and must not be substituted for its exact measurement interval.

## Validation performed before pilot

- bash -n passed for run_baseline_20g.sh, run_pipeline_100g.sh, run_baseline_vcomp_20g_compare.sh, run_existing_100g_paper_workloads.sh, and run_fidelity_100g_campaign.sh.
- Embedded publication/archive/inventory Python scripts compile.
- Manual diff review caught and corrected an intermediate post-load counter variable clobber before any pilot or campaign execution.
- The load-start and post-load-start counters now have separate single assignments; final total writes subtract the original load-start counter.

No database benchmark was launched by this audit agent. Actual paired-load and bounded-workload pilot evidence must be supplied by the campaign coordinator before the authorized 100 GiB run. Remaining model/scheduler fidelity questions are covered by the core implementation audit, not declared resolved by these harness corrections.
