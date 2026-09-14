# Cassandra chunk-cache correctness repair and 100 GiB workload retry

The [first comprehensive attempt](attempts/20260914-141045_cassandra_100g_comprehensive/README.md)
completed both 100 GiB loads and failed in its first A baseline workload.
No performance threshold or seed choice triggers this retry.

Initial diagnosis: native ChunkCache.Key includes file path, chunk-reader class,
and aligned offset, but omits chunk size. Native BigTableWriter early opening can
change the Index.db reader buffer size as the file grows. An 8 KiB request can
therefore receive a cached 4 KiB buffer, producing `newPosition > limit`.
Cached partial tail blocks across growing readers also need separate identities.
The repair will retain chunk caching, native compaction, and the same workload settings.

Before the new full workload campaign, require focused reproducer/regression
checks, full build/Checkstyle, and an A workload on both preserved 100 GiB load
checkpoints with the unchanged 48-worker settings for 60 seconds per arm.
These checks qualify correctness only; no performance result chooses settings.
Then all 14 workload cells will start from independent checkpoints under a new
run ID, using the same seed 20260909 and 300-second duration.

The loaded database artifacts and old binary provenance remain unchanged.
The resumed campaign must record the new reader binary separately, hash canonical
SST components before and after, and preserve the failed attempt.
Validation and retry results are pending.

The workload runner now also requires a fresh server log and zero ERROR entries
through daemon shutdown before writing a cell SUCCESS marker. A successful client
return alone is insufficient; driver retries could otherwise conceal a server error.
Original failed logs and the initial frozen runner remain available.

## Qualification passed (15:00 KST)

[Core repair evidence](core-cache-repair.md): five regression tests pass; the original
cache overlay fails the two reproductions. Full runtime build and Checkstyle pass.
The original full JAR was not retained before rebuild; source, cache-class overlay
and its historical hash are available. The repaired JAR will be retained in the retry.

Both actual 100 GiB A cells completed with 48 workers for at least 60 seconds.
Server ERROR, OOM and BlockedOnAllocation counts were zero on both arms; native
compactions completed 13/16 times. Native chunk-cache capacity was 5,368,709,120 B.
Pilot evidence, including performance values without selection, is archived under
`pilot/` and `pilot-control/`. See [the exact command](pilot-command.sh).

[Resumption validation](runner-resume-audit.md) records preserved-load validation,
byte-level canonical checks and separate load/reader provenance.
