# Cassandra 1 TiB ordered-partition campaign (stopped)

- Status: stopped by user during the native baseline load
- Started: 2026-09-13 19:48:47 KST
- Stopped: 2026-09-13 22:04:36 KST (exit status 130)
- Raw source: `/work/vcomp-pebble-1tb/cassandra-vcomp-1tib-fixedtrace-q1-20260913-194834`
- Progress at stop: 564,723,712 / 1,073,741,824 writes (52.59%), 8,617 explicit flushes
- Partial raw size at inspection: 634 GiB

The wrapper, loader, Cassandra daemon, and tmux session were all confirmed absent
after the stop. The partial database and logs were retained; no VComp load or
workload phase ran, so this directory contains no performance result or graph.

The campaign was stopped before completion because the completed 100 GiB run
showed a structural mismatch (184 native SSTables versus 165 VComp SSTables) and
read-heavy workload deltas of roughly 9--12%. Code inspection subsequently found
that the current VComp path is a standalone offline pipeline with a synthetic
background-compaction clock and an independently reconstructed UCS output-shard
split. It shares the picker kernel with native UCS but does not execute through
the native Cassandra compaction strategy, lifecycle transaction, executor, and
writer path. The partial 1 TiB run must therefore not be used as an evaluation
result.
