# Pebble VComp paper-style experiment

- Dataset: 1 TiB (1.000 TiB)
- KV: 24 B key + 1000 B value
- Workload: 48 clients, 5 minutes each, 32 GiB block cache (host-memory cap)
- WAL/compression: disabled

## Loading and final state

| System | Load time (s) | SST write (TB) | Write amp | Final DB (GB) | SST count | Avg SST (MB) |
|---|---:|---:|---:|---:|---:|---:|
| Baseline | 8769.5 | 22.073 | 29.54× | 747.342 | 12733 | 58.693 |
| VComp | 515.8 | 0.767 | 1.00× | 766.595 | 13285 | 57.704 |

## YCSB A–F and MixGraph

| Workload | System | Throughput (ops/s) | p50 (µs) | p95 (µs) | p99 (µs) | Disk read (GB) | Disk write (MB) |
|---|---|---:|---:|---:|---:|---:|---:|
| A | Baseline | 165759 | 324 | 1977 | 3950 | 265.114 | 184428.950 |
| A | VComp | 148184 | 348 | 2315 | 4522 | 296.808 | 211022.557 |
| B | Baseline | 123758 | 218 | 1496 | 3147 | 266.811 | 12749.173 |
| B | VComp | 106360 | 265 | 1772 | 3583 | 288.495 | 18457.850 |
| C | Baseline | 132860 | 184 | 1476 | 3191 | 291.783 | 4141.928 |
| C | VComp | 108718 | 218 | 1800 | 3655 | 305.468 | 9276.350 |
| D | Baseline | 299164 | 4 | 387 | 998 | 294.839 | 7306.170 |
| D | VComp | 282744 | 4 | 445 | 1113 | 326.761 | 9751.474 |
| E | Baseline | 39588 | 0 | 0 | 0 | 864.617 | 13439.418 |
| E | VComp | 35825 | 0 | 0 | 0 | 846.146 | 24573.153 |
| F | Baseline | 100336 | 266 | 1673 | 3428 | 268.727 | 103770.501 |
| F | VComp | 87608 | 302 | 1981 | 3935 | 297.690 | 122741.142 |
| MixGraph | Baseline | 64059 | 72 | 1171 | 3869 | 446.034 | 11412.464 |
| MixGraph | VComp | 57344 | 93 | 1296 | 5194 | 423.898 | 11035.468 |
