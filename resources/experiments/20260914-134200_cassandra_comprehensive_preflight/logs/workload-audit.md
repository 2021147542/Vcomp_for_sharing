# Cassandra workload client audit and preflight validation

The audited client is [CassandraPaperWorkload.java](../../../../cassandra_check/src/CassandraPaperWorkload.java),
with regression checks in [CassandraPaperWorkloadTest.java](../../../../cassandra_check/test/CassandraPaperWorkloadTest.java).
The new generator version is `reference-streams-v3`. These changes correct workload semantics and
measurement provenance; they do not establish that the resulting VComp DB matches the baseline.
No seed search, benchmark-result selection, DB experiment, or daemon build was performed by this
client audit. The standalone tests use driver proxies without connecting to Cassandra. Actual paired
load/workload pilots are separate evidence in this bundle.

## Findings and corrections

| Finding | Correction and evidence |
|---|---|
| E reused the operation-choice remainder for scan length. Conditioning on the 95% scan branch restricted lengths to 1–95. | A fresh uniform draw supplies lengths 1–100. The regression samples 100,000 choices and verifies every length, including 96–100, and a mean close to 50.5. The range comes from the local RocksDB E command record linked below, not an explicit range in the paper's prose. |
| Operation-choice bits also selected payload offsets and the byte modified by F. Payload preparation consumed the request RNG stream. | Each worker splits a separate payload RNG. Worker streams remain deterministic splits of the previously selected seed; no favorable seed was chosen. E uses an additional independent length draw. |
| D used the latest allocated insert key, which could still be in flight, and kept the initial Zipf normalizer as the insertion range grew. | A completed, contiguous insert frontier determines the latest visible key. Per-worker Zipf state incrementally extends its maximum and normalizer. Tests cover out-of-order completion and compare an extended distribution with one rebuilt at the new size. |
| MixGraph used continuous normalized weights, a different shuffle and fixed shuffle seed, and SplitMix64 as the key-offset hash. | Restore the local RocksDB discrete integer weights, total-weight-seeded range swaps, and `std::mt19937_64` key mapping. Independent C++ outputs are preserved below and used as golden vectors in the Java tests. |
| MixGraph used a full-width random number for operation/key selection and Pareto probability, unlike the reference's uniform key-space draw. | Derive the key, query type, and Pareto probability from one uniform `[0, keyspace)` draw, as in local RocksDB `GetRandomKey`/`MixGraph`. Their shared-draw dependence is intentional in the reference and is preserved. |
| MixGraph narrowed a long Pareto value to int before applying the size cap. Large values could wrap negative and become 10-byte writes. | Apply the reference minimum/cap/modulo in 64 bits before narrowing. Tests include a value above `Integer.MAX_VALUE` and the reference's permitted zero-byte modulo result. |
| A zero-length MixGraph scan skipped all DB access, whereas RocksDB still creates an iterator and seeks. | Execute the CQL seek emulation with `LIMIT 1`, account the returned row as `scan_seek_only_rows`, and exclude it from logical scan-returned rows/bytes. |
| JSON could contain a nominal duration without stating that a fixed operation limit actually controlled execution. Scan work could not be normalized by returned rows. | Record actual mode, requested duration, per-thread operation limit, keyspace size, and scan requested/returned rows, returned bytes, CQL statement count, length min/max, empty scans, and seek-only rows. |

References:

- [Local RocksDB E command record](../../../../experiments/results/baseline_repeat_ycsb_all_260909_n01_all/evidence/full/workloade/baseline_repeat_01/command.sh):
  `ycsb_minscanlength=1`, `ycsb_maxscanlength=100`, `ycsb_scanlengthdistribution=uniform`.
  This archived command is not asserted to be the exact Figure 11 executable invocation.
- [Local RocksDB MixGraph implementation](../../../../tools/db_bench_tool.cc):
  `GetRandomKey`, `GenerateTwoTermExpKeys`, `QueryDecider`, `ParetoCdfInversion`, `MixGraph`.
- [Local RocksDB Random64](../../../../util/random.h): `std::mt19937_64`.
- [Local Zipf/latest counterpart](../../../../pebble-vcomp/internal/randvar/skewed_latest.go) and
  [Zipf implementation](../../../../pebble-vcomp/internal/randvar/zipf.go).
  Pebble source and experiments were not modified.

## Standalone validation

Run from the repository root. The Cassandra main classes and dependency JARs must already exist;
these commands do not rebuild or start a daemon. `md0` is read only through `/proc/diskstats`.

