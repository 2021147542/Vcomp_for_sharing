# Cassandra 100 GiB audited comparison

Completed on 2026-09-12 after auditing the restricted Cassandra VComp path and
strengthening its correctness gates.

## Configuration

- 100 GiB logical input, 104,857,600 writes
- 24 B clustering key + 1,000 B value
- one Cassandra partition (`partition_id = 'all'`)
- 64 MiB explicit flush cadence
- UCS `T4`, 48 concurrent compactors
- compression and durable writes disabled
- VComp KMV estimator: common theta, 512 global samples
- VComp picker size metadata: logical estimate
- partition-atomic output (`TARGET_SST_BYTES=0`)

## Measured result

| System | End-to-end time (s) | Device write (GiB) | Write amp | Final DB (GiB) | SSTs | Largest SST (MiB) |
|---|---:|---:|---:|---:|---:|---:|
| Baseline | 6009.799 | 454.169 | 5.915x | 76.789 | 4 | 54551.786 |
| VComp | 112.630 | 84.596 | 1.000x | 84.586 | 4 | 51326.747 |

Both full-table scans verified every key ordering constraint and every
deterministic 1,000-byte value:

- baseline: 66,278,498 visible rows, SHA-256
  `c5d601912136141d214d609f47caa31e90ae0247f3658596b6dd9183bd2eab45`
- VComp: 67,768,639 visible rows, SHA-256
  `c6d9462020c9358481deac7cdb7e73baa7f3a0929a6a84a0e4c12e2dabede84c`
- cardinality delta: +1,490,141 rows (+2.2483%)

The fingerprints are intentionally not equal because VComp materializes an
approximate learned/KMV representation. The relevant accuracy result is the
visible-row delta after Cassandra reconciles the four overlapping final runs,
not the sum of physical rows written across those runs.

## Audit fixes included in this run

- Materialization verification now rejects a truncated result even when all
  expected SSTable component directories exist.
- The experiment fingerprint now validates every key and value, rather than
  hashing unchecked rows after checking only the first 1,000.
- The compaction drain timeout is configurable and defaults to six hours, which
  prevents a false 15-minute failure at this scale.

## Scale-specific baseline observation

Vanilla Cassandra applies compaction throttling after `ci.next()` returns. With
this restricted schema, `ci.next()` processes the entire giant partition, so a
74 GiB compaction first reports 100% byte progress and then spends a long time
inside the global 64 MiB/s rate limiter. This is not a VComp deadlock. The
baseline completed 249 compactions with zero aborts and compacted about
403.39 GB cumulatively.

Raw preserved data and logs:
`/work/vcomp-pebble-1tb/cassandra-vcomp-100g-audited-compare`

