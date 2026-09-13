# Cassandra 100 GiB single-partition fidelity failure

This bundle preserves the small evidence from the aborted campaign whose raw
run directory was
`/work/vcomp-pebble-1tb/cassandra-100g-single-partition-20260913-125952`.
The campaign was stopped on 2026-09-13 after the completed load results and the
first four paired workloads showed that baseline and VComp did not represent a
sufficiently similar final state. The multi-terabyte-capable database area was
then deleted at the user's request; `raw/` contains only configuration,
provenance, logs, metrics, fingerprints, SST TOC files, and completed workload
JSON files.

## Configuration

- Requested input: 100 GiB (104,857,600 writes of 1,000-byte values)
- Key shape: one exact partition key and a 24-byte clustering key
- Compaction: Unified Compaction Strategy, T4, 48 compactors
- Picker seed: 20260909; the loaded runtime JAR was verified by SHA-256 and
  `javap` before the run
- VComp: token-ordered partition-boundary output policy, logical SST-size
  model, common-theta KMV union estimator, 0.9 compaction work per flush

## Completed load results

| Metric | Native baseline | VComp | Difference |
|---|---:|---:|---:|
| Load time | 5,922.127 s | 122.152 s | -97.94% (48.48x) |
| Device writes | 482.947 GB | 87.606 GB | -81.86% |
| Final physical SST/DB bytes | 83.940 GB | 87.597 GB | +4.36% |
| Final SST count | 6 | 3 | -50.00% |
| Largest SST | 55.793 GB | not summarized | - |
| Fingerprinted live rows | 66,278,498 | 68,890,595 | +3.94% |

The load speed and write reduction are not accepted as a successful comparison:
the row count differs by 2,612,097 and the physical layouts differ by a factor
of two in SST count. The VComp fingerprint also differs, as expected for
approximate inverse materialization, but the row-count gap alone is sufficient
to reject this state for performance comparison.

## Partial workload results

Only A-D completed for both systems. E completed for baseline and was in
progress for VComp when the campaign was interrupted; F and MixGraph were not
run. Relative VComp throughput was +1.31% (A), +29.28% (B), +55.13% (C), and
+30.85% (D). In workload C, the exact-read miss rate was 42.58% for baseline
and 32.22% for VComp. These are diagnostics, not publishable performance
results: the time-based runs consumed different-length operation prefixes and
the unequal key populations and SST layouts confound the measurements.

## Status and interpretation

This run is an **incomplete fidelity failure**, not a successful 100 GiB
result. Its value is to show that fixing Cassandra to one giant partition does
not by itself yield a controlled baseline/VComp comparison. The next campaign
must first qualify equal deterministic workload traces, closer logical row
counts, and closer physical output shape at 1 GiB before scaling again.

