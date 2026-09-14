# 100 GiB workload attempt — interrupted for folder organization

The user requested that result-folder organization take priority. The workload
service was stopped on 2026-09-14 around 15:11 KST. A baseline completed; A VComp
was interrupted and has no valid complete result. No workload pair is reported
as complete. Original loaded DBs and this attempt are preserved.

The old wrapper continued to canonical-byte verification after systemd stopped
the service, then failed publication because complete result files were absent.
That terminal status does not indicate a new Cassandra cache failure.
See USER_INTERRUPTED.json and the raw campaign at
`/work/vcomp-pebble-1tb/cassandra-cachefixed-100g-20260914-150117`.

All fourteen workload cells must be restarted on fresh checkpoints after
organization, with unchanged seed and benchmark settings.
