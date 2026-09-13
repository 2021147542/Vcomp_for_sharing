#!/usr/bin/env bash
set -euo pipefail

# Run the paper-style YCSB A-F and MixGraph workloads against independent
# hard-linked checkpoints of the preserved 100 GiB baseline and VComp DBs.
# Immutable SSTables are shared with the canonical runs; all newly written
# SSTables, commitlogs, caches, and logs live below OUT_ROOT.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_ROOT="$SCRIPT_DIR/../cassandra_vcomp"
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
CANONICAL_ROOT=/work/vcomp-pebble-1tb/cassandra-vcomp-100g-audited-compare
BASELINE_SOURCE="${BASELINE_SOURCE:-$CANONICAL_ROOT/baseline/cassandra-baseline-100g-audited-common-theta-logical-baseline-20260912-135643}"
VCOMP_SOURCE="${VCOMP_SOURCE:-$CANONICAL_ROOT/vcomp/cassandra-vcomp-100g-audited-common-theta-logical-vcomp-20260912-154148}"
BASELINE_KEYSPACE="${BASELINE_KEYSPACE:-baseline_100g}"
VCOMP_KEYSPACE="${VCOMP_KEYSPACE:-vcomp_100g}"
OUT_ROOT="${OUT_ROOT:-/work/vcomp-pebble-1tb/cassandra-paper-workloads-100g-$(date +%Y%m%d-%H%M%S)}"
DURATION_SECONDS="${DURATION_SECONDS:-300}"
OPERATIONS_PER_THREAD="${OPERATIONS_PER_THREAD:-0}"
THREADS="${THREADS:-48}"
WORKLOADS="${WORKLOADS:-A B C D E F MIXGRAPH}"
KEY_SPACE="${KEY_SPACE:-104857600}"
KEY_BYTES="${KEY_BYTES:-24}"
VALUE_BYTES="${VALUE_BYTES:-1000}"
SEED="${SEED:-20260909}"
UCS_PICKER_SEED="${UCS_PICKER_SEED:-20260909}"
DISK_DEVICE="${DISK_DEVICE:-md0}"
PARTITION_COUNT="${PARTITION_COUNT:-$((((KEY_SPACE + 1048575) / 1048576) * 100))}"
MAX_HEAP_SIZE="${MAX_HEAP_SIZE:-4G}"
SERVER_PID=""
ACTIVE_RUN=""
CAMPAIGN_SUCCEEDED=false
BASE_JVM_OPTS="${JVM_OPTS:-}"

stop_server() {
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        "$SOURCE_ROOT/bin/nodetool" stopdaemon >/dev/null 2>&1 || kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    SERVER_PID=""
}

on_exit() {
    local status=$?
    stop_server
    if [[ "$CAMPAIGN_SUCCEEDED" != true ]] && [[ -d "$OUT_ROOT" ]]; then
        printf 'failed_at=%s\nexit_status=%s\nactive_run=%s\n' \
            "$(date --iso-8601=seconds)" "$status" "$ACTIVE_RUN" >"$OUT_ROOT/FAILED"
    fi
}
trap on_exit EXIT

for source in "$BASELINE_SOURCE" "$VCOMP_SOURCE"; do
    if [[ ! -f "$source/SUCCESS" || ! -d "$source/data/data" ]]; then
        echo "canonical successful run is missing: $source" >&2
        exit 2
    fi
done
if pgrep -f 'org[.]apache[.]cassandra[.]service[.]CassandraDaemon' >/dev/null; then
    echo 'another Cassandra daemon is already running; refusing to overlap benchmarks' >&2
    exit 2
fi
if [[ ! "$DURATION_SECONDS" =~ ^[1-9][0-9]*$ || ! "$THREADS" =~ ^[1-9][0-9]*$ \
      || ! "$OPERATIONS_PER_THREAD" =~ ^[0-9]+$ ]]; then
    echo 'DURATION_SECONDS and THREADS must be positive integers; OPERATIONS_PER_THREAD must be non-negative' >&2
    exit 2
fi

