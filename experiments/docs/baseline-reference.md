# Cassandra baseline reference reuse

Prepare a VComp-only load against the preserved baseline with:

```bash
bash experiments/scripts/cassandra/run_vcomp_against_reference.sh --plan
```

After the differential investigation is ready for another large measurement,
the same command with `--execute` runs only the VComp load. It never calls the
baseline loader when `BASELINE_REFERENCE` is set. The optional reference branch
is also available in `cassandra_check/run_baseline_vcomp_20g_compare.sh` for a
subsequent paired workload campaign. Baseline loading metrics retain their
original timestamp and binary identity.

The reuse branch checks fixed seed, dataset, key/value widths, partition count,
flush size, target SST size, UCS policy and original input/schema helper hashes
before building or writing an output directory. It conservatively rejects a
changed helper source, including the materializer: a change outside input
generation may be compatible, but its semantics must be validated against the
archived helper before widening this guard. The reference is not silently
replaced to make a new candidate pass.

The retained 100 GiB baseline is the native UCS T4 load from 2026-09-14,
seed **20260909**, 104,857,600 writes, 24-byte keys and 1,000-byte values.
Its canonical directory is:

```
/work/vcomp-pebble-1tb/cassandra-comprehensive-100g-20260914-141045/baseline/cassandra-baseline-fidelity-100g-20260914-141045-baseline-20260914-141052
```

The registered [reference manifest](../../resources/experiments/20260914-152956_cassandra_100g_organized_resume/logs/baseline-reference/reference.json)
contains the complete load configuration, input generator and schema source
hashes, original load JAR hash, reader JAR provenance, logical fingerprint,
final state, and all **1,632 component SHA256 values (87,990,345,878 bytes)**.
Its sibling `evidence/` stores independent copies of small provenance files,
original Cassandra configuration, and the source defining keys, values,
partitions, timestamps, and schema. The original load JAR itself was not
retained; its recorded hash and source provenance are retained. The later
cache-fixed reader JAR is archived separately and is not called the load JAR.

Registration reused the matching complete SHA256 audits before and after the
completed 15:30 campaign. It checked the exact current component inventory,
size, and that mtime/ctime preceded that audit, then captured current stat
identity. It did **not** read another 88 GB of data or modify the canonical DB.
This is explicit prior content evidence plus metadata continuity, not a new
cryptographic verification. No filesystem immutable bit or access permissions
were changed; protection is enforced by cooperating experiment runners.

## What is reused

- When only VComp implementation changes, retain the canonical baseline load
  and regenerate VComp with the same input definition. Do not repeat the native
  baseline load or its compaction. Use a new campaign ID for the new VComp.
- Seed alone is insufficient: write count, generator/RNG calls, key codec,
  deterministic value generation, timestamp rule, partition assignment,
  schema, flush boundaries and native baseline configuration also belong to
  the reference. A changed baseline definition needs a separately named
  reference; never overwrite this one or silently describe it as a new load.
- Reusing baseline **DB bytes** does not imply reusing old latency/throughput
  measurements. Reader binaries, workload generators, cache/memory settings,
  scheduling and host conditions affect measurements. Measure both arms from
  fresh checkpoints under the same current settings for final comparisons.
  Old measurements may remain historical diagnostics, explicitly labelled.
- Start each workload on a fresh disposable checkpoint, never on the canonical
  directory or a checkpoint modified by an earlier workload. Prefer independent
  copies/reflinks. Existing hardlink checkpoints are restricted to immutable
  SST components: Cassandra metadata/system files must be separate, and audit
  complete canonical component SHA256 before/after the campaign.
- The fixed seed was retained from the previous campaign. Do not search for
  another seed or choose repetitions based on closeness to baseline.

## Guard and validation interface

Run from the repository root (commands are read-only):

```bash
reference=resources/experiments/20260914-152956_cassandra_100g_organized_resume/logs/baseline-reference/reference.json
python3 experiments/scripts/cassandra/baseline_reference.py validate --reference "$reference"
python3 experiments/scripts/cassandra/baseline_reference.py guard --reference "$reference" --path /work/vcomp-pebble-1tb/NEW-CAMPAIGN
```

`validate` checks archived provenance hashes, the current canonical small
configuration/metrics/verification files against their archived hashes, and
current component stat/inventory.
A changed stat fails closed and requires `validate --full`, which reads and
checks every component SHA256. Creating or removing hardlinks can change ctime
without changing content; this is also a reason to use the full audit, not to
ignore a failed check. Full validation does not refresh the registered stat
snapshot or overwrite the reference. Keep full audits outside timed workloads.

`guard` rejects a proposed writable/output/delete path that contains or lies
inside the canonical DB or its reference evidence, resolves symlink aliases,
and rejects existing hardlink aliases to canonical components. Call it before
creating output/checkpoint directories or passing paths to cleanup utilities.
It is deliberately stricter than `unlink`: a general mutation guard cannot
assume the future operation will only remove a hardlink. A dedicated cleanup
routine for known disposable hardlink checkpoints requires separate safeguards.
The guard is a runner preflight, not an OS security boundary or a race-proof
lock against concurrent processes.

Python integration exposes `load_reference(path)`, `validate(path, full=False)`
and `guard(path, targets)`. Manifest keys used by runners are `canonical_root`,
`keyspace`, `load_config`, `input_identity`, and `original_load_jar_sha256`.
Never infer canonical locations from a mutable `latest` symlink. Configuration
preflight should compare the actual requested config to the manifest before
building, deleting output, starting Cassandra, or running a loader.

For another already completed reference, `register --bundle BUNDLE --output
NEW-DIRECTORY/reference.json` requires matching before/after content audits,
successful full value verification, matching final-state bytes/configuration,
and original input/schema source hashes. Existing reference directories are
never overwritten.
