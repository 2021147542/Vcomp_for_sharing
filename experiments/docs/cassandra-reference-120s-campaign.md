# Preserved-baseline 100 GiB campaign, 120 seconds per workload

The user authorized a corrected VComp implementation and one paired campaign on
2026-09-15. Qualify the final production changes with focused differential tests
and a small pilot before starting this runner. No 100 GiB baseline load is needed.

```bash
bash experiments/scripts/cassandra/run_reference_100g_120s_campaign.sh --plan
# After the corrected source and JAR pass qualification:
bash experiments/scripts/cassandra/run_reference_100g_120s_campaign.sh --tmux
```

`--execute` runs synchronously; `--tmux` starts the same complete pipeline in a
detached session and writes its session name, campaign log, and final exit status
under `experiments/artifacts/<timestamp>_cassandra_100g_reference_120s/`.
The host execution context must permit Cassandra networking, `/work` writes,
tmux and the user systemd bus. A sandbox-only plan is read-only and already passed.

The fixed plan is:

1. Validate the registered baseline's 1,632-component stat inventory and archived
   provenance. Guard every fresh output path. Acquire the shared storage lock
   and refuse any overlapping Cassandra runtime or JUnit fixture.
2. Freeze the qualified JAR, compiled main classes, source and runner hashes.
   Check every JAR class against its corresponding main class. Do not rebuild
   during the campaign. Preserve the source snapshot and dirty diff.
3. Run one VComp-only 100 GiB load: seed 20260909, 104,857,600 writes, 24 B keys,
   1,000 B values, 10,000 partitions, 64 MiB flush and target SST, UCS T4.
   The baseline load identity and its historical loading metrics are retained.
4. Run A, B, C, D, E, F, MIXGRAPH in that order, baseline then VComp for each:
   **14 cells × 120 seconds, 48 threads, time-based mode**. There is no seed
   search, no repetitions selected for closeness, and no reused old workload
   measurements. The measured intervals alone total 28 minutes; copying,
   startup, VComp loading and verification add time.
5. Start each cell from a fresh independent copy (`cp --reflink=auto`) of its
   immutable load state. Verify independent inodes and matching file sizes.
   Retain all 14 disposable checkpoints; about 1.3 TiB may be needed when
   reflinks are unavailable. The host had 4.9 TiB free at preparation.
6. Preserve the existing cache protocol: 5%-of-logical-data native chunk cache,
   20 GiB combined daemon/client cgroup, zero swap, scoped fadvise before startup.
   Sync the independent checkpoint copy before fadvise: dirty copy pages cannot
   reliably be discarded by DONTNEED until writeback has completed. This retains
   the intended cold-start protocol after replacing hardlinks with copies.
   This includes additional OS page cache and is not identical to the paper's
   cache implementation. Auto-compaction stays enabled symmetrically.
7. Reject failed clients or server ERROR logs. Revalidate the canonical baseline
   after each cell and at completion; recheck frozen source/runtime between
   loading and workloads and at completion. A changed canonical stat fails closed.
8. Archive all 14 JSON results and small logs; generate the standard bundle with
   only `figures/`, `logs/`, `results.md` at its top level. `figures/` contains
   exactly loading.svg, workload.svg, io_latency.svg, in the established style.

Time-based cells can finish different operation counts. Disk bytes compare the
two-minute intervals; they are not equal-operation I/O measurements. Device I/O
latency is the diskstats completion-time average, separately labelled from client
point-read/scan percentiles. Preserve every result even if similarity is poor.

## Input/schema compatibility review

Baseline reuse still requires exact original helper hashes for the native input
generator, key codec and partition layout. A materializer-only change may be
accepted **after semantic review and validation**, by passing
`BASELINE_COMPATIBILITY_REVIEW=/absolute/path/review.json`. The runner accepts
only this narrowly scoped, hash-pinned structure:

```json
{
  "reference_sha256": "SHA256 of registered reference.json",
  "sources": {
    "cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/VCompCqlSstableMaterializer.java": {
      "original_sha256": "original reference helper hash",
      "current_sha256": "qualified corrected helper hash",
      "input_schema_semantics_unchanged": true,
      "rationale": "Describe the reviewed changes and why input/schema semantics are identical",
      "validation": "Name concrete passed tests and the evidence location"
    }
  }
}
```

There is no generic skip flag. A changed generator/codec/layout, unreviewed
materializer, stale hash or different reference fails closed. The review is
copied into the raw load provenance. If no guarded helper changed, no exception
file is necessary. `EXPECTED_RUNTIME_JAR_SHA256` can point at the qualified
`sha256sum` output; the campaign checks it and records its own frozen copy.

Preparation validation: all five modified/new runner shells passed `bash -n`,
their embedded Python blocks passed AST parsing, the read-only plan passed the
canonical reference guard/validation, and all 5,858 current JAR classes matched
main classes at the time of preparation. These checks do not replace the root
agent's correction-specific qualification or imply that a benchmark has run.