mkdir -p "$OUT_ROOT"/{classes,results,runs}
{
    printf 'baseline_source=%s\n' "$BASELINE_SOURCE"
    printf 'vcomp_source=%s\n' "$VCOMP_SOURCE"
    printf 'duration_seconds=%s\noperations_per_thread=%s\nthreads=%s\nworkloads=%s\n' \
        "$DURATION_SECONDS" "$OPERATIONS_PER_THREAD" "$THREADS" "$WORKLOADS"
    printf 'key_space=%s\nkey_bytes=%s\nvalue_bytes=%s\npartition_count=%s\nseed=%s\nucs_picker_seed=%s\n' "$KEY_SPACE" "$KEY_BYTES" "$VALUE_BYTES" "$PARTITION_COUNT" "$SEED" "$UCS_PICKER_SEED"
    printf 'disk_device=%s\ncheckpoint_method=hardlink_immutable_sstables\n' "$DISK_DEVICE"
    printf 'cache_start=scoped_posix_fadvise_dontneed\nstarted_at=%s\n' "$(date --iso-8601=seconds)"
} >"$OUT_ROOT/configuration.txt"

source "$SOURCE_ROOT/build-env.sh"
ant -f "$SOURCE_ROOT/build.xml" build >"$OUT_ROOT/build.log" 2>&1
"$JAVA_HOME/bin/javac" -cp "$SOURCE_ROOT/build/classes/main:$SOURCE_ROOT/lib/*" \
    -d "$OUT_ROOT/classes" "$SCRIPT_DIR/src/CassandraVCompPipelineClient.java" \
    "$SCRIPT_DIR/src/CassandraPaperWorkload.java"

java_client() {
    "$JAVA_HOME/bin/java" @"$ACTIVE_RUN/conf/jvm11-clients.options" \
        -Dlogback.configurationFile="$SCRIPT_DIR/logback-smoke.xml" \
        -cp "$OUT_ROOT/classes:$SOURCE_ROOT/build/classes/main:$SOURCE_ROOT/lib/*" \
        CassandraVCompPipelineClient "$@"
}

evict_checkpoint_cache() {
    python3 - "$ACTIVE_RUN/data/data" <<'PY'
import os
import sys

root = sys.argv[1]
advice = getattr(os, "POSIX_FADV_DONTNEED", 4)
for directory, _, names in os.walk(root):
    for name in names:
        path = os.path.join(directory, name)
        try:
            fd = os.open(path, os.O_RDONLY)
            try:
                os.posix_fadvise(fd, 0, 0, advice)
            finally:
                os.close(fd)
        except (OSError, AttributeError):
            pass
PY
}

prepare_checkpoint() {
    local source=$1
    ACTIVE_RUN=$2
    mkdir -p "$ACTIVE_RUN"/{conf,data/data,data/commitlog,data/hints,data/saved_caches,logs}
    cp -a "$source/conf/." "$ACTIVE_RUN/conf/"
    cp -al "$source/data/data/." "$ACTIVE_RUN/data/data/"
    sed -i \
        -e "s|^hints_directory:.*|hints_directory: $ACTIVE_RUN/data/hints|" \
        -e "/^data_file_directories:/{n;s|^    - .*|    - $ACTIVE_RUN/data/data|;}" \
        -e "s|^commitlog_directory:.*|commitlog_directory: $ACTIVE_RUN/data/commitlog|" \
        -e "s|^saved_caches_directory:.*|saved_caches_directory: $ACTIVE_RUN/data/saved_caches|" \
        "$ACTIVE_RUN/conf/cassandra.yaml"
}

