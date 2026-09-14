# Cassandra comprehensive audit and qualification — 2026-09-14

User-authorized scope: audit/fix the Cassandra port and experiment harness, qualify
them, then execute one fresh 100 GiB paired baseline/VComp campaign. Preserve prior
results and databases. Similarity is the objective; favorable performance and seed
selection are not objectives. Base revision: `c54ee7a6` (clean at start).

## Findings and corrections

- [Core audit](core-audit.md): remove complete-KMV key replay during final
  materialization; calibrate native `Data.db` bytes; remove the logical-byte floor
  from calibrated sizing; use native MiB rounding for the estimated flush size.
- [Runner audit](runner-audit.md): actual 300-second execution, symmetric
  load-through-drain metrics, final live-file counts, immutable checkpoint handling,
  runtime/source identity, occupied-port/refusal checks, native cache and read diagnostics.
- Workload v3: independent uniform E scan lengths 1..100; acknowledged inserts and
  growing latest distribution; local RocksDB MixGraph range/hash semantics and safe
  Pareto bounds; explicit scan/seek accounting. Independent C++ vectors are used for
  reference validation. See [workload-audit.md](workload-audit.md).
- The plotting helper now consumes symmetric final metrics and labels historical
  unequal boundaries when operating on older bundles. Historical figures are not regenerated.

## Validation progress

- OpenJDK 11 / local Ant: `ant jar checkstyle` passed; 2,550 source files checked.
- Core and common UCS picker: **53 tests passed**; Controller: **24 tests passed**.
- Standalone workload regression checks passed, followed by all 14 actual
  A–F/MixGraph cells in 5-second time mode. Both E cells exercised lengths 1..100
  and returned the requested row totals. No OOM events occurred.
- A disposable user-service preflight verified `memory.max=21474836480` and
  `memory.swap.max=0`. No sudo access or system-wide cache settings were needed.
- Fresh 1 GiB paired-load pilot completed at
  `/work/vcomp-pebble-1tb/cassandra-comprehensive-preflight-20260914-134500`.
  Its raw log is linked by `pilot-load.log`. Pilot values are qualification evidence,
  not selected final performance results. No pilot performance threshold chooses settings.
- The initial workload attempt stopped before measurement because the schema
  assertion incorrectly rejected Cassandra's omitted/default-enabled option.
  Its failure log is retained. The helper now follows the native default, has a
  regression check, and is supplemented by `statusautocompaction` runtime verification.
- The 48 B key / 43 B value daemon smoke passed with model inverse materialization.
- Additional control qualification uses a new paired load at
  `/work/vcomp-pebble-1tb/cassandra-controls-preflight-20260914-135900`.
  This is needed because the code/settings audit below found remaining default
  compaction-rate and workload memtable-threshold differences; no performance
  result or seed determined these corrections.

## Protocol fixed before the 100 GiB run

100 GiB logical input = 104,857,600 writes × (24 B key + 1,000 B value),
10,000 ordered partitions, 64 MiB flush/target, native UCS T4, calibrated sizing.
Seed **20260909** for both arms and the optional shared picker seed; no seed search.
Each A–F/MixGraph workload uses **48 threads for 300 seconds**, no fixed-operation
limit. Baseline and VComp run serially on separate checkpoints. One repetition.

For workload runs, Cassandra's native chunk cache capacity is **5 GiB**:
`file_cache_size=5152MiB` includes Cassandra's 32 MiB reserved buffer allowance.
Standard file access enables the chunk cache. A shared daemon/client cgroup caps
total memory at **20 GiB**, disables swap, logs `memory.stat`/`memory.events`, and
rejects OOM-affected execution. Startup file cache eviction is scoped advice.
This explicitly bounds native cache and total process-group memory, but **does not
reproduce RocksDB's direct-read cache behavior or a 5% total-data-cache limit**.
Additional OS page cache is measured and disclosed. New I/O code is not introduced
into Cassandra merely to emulate another engine's benchmark environment.

Both arms now disable the inherited 64 MiB/s compaction throttle (`0MiB/s`).
The local RocksDB reference uses the default rate-limiter setting of zero; the
paper text itself does not state that value. For workload runs only, the memtable
pool is explicitly 1 GiB on heap with cleanup threshold 0.0625 and two native
flush writers: a nominal 64 MiB **allocated-heap** trigger. This is not 64 MiB
logical KV data, a fixed-size SST, or exactly 16 literal buffers. Loading retains
the larger default native flush threshold so the explicit 64 MiB input batches
are not preempted by heap accounting. Actual flush counts/sizes and native
`BlockedOnAllocation` counters are collected. Cassandra warns that the cleanup
option is deprecated; it remains valid in this version. These settings are
derived from the reference configuration, not from pilot performance differences.

The predeclared similarity comparisons include throughput, point latency
p50/p95/p99 (scan latency for E), workload disk read/write, and five final-state
metrics (visible rows, SST count, Data bytes, component bytes, maximum SST size).
Each uses symmetric ±10%; a zero baseline has undefined relative error and is not
marked passing. Per-operation/scan I/O and native cache/SST-access diagnostics
supplement the raw 300-second totals. Loading time and load write amplification
are excluded from the similarity criterion.

## Limits that tests do not remove

The virtual path still schedules metadata work synchronously per flush outside
native task/lifecycle execution. Physical size and original key/timestamp membership
remain estimates, and CQL scans emulate a key-ordered iterator across partitions.
These are disclosed open fidelity limitations, not validated equivalence. Passing
qualification means the listed checks found no execution/data/measurement error;
it does not prove the absence of all bugs, structural equivalence, or ±10% results.

## Qualification complete; 100 GiB launched

All listed qualification checks passed. The final controls pilot completed A/E on
both arms with 48 workers for at least 30 seconds, exact configured cache capacity,
zero allocation blocking and zero OOM events. A produced 36/38 memtable switches.
The separate logging check confirmed that client progress and memory samples are
written on the system filesystem and archived byte-identically after timing, so
the monitoring writes do not inflate md0 workload-write counters. Native/shared
device background I/O remains in the original counters; it is not forced to zero.

The single requested 100 GiB run started at **2026-09-14 14:10:45 KST**, in tmux
`cassandra_comprehensive_100g_20260914_141045`.
Follow [its status and results](../../20260914-144957_cassandra_chunk_cache_repair/logs/attempts/20260914-141045_cassandra_100g_comprehensive/README.md).
The exact launch command is in [full-campaign-launch.json](full-campaign-launch.json).
At launch the measurements are pending; no similarity success is claimed.
