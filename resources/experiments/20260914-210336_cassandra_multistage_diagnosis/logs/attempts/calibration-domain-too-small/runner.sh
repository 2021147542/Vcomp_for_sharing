#!/usr/bin/env bash
set -euo pipefail
# Bounded diagnostic only. No main build, no daemon, no canonical database access.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ATTEMPT="${ATTEMPT:-$(date +%Y%m%d-%H%M%S)}"
DIAGNOSTIC_ROOT="/tmp/vcomp-cassandra-native-event-$ATTEMPT"
EVIDENCE_ROOT="${EVIDENCE_ROOT:-$REPO_ROOT/experiments/artifacts/cassandra-full-diagnosis-$ATTEMPT}"
[[ ! -e "$DIAGNOSTIC_ROOT" && ! -L "$DIAGNOSTIC_ROOT" && ! -e "$EVIDENCE_ROOT" ]] || exit 2
mkdir -p "$EVIDENCE_ROOT"
printf '%q ' bash "${BASH_SOURCE[0]}" >"$EVIDENCE_ROOT/command.txt"
printf '\nATTEMPT=%q\nEVIDENCE_ROOT=%q\n' "$ATTEMPT" "$EVIDENCE_ROOT" >>"$EVIDENCE_ROOT/command.txt"
trap 'rc=$?; printf "exit_status=%s\nscratch=%s\n" "$rc" "$DIAGNOSTIC_ROOT" >"$EVIDENCE_ROOT/status.env"' EXIT
exec 9>/tmp/vcomp-cassandra-storage-campaign.lock
flock -n 9 || { echo 'Storage lock busy'; exit 2; }
# Run through standard escalation so this check sees host processes.
ps -eo pid,comm,args | awk '$2 == "java" {print}' >"$EVIDENCE_ROOT/java-processes-before.txt"
if pgrep -f 'org[.]apache[.]cassandra[.]service[.]CassandraDaemon|CassandraPaper[W]orkload|CassandraBaseline[L]oad' >/dev/null; then
    echo 'Active Cassandra measurement; refuse to overlap'; exit 2
fi
mkdir -p "$DIAGNOSTIC_ROOT/classes" "$DIAGNOSTIC_ROOT/tmp" "$DIAGNOSTIC_ROOT/output"
cd "$REPO_ROOT/cassandra_vcomp"
source build-env.sh
python3 - "$DIAGNOSTIC_ROOT" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1])
s=Path('test/conf/cassandra.yaml').read_text().replace('build/test/cassandra',str(root/'storage'))
s=s.replace('heap_dump_path: build/test','heap_dump_path: '+str(root/'storage'))
s += '\nstorage_compatibility_mode: NONE\n'
assert 'build/test' not in s
(root/'cassandra.yaml').write_text(s)
PY
cp "$DIAGNOSTIC_ROOT/cassandra.yaml" "$EVIDENCE_ROOT/cassandra.yaml"
cp "$REPO_ROOT/experiments/scripts/cassandra/run_native_event_diagnostic.sh" "$EVIDENCE_ROOT/runner.sh"
source_file=test/unit/org/apache/cassandra/db/compaction/VCompNativeEventDiagnosticTest.java
cp "$source_file" test/unit/org/apache/cassandra/db/compaction/VCompReadPathDiagnostic.java "$REPO_ROOT/cassandra_check/src/CassandraPaperWorkload.java" "$EVIDENCE_ROOT/"
git -C "$REPO_ROOT" rev-parse HEAD >"$EVIDENCE_ROOT/source-revision.txt"
git -C "$REPO_ROOT" status --short >"$EVIDENCE_ROOT/git-status-before.txt"
sha256sum src/java/org/apache/cassandra/db/compaction/vcomp/*.java src/java/org/apache/cassandra/db/compaction/UnifiedCompactionStrategy.java src/java/org/apache/cassandra/db/lifecycle/{Tracker,LifecycleTransaction}.java >"$EVIDENCE_ROOT/main-sources-before.sha256"
sha256sum build/classes/main/org/apache/cassandra/db/compaction/vcomp/*.class build/classes/main/org/apache/cassandra/db/compaction/{UnifiedCompactionStrategy,AbstractCompactionTask}.class build/classes/main/org/apache/cassandra/db/compaction/unified/{UnifiedCompactionTask,Controller,UnifiedCompactionPicker}.class >"$EVIDENCE_ROOT/main-classes-before.sha256"
"$JAVA_HOME/bin/java" -version >"$EVIDENCE_ROOT/java-version.txt" 2>&1
"$JAVA_HOME/bin/javac" -cp 'build/classes/main:build/test/classes:build/lib/jars/*:build/test/lib/jars/*:test/conf' \
    -d "$DIAGNOSTIC_ROOT/classes" "$source_file" test/unit/org/apache/cassandra/db/compaction/VCompReadPathDiagnostic.java "$REPO_ROOT/cassandra_check/src/CassandraPaperWorkload.java" >"$EVIDENCE_ROOT/javac.log" 2>&1
mapfile -t jvm_access_args < <(sed -n 's/^\(--add-[a-z]*\) \(.*\)$/\1=\2/p' conf/jvm11-server.options)
"$JAVA_HOME/bin/java" -ea -Xmx4g -Dcassandra.test.storage_compatibility_mode=NONE \
    -Dvcomp.event.domain="${DOMAIN:-1048576}" -Dvcomp.event.partitions="${PARTITIONS:-100}" \
    -Dvcomp.event.flushes="${FLUSHES:-16}" -Dvcomp.event.flush_writes="${FLUSH_WRITES:-65536}" "${jvm_access_args[@]}" \
    -Djava.io.tmpdir="$DIAGNOSTIC_ROOT/tmp" -Dcassandra.config="file://$DIAGNOSTIC_ROOT/cassandra.yaml" \
    -Dvcomp.event.output="$DIAGNOSTIC_ROOT/output" -Dlogback.configurationFile="$REPO_ROOT/cassandra_check/logback-smoke.xml" \
    -cp "$DIAGNOSTIC_ROOT/classes:build/classes/main:build/test/classes:build/lib/jars/*:build/test/lib/jars/*:test/conf" \
    org.junit.runner.JUnitCore org.apache.cassandra.db.compaction.VCompNativeEventDiagnosticTest \
    >"$EVIDENCE_ROOT/junit.log" 2>&1
sha256sum -c "$EVIDENCE_ROOT/main-sources-before.sha256" >"$EVIDENCE_ROOT/main-sources-after-check.txt"
sha256sum -c "$EVIDENCE_ROOT/main-classes-before.sha256" >"$EVIDENCE_ROOT/main-classes-after-check.txt"
cp "$DIAGNOSTIC_ROOT/output/"*.json "$EVIDENCE_ROOT/"
echo "Completed: $EVIDENCE_ROOT; raw scratch: $DIAGNOSTIC_ROOT"
