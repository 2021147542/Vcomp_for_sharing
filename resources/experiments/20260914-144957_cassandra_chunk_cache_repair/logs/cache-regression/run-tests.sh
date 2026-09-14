#!/usr/bin/env bash
set -euo pipefail
cd /home/dongju/vcomp/cassandra_vcomp
source build-env.sh
mapfile -t jvm_access_args < <(sed -n 's/^\(--add-[a-z]*\) \(.*\)$/\1=\2/p' conf/jvm11-server.options)
java -ea -Dlogback.configurationFile=/tmp/cassandra-chunk-cache-regression/logback.xml "${jvm_access_args[@]}" -Dcassandra.config=file:///home/dongju/vcomp/cassandra_vcomp/test/conf/cassandra.yaml -cp "${CHUNK_CACHE_TEST_OVERLAY:-/tmp/cassandra-chunk-cache-regression/classes}:/tmp/cassandra-chunk-cache-regression/classes:build/classes/main:build/lib/jars/*:build/test/lib/jars/*:test/conf" org.junit.runner.JUnitCore org.apache.cassandra.io.util.ChunkCacheFileHandleTest
