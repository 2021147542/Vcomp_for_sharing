#!/usr/bin/env bash
set -euo pipefail
cd /home/dongju/vcomp
source cassandra_vcomp/build-env.sh
read_diag_root=/tmp/vcomp-fixed-read-20260914
read_diag_logs=experiments/artifacts/20260914-192939_cassandra_tmux_analysis/read-generator
read_diag_jar=cassandra_vcomp/build/apache-cassandra-5.0.9-SNAPSHOT.jar
mkdir "$read_diag_root/classes"
"$JAVA_HOME/bin/java" -version > "$read_diag_logs/java-version.log" 2>&1
"$JAVA_HOME/bin/javac" -cp "$read_diag_jar:cassandra_vcomp/lib/*" -d "$read_diag_root/classes" cassandra_check/src/CassandraPaperWorkload.java cassandra_check/test/CassandraFixedReadDiagnostic.java > "$read_diag_logs/compile.log" 2>&1
"$JAVA_HOME/bin/java" -Xms32m -Xmx128m -cp "$read_diag_root/classes:$read_diag_jar:cassandra_vcomp/lib/*" CassandraFixedReadDiagnostic "$read_diag_root/requests.json" > "$read_diag_logs/run.log" 2>&1
sha256sum cassandra_check/src/CassandraPaperWorkload.java cassandra_check/test/CassandraFixedReadDiagnostic.java "$read_diag_jar" "$read_diag_root/requests.json" "$read_diag_root/classes/"*.class > "$read_diag_logs/sha256.txt"
cp "$read_diag_root/requests.json" "$read_diag_logs/requests.json"
