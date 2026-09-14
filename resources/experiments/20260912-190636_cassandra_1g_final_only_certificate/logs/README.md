# Cassandra 1 GiB final-only certificate pilot

Completed on 2026-09-12 after aligning Cassandra's model lifecycle with the
current Pebble paper path.

## Change under test

- Flush descriptors retain the continuous GreedyFit/PLR model.
- Intermediate virtual compactions merge continuous models and use
  common-theta KMV cardinality estimates.
- The integer discrete-CDF certificate is attached only after the final
  virtual layout is frozen, immediately before materialization.

The previous Cassandra path attached a discrete certificate at every flush and
fed the discrete projection back into all later virtual merges. That was not
the current paper path and could recursively distort the modeled key
distribution and later range estimates.

## Result

| System/path | Visible rows | Physical SST bytes | Final SSTs | Fingerprint |
|---|---:|---:|---:|---|
| Baseline | 662,885 | 688,132,263 DB bytes | 1 | `ce182ddbacf5...` |
| Old recursive-discrete VComp | 705,870 | 737,007,837 | 1 | `e060dd05cb88...` |
| New final-only VComp | 705,870 | 737,007,839 | 1 | `867d876a3805...` |

The run passed full key ordering, deterministic-value, and fingerprint
verification. The new VComp count is still +42,985 rows (+6.485%) versus the
baseline. This is expected for this fixed seed: the same standalone
512-sample common-theta KMV estimate is 705,870. Moving the certificate changed
the reconstructed key distribution (therefore the fingerprint), but cannot
change the global KMV cardinality estimate.

This one-SST pilot cannot measure the 100 GiB problem where four independently
materialized overlapping runs produced too many physical versions. A fresh
multi-SST run is required before claiming a disk-read improvement.

Raw preserved run:
`/work/vcomp-pebble-1tb/cassandra-vcomp-1g-final-only-compare/vcomp/cassandra-vcomp-1g-final-only-common-theta-logical-vcomp-20260912-190636`

