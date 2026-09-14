# Result bundle layout

Each timestamp folder under `resources/experiments/` contains only:

```
figures/
  loading.svg
  workload.svg
  io_latency.svg
logs/
results.md
```

`results.md` and the three figures use the same values in
`logs/presentation.json`. Original JSON, text logs, configuration, historical
figures, and provenance stay under `logs/`. Missing results are `N/A`; an audit
without a primary benchmark gets explicitly unavailable figures. Existing
completed but unfavorable measurements remain visible.

Loading and workload figures retain the original 2×2 paper layout, with gray
Baseline and blue VComp bars. Loading shows minutes, device writes in GiB,
recorded write amplification, and final DB size in GiB, with values above bars.
Workloads show throughput in M ops/s, point-lookup p50/p95/p99 in µs, disk reads
in GB, and disk writes in MB. Percentile bands end at the recorded percentiles;
they do not add percentile values together. E scan latency remains in the
separate latency figure. Tables use the same display units as the figures;
normalized source measurements keep their original units.

To update only those two figures and their tables while preserving the latency
figure and normalized measurements byte for byte, run:

```
python3 experiments/analysis/restore_paper_figure_style.py resources/experiments/<run-id>
```

Render or refresh a bundle with:

```
python3 experiments/analysis/publish_experiment_bundle.py resources/experiments/<run-id>
```

The Cassandra campaign runners accept `RESULT_ROOT` as the public timestamp
folder and store their internal outputs in `RESULT_ROOT/logs`. They publish
this layout before workload measurement and after completion or failure.

`io_latency.svg` identifies the recorded client DB lookup/scan latency. Historical
logs do not retain device request durations/counts, so device I/O latency cannot
be reconstructed for those runs. The resumed 2026-09-14 campaign also records
diskstats request counts and accumulated milliseconds, allowing separate device
average read/write latency panels. These are device-layer averages including
other processes, not physical NVMe latency percentiles. Server table histogram
snapshots, where present, remain separate raw evidence.

The 2026-09-14 migration manifest records each original path, new path and
original SHA256. Original documents whose relative links changed are retained
under `logs/original-documents/`. The user-requested deletion of the interrupted
20260913-194834 presentation folder leaves its external raw DB/logs untouched.
The failed 141045 and user-interrupted 150117 attempts moved inside the 144957
repair bundle's `logs/attempts/` to preserve load provenance and failure evidence.

The incomplete 20260913-125952 fidelity-stopped attempt is retained under the
iteration collection's `logs/partial-attempts/`, including all completed
unfavorable workload cells. Completed campaigns that failed similarity remain
ordinary timestamp result bundles.
