#!/usr/bin/env bash
set -euo pipefail
# Offline continuation of a retained bounded case; no native load or workload.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
INPUT_ROOT="${1:?retained native output directory required}"
[[ "$INPUT_ROOT" == /tmp/vcomp-* && -f "$INPUT_ROOT/native-events.json" ]] || exit 2
STAMP="$(date +%Y%m%d-%H%M%S)"
REPLAY_ROOT="/tmp/vcomp-cassandra-event-replay-$STAMP"
EVIDENCE_ROOT="$REPO_ROOT/experiments/artifacts/20260914-192939_cassandra_tmux_analysis/replay-$STAMP"
[[ ! -e "$REPLAY_ROOT" && ! -e "$EVIDENCE_ROOT" ]] || exit 2
mkdir -p "$EVIDENCE_ROOT"
trap 'rc=$?; printf "exit_status=%s\nscratch=%s\ninput=%s\n" "$rc" "$REPLAY_ROOT" "$INPUT_ROOT" >"$EVIDENCE_ROOT/status.env"' EXIT
exec 9>/tmp/vcomp-cassandra-storage-campaign.lock
flock -n 9 || exit 2
ps -eo pid,comm,args | awk '$2 == "java" {print}' >"$EVIDENCE_ROOT/java-processes-before.txt"
if pgrep -f 'org[.]apache[.]cassandra[.]service[.]CassandraDaemon|CassandraPaper[W]orkload|CassandraBaseline[L]oad' >/dev/null; then
    echo 'Active Cassandra measurement; refusing offline writer overlap'; exit 2
fi
mkdir -p "$REPLAY_ROOT/classes" "$REPLAY_ROOT/tmp"
cd "$REPO_ROOT/cassandra_vcomp"
source build-env.sh
python3 - "$REPLAY_ROOT" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1])
s=Path('test/conf/cassandra.yaml').read_text().replace('build/test/cassandra',str(root/'storage'))
s=s.replace('heap_dump_path: build/test','heap_dump_path: '+str(root/'storage'))
assert 'build/test' not in s
(root/'cassandra.yaml').write_text(s)
PY
cp "$REPLAY_ROOT/cassandra.yaml" "$EVIDENCE_ROOT/cassandra.yaml"
cp "$REPO_ROOT/experiments/scripts/cassandra/resume_native_event_replay.sh" "$EVIDENCE_ROOT/runner.sh"
cp test/unit/org/apache/cassandra/db/compaction/VCompNativeEventReplay.java "$EVIDENCE_ROOT/"
printf 'bash experiments/scripts/cassandra/resume_native_event_replay.sh %q\n' "$INPUT_ROOT" >"$EVIDENCE_ROOT/command.txt"
sha256sum build/classes/main/org/apache/cassandra/db/compaction/vcomp/*.class >"$EVIDENCE_ROOT/main-classes.sha256"
"$JAVA_HOME/bin/javac" -cp 'build/classes/main:build/test/classes:build/lib/jars/*:build/test/lib/jars/*:test/conf' \
    -d "$REPLAY_ROOT/classes" test/unit/org/apache/cassandra/db/compaction/VCompNativeEventReplay.java \
    >"$EVIDENCE_ROOT/javac.log" 2>&1
mapfile -t jvm_access_args < <(sed -n 's/^\(--add-[a-z]*\) \(.*\)$/\1=\2/p' conf/jvm11-server.options)
"$JAVA_HOME/bin/java" -ea -Xmx2g "${jvm_access_args[@]}" \
    -Djava.io.tmpdir="$REPLAY_ROOT/tmp" -Dcassandra.config="file://$REPLAY_ROOT/cassandra.yaml" \
    -Dlogback.configurationFile="$REPO_ROOT/cassandra_check/logback-smoke.xml" \
    -cp "$REPLAY_ROOT/classes:build/classes/main:build/test/classes:build/lib/jars/*:build/test/lib/jars/*:test/conf" \
    org.apache.cassandra.db.compaction.VCompNativeEventReplay "$INPUT_ROOT" "$REPLAY_ROOT/output" \
    >"$EVIDENCE_ROOT/replay.log" 2>&1
cp "$REPLAY_ROOT/output/"*.json "$EVIDENCE_ROOT/"
sha256sum -c "$EVIDENCE_ROOT/main-classes.sha256" >"$EVIDENCE_ROOT/main-classes-after-check.txt"
echo "Completed offline continuation: $EVIDENCE_ROOT; raw: $REPLAY_ROOT"
