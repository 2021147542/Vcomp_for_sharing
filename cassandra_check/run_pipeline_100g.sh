#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_ROOT="$SCRIPT_DIR/../cassandra_vcomp"
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
EXPERIMENT_ROOT="${EXPERIMENT_ROOT:-/work/vcomp-cassandra-100g}"
DATASET_GIB="${DATASET_GIB:-100}"
RUN_TAG="${RUN_TAG:-${DATASET_GIB}gib}"
RUN_ID="cassandra-vcomp-${RUN_TAG}-$(date +%Y%m%d-%H%M%S)"
RUN_DIR="$EXPERIMENT_ROOT/$RUN_ID"
# CQL unquoted identifiers cannot contain '-'. Keep the human-readable run
# tag for paths, but derive a legal default keyspace name from it.
KEYSPACE="${KEYSPACE:-vcomp_${RUN_TAG//-/_}}"
TABLE=kv
DATASET_BYTES=$((DATASET_GIB * 1024 * 1024 * 1024))
KEY_BYTES="${KEY_BYTES:-24}"
VALUE_BYTES="${VALUE_BYTES:-1000}"
ENTRY_BYTES=$((KEY_BYTES + VALUE_BYTES))
FLUSH_BYTES=$((64 * 1024 * 1024))
TARGET_SST_BYTES="${TARGET_SST_BYTES:-$((64 * 1024 * 1024))}"
PARTITION_COUNT="${PARTITION_COUNT:-$((DATASET_GIB * 100))}"
VCOMP_SST_SIZE_MODEL="${VCOMP_SST_SIZE_MODEL:-calibrated}"
UCS_PICKER_SEED="${UCS_PICKER_SEED:-20260909}"
WRITES=$((DATASET_BYTES / ENTRY_BYTES))
SEED=20260909
DISK_DEVICE="${DISK_DEVICE:-md0}"
COMPACTION_DRAIN_TIMEOUT_SECONDS="${COMPACTION_DRAIN_TIMEOUT_SECONDS:-21600}"
COMPACTION_POLL_SECONDS=5
SERVER_PID=""
MONITOR_PID=""
RUN_SUCCEEDED=false

disk_write_bytes() {
    awk -v device="$DISK_DEVICE" '$3 == device { printf "%.0f\n", $10 * 512; found=1 } END { if (!found) exit 1 }' /proc/diskstats
}

case "$KEY_BYTES" in
    24|48) ;;
    *) echo "KEY_BYTES must be 24 or 48" >&2; exit 2 ;;
esac
if (( VALUE_BYTES <= 0 )); then
    echo "VALUE_BYTES must be positive" >&2
    exit 2
fi
if [[ ! "$TARGET_SST_BYTES" =~ ^[0-9]+$ ]]; then
    echo "TARGET_SST_BYTES must be a non-negative integer" >&2
    exit 2
fi
if (( TARGET_SST_BYTES <= 0 || TARGET_SST_BYTES % (1024 * 1024) != 0 )); then
    echo "TARGET_SST_BYTES must be a positive whole number of MiB" >&2
    exit 2
fi
if [[ ! "$PARTITION_COUNT" =~ ^[1-9][0-9]*$ ]] || (( PARTITION_COUNT > WRITES )); then
    echo "PARTITION_COUNT must be in [1, WRITES]" >&2
    exit 2
fi
if [[ "$VCOMP_SST_SIZE_MODEL" != logical && "$VCOMP_SST_SIZE_MODEL" != calibrated ]]; then
    echo "VCOMP_SST_SIZE_MODEL must be logical or calibrated" >&2
    exit 2
