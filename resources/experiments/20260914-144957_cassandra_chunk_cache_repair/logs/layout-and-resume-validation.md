# Layout and resumption validation

The user requested folder organization before continuing the 100 GiB workloads.
The first A baseline completed; A VComp was interrupted. The user-interrupted
attempt is preserved under `attempts/20260914-150117_cassandra_100g_cachefixed/`.
Canonical loads are reused without regeneration.

All 17 retained timestamp folders expose figures/, logs/, results.md; all 51 SVG
files parse. The 1,204 original non-Markdown file hashes are unchanged after
relocation; original Markdown bytes with repaired links are separately archived.
Workload throughput/device-byte values in the presentation exactly match source JSON.
The empty failed 20260913-194834 presentation folder was removed as requested.

The updated runners put internal outputs under logs/ and publish the three
figures and one table from the same normalized values. A service exit without
WORKLOAD_ROOT/SUCCESS is now explicitly rejected, so a user-stopped service
cannot proceed to publication as though every workload finished.

Both actual runner publication blocks were tested in an isolated synthetic
fixture, using archived pilot JSON as a schema fixture, setting fixture-only
300-second/48-thread metadata and nullable device means plus text scope. Both
retained exactly 47 primary comparisons, included seven defined diagnostic read
means, and did not try to compare text or null values. These fixture outputs
are not benchmark results. No storage benchmark was launched for layout testing.
