#!/usr/bin/env bash
set -euo pipefail

# Vanilla Cassandra comparison run. The matching VComp run is launched only
# after this process drains, so its disk counters remain uncontaminated.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_ROOT="$SCRIPT_DIR/../cassandra_vcomp"
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
EXPERIMENT_ROOT="${EXPERIMENT_ROOT:-/work/vcomp-pebble-1tb/cassandra-vcomp-20g-compare/baseline}"
DATASET_GIB="${DATASET_GIB:-20}"
RUN_TAG="${RUN_TAG:-${DATASET_GIB}gib-baseline}"
RUN_ID="cassandra-baseline-${RUN_TAG}-$(date +%Y%m%d-%H%M%S)"
RUN_DIR="$EXPERIMENT_ROOT/$RUN_ID"
KEYSPACE="${KEYSPACE:-baseline_${RUN_TAG//-/_}}"
TABLE=kv
KEY_BYTES="${KEY_BYTES:-24}"
VALUE_BYTES="${VALUE_BYTES:-1000}"
ENTRY_BYTES=$((KEY_BYTES + VALUE_BYTES))
FLUSH_BYTES=$((64 * 1024 * 1024))
TARGET_SSTABLE_SIZE="${TARGET_SSTABLE_SIZE:-64MiB}"
DATASET_BYTES=$((DATASET_GIB * 1024 * 1024 * 1024))
WRITES=$((DATASET_BYTES / ENTRY_BYTES))
PARTITION_COUNT="${PARTITION_COUNT:-$((DATASET_GIB * 100))}"
SEED=20260909
INFLIGHT=48
DISK_DEVICE=md0
COMPACTION_DRAIN_TIMEOUT_SECONDS="${COMPACTION_DRAIN_TIMEOUT_SECONDS:-21600}"
COMPACTION_POLL_SECONDS=5
UCS_PICKER_SEED="${UCS_PICKER_SEED:-20260909}"
SERVER_PID=""
RUN_SUCCEEDED=false
RUN_CREATED=false

