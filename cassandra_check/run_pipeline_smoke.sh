#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_ROOT="$SCRIPT_DIR/../cassandra_vcomp"
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
KEY_BYTES="${KEY_BYTES:-24}"
VALUE_BYTES="${VALUE_BYTES:-40}"
ENTRY_BYTES=$((KEY_BYTES + VALUE_BYTES))
WRITES=64
FLUSH_WRITES=4
FLUSH_BYTES=$((ENTRY_BYTES * FLUSH_WRITES))
RUN_ID="${KEY_BYTES}b-$(date +%Y%m%d-%H%M%S)-$$"
RUN_DIR="$SCRIPT_DIR/pipeline-runs/$RUN_ID"
KEYSPACE="vcomp_pipeline_smoke_${KEY_BYTES}b"
TABLE=kv
SERVER_PID=""

case "$KEY_BYTES" in
    24|48) ;;
    *) echo "KEY_BYTES must be 24 or 48" >&2; exit 2 ;;
esac
if (( VALUE_BYTES <= 0 )); then
    echo "VALUE_BYTES must be positive" >&2
    exit 2
fi

java_client() {
    "$JAVA_HOME/bin/java" @"$RUN_DIR/conf/jvm11-clients.options" \
        -Dlogback.configurationFile="$SCRIPT_DIR/logback-smoke.xml" \
        -cp "$RUN_DIR/classes:$SOURCE_ROOT/build/classes/main:$SOURCE_ROOT/lib/*" \
        CassandraVCompPipelineClient "$@"
}

stop_server() {
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        "$SOURCE_ROOT/bin/nodetool" stopdaemon >/dev/null 2>&1 || kill "$SERVER_PID"
        wait "$SERVER_PID" 2>/dev/null || true
    fi
}
trap stop_server EXIT

mkdir -p "$RUN_DIR"/{classes,conf,data,sstables,logs}
cp -a "$SOURCE_ROOT/conf/." "$RUN_DIR/conf/"
sed -i \
    -e "s|^cluster_name:.*|cluster_name: 'VComp Pipeline Smoke $RUN_ID'|" \
    -e "s|^num_tokens:.*|num_tokens: 1|" \
    -e "s|^storage_compatibility_mode:.*|storage_compatibility_mode: NONE|" \
    -e "s|^# hints_directory: /var/lib/cassandra/hints|hints_directory: $RUN_DIR/data/hints|" \
    -e "s|^# data_file_directories:|data_file_directories:|" \
    -e "s|^#     - /var/lib/cassandra/data|    - $RUN_DIR/data/data|" \
    -e "s|^# commitlog_directory: /var/lib/cassandra/commitlog|commitlog_directory: $RUN_DIR/data/commitlog|" \
    -e "s|^# saved_caches_directory: /var/lib/cassandra/saved_caches|saved_caches_directory: $RUN_DIR/data/saved_caches|" \
    "$RUN_DIR/conf/cassandra.yaml"

export JAVA_HOME
export CASSANDRA_HOME="$SOURCE_ROOT"
export CASSANDRA_CONF="$RUN_DIR/conf"
export CASSANDRA_LOG_DIR="$RUN_DIR/logs"
export CASSANDRA_LIBJEMALLOC=-
export MAX_HEAP_SIZE=2G

echo "[1/6] Compiling Cassandra source and verification client"
source "$SOURCE_ROOT/build-env.sh"
ant -f "$SOURCE_ROOT/build.xml" build >/dev/null
"$JAVA_HOME/bin/javac" -cp "$SOURCE_ROOT/build/classes/main:$SOURCE_ROOT/lib/*" \
    -d "$RUN_DIR/classes" "$SCRIPT_DIR/src/CassandraVCompPipelineClient.java"

echo "[2/6] Starting isolated Cassandra 5.0.9 source build"
"$SOURCE_ROOT/bin/cassandra" -f >"$RUN_DIR/cassandra.stdout.log" 2>&1 &
SERVER_PID=$!
printf '%s\n' "$SERVER_PID" >"$RUN_DIR/cassandra.pid"
ready=false
for _ in $(seq 1 120); do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "Cassandra exited during startup; see $RUN_DIR/cassandra.stdout.log" >&2
        exit 1
    fi
    if java_client wait >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 1
done
if [[ "$ready" != true ]]; then
    echo "Cassandra did not become ready; see $RUN_DIR/cassandra.stdout.log" >&2
    exit 1
fi

echo "[3/6] Creating restricted single-partition schema"
java_client schema "$KEYSPACE" "$TABLE" "$KEY_BYTES"

echo "[4/6] Running virtual load, materialization, and nodetool import"
"$JAVA_HOME/bin/java" @"$RUN_DIR/conf/jvm11-clients.options" \
    -Dlogback.configurationFile="$SCRIPT_DIR/logback-smoke.xml" \
    -cp "$RUN_DIR/conf:$SOURCE_ROOT/build/classes/main:$SOURCE_ROOT/lib/*" \
    org.apache.cassandra.tools.VCompBulkLoad \
    "$RUN_DIR/sstables" "$SOURCE_ROOT/bin/nodetool" "$KEYSPACE" "$TABLE" \
    "$WRITES" "$ENTRY_BYTES" "$KEY_BYTES" "$VALUE_BYTES" "$FLUSH_BYTES" 0 20260909 | tee "$RUN_DIR/vcomp.log"

echo "[5/6] Verifying imported data through Cassandra"
java_client verify "$KEYSPACE" "$TABLE" "$KEY_BYTES" "$VALUE_BYTES" 1000 | tee "$RUN_DIR/verification.log"
java_client fingerprint "$KEYSPACE" "$TABLE" "$KEY_BYTES" "$VALUE_BYTES" | tee "$RUN_DIR/fingerprint.log"

echo "[6/6] Preserving database and table statistics"
"$SOURCE_ROOT/bin/nodetool" tablestats "$KEYSPACE.$TABLE" >"$RUN_DIR/tablestats.txt"
ln -sfn "pipeline-runs/$RUN_ID" "$SCRIPT_DIR/pipeline-latest"

echo "Smoke test completed successfully."
echo "Preserved run directory: $RUN_DIR"
