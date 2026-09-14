# Cassandra 100 GiB fidelity rerun

Measurement completed. Fidelity result: **30 primary comparisons outside ±10% or undefined**.

Raw data and retained databases: `/work/vcomp-pebble-1tb/cassandra-organized-100g-20260914-152956`.

Reused completed native UCS baseline and VComp loads; 24 B key + 1000 B value, 64 MiB flush/target SST, 10000 ordered partitions, calibrated VComp size model, seed 20260909. A-F and MixGraph each run for 300 seconds with 48 workers. Operation counts differ naturally in time mode. Baseline and VComp run serially; each workload uses an independent hard-linked checkpoint. Canonical loaded databases are preserved and their complete application SST component bytes are SHA256-verified before and after workloads. The failed original A run is retained separately; all fourteen cells restart. Load and workload-reader binaries have separate provenance in [load-reuse-provenance.json](load-reuse-provenance.json). Loading measurements were not repeated.

The fidelity target is similarity, with the same ±10% bound for faster and slower results. Load time and load write amplification are excluded. The 47 preregistered primary comparisons include throughput, read p50/p95/p99, and raw device read/write bytes for every workload, plus five final-state metrics. Per-operation I/O remains diagnostic. Workload I/O, operation latency, hit/miss counts, separate hit/miss latency, per-operation device I/O, and E scan row/byte counters are recorded in fidelity.csv/json. Time mode intentionally allows different operation counts; raw device bytes and bytes per operation must be interpreted together. Final-state sizes and SST counts come from live files after natural compaction drain on both arms.

| Workload | Throughput Δ | Read p50 Δ | Read p95 Δ | Read p99 Δ |
|---|---:|---:|---:|---:|
| A | +19.50% | -14.77% | -18.99% | -5.58% |
| B | +25.95% | -20.91% | -21.25% | -17.68% |
| C | +26.50% | -22.72% | -20.76% | -16.74% |
| D | +23.73% | -11.35% | -31.05% | -22.79% |
| E | +15.16% | -15.02% | -14.05% | -15.04% |
| F | +18.83% | -13.96% | -17.76% | -13.64% |
| MIXGRAPH | +6.32% | -4.28% | -5.93% | -3.40% |

All percentages are `(VComp / baseline − 1) × 100`. E reports scan latency; other rows report point lookup latency.

Limits: one repetition, baseline first in each pair, scoped page-cache advice rather than guaranteed cold caches, a native chunk cache sized to 5% of logical input plus additional OS page cache inside the bounded process group (not the paper cache configuration), shared-device I/O counters, model-generated approximate key membership, and the remaining difference between standalone descriptor scheduling and native task execution. A completed campaign does not imply algorithmic equivalence or statistically established fidelity.

Reused loading time/device writes include natural compaction drain on both arms under the original load binary. Final sizes/counts describe live files after drain. Materialization-only and post-load compaction phases are separately archived under raw/.

Files: [workload figures](paper_workloads.svg), [workload report](workload_report.md), [all comparisons](fidelity.csv), [machine-readable summary](fidelity.json), [final live-file inventory](final_state.json), [archive provenance](archive_inventory.json).