run_one() {
    local system=$1
    local workload=$2
    local source keyspace
    if [[ "$system" == baseline ]]; then
        source="$BASELINE_SOURCE"
        keyspace="$BASELINE_KEYSPACE"
    else
        source="$VCOMP_SOURCE"
        keyspace="$VCOMP_KEYSPACE"
    fi
    local lower_workload=${workload,,}
    local result="$OUT_ROOT/results/${lower_workload}_${system}.json"
    ACTIVE_RUN="$OUT_ROOT/runs/$lower_workload/$system"
    echo "[$(date --iso-8601=seconds)] Preparing $workload $system checkpoint"
    prepare_checkpoint "$source" "$ACTIVE_RUN"
    evict_checkpoint_cache

    export JAVA_HOME CASSANDRA_HOME="$SOURCE_ROOT" CASSANDRA_CONF="$ACTIVE_RUN/conf" CASSANDRA_LOG_DIR="$ACTIVE_RUN/logs"
    export CASSANDRA_LIBJEMALLOC=- MAX_HEAP_SIZE
    export JVM_OPTS="$BASE_JVM_OPTS -Dcassandra.ucs.picker_seed=$UCS_PICKER_SEED"
    "$SOURCE_ROOT/bin/cassandra" -f >"$ACTIVE_RUN/cassandra.stdout.log" 2>&1 &
    SERVER_PID=$!
    printf '%s\n' "$SERVER_PID" >"$ACTIVE_RUN/cassandra.pid"
    local ready=false
    for _ in $(seq 1 180); do
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            echo "Cassandra exited during startup: $ACTIVE_RUN" >&2
            return 1
        fi
        if java_client wait >/dev/null 2>&1; then
            ready=true
            break
        fi
        sleep 1
    done
    if [[ "$ready" != true ]]; then
        echo "Cassandra did not become ready: $ACTIVE_RUN" >&2
        return 1
    fi

    "$SOURCE_ROOT/bin/nodetool" tablestats "$keyspace.kv" >"$ACTIVE_RUN/tablestats-before.txt"
    "$SOURCE_ROOT/bin/nodetool" compactionstats >"$ACTIVE_RUN/compactionstats-before.txt"
    local workload_limit=()
    local execution_label="${DURATION_SECONDS}s"
    if (( OPERATIONS_PER_THREAD > 0 )); then
        workload_limit=("$OPERATIONS_PER_THREAD")
        execution_label="${OPERATIONS_PER_THREAD} operations/thread"
    fi
    echo "[$(date --iso-8601=seconds)] Running $workload $system (${THREADS} threads, ${execution_label})"
    set +e
    "$JAVA_HOME/bin/java" -Xms1G -Xmx2G @"$ACTIVE_RUN/conf/jvm11-clients.options" \
        -Dlogback.configurationFile="$SCRIPT_DIR/logback-smoke.xml" \
        -cp "$OUT_ROOT/classes:$SOURCE_ROOT/build/classes/main:$SOURCE_ROOT/lib/*" \
        CassandraPaperWorkload "$keyspace" kv "$workload" "$DURATION_SECONDS" "$THREADS" \
        "$KEY_SPACE" "$KEY_BYTES" "$VALUE_BYTES" "$PARTITION_COUNT" "$SEED" "$DISK_DEVICE" \
        "${workload_limit[@]}" \
        2>&1 | tee "$ACTIVE_RUN/workload.log"
    local workload_status=${PIPESTATUS[0]}
    set -e
    if [[ "$workload_status" -ne 0 ]]; then
        return "$workload_status"
    fi
    sed -n 's/^WORKLOAD_RESULT //p' "$ACTIVE_RUN/workload.log" | tail -n 1 >"$result"
    if [[ ! -s "$result" ]]; then
        echo "workload result was not produced: $ACTIVE_RUN" >&2
        return 1
    fi
    "$SOURCE_ROOT/bin/nodetool" tablestats "$keyspace.kv" >"$ACTIVE_RUN/tablestats-after.txt"
    "$SOURCE_ROOT/bin/nodetool" compactionstats >"$ACTIVE_RUN/compactionstats-after.txt"
    printf 'completed_at=%s\n' "$(date --iso-8601=seconds)" >"$ACTIVE_RUN/SUCCESS"
    stop_server
    sync
}

for workload in $WORKLOADS; do
    run_one baseline "$workload"
    run_one vcomp "$workload"
done

printf 'completed_at=%s\n' "$(date --iso-8601=seconds)" >"$OUT_ROOT/SUCCESS"
rm -f "$OUT_ROOT/FAILED"
CAMPAIGN_SUCCEEDED=true
echo "Completed Cassandra paper workloads: $OUT_ROOT"
