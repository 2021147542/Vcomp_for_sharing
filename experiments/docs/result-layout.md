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

Render or refresh a bundle with:

```
python3 experiments/analysis/publish_experiment_bundle.py resources/experiments/<run-id>
```

The Cassandra campaign runners accept `RESULT_ROOT` as the public timestamp
folder and store their internal outputs in `RESULT_ROOT/logs`. They publish
this layout before workload measurement and after completion or failure.

`io_latency.svg` identifies the recorded client DB lookup/scan latency. Existing
logs do not retain physical-device request durations/counts, so physical disk
I/O latency cannot be reconstructed. Server table histogram snapshots, where
present, remain separate raw evidence; they are not relabeled as device latency.

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
