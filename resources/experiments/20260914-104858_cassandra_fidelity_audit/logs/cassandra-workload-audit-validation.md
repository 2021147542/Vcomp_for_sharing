# Cassandra workload client validation

No Cassandra daemon or storage benchmark was started. The regression test uses in-memory Java driver proxies. The trace audit reconstructs baseline membership in a Java BitSet and reads no database files.

Source SHA-256 (`cassandra_check/src/CassandraPaperWorkload.java`): `7ae0637857acf5a2057aa08b14eccf9bc73b19c96a4fdd65a40ecdd9528eb60b`.

Commands executed from `/home/dongju`:

```sh
/usr/lib/jvm/java-11-openjdk-amd64/bin/javac -cp '/home/dongju/vcomp/cassandra_vcomp/build/classes/main:/home/dongju/vcomp/cassandra_vcomp/lib/*' -d /tmp/cassandra-workload-audit-classes /home/dongju/vcomp/cassandra_check/src/CassandraPaperWorkload.java /home/dongju/vcomp/cassandra_check/test/CassandraPaperWorkloadTest.java /home/dongju/vcomp/experiments/analysis/CassandraWorkloadTraceAudit.java
/usr/lib/jvm/java-11-openjdk-amd64/bin/java -Dlogback.configurationFile=/home/dongju/vcomp/cassandra_check/logback-smoke.xml -cp '/tmp/cassandra-workload-audit-classes:/home/dongju/vcomp/cassandra_vcomp/build/classes/main:/home/dongju/vcomp/cassandra_vcomp/lib/*' CassandraPaperWorkloadTest md0
/usr/lib/jvm/java-11-openjdk-amd64/bin/java -cp '/tmp/cassandra-workload-audit-classes:/home/dongju/vcomp/cassandra_vcomp/build/classes/main:/home/dongju/vcomp/cassandra_vcomp/lib/*' CassandraWorkloadTraceAudit 7ae0637857acf5a2057aa08b14eccf9bc73b19c96a4fdd65a40ecdd9528eb60b > /tmp/cassandra-workload-trace-audit.jsonl
git -C /home/dongju/vcomp diff --check -- cassandra_check/src/CassandraPaperWorkload.java cassandra_check/test/CassandraPaperWorkloadTest.java experiments/analysis/CassandraWorkloadTraceAudit.java
```

Compilation and diff check passed. Regression output:

```text
CassandraPaperWorkloadTest: independent deterministic streams, fixed counts, hit/miss output, and failure cleanup passed
```

The JVM emitted the existing jamm library's Java 11 illegal reflective-access warning; the test exited 0.

Trace output is `/tmp/cassandra-workload-trace-audit.jsonl`. All key/request/miss/shift fields are counts; hit_rate_fraction is in [0, 1]. The workload_source_sha256 field identifies the tested current harness source, not the historical executable. Legacy generation is explicitly reconstructed in the audit helper and checked against the retained result `resources/experiments/20260914-010000_cassandra_faithful_v2_100g/results/c_baseline.json`.

Legacy: 960,000 requests, 28,109 distinct keys, 408,858 misses, and 19,999/19,999 identical worker-0-offset-by-one versus worker-2 keys. Current split streams: 543,995 distinct keys, 408,765 misses, and 50/19,999 equal keys (Zipf hotkey repetition). Both reconstruct 66,278,498 baseline live keys. This establishes a workload diversity defect; the nearly unchanged 57.4% hit rate also demonstrates that fixing worker streams does not by itself correct reconstructed VComp membership or predict measured performance convergence.

Workload D remains schedule-dependent: its reads observe shared nextInsert while concurrent writers reserve insert keys. Split streams guarantee reproducible per-worker random draws, not a fully deterministic global operation order or read visibility. Workload mixtures and distribution formulas are otherwise unchanged.
