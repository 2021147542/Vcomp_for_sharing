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
# Cassandra reserves 32 MiB of file_cache_size for buffer pooling. The remaining
# native chunk cache is 5% of logical input, rounded down to a whole MiB.
CHUNK_CACHE_MIB=$((KEY_SPACE * (KEY_BYTES + VALUE_BYTES) / 20 / 1048576))
FILE_CACHE_MIB=$((CHUNK_CACHE_MIB + 32))
SERVER_PID=""
ACTIVE_RUN=""
CAMPAIGN_SUCCEEDED=false
OUTPUT_CREATED=false
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
    if [[ "$CAMPAIGN_SUCCEEDED" != true && "$OUTPUT_CREATED" == true ]] && [[ -d "$OUT_ROOT" ]]; then
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

if [[ -e "$OUT_ROOT" || -L "$OUT_ROOT" ]]; then
    echo "Output path already exists: $OUT_ROOT" >&2
    exit 2
fi
if ss -H -ltn | awk '{print $4}' | rg -q ':(7000|7001|7199|9042)$'; then
    echo 'A Cassandra listener already exists; refusing to overlap' >&2
    exit 2
fi
# This runner's cache protocol requires the bounded wrapper even when used
# outside a campaign. Verify the kernel limits, not an environment assertion.
python3 - <<'PYCGROUP'
from pathlib import Path
membership = Path('/proc/self/cgroup').read_text().splitlines()
relative = next(line[3:] for line in membership if line.startswith('0::'))
group = Path('/sys/fs/cgroup') / relative.lstrip('/')
if (group / 'memory.max').read_text().strip() != str(20 * 1024 ** 3) or (group / 'memory.swap.max').read_text().strip() != '0':
    raise SystemExit('Use experiments/scripts/cassandra/run_bounded_workloads.sh: requires 20 GiB memory and zero swap for daemon + clients')
PYCGROUP
mkdir -p "$OUT_ROOT"/{classes,results,runs}
OUTPUT_CREATED=true
{
    printf 'baseline_source=%s\n' "$BASELINE_SOURCE"
    printf 'vcomp_source=%s\n' "$VCOMP_SOURCE"
    printf 'duration_seconds=%s\noperations_per_thread=%s\nthreads=%s\nworkloads=%s\n' \
        "$DURATION_SECONDS" "$OPERATIONS_PER_THREAD" "$THREADS" "$WORKLOADS"
    printf 'key_space=%s\nkey_bytes=%s\nvalue_bytes=%s\npartition_count=%s\nseed=%s\nucs_picker_seed=%s\n' "$KEY_SPACE" "$KEY_BYTES" "$VALUE_BYTES" "$PARTITION_COUNT" "$SEED" "$UCS_PICKER_SEED"
    printf 'disk_device=%s\ncheckpoint_method=hardlink_immutable_sstables\n' "$DISK_DEVICE"
    printf 'cache_protocol=native_chunkcache_5pct_bounded_process_group\nchunk_cache_mib=%s\nfile_cache_mib=%s\n' "$CHUNK_CACHE_MIB" "$FILE_CACHE_MIB"
    printf 'cache_equivalence=not_paper_equivalent_additional_OS_page_cache\ndisk_access_mode=standard\n'
    printf 'compaction_throughput_mib_per_second=0\nmemtable_heap_mib=1024\nmemtable_cleanup_threshold=0.0625\nmemtable_flush_writers=2\nnominal_nonflushing_memtable_heap_trigger_mib=64\n'
    printf 'cache_start=scoped_posix_fadvise_dontneed\nstarted_at=%s\n' "$(date --iso-8601=seconds)"
} >"$OUT_ROOT/configuration.txt"

source "$SOURCE_ROOT/build-env.sh"
if [[ "${SKIP_CASSANDRA_BUILD:-false}" != true ]]; then
    ant -f "$SOURCE_ROOT/build.xml" jar >"$OUT_ROOT/build.log" 2>&1
