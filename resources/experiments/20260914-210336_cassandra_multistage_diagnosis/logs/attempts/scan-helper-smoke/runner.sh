#!/usr/bin/env bash
set -euo pipefail
# Fresh local read continuation on retained diagnostic SSTs only. No load or canonical database access.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
INPUT_ROOT="${1:?retained /tmp/vcomp-* output directory required}"
[[ "$INPUT_ROOT" == /tmp/vcomp-* && -f "$INPUT_ROOT/native-events.json" ]] || exit 2
STAMP="${ATTEMPT:-$(date +%Y%m%d-%H%M%S)}"
DIAGNOSTIC_ROOT="/tmp/vcomp-retained-scan-$STAMP"
EVIDENCE_ROOT="${EVIDENCE_ROOT:-$REPO_ROOT/experiments/artifacts/cassandra-retained-scan-$STAMP}"
[[ ! -e "$DIAGNOSTIC_ROOT" && ! -L "$DIAGNOSTIC_ROOT" && ! -e "$EVIDENCE_ROOT" ]] || exit 2
exec 9>/tmp/vcomp-cassandra-storage-campaign.lock
flock -n 9 || { echo 'Storage lock busy' >&2; exit 2; }
if pgrep -f 'org[.]apache[.]cassandra[.]service[.]CassandraDaemon|CassandraPaper[W]orkload|CassandraBaseline[L]oad' >/dev/null; then
    echo 'Another Cassandra measurement is active' >&2; exit 2
fi
mkdir -p "$DIAGNOSTIC_ROOT/classes" "$DIAGNOSTIC_ROOT/tmp" "$DIAGNOSTIC_ROOT/output" "$EVIDENCE_ROOT"
trap 'rc=$?; printf "exit_status=%s\nscratch=%s\ninput=%s\n" "$rc" "$DIAGNOSTIC_ROOT" "$INPUT_ROOT" >"$EVIDENCE_ROOT/status.env"' EXIT
cd "$REPO_ROOT/cassandra_vcomp"
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
source build-env.sh
python3 - "$DIAGNOSTIC_ROOT" <<'PY'
from pathlib import Path
import re,sys
root=Path(sys.argv[1])
s=Path('test/conf/cassandra.yaml').read_text().replace('build/test/cassandra',str(root/'storage'))
s=s.replace('heap_dump_path: build/test','heap_dump_path: '+str(root/'storage'))
s=re.sub(r'^(?:storage_compatibility_mode|file_cache_size|incremental_backups):.*\n','',s,flags=re.M)
s+='\nstorage_compatibility_mode: NONE\nfile_cache_size: 51MiB\nincremental_backups: false\n'
assert 'build/test' not in s
(root/'cassandra.yaml').write_text(s)
PY
cp "$DIAGNOSTIC_ROOT/cassandra.yaml" "$EVIDENCE_ROOT/cassandra.yaml"
cp "$REPO_ROOT/experiments/scripts/cassandra/run_retained_scan_diagnostic.sh" "$EVIDENCE_ROOT/runner.sh"
sources=(test/unit/org/apache/cassandra/db/compaction/VCompRetainedScanDiagnosticTest.java
         test/unit/org/apache/cassandra/db/compaction/VCompScanPathDiagnostic.java
         "$REPO_ROOT/cassandra_check/src/CassandraPaperWorkload.java")
for source in "${sources[@]}"; do cp "$source" "$EVIDENCE_ROOT/"; done
git -C "$REPO_ROOT" rev-parse HEAD >"$EVIDENCE_ROOT/source-revision.txt"
sha256sum build/classes/main/org/apache/cassandra/db/compaction/vcomp/*.class >"$EVIDENCE_ROOT/main-classes-before.sha256"
printf 'bash experiments/scripts/cassandra/run_retained_scan_diagnostic.sh %q\nSCAN_OPERATIONS=%s\n' "$INPUT_ROOT" "${SCAN_OPERATIONS:-1000}" >"$EVIDENCE_ROOT/command.txt"
"$JAVA_HOME/bin/javac" -cp 'build/classes/main:build/test/classes:build/lib/jars/*:build/test/lib/jars/*:test/conf' \
    -d "$DIAGNOSTIC_ROOT/classes" "${sources[@]}" >"$EVIDENCE_ROOT/javac.log" 2>&1
mapfile -t jvm_access_args < <(sed -n 's/^\(--add-[a-z]*\) \(.*\)$/\1=\2/p' conf/jvm11-server.options)
"$JAVA_HOME/bin/java" -ea -Xmx2g -Dcassandra.test.storage_compatibility_mode=NONE "${jvm_access_args[@]}" \
    -Djava.io.tmpdir="$DIAGNOSTIC_ROOT/tmp" -Dcassandra.config="file://$DIAGNOSTIC_ROOT/cassandra.yaml" \
    -Dvcomp.retained.input="$INPUT_ROOT" -Dvcomp.retained.output="$DIAGNOSTIC_ROOT/output" \
    -Dvcomp.scan.operations="${SCAN_OPERATIONS:-1000}" \
    -Dlogback.configurationFile="$REPO_ROOT/cassandra_check/logback-smoke.xml" \
    -cp "$DIAGNOSTIC_ROOT/classes:build/classes/main:build/test/classes:build/lib/jars/*:build/test/lib/jars/*:test/conf" \
    org.junit.runner.JUnitCore org.apache.cassandra.db.compaction.VCompRetainedScanDiagnosticTest \
    >"$EVIDENCE_ROOT/junit.log" 2>&1
sha256sum -c "$EVIDENCE_ROOT/main-classes-before.sha256" >"$EVIDENCE_ROOT/main-classes-after-check.txt"
cp "$DIAGNOSTIC_ROOT/output/"*.json "$EVIDENCE_ROOT/"
for arm in model exact-writer-control timestamp-control; do
    if [[ -d "$DIAGNOSTIC_ROOT/output/$arm" ]]; then
        mkdir -p "$EVIDENCE_ROOT/$arm"
        cp "$DIAGNOSTIC_ROOT/output/$arm/"*.json "$EVIDENCE_ROOT/$arm/"
    fi
done
echo "Completed retained scan diagnosis: $EVIDENCE_ROOT; raw: $DIAGNOSTIC_ROOT"