fi
if [[ ! "$COMPACTION_DRAIN_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] \
   || (( COMPACTION_DRAIN_TIMEOUT_SECONDS < COMPACTION_POLL_SECONDS )); then
    echo "COMPACTION_DRAIN_TIMEOUT_SECONDS must be an integer >= $COMPACTION_POLL_SECONDS" >&2
    exit 2
fi

java_client() {
    "$JAVA_HOME/bin/java" @"$RUN_DIR/conf/jvm11-clients.options" \
        -Dlogback.configurationFile="$SCRIPT_DIR/logback-smoke.xml" \
        -cp "$RUN_DIR/classes:$SOURCE_ROOT/build/classes/main:$SOURCE_ROOT/lib/*" \
        CassandraVCompPipelineClient "$@"
}

stop_processes() {
    if [[ -n "$MONITOR_PID" ]] && kill -0 "$MONITOR_PID" 2>/dev/null; then
        kill "$MONITOR_PID" 2>/dev/null || true
        wait "$MONITOR_PID" 2>/dev/null || true
    fi
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        "$SOURCE_ROOT/bin/nodetool" stopdaemon >/dev/null 2>&1 || kill "$SERVER_PID"
        wait "$SERVER_PID" 2>/dev/null || true
    fi
}

wait_for_compactions() {
    local stable=0
    local stats
    local attempts=$((COMPACTION_DRAIN_TIMEOUT_SECONDS / COMPACTION_POLL_SECONDS))
    for ((attempt = 0; attempt < attempts; attempt++)); do
        if ! stats=$("$SOURCE_ROOT/bin/nodetool" compactionstats); then
            echo "could not read compaction state after enabling UCS" >&2
            return 1
        fi
        printf '%s\n' "$stats" | tee -a "$RUN_DIR/post-load-compactionstats.log"
        if [[ "$stats" =~ pending[[:space:]]+tasks[[:space:]]+0 ]]; then
            stable=$((stable + 1))
            if (( stable >= 3 )); then
                return 0
            fi
        else
            stable=0
        fi
        sleep "$COMPACTION_POLL_SECONDS"
    done
    echo "automatic UCS compactions did not drain within ${COMPACTION_DRAIN_TIMEOUT_SECONDS} seconds" >&2
    return 1
}

on_exit() {
    status=$?
    stop_processes
    if [[ "$RUN_SUCCEEDED" != true ]] && [[ -d "$RUN_DIR" ]]; then
        printf 'failed_at=%s\nexit_status=%s\n' "$(date --iso-8601=seconds)" "$status" >"$RUN_DIR/FAILED"
    fi
}
trap on_exit EXIT

mkdir -p "$RUN_DIR"/{classes,conf,data,sstables,logs}
ln -sfn "$RUN_DIR" "$EXPERIMENT_ROOT/latest-$RUN_TAG"
cp -a "$SOURCE_ROOT/conf/." "$RUN_DIR/conf/"
sed -i \
    -e "s|^cluster_name:.*|cluster_name: 'VComp ${DATASET_GIB}GiB $RUN_ID'|" \
    -e "s|^num_tokens:.*|num_tokens: 1|" \
    -e "s|^storage_compatibility_mode:.*|storage_compatibility_mode: NONE|" \
    -e "s|^concurrent_writes:.*|concurrent_writes: 48|" \
    -e "s|^# concurrent_compactors: 1|concurrent_compactors: 48|" \
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
export MAX_HEAP_SIZE=4G

{
    printf 'run_id=%s\n' "$RUN_ID"
    printf 'dataset_bytes=%s\n' "$DATASET_BYTES"
    printf 'dataset_gib=%s\n' "$DATASET_GIB"
    printf 'writes=%s\n' "$WRITES"
    printf 'entry_bytes=%s\n' "$ENTRY_BYTES"
    printf 'key_bytes=%s\n' "$KEY_BYTES"
    printf 'value_bytes=%s\n' "$VALUE_BYTES"
    printf 'flush_bytes=%s\n' "$FLUSH_BYTES"
    printf 'target_sst_bytes=%s\n' "$TARGET_SST_BYTES"
    printf 'sst_size_model=%s\n' "$VCOMP_SST_SIZE_MODEL"
    printf 'kmv_union_estimator=common_theta\n'
    printf 'output_policy=token_ordered_partition_boundaries\n'
    printf 'writes_per_flush=%s\n' "$((FLUSH_BYTES / ENTRY_BYTES))"
    printf 'partition_keys=%s\n' "$PARTITION_COUNT"
    printf 'compaction=UnifiedCompactionStrategy T4\n'
    printf 'compression=disabled\n'
    printf 'durable_writes=false\n'
    printf 'concurrent_compactors=48\n'
    printf 'vcomp_scheduler=immediate_descriptor_completion\n'
    printf 'ucs_picker_seed=%s\n' "$UCS_PICKER_SEED"
    printf 'compaction_drain_timeout_seconds=%s\n' "$COMPACTION_DRAIN_TIMEOUT_SECONDS"
    printf 'disk_device=%s\n' "$DISK_DEVICE"
    printf 'seed=%s\n' "$SEED"
    printf 'started_at=%s\n' "$(date --iso-8601=seconds)"
    printf 'load_metric_boundary=materialization_and_import_complete_before_compaction_reenable\n'
    printf 'post_load_compaction_io_included_in_load_metrics=false\n'
} >"$RUN_DIR/configuration.txt"
df -h /work >"$RUN_DIR/disk-before.txt"
free -h >"$RUN_DIR/memory-before.txt"

echo "[1/8] Building Cassandra and verification client"
source "$SOURCE_ROOT/build-env.sh"
ant -f "$SOURCE_ROOT/build.xml" build >"$RUN_DIR/build.log" 2>&1
"$JAVA_HOME/bin/javac" -cp "$SOURCE_ROOT/build/classes/main:$SOURCE_ROOT/lib/*" \
    -d "$RUN_DIR/classes" "$SCRIPT_DIR/src/CassandraVCompPipelineClient.java"

echo "[2/8] Starting isolated Cassandra 5.0.9 node for ${DATASET_GIB} GiB"
"$SOURCE_ROOT/bin/cassandra" -f >"$RUN_DIR/cassandra.stdout.log" 2>&1 &
SERVER_PID=$!
printf '%s\n' "$SERVER_PID" >"$RUN_DIR/cassandra.pid"
ready=false
for _ in $(seq 1 180); do
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

echo "[3/8] Creating ${PARTITION_COUNT}-partition ordered ${KEY_BYTES} B key / ${VALUE_BYTES} B value schema"
java_client schema "$KEYSPACE" "$TABLE" "$KEY_BYTES" "$WRITES" "$PARTITION_COUNT" "$TARGET_SST_BYTES"

echo "[4/8] Running ${DATASET_GIB} GiB virtual load and streaming materialization"
load_disk_write_start=$(disk_write_bytes)
(
    printf 'timestamp\toutput_bytes\tdb_bytes\tjava_rss_kib\n'
    while true; do
        output_bytes=$(du -sb "$RUN_DIR/sstables" 2>/dev/null | awk '{print $1}')
        db_bytes=$(du -sb "$RUN_DIR/data/data" 2>/dev/null | awk '{print $1}')
        java_rss=$(ps -eo rss,args | awk '/org[.]apache[.]cassandra[.]tools[.]VCompBulkLoad/ {sum += $1} END {print sum + 0}')
        printf '%s\t%s\t%s\t%s\n' "$(date --iso-8601=seconds)" "$output_bytes" "$db_bytes" "$java_rss"
        sleep 60
    done
) >"$RUN_DIR/progress.tsv" &
MONITOR_PID=$!

set +e
"$JAVA_HOME/bin/java" -Xms2G -Xmx12G @"$RUN_DIR/conf/jvm11-clients.options" \
    -Dcassandra.ucs.picker_seed="$UCS_PICKER_SEED" \
    -Dlogback.configurationFile="$SCRIPT_DIR/logback-smoke.xml" \
    -cp "$RUN_DIR/conf:$SOURCE_ROOT/build/classes/main:$SOURCE_ROOT/lib/*" \
    org.apache.cassandra.tools.VCompBulkLoad \
    "$RUN_DIR/sstables" "$SOURCE_ROOT/bin/nodetool" "$KEYSPACE" "$TABLE" \
    "$WRITES" "$ENTRY_BYTES" "$KEY_BYTES" "$VALUE_BYTES" "$FLUSH_BYTES" "$TARGET_SST_BYTES" "$PARTITION_COUNT" "$SEED" \
    "$VCOMP_SST_SIZE_MODEL" \
    2>&1 | tee "$RUN_DIR/vcomp.log"
load_status=${PIPESTATUS[0]}
set -e
kill "$MONITOR_PID" 2>/dev/null || true
wait "$MONITOR_PID" 2>/dev/null || true
MONITOR_PID=""
if [[ "$load_status" -ne 0 ]]; then
    printf 'failed_at=%s\nexit_status=%s\n' "$(date --iso-8601=seconds)" "$load_status" >"$RUN_DIR/FAILED"
    exit "$load_status"
fi
load_disk_write_end=$(disk_write_bytes)
printf 'disk_write_bytes=%s\n' "$((load_disk_write_end - load_disk_write_start))" >"$RUN_DIR/load_metrics.env"

echo "[5/8] Re-enabling UCS and draining any post-import automatic compactions"
# Loading metrics deliberately stop above: they measure VComp generation,
# materialization, and import, matching the existing VCOMP_RESULT timing. Any
# work triggered merely by returning the table to its normal post-load state
# is recorded separately and must not silently inflate the load measurement.
post_load_disk_write_start=$(disk_write_bytes)
java_client enable-compaction "$KEYSPACE" "$TABLE" "$TARGET_SST_BYTES" | tee "$RUN_DIR/compaction-enable.log"
# ALTER TABLE persists the setting; the nodetool call also makes the local
# node's runtime state explicit before we inspect/drain it.
"$SOURCE_ROOT/bin/nodetool" enableautocompaction "$KEYSPACE" "$TABLE" \
    | tee -a "$RUN_DIR/compaction-enable.log"
java_client assert-compaction-enabled "$KEYSPACE" "$TABLE" \
    | tee -a "$RUN_DIR/compaction-enable.log"
wait_for_compactions
java_client assert-compaction-enabled "$KEYSPACE" "$TABLE" \
    | tee -a "$RUN_DIR/compaction-enable.log"
post_load_disk_write_end=$(disk_write_bytes)
{
    printf 'disk_write_bytes=%s\n' "$((post_load_disk_write_end - post_load_disk_write_start))"
    printf 'included_in_load_metrics=false\n'
} >"$RUN_DIR/post_load_compaction_metrics.env"

echo "[6/8] Verifying imported rows through CQL"
java_client verify "$KEYSPACE" "$TABLE" "$KEY_BYTES" "$VALUE_BYTES" 1000 "$WRITES" "$PARTITION_COUNT" \
    | tee "$RUN_DIR/verification.log"
java_client fingerprint "$KEYSPACE" "$TABLE" "$KEY_BYTES" "$VALUE_BYTES" "$WRITES" "$PARTITION_COUNT" | tee "$RUN_DIR/fingerprint.log"

echo "[7/8] Collecting maximum SST and table metrics"
find "$RUN_DIR/sstables" -path '*/.vcomp-calibration' -prune -o \
    -name '*-Data.db' -printf '%s\t%p\n' \
    | sort -nr >"$RUN_DIR/sstable-sizes.tsv"
"$SOURCE_ROOT/bin/nodetool" tablestats "$KEYSPACE.$TABLE" >"$RUN_DIR/tablestats.txt"
"$SOURCE_ROOT/bin/nodetool" status "$KEYSPACE" >"$RUN_DIR/status.txt"
df -h /work >"$RUN_DIR/disk-after.txt"
free -h >"$RUN_DIR/memory-after.txt"
du -sh "$RUN_DIR" "$RUN_DIR/sstables" "$RUN_DIR/data" >"$RUN_DIR/directory-sizes.txt"
fingerprint_rows=$(awk -F '[ =]' '/^FINGERPRINT / { print $3 }' "$RUN_DIR/fingerprint.log")
fingerprint_sha256=$(awk -F '[ =]' '/^FINGERPRINT / { print $5 }' "$RUN_DIR/fingerprint.log")
if [[ -z "$fingerprint_rows" || -z "$fingerprint_sha256" ]]; then
    echo 'VComp fingerprint was not produced' >&2
    exit 1
fi
{
    cat "$RUN_DIR/load_metrics.env"
    printf 'fingerprint_rows=%s\n' "$fingerprint_rows"
    printf 'fingerprint_sha256=%s\n' "$fingerprint_sha256"
} >"$RUN_DIR/load_metrics.tmp"
mv "$RUN_DIR/load_metrics.tmp" "$RUN_DIR/load_metrics.env"

echo "[8/8] Completed; preserving DB, materialized SSTables, and logs"
printf 'completed_at=%s\n' "$(date --iso-8601=seconds)" >"$RUN_DIR/SUCCESS"
RUN_SUCCEEDED=true
echo "Preserved run directory: $RUN_DIR"
