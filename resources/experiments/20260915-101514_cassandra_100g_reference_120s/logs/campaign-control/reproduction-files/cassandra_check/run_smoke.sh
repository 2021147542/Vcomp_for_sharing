#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CASSANDRA_VERSION=4.1.12
CASSANDRA_ARCHIVE="apache-cassandra-${CASSANDRA_VERSION}-bin.tar.gz"
CASSANDRA_SHA256=8729a781d2a27a0d2718bc54ade5365266c4334c09246e355ee5106a5474a166
RUNTIME_ROOT="$SCRIPT_DIR/.runtime"
CASSANDRA_HOME="$RUNTIME_ROOT/apache-cassandra-${CASSANDRA_VERSION}"
ARCHIVE_PATH="$RUNTIME_ROOT/$CASSANDRA_ARCHIVE"
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
RUN_ID="$(date +%Y%m%d-%H%M%S)-$$"
RUN_DIR="$SCRIPT_DIR/runs/$RUN_ID"
SERVER_PID=""

java_client() {
    "$JAVA_HOME/bin/java" @"$RUN_DIR/conf/jvm11-clients.options" \
        -Dlogback.configurationFile="$SCRIPT_DIR/logback-smoke.xml" \
        -cp "$RUN_DIR/classes:$CASSANDRA_HOME/lib/*" \
        CassandraSmokeClient "$@"
}

stop_server() {
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        "$CASSANDRA_HOME/bin/nodetool" stopdaemon >/dev/null 2>&1 || kill "$SERVER_PID"
        wait "$SERVER_PID" 2>/dev/null || true
    fi
}
trap stop_server EXIT

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "Java 11 not found at $JAVA_HOME" >&2
    exit 1
fi

mkdir -p "$RUNTIME_ROOT" "$SCRIPT_DIR/runs"
if [[ ! -d "$CASSANDRA_HOME" ]]; then
    if [[ ! -f "$ARCHIVE_PATH" ]]; then
        curl -fL --retry 3 \
            -o "$ARCHIVE_PATH" \
            "https://downloads.apache.org/cassandra/${CASSANDRA_VERSION}/${CASSANDRA_ARCHIVE}"
    fi
    printf '%s  %s\n' "$CASSANDRA_SHA256" "$ARCHIVE_PATH" | sha256sum --check --status
    tar -xzf "$ARCHIVE_PATH" -C "$RUNTIME_ROOT"
fi

mkdir -p "$RUN_DIR"/{classes,conf,data,sstables,logs}
cp -a "$CASSANDRA_HOME/conf/." "$RUN_DIR/conf/"

# Isolate all mutable node state inside this run directory.  The run is kept
# after completion so its imported Cassandra DB and evidence can be inspected.
sed -i \
    -e "s|^cluster_name:.*|cluster_name: 'VComp Smoke $RUN_ID'|" \
    -e "s|^num_tokens:.*|num_tokens: 1|" \
    -e "s|^# hints_directory: /var/lib/cassandra/hints|hints_directory: $RUN_DIR/data/hints|" \
    -e "s|^# data_file_directories:|data_file_directories:|" \
    -e "s|^#     - /var/lib/cassandra/data|    - $RUN_DIR/data/data|" \
    -e "s|^# commitlog_directory: /var/lib/cassandra/commitlog|commitlog_directory: $RUN_DIR/data/commitlog|" \
    -e "s|^# saved_caches_directory: /var/lib/cassandra/saved_caches|saved_caches_directory: $RUN_DIR/data/saved_caches|" \
    "$RUN_DIR/conf/cassandra.yaml"

export JAVA_HOME
export CASSANDRA_HOME
export CASSANDRA_CONF="$RUN_DIR/conf"
export CASSANDRA_LOG_DIR="$RUN_DIR/logs"
export CASSANDRA_LIBJEMALLOC=-
export MAX_HEAP_SIZE=512M
export HEAP_NEWSIZE=128M

echo "[1/6] Compiling the native client and VComp materializer"
"$JAVA_HOME/bin/javac" \
    -cp "$CASSANDRA_HOME/lib/*" \
    -d "$RUN_DIR/classes" \
    "$SCRIPT_DIR/src/CassandraSmokeClient.java" \
    "$SCRIPT_DIR/src/CassandraVCompSmoke.java"

echo "[2/6] Starting isolated Cassandra $CASSANDRA_VERSION node"
"$CASSANDRA_HOME/bin/cassandra" -f >"$RUN_DIR/cassandra.stdout.log" 2>&1 &
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

echo "[3/6] Creating single-partition, single-value-column schema"
java_client schema

echo "[4/6] Building virtual outputs and materializing Cassandra SSTables"
"$JAVA_HOME/bin/java" @"$RUN_DIR/conf/jvm11-clients.options" \
    -Dlogback.configurationFile="$SCRIPT_DIR/logback-smoke.xml" \
    -cp "$RUN_DIR/conf:$RUN_DIR/classes:$CASSANDRA_HOME/lib/*" \
    CassandraVCompSmoke "$RUN_DIR/sstables" "$RUN_DIR/expected.tsv" \
    | tee "$RUN_DIR/materializer.log"

echo "[5/6] Importing the external SSTables into Cassandra"
mapfile -t SSTABLE_DIRS < <(
    find "$RUN_DIR/sstables" -mindepth 1 -maxdepth 1 -type d | sort
)
if [[ "${#SSTABLE_DIRS[@]}" -eq 0 ]]; then
    echo "No materialized SSTable directories found" >&2
    exit 1
fi
"$CASSANDRA_HOME/bin/nodetool" import --copy-data -- \
    vcomp_smoke kv "${SSTABLE_DIRS[@]}" \
    | tee "$RUN_DIR/import.log"

echo "[6/6] Verifying clustering order and newest-value semantics"
java_client verify "$RUN_DIR/expected.tsv" "$RUN_DIR/actual.tsv" \
    | tee "$RUN_DIR/verification.log"

"$CASSANDRA_HOME/bin/nodetool" tablestats vcomp_smoke.kv >"$RUN_DIR/tablestats.txt"
ln -sfn "runs/$RUN_ID" "$SCRIPT_DIR/latest"

echo
echo "Smoke test completed successfully."
echo "Preserved run directory: $RUN_DIR"
echo "Latest result link: $SCRIPT_DIR/latest"
