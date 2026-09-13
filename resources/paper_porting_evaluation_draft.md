# Pebble/Cassandra porting evaluation insertion draft

## Recommended placement

Insert the main text as a new **Section 5.5, “Portability Across LSM
Implementations,”** immediately after Section 5.4, “Final State Fidelity,” and
before the current Section 5.5, “Memory Overhead.” Renumber Memory Overhead to
Section 5.6. This placement first establishes the fidelity criteria on the
primary RocksDB implementation and then asks whether the same mechanism
survives changes in the storage engine. Memory overhead remains a separate,
implementation-independent cost discussion.

Add the short implementation paragraph below at the end of Section 4 so the
Evaluation section does not have to introduce all port-specific machinery.
Also add the one-sentence roadmap to the opening paragraph of Section 5.

The Pebble numbers below are from the completed 1 TiB paper-style run. The
Cassandra tokens in square brackets must be replaced only after the ongoing
single-partition campaign has completed and produced its final load metrics,
all fourteen workload JSON files, and a `SUCCESS` marker. Do not substitute
numbers from an older Cassandra implementation version.

## Addition at the end of Section 4 (Implementation)

**Ports to other LSM implementations.** We additionally port F2Load to Pebble
and Cassandra to separate the design from RocksDB-specific interfaces. The
descriptor operations---learned-index construction and merge, range-local KMV
union, virtual output splitting, and final materialization---remain unchanged
in purpose. Each port instead supplies an engine adapter at three boundaries:
(1) exposing an in-memory vSST through the metadata consumed by the native
compaction policy, (2) applying the engine's own input-selection and output-
boundary rules, and (3) installing the final materialized files in a durable
version or manifest update. In Pebble, vSSTs are represented as synthetic
`TableMetadata`; virtual compaction invokes Pebble's score picker and the
boundary events of its `OutputSplitter`, and final SSTs are installed with a
batched `VersionEdit`. In Cassandra, native SSTables and virtual sorted runs
provide lossless metadata adapters to a common picker derived from Unified
Compaction Strategy (UCS); only after a compaction has been selected do the
physical and virtual execution paths diverge.

The Cassandra port deliberately targets a restricted schema: one node, one
partition key, the record key as the clustering key, and one value column. This
preserves the one-dimensional total order assumed by F2Load, but creates a
giant Cassandra partition and preserves Cassandra's partition-atomic SSTable
output. We therefore use Cassandra as a constrained portability case study,
not as a claim that the current prototype supports arbitrary Cassandra schemas
or distributed operation.

## Addition to the opening of Section 5 (Evaluation)

Finally, we evaluate ports to Pebble and Cassandra to determine whether
F2Load's virtual-compaction abstraction survives changes in compaction
metadata, scheduling, output splitting, and physical table format.

## New Section 5.5: Portability Across LSM Implementations

### Portability Across LSM Implementations

The RocksDB evaluation establishes F2Load's performance and final-state
fidelity in its primary implementation. We next ask a different question:
does the design depend on RocksDB internals, or can the same descriptor-only
execution model follow the compaction decisions of other LSM implementations?
We answer this question with a port to Pebble, a Go LSM engine with a different
version-edit and output-splitting implementation. We also build a restricted
port to Cassandra, a distributed wide-column store. This port does not support
general multi-partition ordering; instead, it fixes one partition key and
reduces all records to a single clustering-key order.

We compare F2Load only with the native baseline of the same engine; absolute
throughput across engines is not compared. Within each pair, the baseline and
F2Load receive the same generated write stream, flush cadence, compaction
parameters, and workload seed. We report loading time, SST or device writes,
write amplification, final physical size and SST count, followed by throughput,
tail latency, disk reads, and disk writes under YCSB A--F and MixGraph. These
porting runs use [PORT_HOST_CPU], [PORT_HOST_RAM], and [PORT_HOST_STORAGE],
which differ from the reference server used for the RocksDB results. Pebble's
1 TiB workload uses 48 clients and a 32 GiB block cache, a host-memory cap below
the paper's nominal 5% rule. Cassandra uses a 4 GiB JVM heap and the operating
system page cache rather than a Pebble-style block cache. Consequently, the
figures support paired baseline-versus-F2Load conclusions within each port,
not cross-engine rankings.

**Pebble.** The Pebble experiment loads 1 TiB of 24 B keys and 1,000 B values
with WAL and compression disabled, 64 MiB memtables and target SSTs, and up to
48 background or materialization workers. Native loading takes 8,769.5 s,
whereas F2Load takes 515.8 s, a 17.0x speedup. SST writes fall from 22.073 TB
to 0.767 TB, reducing the measured SST rewrite amplification from 29.54x to
1.00x. The resulting physical state is close in aggregate size: F2Load produces
766.595 GB in 13,285 SSTs, compared with 747.342 GB in 12,733 SSTs for the
baseline. Thus, adapting Pebble's own picker, splitter, and version update is
sufficient to retain the central loading benefit without importing RocksDB's
compaction policy.

