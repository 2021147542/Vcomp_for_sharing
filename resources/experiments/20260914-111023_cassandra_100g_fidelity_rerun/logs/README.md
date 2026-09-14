# Cassandra 100 GiB fidelity rerun

Measurement completed. Fidelity result: **28 primary comparisons outside ±10% or undefined**.

Raw data and retained databases: `/work/vcomp-pebble-1tb/cassandra-fidelity-100g-20260914-111023`.

One fresh native UCS baseline and one VComp load; 24 B key + 1000 B value, 64 MiB flush/target SST, 10000 ordered partitions, calibrated VComp size model, seed 20260909. A-F and MixGraph each execute 960,000 operations (48 workers × 20,000), using split-streams-v2. Baseline and VComp run serially; each workload uses an independent hard-linked checkpoint. Canonical loaded databases are preserved.

The fidelity target is similarity, with the same ±10% bound for faster and slower results. Load time and load write amplification are excluded. Workload I/O, operation latency, hit/miss counts and separate hit/miss latency are recorded in fidelity.csv/json. Final-state sizes and SST counts come from live files after natural compaction drain on both arms.

| Workload | Throughput Δ | Point p50 Δ | Point p95 Δ | Point p99 Δ |
|---|---:|---:|---:|---:|
| A | -27.41% | +30.21% | +56.30% | +40.05% |
| B | -16.65% | +15.55% | +34.70% | +12.30% |
| C | -24.50% | +27.33% | +36.07% | +30.16% |
| D | -24.09% | +40.11% | +32.34% | +16.44% |
| E | -23.79% | n/a | n/a | n/a |
| F | -11.54% | +9.54% | +28.34% | +6.30% |
| MIXGRAPH | -10.59% | +12.24% | +9.42% | +10.24% |

All percentages are `(VComp / baseline − 1) × 100`. E uses scans, so point latency is not applicable.

Limits: one repetition, baseline first in each pair, scoped page-cache advice rather than guaranteed cold caches, shared-device I/O counters, model-generated approximate key membership, and the remaining difference between standalone descriptor scheduling and native task execution. A completed campaign does not imply algorithmic equivalence or statistically established fidelity.

The legacy loading figures retain their original metric boundaries: baseline includes compaction drain while VComp timing/device writes stop before post-import compaction; VComp plotted physical SST size/count describe materialization outputs. Use final_state.json and fidelity.csv for the symmetric final-state comparison. Post-load compaction metrics are archived under raw/.

Files: [workload figures](paper_workloads.svg), [workload report](workload_report.md), [all comparisons](fidelity.csv), [machine-readable summary](fidelity.json), [final live-file inventory](final_state.json), [archive provenance](archive_inventory.json).
