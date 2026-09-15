# Commands and scope

Native storage (one case, one attempt; standard require_escalated approval):

```bash
bash experiments/scripts/cassandra/run_native_event_diagnostic.sh
```

Native success / postprocessing failure retained in `attempt-20260914-194012/`.
No native retry. Offline continuation (same evidence, fresh writer scratch; standard approval):

```bash
bash experiments/scripts/cassandra/resume_native_event_replay.sh /tmp/vcomp-cassandra-native-event-20260914-194012/output
```

Actual C request generator CPU-only compile/run commands: `read-generator/commands.sh`.
Analysis:

```bash
python3 experiments/analysis/analyze_cassandra_native_events.py /tmp/vcomp-cassandra-native-event-20260914-194012/output /tmp/vcomp-cassandra-event-replay-20260914-194611/output /tmp/vcomp-fixed-read-20260914/requests.json experiments/artifacts/20260914-192939_cassandra_tmux_analysis/analysis
MPLCONFIGDIR=/tmp/vcomp-event-mpl python3 experiments/analysis/publish_experiment_bundle.py resources/experiments/20260914-194012_cassandra_native_event_diagnosis
```

The publisher produced three N/A SVGs and empty performance tables from presentation.json. The final diagnostic narrative replaces generated results.md; diagnostic tables below use recorded JSON, not performance data.
Production main sources and compiled class hashes checked unchanged after both phases. The current standalone native diagnostic source includes a zero-initial-flush guard added after failure; it was compiled but not rerun. The exact measured source is preserved in the attempt directory.
