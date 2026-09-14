# Validation and reproduction

Working tree base: `301dcd92e04bd7f93dea2d7fcea5ff9f07001382` plus the changes
identified in `source-manifest.json`. Source started clean. No commit was made.

## Build and unit tests

Executed with OpenJDK 11 and repository-local Ant/dependencies. Tests that create
SSTs require local network-interface enumeration during Cassandra TimeUUID
initialization. They were run outside the filesystem/network sandbox after
automatic approval; they do not query an external service.

From `/home/dongju/vcomp/cassandra_vcomp`:

```bash
source build-env.sh
ant jar
mkdir -p /tmp/vcomp-fidelity-tests
javac -cp 'build/classes/main:build/lib/jars/*:build/test/lib/jars/*:build/test/classes' \
  -d /tmp/vcomp-fidelity-tests \
  test/unit/org/apache/cassandra/db/compaction/vcomp/*Test.java \
  test/unit/org/apache/cassandra/db/compaction/unified/UnifiedCompactionPickerTest.java \
  test/unit/org/apache/cassandra/db/compaction/unified/ControllerTest.java
tests=()
for test in test/unit/org/apache/cassandra/db/compaction/vcomp/*Test.java; do
  test_class="${test#test/unit/}"
  tests+=("${test_class%.java}")
done
tests=("${tests[@]//\//.}")
java @conf/jvm11-clients.options \
  -Dlogback.configurationFile=../cassandra_check/logback-smoke.xml \
  -cp '/tmp/vcomp-fidelity-tests:build/classes/main:build/lib/jars/*:build/test/lib/jars/*:build/test/classes' \
  org.junit.runner.JUnitCore "${tests[@]}" \
  org.apache.cassandra.db.compaction.unified.UnifiedCompactionPickerTest
java @conf/jvm11-clients.options \
  -Dcassandra.config=file:/home/dongju/vcomp/cassandra_vcomp/test/conf/cassandra.yaml \
  -Dcassandra.storagedir=/tmp/vcomp-controller-test \
  -Dlogback.configurationFile=../cassandra_check/logback-smoke.xml \
  -cp '/tmp/vcomp-fidelity-tests:build/classes/main:build/lib/jars/*:build/test/lib/jars/*:build/test/classes' \
  org.junit.runner.JUnitCore org.apache.cassandra.db.compaction.unified.ControllerTest
```

Results: 50 and 23 tests passed. Separate JVMs are necessary because the CQL
writer initializes client mode and ControllerTest initializes daemon mode.
`combined-jvm-initialization-failure.log` records the unsuccessful combined run.

`ant checkstyle` inspected 2,550 source files and reported exactly one violation,
the pre-existing `Controller.java:202` direct `Long.getLong` call. Verified with
`git show HEAD:cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/unified/Controller.java`.
No Checkstyle violation was reported in the changed files. This is not recorded
as an overall Checkstyle pass; the complete output is `checkstyle.log`.

## Real Cassandra smoke

Ran serially from `/home/dongju/vcomp`, after verifying no existing Cassandra
daemon/listener. The runner now builds the actual daemon JAR, refuses occupied
ports, and stops only its own child PID.

```bash
KEY_BYTES=24 VALUE_BYTES=1000 cassandra_check/run_pipeline_smoke.sh
KEY_BYTES=48 VALUE_BYTES=43 cassandra_check/run_pipeline_smoke.sh
```

Both passed; `smoke/*/source-path.txt` points to the preserved raw run. Each
directory includes the measured JAR hash and configuration. Source patches and
full build/server logs remain in the raw directories. After both runs, the
owned daemon PIDs had exited and ports 9042/7199/7000 were free. These are tiny
functional tests, not throughput/latency qualification.

## CPU-only diagnostics

Workload reproduction and proxy test commands are in
`cassandra-workload-audit-validation.md`. Its source hash identifies the new
client; legacy draws are explicitly replayed and checked against archived C
cardinality/miss measurements. No DB scan was used for this reproduction.

The model probe's measured driver is `experiments/analysis/ModelSplitProbe.java`.
`model-split/commands.sh` records commands executed using isolated old/new class
directories under `/tmp/vcomp-model-audit`. Old classes were copied before the
split fix was built; their hashes are in `model-split/isolated-classes.sha256`.
To recreate old classes after those temporary directories are lost, extract
`DefaultVirtualCompaction.java` and `VCompLearnedModel.java` from the base revision
above and compile them into a separate directory against current dependencies.
Keep both old classes ahead of the common dependency classpath. Do not mix an
old splitter with the new model API. No before/after DB was created.

Use `before-1g/` and `after-1g-final/`, not intermediate probe directories.
`distribution.csv` contains counts in 100 equal-width key buckets; ECDF error
uses the maximum absolute difference of cumulative counts normalized by each
union cardinality. `hot_keys.csv` is only the first 1,000 scalar keys, despite its
historical filename; it does not identify the Zipf workload's actual hot keys.
Timing in `summary.env` is metadata-probe elapsed time, not a load-speed claim.
