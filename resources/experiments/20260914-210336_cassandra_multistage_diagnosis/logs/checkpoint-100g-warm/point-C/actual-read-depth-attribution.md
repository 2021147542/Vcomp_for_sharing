# Fixed-query candidate-depth attribution

Post-execution grouping of all pre-fixed queries/rounds; paired same keys on both sides, no query/seed selection. Range-depth grouping is observational, not an intervention that changes only overlap. Process disk bytes are phase-level and cannot be allocated to these groups.

| Group, all four rounds | Requests per side | Native SST/read | VComp SST/read | Native mean µs | VComp mean µs | Mean latency difference |
|---|---:|---:|---:|---:|---:|---:|
| vcomp_depth_4/all | 3068 | 3.5580 | 3.4589 | 24.606 | 22.456 | -8.74% |
| vcomp_depth_4/both_hit | 1096 | 3.2628 | 3.3212 | 26.182 | 23.461 | -10.39% |
| vcomp_depth_4/both_miss | 312 | 4.0000 | 4.0000 | 27.698 | 24.556 | -11.34% |
| vcomp_depth_4/native_only_hit | 584 | 3.0616 | 4.0000 | 24.425 | 22.829 | -6.53% |
| vcomp_depth_4/vcomp_only_hit | 1076 | 4.0000 | 3.1487 | 22.201 | 20.621 | -7.12% |
| vcomp_depth_5/all | 532 | 3.3835 | 4.6316 | 26.292 | 26.035 | -0.98% |
| vcomp_depth_5/both_hit | 260 | 2.9385 | 4.4769 | 24.795 | 25.991 | +4.82% |
| vcomp_depth_5/both_miss | 60 | 4.0000 | 5.0000 | 28.127 | 24.792 | -11.86% |
| vcomp_depth_5/native_only_hit | 76 | 3.3158 | 5.0000 | 27.343 | 26.862 | -1.76% |
| vcomp_depth_5/vcomp_only_hit | 136 | 4.0000 | 4.5588 | 27.757 | 26.206 | -5.59% |
| vcomp_depth_6/all | 400 | 3.6100 | 5.7100 | 27.497 | 30.214 | +9.88% |
| vcomp_depth_6/both_hit | 176 | 3.4318 | 5.5455 | 27.938 | 31.567 | +12.99% |
| vcomp_depth_6/both_miss | 44 | 4.0000 | 6.0000 | 27.856 | 28.687 | +2.98% |
| vcomp_depth_6/native_only_hit | 100 | 3.4400 | 6.0000 | 27.468 | 27.619 | +0.55% |
| vcomp_depth_6/vcomp_only_hit | 80 | 4.0000 | 5.5500 | 26.362 | 31.324 | +18.82% |
