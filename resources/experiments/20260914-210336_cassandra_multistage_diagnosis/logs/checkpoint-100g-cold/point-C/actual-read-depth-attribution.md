# Fixed-query candidate-depth attribution

Post-execution grouping of all pre-fixed queries/rounds; paired same keys on both sides, no query/seed selection. Range-depth grouping is observational, not an intervention that changes only overlap. Process disk bytes are phase-level and cannot be allocated to these groups.

| Group, all four rounds | Requests per side | Native SST/read | VComp SST/read | Native mean µs | VComp mean µs | Mean latency difference |
|---|---:|---:|---:|---:|---:|---:|
| vcomp_depth_4/all | 3068 | 3.5580 | 3.4589 | 576.501 | 619.840 | +7.52% |
| vcomp_depth_4/both_hit | 1096 | 3.2628 | 3.3212 | 620.661 | 660.752 | +6.46% |
| vcomp_depth_4/both_miss | 312 | 4.0000 | 4.0000 | 717.246 | 838.867 | +16.96% |
| vcomp_depth_4/native_only_hit | 584 | 3.0616 | 4.0000 | 582.284 | 742.500 | +27.52% |
| vcomp_depth_4/vcomp_only_hit | 1076 | 4.0000 | 3.1487 | 487.571 | 448.085 | -8.10% |
| vcomp_depth_5/all | 532 | 3.3835 | 4.6316 | 644.456 | 926.387 | +43.75% |
| vcomp_depth_5/both_hit | 260 | 2.9385 | 4.4769 | 554.865 | 904.003 | +62.92% |
| vcomp_depth_5/both_miss | 60 | 4.0000 | 5.0000 | 840.822 | 1005.294 | +19.56% |
| vcomp_depth_5/native_only_hit | 76 | 3.3158 | 5.0000 | 645.453 | 1023.407 | +58.56% |
| vcomp_depth_5/vcomp_only_hit | 136 | 4.0000 | 4.5588 | 728.545 | 880.152 | +20.81% |
| vcomp_depth_6/all | 400 | 3.6100 | 5.7100 | 676.106 | 1128.062 | +66.85% |
| vcomp_depth_6/both_hit | 176 | 3.4318 | 5.5455 | 723.448 | 1160.996 | +60.48% |
| vcomp_depth_6/both_miss | 44 | 4.0000 | 6.0000 | 617.980 | 1108.471 | +79.37% |
| vcomp_depth_6/native_only_hit | 100 | 3.4400 | 6.0000 | 578.864 | 1110.244 | +91.80% |
| vcomp_depth_6/vcomp_only_hit | 80 | 4.0000 | 5.5500 | 725.473 | 1088.656 | +50.06% |
