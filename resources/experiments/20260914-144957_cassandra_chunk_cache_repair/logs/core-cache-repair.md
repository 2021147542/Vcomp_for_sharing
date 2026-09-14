# Native chunk-cache reader geometry repair

## Observed failure and source cause

The preserved first A/baseline failure reported `newPosition > limit: (7523 >
4096)` in `RandomAccessReader.reBufferAt`, and an EOF while reading the growing
`oa-3921-big-Index.db`. The compaction creating this file was still active.

`ChunkCache.Key` originally identified entries by file path, ChunkReader class
and aligned offset alone. `BigTableWriter.openInternal` recalculates the primary
index buffer size from current index length / summary size on each early-open
reader. Consequently, different readers of the same growing SST can request
4 KiB and 8 KiB chunks at offset zero but collide in the cache. An 8 KiB reader
then receives the older 4 KiB buffer, producing precisely the observed exception.

A second collision exists even with a constant chunk size: a cached partial tail
at an earlier physical EOF is reused after the file grows. This reproduces an
EOF after 4,096 of 8,192 requested bytes.

These are native reader/cache correctness defects, not changes to the VComp
model or UCS compaction policy. The exact first error and the related growing-tail
failure were reproduced with ordinary FileHandles without running a benchmark.
The controlled cases prove the failure mechanisms; the historical stack trace
alone does not identify which cached key produced every recorded error.

## Change

Only production `cassandra_vcomp/src/java/org/apache/cassandra/cache/ChunkCache.java`
changes: key equality and hash now include requested chunk size and the number
of readable bytes in this chunk for the reader's file-length snapshot:

```
readableLength = position >= fileLength ? 0 : min(chunkSize, fileLength - position)
```

Negative positions are rejected before subtraction. Complete chunks retain the
same key when a file grows; adding the entire file length to every key would
needlessly invalidate/reload all earlier full chunks and is deliberately avoided.
Different chunk sizes and successive partial-tail views cannot collide.

`CompressedChunkReader` exposes `metadata.dataLength` as its fileLength, and its
positions and chunkSize are also uncompressed. Thus the added geometry uses a
consistent coordinate space for compressed and uncompressed sources; it does not
mix physical compressed-file offsets with logical offsets.

`invalidatePosition` continues to invalidate the current FileHandle's geometry.
An old partial tail now has a separate key and cannot satisfy a newer view; an
old full chunk with matching geometry remains covered by ordinary invalidation.
`invalidateFile` still removes every geometry for that path on reader cleanup.
No global cache scan was added to each lookup or positional invalidation. The
native cache remains enabled and compaction/early opening remain enabled.

## Regression evidence

`ChunkCacheFileHandleTest` contains five temporary-file tests. Early and later
handles are held open together so their cleanup cannot accidentally erase the
cache and mask the original collision.

| Case | Preserved original ChunkCache | Fixed ChunkCache |
|---|---|---|
| Same path/offset, 4 KiB then 8 KiB reader, seek 7,523 | Fails: 7523 > 4096 | Pass |
| 8 KiB chunks, file grows from 4 KiB to 8 KiB | Fails: EOF after 4096 of 8192 | Pass |
| Complete 4 KiB chunk shared across file growth | Pass; hit-count control | Pass; hit-count control |
| Current-view invalidatePosition forces reload | Pass; miss-count control | Pass; miss-count control |
| Compressed file uses logical offsets and returns correct full content | Pass | Pass |

Authoritative logs are `cache-regression/final-before.log` (5 tests, 2 failures)
and `cache-regression/final-after.log` (5 tests, all pass). Both use the same final
test class. The before run prepends preserved original `ChunkCache*.class` files
to the classpath; repository source was not rolled back. Original/fixed source,
old cache classes, compiled test and the command script are retained alongside
these logs. The original full runtime JAR was **not** copied before rebuild and
must not be described as retained; its historical campaign hash remains in the
failed campaign provenance.

`cache-regression/build-checkstyle.log` records `ant jar checkstyle` succeeding
with Java 11 and 2,550 source files checked. `fixed-sources-runtime.sha256` records
the repaired runtime JAR and relevant source/test hashes. The build preceded the
addition of the compressed-coordinate test, which is test-only and was compiled
and executed directly afterwards; runtime source did not change again.

Earlier diagnostic logs are also retained. `initialization-failure.log` reflects
a first invocation missing required Java module access, before any test ran.
`after-compressed.log` records a test-fixture error using a 16-byte compression
chunk instead of 16 KiB; the final test fixes this fixture. The final runner uses
a standalone logging configuration because the ad-hoc test classpath omits
Cassandra's optional test logging helper classes. These are not DB benchmark
results and none is silently substituted for an earlier successful measurement.

## Commands

Run from `cassandra_vcomp`, after `source build-env.sh`:

```
javac -cp 'build/classes/main:build/lib/jars/*:build/test/lib/jars/*:lib/*' \
  -d /tmp/cassandra-chunk-cache-regression/classes \
  test/unit/org/apache/cassandra/io/util/ChunkCacheFileHandleTest.java
ant jar checkstyle
bash /tmp/cassandra-chunk-cache-regression/run-tests.sh
CHUNK_CACHE_TEST_OVERLAY=/tmp/cassandra-chunk-cache-regression/before-classes \
  bash /tmp/cassandra-chunk-cache-regression/run-tests.sh
```

The preserved command script supplies the repository's Java 11 module-access
options, enabled-cache test YAML, JUnit classpath and explicit logging config.
The before command is expected to exit nonzero. These tests need Cassandra's
local network-interface initialization but do not start a daemon or DB workload.
Parent-controlled paired A qualification must establish that the live concurrent
load/compaction failure is absent before restarting the full workload campaign.
