# Pebble 1 TiB tree-shape regression diagnosis

- Analysis start: 2026-09-11 11:51 KST
- Repository: `/home/dongju/vcomp/pebble-vcomp`
- Input: 1,073,741,824 deterministic writes, 24 B key + 1,000 B value
- Full-result source: `/work/vcomp-pebble-1tb/pebble-paper-1tib-1kb-20260910-205045`
- Qualification method: descriptor-only `TestVCompSimulationOnly`; no new 1 TiB SSTs were written

## Finding

The September 10 VComp result was not a generic 1 TiB scaling effect. The
post-paper sampled-ratio KMV estimator and calibrated physical-SST metadata
changed Pebble's dynamic-level scores. Their accumulated size overestimate
left substantially more data in L4/L5, which correlates with the subsequent
5.5% to 18.2% workload-throughput deficit.

| Path | L2 | L3 | L4 | L5 | L6 | Metadata bytes | Simulation |
|---|---:|---:|---:|---:|---:|---:|---:|
| 2026-09-10 full baseline | 7 | 50 | 138 | 1,312 | 11,223 | 747.342 GB physical | 8,769.5 s full load |
| 2026-09-10 full VComp, recursive discrete/ratio/calibrated | 7 | 56 | 364 | 1,588 | 11,269 | 766.595 GB physical | 515.8 s full load |
| continuous/ratio/calibrated simulation | 8 | 55 | 361 | 1,593 | 11,246 | 773.358 GB predicted | 410.4 s |
| continuous/common-theta/calibrated simulation | 7 | 51 | 342 | 1,322 | 11,296 | 759.120 GB predicted | 422.2 s |
| **continuous/common-theta/logical simulation** | **0** | **13** | **123** | **1,306** | **11,159** | **737.897 GB logical** | **349.6 s** |

The selected default reduces the L4 table error from +226 to -15 and the L5
error from +276 to -6. Total final table count is 12,602 versus baseline
12,733 (-1.03%), rather than 13,285 (+4.34%) in the regressed full run.

The logical and calibrated byte columns are different metadata conventions and
must not be compared as physical-byte measurements. A fresh full run is needed
to measure the selected path's materialized bytes and workload performance.

## Correctness qualification

Plain continuous inverse materialization failed an 8 GiB control by emitting
65,280 keys for a descriptor that planned 65,286. The adopted hybrid therefore
freezes the paper-path tree first and then attaches a discrete count/select
certificate to each surviving descriptor only. A fresh 8 GiB full smoke passed:

- baseline logical keys: 5,304,052;
- VComp materialized keys: 5,324,517;
- descriptor union equals the reopened DB iterator;
- value errors: 0;
- baseline L4/L5/L6 tables: 1/12/90;
- VComp L4/L5/L6 tables: 1/11/89.

Increasing the KMV budget from 512 to 2,048 was rejected. At 1 TiB it worsened
L5 from 1,322 to 1,400 and increased simulation time from 422.2 s to 601.9 s.

## Code changes

- Paper-era continuous/common-theta merge is an explicit model API.
- Recursive discrete-CDF and calibrated-size paths are opt-in through
  `VCOMP_DISCRETE_CDF=1` and `VCOMP_SST_SIZE_MODEL=calibrated`.
- Final-only certification makes materialization count-safe.
- `TestVCompSimulationOnly` provides a no-SST scale qualification gate.
- Paper runners explicitly record the selected model path, and paper JSON now
  records `discrete_cdf` and `sst_size_model`.

The retained September 10 canonical databases were not modified.

## Fresh-run certification failure and recovery

The fresh 1 TiB run `pebble-paper-1tib-1kb-20260911-133950` completed and
validated its baseline, then failed before VComp materialization when one
continuous descriptor claimed 1,011 distinct entries in an integer key range
with capacity 312. The cause was a local KMV/rank-split cardinality estimate
that exceeded the output range's distinct-key capacity; it was not a baseline
or Pebble compaction-picker failure.

Final certification now projects only infeasible excess cardinality to the
nearest descriptor with spare capacity in the same level. This happens after
the compaction graph is frozen, so it does not change picker decisions, table
bounds, levels, or SST counts. A full 1 TiB descriptor-only qualification
passed with the same L3/L4/L5/L6 table counts (13/123/1,306/11,159) and moved
4,409 keys in total.

The runner supports guarded `RESUME_BASELINE=1`: it requires an existing
`baseline` and refuses to proceed if `virtual` already exists. The recovered
baseline's final tree remains reportable, but its in-memory load-duration and
historical SST-write counters were lost with the failed process. Generated
figures therefore mark those baseline load metrics unavailable instead of
plotting fabricated zeroes. VComp and workload measurements remain fresh.