```bash
source cassandra_vcomp/build-env.sh
mkdir -p /tmp/cassandra-workload-v3-tests
"$JAVA_HOME/bin/javac" \
  -cp 'cassandra_vcomp/build/classes/main:cassandra_vcomp/lib/*' \
  -d /tmp/cassandra-workload-v3-tests \
  cassandra_check/src/CassandraPaperWorkload.java \
  cassandra_check/test/CassandraPaperWorkloadTest.java
"$JAVA_HOME/bin/java" @cassandra_vcomp/conf/jvm11-clients.options \
  -Dlogback.configurationFile=cassandra_check/logback-smoke.xml \
  -cp '/tmp/cassandra-workload-v3-tests:cassandra_vcomp/build/classes/main:cassandra_vcomp/lib/*' \
  CassandraPaperWorkloadTest md0 \
  > /tmp/cassandra-workload-v3-tests/result.log 2>&1
git diff --check -- \
  cassandra_check/src/CassandraPaperWorkload.java \
  cassandra_check/test/CassandraPaperWorkloadTest.java
```

Both compile/test and whitespace validation passed. The unedited test output is
[workload-tests.log](workload-tests.log). It includes the existing Java 11/Jamm reflective-access
warning; that warning did not fail the tests.

The test suite checks deterministic non-overlapping worker prefixes; E bounds/distribution;
C++ MT/key vectors; completed insert acknowledgment; growing/single-key Zipf distributions;
scan continuation across a partition boundary with decreasing remaining row limit and rebased
lower bound; returned row/byte and seek-only accounting; fixed operation counts; separate point
hit/miss metrics; a real one-second time-mode run against proxies; and cleanup after disk-counter
or query failures. The one-second test verifies that time mode does not stop early. It is not a
DB throughput measurement.

## Independent C++ oracle reproduction

The oracle contains the small reference mapping calculation and uses the standard library RNG,
not the Java RNG implementation. It prints MT draws, shuffled integer weights, and key mappings
for fixed inputs. Its source and output are preserved as
[mixgraph_oracle.cc](mixgraph_oracle.cc) and [mixgraph_oracle.txt](mixgraph_oracle.txt).

```bash
g++ -std=c++17 -O2 \
  -o /tmp/mixgraph_oracle \
  resources/experiments/20260914-134200_cassandra_comprehensive_preflight/mixgraph_oracle.cc
/tmp/mixgraph_oracle
```

The Java tests use the first four MT draws for five seeds and nine 100-GiB-keyspace mappings as
fixed expected vectors. The oracle also prints mappings for a 1,000-key space. These vectors
validate the range/key transformation, not byte-identical complete C++ and Java request traces:
the worker request generator remains Java `SplittableRandom`.

## Result field interpretation and remaining limits

- `measurement_mode` is `time` when `operations_per_thread` is zero; positive limits select
  `fixed-operations` and take precedence over the recorded `requested_duration_seconds`.
  `wall_seconds` includes completion of in-flight operations after the time deadline.
- `scan_requested_rows` is the sum of logical row limits. `scan_returned_rows` and
  `scan_returned_bytes` count the logical scan rows and their clustering-key/value payload.
  They omit partition-key encoding, protocol overhead, storage read amplification, and the
  separately reported seek-only row.
- `scan_cql_requests` counts `session.execute` calls, including partition continuation. It does
  not count driver-internal page fetches. E has at most 100 rows and one page per statement;
  larger MixGraph scans can paginate.
- `empty_scans` counts operations with zero logical returned rows. Therefore a length-zero
  MixGraph operation is an empty logical scan even when its seek finds a row.
- `disk_read_bytes`/`disk_write_bytes` remain device-wide `/proc/diskstats` deltas (512-byte
  sectors), not logical bytes or guaranteed Cassandra-only I/O. Host isolation and cache
  configuration are runner responsibilities.
- CQL has no equivalent of an in-process RocksDB iterator or cursor-only seek. Partition
  continuation and the `LIMIT 1` seek emulation are explicit engine-adapter differences.
  Read-modify-write is a read followed by a write, without a transaction.
- D's exact keys depend on completion scheduling even with the same seed. With timed workloads,
  the faster system executes more operations, so paired operation totals are not expected to
  match. Deterministic seeds do not make two different-duration traces identical.
- The client preserves the inherited scrambled Zipf/FNV distribution used by the local port.
  A complete independent equivalence proof against the archived RocksDB YCSB executable is not
  available from this audit; its source for the YCSB extension is not present in the current
  `tools/db_bench_tool.cc`. Do not describe these tests as proof of exact Figure 11 reproduction.
- Historical generator versions remain different workloads. Do not attribute performance changes
  between those campaigns and v3 solely to changes in the VComp implementation.
