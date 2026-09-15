#!/usr/bin/env bash
set -euo pipefail
# Bounded read-only C/E attribution on independent copies of registered 100GiB states.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
MANIFEST="${1:?fresh checkpoint manifest JSON required}"
[[ -f "$MANIFEST" ]] || exit 2
CHECKPOINT_ROOT="$(python3 - "$MANIFEST" <<'PY'
from pathlib import Path
import json,sys
p=Path(sys.argv[1]).resolve(strict=True)
d=json.loads(p.read_text())
r=Path(d['checkpoint_root']).resolve(strict=True)
assert r.name.startswith('vcomp-cassandra-read-diagnosis-')
assert p.is_relative_to(r)
for key in ('native_data_paths','vcomp_data_paths'):
    assert d[key]
    for value in d[key]:
        f=Path(value).resolve(strict=True)
        assert f.is_relative_to(r) and f.name.endswith('-Data.db')
print(r)
PY
)"
EVICT_DATA_CACHE="${EVICT_DATA_CACHE:-false}"
[[ "$EVICT_DATA_CACHE" == true || "$EVICT_DATA_CACHE" == false ]] || exit 2
STAMP="${ATTEMPT:-$(date +%Y%m%d-%H%M%S)}"
DIAGNOSTIC_ROOT="/tmp/vcomp-checkpoint-read-$STAMP"
EVIDENCE_ROOT="${EVIDENCE_ROOT:-$REPO_ROOT/experiments/artifacts/cassandra-checkpoint-read-$STAMP}"
[[ ! -e "$DIAGNOSTIC_ROOT" && ! -L "$DIAGNOSTIC_ROOT" && ! -e "$EVIDENCE_ROOT" ]] || exit 2
exec 9>/tmp/vcomp-cassandra-storage-campaign.lock
flock -n 9 || { echo 'Storage lock busy' >&2; exit 2; }
if pgrep -f 'org[.]apache[.]cassandra[.]service[.]CassandraDaemon|CassandraPaper[W]orkload|CassandraBaseline[L]oad' >/dev/null; then
    echo 'Another Cassandra measurement is active' >&2; exit 2
fi
mkdir -p "$DIAGNOSTIC_ROOT/classes" "$DIAGNOSTIC_ROOT/tmp" "$DIAGNOSTIC_ROOT/output" "$EVIDENCE_ROOT"
trap 'rc=$?; printf "exit_status=%s\nscratch=%s\nmanifest=%s\n" "$rc" "$DIAGNOSTIC_ROOT" "$MANIFEST" >"$EVIDENCE_ROOT/status.env"' EXIT
cd "$REPO_ROOT/cassandra_vcomp"
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
source build-env.sh
python3 - "$DIAGNOSTIC_ROOT" <<'PY'
from pathlib import Path
import re,sys
root=Path(sys.argv[1])
s=Path('test/conf/cassandra.yaml').read_text().replace('build/test/cassandra',str(root/'storage'))
s=s.replace('heap_dump_path: build/test','heap_dump_path: '+str(root/'storage'))
s=re.sub(r'^(?:storage_compatibility_mode|file_cache_size|incremental_backups|disk_access_mode):.*\n','',s,flags=re.M)
s+='\nstorage_compatibility_mode: NONE\nfile_cache_size: 5152MiB\nincremental_backups: false\ndisk_access_mode: standard\n'
assert 'build/test' not in s
(root/'cassandra.yaml').write_text(s)
PY
cp "$DIAGNOSTIC_ROOT/cassandra.yaml" "$EVIDENCE_ROOT/cassandra.yaml"
cp "$REPO_ROOT/experiments/scripts/cassandra/run_checkpoint_read_diagnostic.sh" "$EVIDENCE_ROOT/runner.sh"
sources=(test/unit/org/apache/cassandra/db/compaction/VCompCheckpointReadDiagnosticTest.java
         test/unit/org/apache/cassandra/db/compaction/VCompReadPathDiagnostic.java
         test/unit/org/apache/cassandra/db/compaction/VCompScanPathDiagnostic.java
         "$REPO_ROOT/cassandra_check/src/CassandraPaperWorkload.java")
for source in "${sources[@]}"; do cp "$source" "$EVIDENCE_ROOT/"; done
git -C "$REPO_ROOT" rev-parse HEAD >"$EVIDENCE_ROOT/source-revision.txt"
sha256sum build/classes/main/org/apache/cassandra/db/compaction/vcomp/*.class >"$EVIDENCE_ROOT/main-classes-before.sha256"
printf 'bash experiments/scripts/cassandra/run_checkpoint_read_diagnostic.sh %q\nPOINT_REQUESTS=%s\nSCAN_OPERATIONS=%s\nEVICT_DATA_CACHE=%s\n' \
    "$MANIFEST" "${POINT_REQUESTS:-1000}" "${SCAN_OPERATIONS:-1000}" "$EVICT_DATA_CACHE" >"$EVIDENCE_ROOT/command.txt"
"$JAVA_HOME/bin/java" -version >"$EVIDENCE_ROOT/java-version.txt" 2>&1
"$JAVA_HOME/bin/javac" -cp 'build/classes/main:build/test/classes:build/lib/jars/*:build/test/lib/jars/*:test/conf' \
    -d "$DIAGNOSTIC_ROOT/classes" "${sources[@]}" >"$EVIDENCE_ROOT/javac.log" 2>&1
mapfile -t jvm_access_args < <(sed -n 's/^\(--add-[a-z]*\) \(.*\)$/\1=\2/p' conf/jvm11-server.options)
"$JAVA_HOME/bin/java" -ea -Xmx4g -XX:MaxDirectMemorySize=8g -Dcassandra.test.storage_compatibility_mode=NONE "${jvm_access_args[@]}" \
    -Djava.io.tmpdir="$DIAGNOSTIC_ROOT/tmp" -Dcassandra.config="file://$DIAGNOSTIC_ROOT/cassandra.yaml" \
    -Dvcomp.checkpoint.manifest="$MANIFEST" -Dvcomp.checkpoint.output="$DIAGNOSTIC_ROOT/output" \
    -Dvcomp.diagnostic.checkpoint_root="$CHECKPOINT_ROOT" \
    -Dvcomp.read.requests="${POINT_REQUESTS:-1000}" -Dvcomp.scan.operations="${SCAN_OPERATIONS:-1000}" \
    -Dvcomp.read.evict_data_cache="$EVICT_DATA_CACHE" -Dvcomp.scan.evict_data_cache="$EVICT_DATA_CACHE" \
    -Dlogback.configurationFile="$REPO_ROOT/cassandra_check/logback-smoke.xml" \
    -cp "$DIAGNOSTIC_ROOT/classes:build/classes/main:build/test/classes:build/lib/jars/*:build/test/lib/jars/*:test/conf" \
    org.junit.runner.JUnitCore org.apache.cassandra.db.compaction.VCompCheckpointReadDiagnosticTest \
    >"$EVIDENCE_ROOT/junit.log" 2>&1
sha256sum -c "$EVIDENCE_ROOT/main-classes-before.sha256" >"$EVIDENCE_ROOT/main-classes-after-check.txt"
cp "$DIAGNOSTIC_ROOT/output/"*.json "$EVIDENCE_ROOT/"
for workload in point-C scan-E; do
    mkdir -p "$EVIDENCE_ROOT/$workload"
    cp "$DIAGNOSTIC_ROOT/output/$workload/"*.json "$EVIDENCE_ROOT/$workload/"
done
echo "Completed checkpoint read diagnosis: $EVIDENCE_ROOT; raw: $DIAGNOSTIC_ROOT"
