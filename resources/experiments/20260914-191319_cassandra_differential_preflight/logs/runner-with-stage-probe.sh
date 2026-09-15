#!/usr/bin/env bash
set -euo pipefail

# Diagnostic only: real small native flush/compaction plus test-only exact rows.
# No production algorithm toggle and no retained benchmark database is opened.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
CASSANDRA_ROOT="$REPO_ROOT/cassandra_vcomp"
RUN_STAMP="${RUN_STAMP:-$(date +%Y%m%d-%H%M%S)}"
DIAGNOSTIC_ROOT="${DIAGNOSTIC_ROOT:-/tmp/vcomp-cassandra-differential-$RUN_STAMP}"
BUNDLE_ROOT="${RESULT_ROOT:-$REPO_ROOT/resources/experiments/${RUN_STAMP}_cassandra_differential_preflight}"
[[ ! -e "$DIAGNOSTIC_ROOT" && ! -L "$DIAGNOSTIC_ROOT" ]] || { echo 'Diagnostic output already exists' >&2; exit 2; }
[[ ! -e "$BUNDLE_ROOT" && ! -L "$BUNDLE_ROOT" ]] || { echo 'Result bundle already exists' >&2; exit 2; }
exec 9>/tmp/vcomp-cassandra-storage-campaign.lock
flock -n 9 || { echo 'Another storage campaign is running' >&2; exit 2; }
if pgrep -f 'org[.]apache[.]cassandra[.]service[.]CassandraDaemon|CassandraPaper[W]orkload|CassandraBaseline[L]oad' >/dev/null; then
    echo 'A Cassandra measurement is running; refusing to overlap' >&2
    exit 2
fi
mkdir -p "$DIAGNOSTIC_ROOT/classes" "$DIAGNOSTIC_ROOT/tmp" "$BUNDLE_ROOT/logs" "$BUNDLE_ROOT/figures"
finished=false
on_exit() {
    local code=$?
    if [[ "$finished" != true ]]; then
        (( code != 0 )) || code=1
        printf 'diagnostic_only=true\nseed=20260909\nstatus=failed\nexit_status=%s\n' "$code" >"$BUNDLE_ROOT/logs/status.env"
        python3 "$REPO_ROOT/experiments/analysis/publish_experiment_bundle.py" "$BUNDLE_ROOT" >/dev/null || true
    fi
    exit "$code"
}
trap on_exit EXIT
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
source "$CASSANDRA_ROOT/build-env.sh"
python3 - "$CASSANDRA_ROOT/test/conf/cassandra.yaml" "$DIAGNOSTIC_ROOT" <<'PY'
from pathlib import Path
import sys
source, root = map(Path, sys.argv[1:])
text = source.read_text().replace('build/test/cassandra', str(root / 'storage'))
text = text.replace('heap_dump_path: build/test', 'heap_dump_path: ' + str(root / 'storage'))
(root / 'cassandra.yaml').write_text(text)
PY
git -C "$REPO_ROOT" rev-parse HEAD >"$BUNDLE_ROOT/logs/source-revision.txt"
git -C "$REPO_ROOT" diff -- cassandra_vcomp >"$BUNDLE_ROOT/logs/source-changes.patch"
cp "$DIAGNOSTIC_ROOT/cassandra.yaml" "$BUNDLE_ROOT/logs/cassandra.yaml"
cp "${BASH_SOURCE[0]}" "$BUNDLE_ROOT/logs/runner.sh"
printf 'diagnostic_only=true\nseed=20260909\nstatus=running\n' >"$BUNDLE_ROOT/logs/status.env"
python3 "$REPO_ROOT/experiments/analysis/publish_experiment_bundle.py" "$BUNDLE_ROOT" >/dev/null

echo 'Building current main classes and compiling the two bounded diagnostic tests'
ant -f "$CASSANDRA_ROOT/build.xml" build >"$BUNDLE_ROOT/logs/build.log" 2>&1
cd "$CASSANDRA_ROOT"
if [[ ! -f build/test/classes/org/apache/cassandra/cql3/CQLTester.class ]]; then
    ant build-test >>"$BUNDLE_ROOT/logs/build.log" 2>&1
fi
test_sources=(test/unit/org/apache/cassandra/db/compaction/vcomp/VCompNativeExactDifferentialTest.java
              test/unit/org/apache/cassandra/db/compaction/vcomp/VCompModelStageProbe.java
              test/unit/org/apache/cassandra/db/compaction/VCompNativePickerDifferentialTest.java)
"$JAVA_HOME/bin/javac" -cp 'build/classes/main:build/test/classes:build/lib/jars/*:build/test/lib/jars/*:test/conf' \
    -d "$DIAGNOSTIC_ROOT/classes" "${test_sources[@]}" >"$BUNDLE_ROOT/logs/javac.log" 2>&1
for source in "${test_sources[@]}"; do
    cp "$source" "$BUNDLE_ROOT/logs/$(basename "$source")"
done
mapfile -t jvm_access_args < <(sed -n 's/^\(--add-[a-z]*\) \(.*\)$/\1=\2/p' conf/jvm11-server.options)
echo 'Running actual native jobs and exact reference comparison'
set +e
"$JAVA_HOME/bin/java" -ea -Xmx2g "${jvm_access_args[@]}" \
    -Djava.io.tmpdir="$DIAGNOSTIC_ROOT/tmp" \
    -Dcassandra.config="file://$DIAGNOSTIC_ROOT/cassandra.yaml" \
    -Dvcomp.differential.output="$BUNDLE_ROOT/logs/native-exact-trace.json" \
    -Dvcomp.picker.differential.output="$BUNDLE_ROOT/logs/native-picker-trace.json" \
    -Dlogback.configurationFile="$REPO_ROOT/cassandra_check/logback-smoke.xml" \
    -cp "$DIAGNOSTIC_ROOT/classes:build/classes/main:build/test/classes:build/lib/jars/*:build/test/lib/jars/*:test/conf" \
    org.junit.runner.JUnitCore org.apache.cassandra.db.compaction.vcomp.VCompNativeExactDifferentialTest \
    org.apache.cassandra.db.compaction.VCompNativePickerDifferentialTest >"$BUNDLE_ROOT/logs/junit.log" 2>&1
test_status=$?
set -e
printf 'diagnostic_only=true\nseed=20260909\nstatus=%s\nexit_status=%s\n' \
    "$([[ "$test_status" == 0 ]] && echo complete || echo failed)" "$test_status" >"$BUNDLE_ROOT/logs/status.env"
if (( test_status != 0 )); then
    python3 "$REPO_ROOT/experiments/analysis/publish_experiment_bundle.py" "$BUNDLE_ROOT" >/dev/null
    echo "Diagnostic execution failed; see $BUNDLE_ROOT/logs/junit.log" >&2
    exit "$test_status"
fi
"$JAVA_HOME/bin/java" -ea \
    -cp "$DIAGNOSTIC_ROOT/classes:build/classes/main:build/lib/jars/*" \
    org.apache.cassandra.db.compaction.vcomp.VCompModelStageProbe \
    "$BUNDLE_ROOT/logs/native-exact-trace.json" "$BUNDLE_ROOT/logs/model-stage-probe.json" \
    >"$BUNDLE_ROOT/logs/model-stage-probe.log" 2>&1
python3 "$REPO_ROOT/experiments/analysis/publish_cassandra_differential.py" "$BUNDLE_ROOT"
finished=true
echo "Diagnostic recorded: $BUNDLE_ROOT/results.md (JUnit success is not a fidelity pass)"
