#!/usr/bin/env bash
set -euo pipefail
# Matched native/CQL/production writer diagnostic. Test-only exact keys; canonical DB never opened.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
COMPATIBILITY_MODE="${COMPATIBILITY_MODE:-NONE}"
[[ "$COMPATIBILITY_MODE" == NONE || "$COMPATIBILITY_MODE" == CASSANDRA_4 ]] || exit 2
DIAGNOSTIC_ROOT="${DIAGNOSTIC_ROOT:-/tmp/vcomp-matched-materialization-$(date +%Y%m%d-%H%M%S)}"
[[ ! -e "$DIAGNOSTIC_ROOT" ]] || { echo 'Fresh diagnostic root required' >&2; exit 2; }
exec 9>/tmp/vcomp-cassandra-storage-campaign.lock
flock -n 9 || { echo 'Another storage campaign is running' >&2; exit 2; }
if pgrep -f 'org[.]apache[.]cassandra[.]service[.]CassandraDaemon|CassandraPaper[W]orkload|CassandraBaseline[L]oad' >/dev/null; then
    echo 'Another Cassandra measurement is running' >&2
    exit 2
fi
mkdir -p "$DIAGNOSTIC_ROOT/classes" "$DIAGNOSTIC_ROOT/tmp"
cd "$REPO_ROOT/cassandra_vcomp"
[[ -f build/classes/main/org/apache/cassandra/db/compaction/vcomp/VCompCqlSstableMaterializer.class ]] || {
    echo 'Build current main classes before running the diagnostic' >&2; exit 2;
}
python3 - "$DIAGNOSTIC_ROOT" "$COMPATIBILITY_MODE" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1])
text=Path('test/conf/cassandra.yaml').read_text().replace('build/test/cassandra', str(root/'storage'))
text=text.replace('heap_dump_path: build/test', 'heap_dump_path: '+str(root/'storage'))
import re
text=re.sub(r'^storage_compatibility_mode:.*$', 'storage_compatibility_mode: '+sys.argv[2], text, flags=re.M)
(root/'cassandra.yaml').write_text(text)
PY
source=test/unit/org/apache/cassandra/db/compaction/vcomp/VCompMatchedMaterializationTest.java
cp "$source" "$DIAGNOSTIC_ROOT/"
cp "$SCRIPT_DIR/run_matched_materialization_diagnostic.sh" "$DIAGNOSTIC_ROOT/runner.sh"
sha256sum build/classes/main/org/apache/cassandra/db/compaction/vcomp/*.class >"$DIAGNOSTIC_ROOT/main-classes.sha256"
sha256sum src/java/org/apache/cassandra/db/compaction/vcomp/*.java >"$DIAGNOSTIC_ROOT/main-sources.sha256"
git -C "$REPO_ROOT" rev-parse HEAD >"$DIAGNOSTIC_ROOT/source-revision.txt"
/usr/lib/jvm/java-11-openjdk-amd64/bin/javac \
    -cp 'build/classes/main:build/test/classes:build/lib/jars/*:build/test/lib/jars/*:test/conf' \
    -d "$DIAGNOSTIC_ROOT/classes" "$source" >"$DIAGNOSTIC_ROOT/javac.log" 2>&1
mapfile -t jvm_access_args < <(sed -n 's/^\(--add-[a-z]*\) \(.*\)$/\1=\2/p' conf/jvm11-server.options)
/usr/lib/jvm/java-11-openjdk-amd64/bin/java -ea -Xmx1g "${jvm_access_args[@]}" \
    -Djava.io.tmpdir="$DIAGNOSTIC_ROOT/tmp" \
    -Dcassandra.test.storage_compatibility_mode="$COMPATIBILITY_MODE" \
    -Dcassandra.config="file://$DIAGNOSTIC_ROOT/cassandra.yaml" \
    -Dvcomp.matched.output="$DIAGNOSTIC_ROOT/output" \
    -Dlogback.configurationFile="$REPO_ROOT/cassandra_check/logback-smoke.xml" \
    -cp "$DIAGNOSTIC_ROOT/classes:build/classes/main:build/test/classes:build/lib/jars/*:build/test/lib/jars/*:test/conf" \
    org.junit.runner.JUnitCore org.apache.cassandra.db.compaction.vcomp.VCompMatchedMaterializationTest \
    >"$DIAGNOSTIC_ROOT/junit.log" 2>&1
echo "$DIAGNOSTIC_ROOT/output/matched-materialization.json"