if [[ ! "$COMPACTION_DRAIN_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] \
   || (( COMPACTION_DRAIN_TIMEOUT_SECONDS < COMPACTION_POLL_SECONDS )); then
    echo "COMPACTION_DRAIN_TIMEOUT_SECONDS must be an integer >= $COMPACTION_POLL_SECONDS" >&2
    exit 2
fi

disk_write_bytes() {
    awk -v device="$DISK_DEVICE" '$3 == device { printf "%.0f\n", $10 * 512; found=1 } END { if (!found) exit 1 }' /proc/diskstats
}

java_client() {
    "$JAVA_HOME/bin/java" @"$RUN_DIR/conf/jvm11-clients.options" \
        -Dlogback.configurationFile="$SCRIPT_DIR/logback-smoke.xml" \
        -cp "$RUN_DIR/classes:$SOURCE_ROOT/build/classes/main:$SOURCE_ROOT/lib/*" \
        CassandraVCompPipelineClient "$@"
}

stop_server() {
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        "$SOURCE_ROOT/bin/nodetool" stopdaemon >/dev/null 2>&1 || kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
}

on_exit() {
    status=$?
    stop_server
    if [[ "$RUN_SUCCEEDED" != true && "$RUN_CREATED" == true ]] && [[ -d "$RUN_DIR" ]]; then
        printf 'failed_at=%s\nexit_status=%s\n' "$(date --iso-8601=seconds)" "$status" >"$RUN_DIR/FAILED"
    fi
}
trap on_exit EXIT

wait_for_compactions() {
    local stable=0
    local attempts=$((COMPACTION_DRAIN_TIMEOUT_SECONDS / COMPACTION_POLL_SECONDS))
    for ((attempt = 0; attempt < attempts; attempt++)); do
        local stats
        stats=$("$SOURCE_ROOT/bin/nodetool" compactionstats) || return 1
        printf '%s\n' "$stats" | tee -a "$RUN_DIR/compactionstats.log"
        if [[ "$stats" =~ pending[[:space:]]+tasks[[:space:]]+0 ]] \
           && ! [[ "$stats" =~ compaction[[:space:]]+type ]]; then
            stable=$((stable + 1))
            if (( stable >= 3 )); then
                return
            fi
        else
            stable=0
        fi
        sleep "$COMPACTION_POLL_SECONDS"
    done
    echo "automatic UCS compactions did not drain within ${COMPACTION_DRAIN_TIMEOUT_SECONDS} seconds" >&2
    return 1
}

if pgrep -f 'org[.]apache[.]cassandra[.]service[.]CassandraDaemon' >/dev/null \
   || ss -H -ltn | awk '{print $4}' | rg -q ':(7000|7001|7199|9042)$'; then
    echo 'Another Cassandra daemon or listener exists; refusing to overlap' >&2
    exit 2
fi
[[ ! -e "$RUN_DIR" && ! -L "$RUN_DIR" ]] || { echo "Run path already exists: $RUN_DIR" >&2; exit 2; }
mkdir -p "$RUN_DIR"/{classes,conf,data,logs}
RUN_CREATED=true
ln -sfn "$RUN_DIR" "$EXPERIMENT_ROOT/latest-$RUN_TAG"
cp -a "$SOURCE_ROOT/conf/." "$RUN_DIR/conf/"
sed -i \
    -e "s|^cluster_name:.*|cluster_name: 'Baseline ${DATASET_GIB}GiB $RUN_ID'|" \
    -e "s|^num_tokens:.*|num_tokens: 1|" \
    -e "s|^storage_compatibility_mode:.*|storage_compatibility_mode: NONE|" \
    -e "s|^concurrent_writes:.*|concurrent_writes: 48|" \
    -e "s|^compaction_throughput:.*|compaction_throughput: 0MiB/s|" \
    -e "s|^# concurrent_compactors: 1|concurrent_compactors: 48|" \
    -e "s|^# hints_directory: /var/lib/cassandra/hints|hints_directory: $RUN_DIR/data/hints|" \
    -e "s|^# data_file_directories:|data_file_directories:|" \
    -e "s|^#     - /var/lib/cassandra/data|    - $RUN_DIR/data/data|" \
    -e "s|^# commitlog_directory: /var/lib/cassandra/commitlog|commitlog_directory: $RUN_DIR/data/commitlog|" \
    -e "s|^# saved_caches_directory: /var/lib/cassandra/saved_caches|saved_caches_directory: $RUN_DIR/data/saved_caches|" \
    "$RUN_DIR/conf/cassandra.yaml"

export JAVA_HOME CASSANDRA_HOME="$SOURCE_ROOT" CASSANDRA_CONF="$RUN_DIR/conf" CASSANDRA_LOG_DIR="$RUN_DIR/logs"
export CASSANDRA_LIBJEMALLOC=- MAX_HEAP_SIZE=4G
export JVM_OPTS="${JVM_OPTS:-} -Dcassandra.ucs.picker_seed=$UCS_PICKER_SEED"

{
    printf 'run_id=%s\n' "$RUN_ID"
    printf 'dataset_bytes=%s\n' "$DATASET_BYTES"
    printf 'dataset_gib=%s\n' "$DATASET_GIB"
    printf 'writes=%s\n' "$WRITES"
    printf 'entry_bytes=%s\nkey_bytes=%s\nvalue_bytes=%s\n' "$ENTRY_BYTES" "$KEY_BYTES" "$VALUE_BYTES"
    printf 'flush_bytes=%s\nwrites_per_flush=%s\n' "$FLUSH_BYTES" "$((FLUSH_BYTES / ENTRY_BYTES))"
    printf 'partition_keys=%s\ncompaction=UnifiedCompactionStrategy T4\ntarget_sstable_size=%s\n' "$PARTITION_COUNT" "$TARGET_SSTABLE_SIZE"
    printf 'concurrent_compactors=48\ninflight_cql_requests=48\ncompression=disabled\ndurable_writes=false\n'
    printf 'compaction_throughput_mib_per_second=0\n'
    printf 'ucs_picker_seed=%s\n' "$UCS_PICKER_SEED"
    printf 'compaction_drain_timeout_seconds=%s\n' "$COMPACTION_DRAIN_TIMEOUT_SECONDS"
    printf 'disk_device=%s\nseed=%s\nstarted_at=%s\n' "$DISK_DEVICE" "$SEED" "$(date --iso-8601=seconds)"
} >"$RUN_DIR/configuration.txt"
df -h /work >"$RUN_DIR/disk-before.txt"

echo '[1/6] Building Cassandra and baseline client'
source "$SOURCE_ROOT/build-env.sh"
ant -f "$SOURCE_ROOT/build.xml" build >"$RUN_DIR/build.log" 2>&1
"$JAVA_HOME/bin/javac" -cp "$SOURCE_ROOT/build/classes/main:$SOURCE_ROOT/lib/*" -d "$RUN_DIR/classes" \
    "$SCRIPT_DIR/src/CassandraVCompPipelineClient.java" "$SCRIPT_DIR/src/CassandraBaselineLoad.java"

echo "[2/6] Starting isolated Cassandra node for ${DATASET_GIB} GiB baseline"
"$SOURCE_ROOT/bin/cassandra" -f >"$RUN_DIR/cassandra.stdout.log" 2>&1 &
SERVER_PID=$!
printf '%s\n' "$SERVER_PID" >"$RUN_DIR/cassandra.pid"
for _ in $(seq 1 180); do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo 'Cassandra exited during startup' >&2
        exit 1
    fi
    if java_client wait >/dev/null 2>&1; then
        break
    fi
    sleep 1
done
java_client wait >/dev/null

echo '[3/6] Native CQL writes with explicit 64 MiB flushes and UCS T4 enabled'
start_ns=$(date +%s%N)
start_disk=$(disk_write_bytes)
set +e
"$JAVA_HOME/bin/java" -Xms2G -Xmx6G @"$RUN_DIR/conf/jvm11-clients.options" \
    -Dlogback.configurationFile="$SCRIPT_DIR/logback-smoke.xml" \
    -cp "$RUN_DIR/conf:$RUN_DIR/classes:$SOURCE_ROOT/build/classes/main:$SOURCE_ROOT/lib/*" \
    CassandraBaselineLoad "$SOURCE_ROOT/bin/nodetool" "$KEYSPACE" "$TABLE" \
    "$WRITES" "$KEY_BYTES" "$VALUE_BYTES" "$FLUSH_BYTES" "$TARGET_SSTABLE_SIZE" "$PARTITION_COUNT" "$SEED" "$INFLIGHT" \
    2>&1 | tee "$RUN_DIR/baseline.log"
load_status=${PIPESTATUS[0]}
set -e
if [[ "$load_status" -ne 0 ]]; then
    exit "$load_status"
fi

echo '[4/6] Draining automatic UCS compactions (no forced major compaction)'
"$SOURCE_ROOT/bin/nodetool" flush "$KEYSPACE" "$TABLE"
wait_for_compactions
end_ns=$(date +%s%N)
end_disk=$(disk_write_bytes)

echo '[5/6] Verifying materialized Cassandra rows and collecting physical metrics'
java_client verify "$KEYSPACE" "$TABLE" "$KEY_BYTES" "$VALUE_BYTES" 1000 "$WRITES" "$PARTITION_COUNT" | tee "$RUN_DIR/verification.log"
java_client fingerprint "$KEYSPACE" "$TABLE" "$KEY_BYTES" "$VALUE_BYTES" "$WRITES" "$PARTITION_COUNT" | tee "$RUN_DIR/fingerprint.log"
"$SOURCE_ROOT/bin/nodetool" tablestats "$KEYSPACE.$TABLE" >"$RUN_DIR/tablestats.txt"
find "$RUN_DIR/data/data/$KEYSPACE" -mindepth 2 -maxdepth 2 -type f -name '*-Data.db' -printf '%s\t%p\n' | sort -nr >"$RUN_DIR/sstable-sizes.tsv"
final_db_bytes=$(find "$RUN_DIR/data/data/$KEYSPACE" -mindepth 2 -maxdepth 2 -type f -printf '%s\n' | awk '{sum += $1} END {printf "%.0f", sum}')
elapsed_seconds=$(awk -v start="$start_ns" -v end="$end_ns" 'BEGIN { printf "%.3f", (end - start) / 1000000000 }')
disk_write_delta=$((end_disk - start_disk))
fingerprint_rows=$(awk -F '[ =]' '/^FINGERPRINT / { print $3 }' "$RUN_DIR/fingerprint.log")
fingerprint_sha256=$(awk -F '[ =]' '/^FINGERPRINT / { print $5 }' "$RUN_DIR/fingerprint.log")
if [[ -z "$fingerprint_rows" || -z "$fingerprint_sha256" ]]; then
    echo 'baseline fingerprint was not produced' >&2
    exit 1
fi
{
    printf 'load_metric_boundary=load_start_through_natural_compaction_drain\n'
    printf 'load_seconds=%s\n' "$elapsed_seconds"
    printf 'disk_write_bytes=%s\n' "$disk_write_delta"
    printf 'final_db_bytes=%s\n' "$final_db_bytes"
    printf 'sstable_count=%s\n' "$(wc -l <"$RUN_DIR/sstable-sizes.tsv")"
    printf 'max_sstable_bytes=%s\n' "$(awk 'NR == 1 { print $1 }' "$RUN_DIR/sstable-sizes.tsv")"
    printf 'fingerprint_rows=%s\n' "$fingerprint_rows"
    printf 'fingerprint_sha256=%s\n' "$fingerprint_sha256"
} >"$RUN_DIR/baseline_metrics.env"
df -h /work >"$RUN_DIR/disk-after.txt"

echo '[6/6] Completed; preserving baseline DB, logs, and metrics'
printf 'completed_at=%s\n' "$(date --iso-8601=seconds)" >"$RUN_DIR/SUCCESS"
RUN_SUCCEEDED=true
echo "Preserved run directory: $RUN_DIR"
