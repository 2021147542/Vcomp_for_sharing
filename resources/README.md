# Resources and experiment outputs

`resources/` contains the paper, handoff notes, analysis helpers, and the
small result artifacts that must remain easy to inspect without copying the
multi-terabyte databases.

## Experiment result layout

Store each experiment's tables, graphs, summaries, and JSON results under:

```text
resources/experiments/<YYYYMMDD-HHMMSS>_<experiment-name>/
```

The timestamp is the experiment run's start/run ID time, not the time at which
the files were copied. Keep all artifacts from one run in that directory and
never overwrite a historical directory with a rerun. A rerun receives a new
timestamped directory.

Large databases, SSTables, traces, and high-volume raw logs remain under
`/work` because they can be terabytes in size. Each result directory should
record the corresponding `/work` run root so the small evidence retains its
provenance. Do not copy database contents into `resources/`.

The established `experiments/results/<run-id>/` bundles remain the location
for the RocksDB harness's formally published, revision- and hash-qualified
results. New user-facing result exports and porting-experiment artifacts go in
the timestamped `resources/experiments/` layout above; copy selected evidence
into a formal published bundle as needed.
