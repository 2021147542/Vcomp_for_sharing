#!/usr/bin/env bash
set -euo pipefail
repo="${REPO_ROOT:-/home/dongju/vcomp}"
trace="${1:?native trace JSON path required}"
output="${2:?new probe JSON path required}"
classes=$(mktemp -d /tmp/vcomp-model-probe-classes.XXXXXX)
/usr/lib/jvm/java-11-openjdk-amd64/bin/javac -cp "$repo/cassandra_vcomp/build/classes/main:$repo/cassandra_vcomp/build/lib/jars/*" -d "$classes" "$repo/cassandra_vcomp/test/unit/org/apache/cassandra/db/compaction/vcomp/VCompModelStageProbe.java"
/usr/lib/jvm/java-11-openjdk-amd64/bin/java -ea -cp "$classes:$repo/cassandra_vcomp/build/classes/main:$repo/cassandra_vcomp/build/lib/jars/*" org.apache.cassandra.db.compaction.vcomp.VCompModelStageProbe "$trace" "$output"