[그래프: Pebble 1 TiB baseline/F2Load loading time, SST writes, write
amplification, final DB size, and SST count]

Across YCSB A--F and MixGraph, Pebble F2Load throughput is 5.5%--18.2% lower
than the paired baseline, while the relative pattern across workloads remains
similar. Tail latency and workload I/O show corresponding, workload-dependent
gaps rather than a uniform failure mode. These measurements characterize the
state produced by the port; they are not a controlled layout-only comparison.
As in the RocksDB prototype, inverse materialization from an approximate
learned descriptor does not preserve the exact original key set, so final-size
similarity alone must not be interpreted as byte-for-byte logical equivalence.

[그래프: Pebble YCSB A--F and MixGraph throughput, p50/p95/p99 latency,
disk reads, and disk writes]

**Cassandra.** We evaluate the restricted Cassandra port on a 100 GiB input
with a single partition, a 24 B clustering key, a 1,000 B value, 64 MiB flush
cadence, UCS with scaling parameter T4, and 48 compactors. The baseline and
F2Load use the same seeded UCS selection inputs. The native path executes the
selected compactions on SSTables, whereas F2Load applies the same decisions to
virtual sorted runs and materializes only the final partition-atomic outputs.
Native loading completes in [CASS_BASELINE_LOAD_S] s and writes
[CASS_BASELINE_DEVICE_GIB] GiB, while F2Load completes in
[CASS_F2LOAD_LOAD_S] s and writes [CASS_F2LOAD_DEVICE_GIB] GiB. This corresponds
to a [CASS_SPEEDUP]x loading speedup and a [CASS_WRITE_REDUCTION]% reduction in
device writes. The final physical sizes differ by [CASS_FINAL_SIZE_DELTA]%, and
the final states contain [CASS_BASELINE_SSTS] and [CASS_F2LOAD_SSTS] SSTables,
respectively.

[그래프: Cassandra 100 GiB single-partition baseline/F2Load loading time,
device writes, write amplification, final DB size, SST count, and largest SST]

For the post-load workloads, F2Load throughput differs from the baseline by
[CASS_TPUT_MIN_DELTA]% to [CASS_TPUT_MAX_DELTA]% across YCSB A--F and
MixGraph; the corresponding p99-latency range is [CASS_P99_DELTA_RANGE], and
disk-read and disk-write differences are [CASS_IO_SUMMARY]. Full scans observe
[CASS_BASELINE_ROWS] baseline rows and [CASS_F2LOAD_ROWS] F2Load rows, a
[CASS_ROW_DELTA]% difference. We report this cardinality difference together
with workload performance because different visible key populations can alter
hit rate and invalidate a pure layout comparison.

[그래프: Cassandra YCSB A--F and MixGraph throughput, point/scan tail
latency, disk reads, disk writes, and read-miss rate]

The Cassandra result also exposes a portability boundary. A single global key
order maps cleanly onto one Cassandra partition, but compaction must then merge
and write a giant partition as one physical unit; it cannot freely emit the
nominal 64 MiB ranges used by a flat KV LSM. The resulting very large SSTables
and long rate-limited compactions are consequences of Cassandra's partition
semantics, not a new F2Load splitting policy. Supporting ordinary
multi-partition Cassandra schemas would require a descriptor and materializer
whose ordering and deduplication semantics are explicitly partition-aware.

**Summary.** The Pebble port and the restricted Cassandra port show that
F2Load's core mechanism is not tied only to RocksDB's concrete metadata
classes. In Pebble, the virtual merge, cardinality estimation, and one-time
materialization structure can be connected while retaining native compaction
decisions. In Cassandra, the same structure can be applied after fixing a
single partition key, but this does not demonstrate support for general
multi-partition Cassandra. Portability is therefore not automatic. Engine
invariants such as Pebble's internal sequence-number visibility and
Cassandra's partition-atomic output must be preserved by the adapter, and
approximate key reconstruction remains a fidelity limitation. We interpret
these experiments as evidence for portability under the stated restrictions,
rather than transparent support for every workload and data model exposed by
the target systems.

## Optional sentence for Section 6 (Conclusion)

Ports to Pebble and a restricted single-partition Cassandra configuration show
that descriptor-only compaction can reuse different engines' native selection
rules, while also exposing engine-specific obligations such as internal
sequence ordering and partition-atomic materialization.

## Replacement checklist after the Cassandra campaign

- Replace every `CASS_*` token from the new campaign's final metrics and
  workload JSON files.
- Replace `PORT_HOST_*` with the measured port-host specification.
- Generate figures only from the exact Pebble run and the new Cassandra run;
  do not combine older scheduler/materializer versions.
- State whether both full-table verifications passed, and retain visible-row or
  membership differences even if aggregate final sizes are close.
- Verify that all fourteen Cassandra workload JSON files and the campaign
  `SUCCESS` marker exist before removing provisional wording.
- Assign final figure numbers and repair the existing unresolved `Figure ??`
  and `Section ??` references during the LaTeX integration pass.