fi
RUNTIME_JAR=$(find "$SOURCE_ROOT/build" -maxdepth 1 -name 'apache-cassandra-*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)
[[ -n "$RUNTIME_JAR" ]] || { echo 'Missing qualified Cassandra runtime JAR' >&2; exit 2; }
sha256sum "$RUNTIME_JAR" >"$OUT_ROOT/runtime-jar.sha256"
if [[ -n "${EXPECTED_RUNTIME_JAR_SHA256:-}" ]]; then
    sha256sum --check --status "$EXPECTED_RUNTIME_JAR_SHA256" || { echo 'Runtime JAR identity mismatch' >&2; exit 2; }
fi
"$JAVA_HOME/bin/javac" -cp "$RUNTIME_JAR:$SOURCE_ROOT/lib/*" \
    -d "$OUT_ROOT/classes" "$SCRIPT_DIR/src/CassandraVCompPipelineClient.java" \
    "$SCRIPT_DIR/src/CassandraPaperWorkload.java" "$SCRIPT_DIR/src/CassandraRuntimeMetrics.java"

java_client() {
    "$JAVA_HOME/bin/java" @"$ACTIVE_RUN/conf/jvm11-clients.options" \
        -Dlogback.configurationFile="$SCRIPT_DIR/logback-smoke.xml" \
        -cp "$OUT_ROOT/classes:$RUNTIME_JAR:$SOURCE_ROOT/lib/*" \
        CassandraVCompPipelineClient "$@"
}

runtime_metrics() {
    "$JAVA_HOME/bin/java" @"$ACTIVE_RUN/conf/jvm11-clients.options" \
        -Dlogback.configurationFile="$SCRIPT_DIR/logback-smoke.xml" \
        -cp "$OUT_ROOT/classes:$RUNTIME_JAR:$SOURCE_ROOT/lib/*" \
        CassandraRuntimeMetrics "$((CHUNK_CACHE_MIB * 1048576))"
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
        fd = os.open(path, os.O_RDONLY)
        try:
            os.posix_fadvise(fd, 0, 0, advice)
        finally:
            os.close(fd)
PY
}

prepare_checkpoint() {
    local source=$1
    ACTIVE_RUN=$2
    mkdir -p "$ACTIVE_RUN"/{conf,data/data,data/commitlog,data/hints,data/saved_caches,logs}
    cp -a "$source/conf/." "$ACTIVE_RUN/conf/"
    # SSTables are immutable in this no-repair campaign. Copy system keyspaces
    # separately so metadata writes cannot modify a canonical checkpoint inode.
    for directory in "$source/data/data/"*; do
        [[ -d "$directory" ]] || continue
        if [[ "$(basename "$directory")" == system* ]]; then
            cp -a --reflink=auto "$directory" "$ACTIVE_RUN/data/data/"
        else
            cp -al "$directory" "$ACTIVE_RUN/data/data/"
        fi
    done
    sed -i \
        -e "s|^hints_directory:.*|hints_directory: $ACTIVE_RUN/data/hints|" \
        -e "/^data_file_directories:/{n;s|^    - .*|    - $ACTIVE_RUN/data/data|;}" \
        -e "s|^commitlog_directory:.*|commitlog_directory: $ACTIVE_RUN/data/commitlog|" \
        -e "s|^saved_caches_directory:.*|saved_caches_directory: $ACTIVE_RUN/data/saved_caches|" \
        "$ACTIVE_RUN/conf/cassandra.yaml"
    # Remove any prior assignments (including inherited overrides), then set
    # explicit symmetric chunk cache controls. This does not disable OS cache.
    sed -i -E '/^[[:space:]]*(disk_access_mode|file_cache_enabled|file_cache_size):/d' "$ACTIVE_RUN/conf/cassandra.yaml"
    printf '\ndisk_access_mode: standard\nfile_cache_enabled: true\nfile_cache_size: %sMiB\n' \
        "$FILE_CACHE_MIB" >>"$ACTIVE_RUN/conf/cassandra.yaml"
    # The local RocksDB reference has no rate limiter and a 64 MiB write buffer.
    # C* triggers on allocated heap (including object overhead), not logical KV
    # bytes. Keep a 1 GiB pool and two native flush writers; document this mapping.
    sed -i -E '/^[[:space:]]*(compaction_throughput|memtable_heap_space|memtable_offheap_space|memtable_cleanup_threshold|memtable_flush_writers|memtable_allocation_type):/d' "$ACTIVE_RUN/conf/cassandra.yaml"
    printf '\ncompaction_throughput: 0MiB/s\nmemtable_heap_space: 1024MiB\nmemtable_offheap_space: 1024MiB\nmemtable_cleanup_threshold: 0.0625\nmemtable_flush_writers: 2\nmemtable_allocation_type: heap_buffers\n' \
        >>"$ACTIVE_RUN/conf/cassandra.yaml"
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

    java_client assert-compaction-enabled "$keyspace" kv >"$ACTIVE_RUN/compaction-enabled.txt"
    "$SOURCE_ROOT/bin/nodetool" statusautocompaction "$keyspace" kv >"$ACTIVE_RUN/compaction-runtime.txt"
    if ! rg -qx 'running' "$ACTIVE_RUN/compaction-runtime.txt"; then
        echo "Runtime auto compaction is not enabled: $ACTIVE_RUN" >&2
        return 1
    fi
    "$SOURCE_ROOT/bin/nodetool" tablestats "$keyspace.kv" >"$ACTIVE_RUN/tablestats-before.txt"
    "$SOURCE_ROOT/bin/nodetool" compactionstats >"$ACTIVE_RUN/compactionstats-before.txt"
    "$SOURCE_ROOT/bin/nodetool" tablehistograms "$keyspace" kv >"$ACTIVE_RUN/tablehistograms-before.txt"
    "$SOURCE_ROOT/bin/nodetool" info >"$ACTIVE_RUN/node-info-before.txt"
    rg -q '^Chunk Cache[[:space:]]*:' "$ACTIVE_RUN/node-info-before.txt" \
        || { echo 'Configured native chunk cache is not active' >&2; return 1; }
    cat "/proc/$SERVER_PID/io" >"$ACTIVE_RUN/daemon-io-before.txt"
    runtime_metrics >"$ACTIVE_RUN/runtime-metrics-before.env"
    local workload_limit=()
    local execution_label="${DURATION_SECONDS}s"
    if (( OPERATIONS_PER_THREAD > 0 )); then
        workload_limit=("$OPERATIONS_PER_THREAD")
        execution_label="${OPERATIONS_PER_THREAD} operations/thread"
    fi
    echo "[$(date --iso-8601=seconds)] Running $workload $system (${THREADS} threads, ${execution_label})"
    local live_workload_log
    live_workload_log=$(mktemp /tmp/vcomp-cassandra-workload.XXXXXX.log)
    if [[ "$(stat -c %d "$live_workload_log")" == "$(stat -c %d "$ACTIVE_RUN")" ]]; then
        echo 'Client diagnostics must use a filesystem outside the measured DB device' >&2
        return 1
    fi
    printf '%s\n' "$live_workload_log" >"$ACTIVE_RUN/workload-live-path.txt"
    # Flush checkpoint/configuration writes before device counters start. Client
    # progress and memory samples go to the system filesystem, not measured md0.
    sync -f "$ACTIVE_RUN"
    set +e
    "$JAVA_HOME/bin/java" -Xms1G -Xmx2G @"$ACTIVE_RUN/conf/jvm11-clients.options" \
        -Dlogback.configurationFile="$SCRIPT_DIR/logback-smoke.xml" \
        -cp "$OUT_ROOT/classes:$RUNTIME_JAR:$SOURCE_ROOT/lib/*" \
        CassandraPaperWorkload "$keyspace" kv "$workload" "$DURATION_SECONDS" "$THREADS" \
        "$KEY_SPACE" "$KEY_BYTES" "$VALUE_BYTES" "$PARTITION_COUNT" "$SEED" "$DISK_DEVICE" \
        "${workload_limit[@]}" >"$live_workload_log" 2>&1
    local workload_status=$?
    set -e
    cp "$live_workload_log" "$ACTIVE_RUN/workload.log"
    cat "$ACTIVE_RUN/workload.log"
    if [[ "$workload_status" -ne 0 ]]; then
        return "$workload_status"
    fi
    sed -n 's/^WORKLOAD_RESULT //p' "$ACTIVE_RUN/workload.log" | tail -n 1 >"$result"
    if [[ ! -s "$result" ]]; then
        echo "workload result was not produced: $ACTIVE_RUN" >&2
        return 1
    fi
    cat "/proc/$SERVER_PID/io" >"$ACTIVE_RUN/daemon-io-after.txt"
    runtime_metrics >"$ACTIVE_RUN/runtime-metrics-after.env"
    "$SOURCE_ROOT/bin/nodetool" info >"$ACTIVE_RUN/node-info-after.txt"
    "$SOURCE_ROOT/bin/nodetool" tablehistograms "$keyspace" kv >"$ACTIVE_RUN/tablehistograms-after.txt"
    "$SOURCE_ROOT/bin/nodetool" tablestats "$keyspace.kv" >"$ACTIVE_RUN/tablestats-after.txt"
    "$SOURCE_ROOT/bin/nodetool" compactionstats >"$ACTIVE_RUN/compactionstats-after.txt"
    sha256sum --check --status "$OUT_ROOT/runtime-jar.sha256" || return 1
    printf 'completed_at=%s\n' "$(date --iso-8601=seconds)" >"$ACTIVE_RUN/SUCCESS"
    stop_server
    sync -f "$ACTIVE_RUN"
}

for workload in $WORKLOADS; do
    run_one baseline "$workload"
    run_one vcomp "$workload"
done

printf 'completed_at=%s\n' "$(date --iso-8601=seconds)" >"$OUT_ROOT/SUCCESS"
rm -f "$OUT_ROOT/FAILED"
CAMPAIGN_SUCCEEDED=true
echo "Completed Cassandra paper workloads: $OUT_ROOT"
