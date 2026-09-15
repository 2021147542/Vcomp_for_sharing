#!/usr/bin/env bash
set -euo pipefail
repo=/home/dongju/vcomp
out="$repo/experiments/artifacts/cassandra-repair-qualification-20260915"
scratch=/tmp/vcomp-cassandra-repair-qualification-20260915
exec 9>/tmp/vcomp-cassandra-storage-campaign.lock
flock -n 9
if pgrep -f 'org[.]apache[.]cassandra[.]service[.]CassandraDaemon|CassandraPaper[W]orkload|CassandraBaseline[L]oad|org[.]junit[.]runner[.]JUnitCore' >/dev/null; then exit 2; fi
mkdir -p "$scratch/tmp" "$out/classes"
cd "$repo/cassandra_vcomp"
source build-env.sh
ant jar >"$out/build.log" 2>&1
sha256sum build/apache-cassandra-5.0.9-SNAPSHOT.jar >"$out/runtime-relative.sha256"
sha256sum "$PWD/build/apache-cassandra-5.0.9-SNAPSHOT.jar" >"$out/runtime-jar.sha256"
python3 - "$scratch" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1])
s=Path('test/conf/cassandra.yaml').read_text().replace('build/test/cassandra',str(root/'storage')).replace('heap_dump_path: build/test','heap_dump_path: '+str(root/'storage'))
s+='\nstorage_compatibility_mode: NONE\nfile_cache_size: 51MiB\n'
(root/'cassandra.yaml').write_text(s)
PY
tests=(VCompPipelineTest DefaultFlushVirtualizerTest DefaultVirtualCompactionTest VCompDiscreteCdfTest VCompOrderedPartitionLayoutTest VCompPartitionBoundarySplitterTest VCompUcsPlannerTest VCompContinuousRankTest VCompCompleteSketchShardTest VCompCompleteCardinalityTest VCompFirstFlushSizingTest VCompCqlSstableMaterializerTest)
sources=()
names=()
for name in "${tests[@]}"; do
  sources+=("test/unit/org/apache/cassandra/db/compaction/vcomp/$name.java")
  names+=("org.apache.cassandra.db.compaction.vcomp.$name")
done
"$JAVA_HOME/bin/javac" -cp 'build/classes/main:build/test/classes:build/lib/jars/*:build/test/lib/jars/*:test/conf' -d "$out/classes" "${sources[@]}" >"$out/javac.log" 2>&1
mapfile -t access < <(sed -n 's/^\(--add-[a-z]*\) \(.*\)$/\1=\2/p' conf/jvm11-server.options)
"$JAVA_HOME/bin/java" -ea -Xmx4g "${access[@]}" -Djava.io.tmpdir="$scratch/tmp" -Dcassandra.test.storage_compatibility_mode=NONE -Dcassandra.config="file://$scratch/cassandra.yaml" -Dlogback.configurationFile="$repo/cassandra_check/logback-smoke.xml" -cp "$out/classes:build/classes/main:build/test/classes:build/lib/jars/*:build/test/lib/jars/*:test/conf" org.junit.runner.JUnitCore "${names[@]}" >"$out/junit.log" 2>&1
printf 'status=passed\n' >"$out/status.env"
tail -n 5 "$out/junit.log"
