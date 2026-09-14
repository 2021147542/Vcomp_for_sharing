# Device-layer average request latency telemetry

This change applies to future workload executions. Historical byte-only results
have no device-latency values and must remain N/A. No DB benchmark was run to
validate this change; `workload-test.log` records an in-memory driver-proxy test.

## Measurement contract

The client already reads `/proc/diskstats` immediately before releasing prepared
workers and after all workers complete. These same two snapshots now retain
completed read/write requests and cumulative read/write milliseconds in addition
to the existing sector counters. No additional timed sampling, seed changes,
workload changes, or generator-version changes were introduced.

Field indices follow the [Linux kernel I/O-statistics documentation](https://www.kernel.org/doc/html/latest/admin-guide/iostats.html):
`/proc/diskstats` prefixes the counters with major, minor and device name. Thus
zero-based fields 3/6/7/10 are completed reads/read milliseconds/completed
writes/write milliseconds, and fields 5/9 remain read/written sectors. Sector
bytes retain their existing 512-byte conversion.

| New JSON field | Value |
|---|---|
| `disk_read_requests`, `disk_write_requests` | Completed request-count delta |
| `disk_read_time_ms`, `disk_write_time_ms` | Cumulative request-time delta, milliseconds |
| `disk_read_latency_avg_ms`, `disk_write_latency_avg_ms` | Corresponding time delta / completed request delta |
| `disk_latency_scope` | `block device md0; mean completed request latency; not percentiles` (actual configured device replaces md0) |

A direction's new count/time/average fields become JSON `null` if its request,
time or sector-byte counters decrease between snapshots. This detects visible
resets/rollovers; it cannot infer a reset that rises past its previous value
before the second snapshot. Unchanged counters retain count/time zero and have
an undefined (`null`) average because no request completed. Positive request
counts with zero elapsed milliseconds yield measured 0.0, reflecting counter
resolution rather than an absent measurement. The legacy byte deltas retain
their previous signed-subtraction semantics.

These averages concern the selected **block-device layer**. `md0` is the RAID
logical-device layer, not an individual NVMe drive. They include all I/O attributed
to that device, including compaction and other processes. They are not client
latencies, server request percentiles, latency distributions, or isolated media
service times. Request-time totals can exceed wall time under concurrency; time
counters have millisecond reporting resolution. Bytes divided by throughput are
not used to infer latency.

## Validation

The standalone suite passes with Java assertions enabled. Added fixtures verify:

- Legacy and extended diskstats field layouts, exact device selection, and
  separation of completed from merged request counts.
- 512-byte sector conversion; 10 milliseconds / 4 completed reads = 2.5 ms and
  4 milliseconds / 2 completed writes = 2.0 ms.
- No completed requests produce JSON `null`, never NaN/Infinity or quoted null.
- Decreasing request, time and sector counters invalidate the affected direction;
  the other direction remains valid. Millisecond-truncated zero is retained.
- Truncated and negative target-device counters are rejected.
- Numeric average/count output and explicit block-device scope.

All existing worker-stream, E scan, MixGraph reference-vector, acknowledged
insert, growing-Zipf, scan-accounting, time/operation-mode and failure-cleanup
checks also passed. Enabling `-ea` exposed a pre-existing proxy-fixture issue:
PreparedId was constructed with null metadata. The test now constructs valid
empty metadata. The initial assertion failure is retained separately; production
workload semantics were not changed to accommodate the fixture.

## Reproduction

From `/home/dongju/vcomp`:

```bash
source cassandra_vcomp/build-env.sh
mkdir -p /tmp/cassandra-device-latency-test
javac -cp 'cassandra_vcomp/build/apache-cassandra-5.0.9-SNAPSHOT.jar:cassandra_vcomp/lib/*:cassandra_vcomp/build/lib/jars/*' \
  -d /tmp/cassandra-device-latency-test \
  cassandra_check/src/CassandraPaperWorkload.java cassandra_check/test/CassandraPaperWorkloadTest.java
mapfile -t latency_test_jvm_args < <(sed -n 's/^\(--add-[a-z]*\) \(.*\)$/\1=\2/p' cassandra_vcomp/conf/jvm11-server.options)
java -ea "${latency_test_jvm_args[@]}" \
  -Dlogback.configurationFile=/tmp/cassandra-chunk-cache-regression/logback.xml \
  -cp '/tmp/cassandra-device-latency-test:cassandra_vcomp/build/apache-cassandra-5.0.9-SNAPSHOT.jar:cassandra_vcomp/lib/*:cassandra_vcomp/build/lib/jars/*' \
  CassandraPaperWorkloadTest md0
```

`md0` must exist for the existing two-snapshot smoke portion, which issues only
in-memory proxy requests. The deterministic diskstats fixtures do not depend on
actual device activity. Source copies and their hashes are retained here.
