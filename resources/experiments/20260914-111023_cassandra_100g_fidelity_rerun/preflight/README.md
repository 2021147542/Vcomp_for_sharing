# Seed setting cleanup validation

`cassandra.ucs.picker_seed` is now registered as `CassandraRelevantProperties.UCS_PICKER_SEED`.
Unset keeps the native ThreadLocalRandom path; valid seeds retain java.util.Random sequences.
Invalid seed text now fails configuration validation instead of silently disabling reproducibility.

- `ant jar checkstyle`: PASS, 2,550 source files checked.
- Controller tests: 24 PASS, including unset/20260909/invalid seed cases.
- Both runtime JAR seed checks updated: Controller enum reference and enum property key.
- New seeded 24 B/1000 B smoke: PASS, 64 writes, 43 verified rows; fingerprint unchanged after UCS enabling.
- VComp daemon now receives the same seed as the offline loader and baseline daemon.
- Runner shell and embedded Python checks, including synthetic publication boundary tests: PASS.

Smoke source: `/home/dongju/vcomp/cassandra_check/pipeline-runs/24b-20260914-110537-3671580`.
These are qualification checks; 100 GiB results are pending. See ../status.env.
