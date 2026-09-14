# Cassandra VComp porting smoke test

## Cassandra 5.0 source-pipeline smoke

The current port lives in `../cassandra_vcomp` and is exercised with:

```bash
KEY_BYTES=24 VALUE_BYTES=1000 ./run_pipeline_smoke.sh
KEY_BYTES=48 VALUE_BYTES=43 ./run_pipeline_smoke.sh
```

`cassandra_vcomp/build-env.sh` resolves Ant and the Maven cache from this
repository's `.tools/` and `.m2/` directories; it does not depend on
`vcomp_old`. The source pipeline uses Cassandra 5.0.9 and runs compaction
selection after flushes. The default picker metadata is a calibrated estimate
of encoded SSTable file bytes, matching the file-size metadata used by native
UCS. The logical-byte model remains an explicit diagnostic selected with
`VCOMP_SST_SIZE_MODEL=logical`.
Vanilla UCS and VComp now call the same metadata-only `UnifiedCompactionPicker`;
only after it returns the selected inputs do they branch into Cassandra's
physical compaction task or VComp's model/KMV merge. The restricted VComp path
uses the paper's common-theta KMV estimator and an exact discrete count/select certificate,
keeps each single-partition compaction output partition-atomic, materializes the
final SSTables, imports them, and verifies rows through CQL. When calibration
is explicitly enabled, its files are retained under `.vcomp-calibration` but
are not imported or included in the reported final SST metrics.

The older Cassandra 4.1.12 feasibility probe described below remains available
as `run_smoke.sh`; it is not the implementation-validation path.

This directory tests one narrow end-to-end porting path against a real,
single-node Apache Cassandra process:

1. Every row uses the fixed partition key `partition_id = 'all'`.
2. The clustering key `ck` is the one-dimensional VComp key.
3. `value` is the only regular column.
4. A probe writes two versions of one cell and checks that Cassandra returns
   the value with the newer timestamp.
5. Two virtual input descriptors are reconstructed from piecewise-linear
   models, merged by clustering key, and deduplicated by Cassandra timestamp.
6. The merged key space is split into virtual output descriptors.
7. Each output descriptor is materialized as a distinct physical SSTable with Cassandra's
   `CQLSSTableWriter`, imported with `nodetool import`, and queried through
   Cassandra's bundled Java native-protocol driver.
8. The verifier checks ascending clustering-key order and exact newest-value
   selection.

Run it with:

```bash
./run_smoke.sh
```

The script downloads the official Apache Cassandra 4.1.12 binary distribution
on first use and pins its SHA-256. It uses Java 11 and a 512 MiB heap. Each run
is preserved below `runs/`; `latest` points to the newest successful run.
The Java client is intentional: Cassandra 4.1's bundled cqlsh dependencies are
not compatible with Python 3.12 on this host.

## Scope and limitations

This is integration scaffolding, not yet a Cassandra `CompactionStrategy`.
Selection is shared with vanilla UCS, but VComp scheduling and model merge still
run in a standalone Java process; only the final SSTables are handed to
Cassandra.

The values in this smoke workload are deterministic functions of key and
timestamp. A production port must define how arbitrary value bytes remain
reachable while compaction is virtual. Restricting the schema to one regular
column removes Cassandra's per-column reconciliation problem, but it does not
by itself make arbitrary values reconstructable.

Most importantly, one fixed partition key creates one enormous Cassandra
partition. Cassandra's leveled compaction does not split a single partition
across multiple SSTables during compaction. The schema is therefore useful for
checking ordering and reconciliation at smoke-test scale, but it is not a
viable 1 TiB layout. A scalable port will need a partition-bucket/range design
or deeper changes to Cassandra's storage and compaction boundaries.
