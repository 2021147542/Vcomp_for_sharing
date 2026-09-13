#!/usr/bin/env bash
set -euo pipefail

# Fresh 1 TiB ordered-partition scale campaign.  The native and VComp loads
# run serially, followed by equal-operation A-F/MixGraph measurements against
# hard-linked checkpoints of the exact retained databases.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/.."
RUN_STAMP="${RUN_STAMP:-$(date +%Y%m%d-%H%M%S)}"
CAMPAIGN_ROOT="${CAMPAIGN_ROOT:-/work/vcomp-pebble-1tb/cassandra-vcomp-1tib-fixedtrace-q1-$RUN_STAMP}"
RESULT_ROOT="${RESULT_ROOT:-$REPO_ROOT/resources/experiments/${RUN_STAMP}_cassandra_1tib_fixed_operations_q1}"
DATASET_GIB=1024
KEY_SPACE=1073741824
PARTITION_COUNT=102400
OPERATIONS_PER_THREAD=20000
THREADS=48
UCS_PICKER_SEED=20260909
BASELINE_KEYSPACE=baseline_1tib
VCOMP_KEYSPACE=vcomp_1tib
CAMPAIGN_SUCCEEDED=false

mkdir -p "$CAMPAIGN_ROOT" "$RESULT_ROOT"
exec > >(tee -a "$CAMPAIGN_ROOT/campaign.log") 2>&1

on_exit() {
    local status=$?
    if [[ "$CAMPAIGN_SUCCEEDED" != true ]]; then
        printf 'failed_at=%s\nexit_status=%s\nraw_root=%s\n' \
            "$(date --iso-8601=seconds)" "$status" "$CAMPAIGN_ROOT" \
            >"$RESULT_ROOT/FAILED"
    fi
}
trap on_exit EXIT

if pgrep -f 'org[.]apache[.]cassandra[.]service[.]CassandraDaemon' >/dev/null; then
    echo 'another Cassandra daemon is already running; refusing to overlap benchmarks' >&2
    exit 2
fi

{
    printf 'status=running\n'
    printf 'raw_root=%s\n' "$CAMPAIGN_ROOT"
    printf 'dataset_gib=%s\nkey_space=%s\npartition_count=%s\n' \
        "$DATASET_GIB" "$KEY_SPACE" "$PARTITION_COUNT"
    printf 'key_bytes=24\nvalue_bytes=1000\n'
    printf 'operations_per_thread=%s\nthreads=%s\n' "$OPERATIONS_PER_THREAD" "$THREADS"
    printf 'workloads=A B C D E F MIXGRAPH\nucs_picker_seed=%s\n' "$UCS_PICKER_SEED"
    printf 'started_at=%s\n' "$(date --iso-8601=seconds)"
} >"$RESULT_ROOT/campaign.env"
df -h /work >"$RESULT_ROOT/disk-before.txt"

echo '[campaign 1/3] Running fresh native baseline and VComp 1 TiB loads'
env \
    DATASET_GIB="$DATASET_GIB" \
    PARTITION_COUNT="$PARTITION_COUNT" \
    EXPERIMENT_ROOT="$CAMPAIGN_ROOT" \
    RUN_TAG_PREFIX=1tib-fixedtrace-q1 \
    BASELINE_KEYSPACE="$BASELINE_KEYSPACE" \
    VCOMP_KEYSPACE="$VCOMP_KEYSPACE" \
    UCS_PICKER_SEED="$UCS_PICKER_SEED" \
    COMPACTION_DRAIN_TIMEOUT_SECONDS=86400 \
    bash "$SCRIPT_DIR/run_baseline_vcomp_20g_compare.sh"

BASELINE_SOURCE=$(readlink -f "$CAMPAIGN_ROOT/baseline/latest-1tib-fixedtrace-q1-baseline")
VCOMP_SOURCE=$(readlink -f "$CAMPAIGN_ROOT/vcomp/latest-1tib-fixedtrace-q1-vcomp")
if [[ ! -f "$BASELINE_SOURCE/SUCCESS" || ! -f "$VCOMP_SOURCE/SUCCESS" ]]; then
    echo 'paired load did not produce successful retained databases' >&2
    exit 1
fi

echo '[campaign 2/3] Running fixed-operation A-F and MixGraph'
env \
    BASELINE_SOURCE="$BASELINE_SOURCE" \
    VCOMP_SOURCE="$VCOMP_SOURCE" \
    BASELINE_KEYSPACE="$BASELINE_KEYSPACE" \
    VCOMP_KEYSPACE="$VCOMP_KEYSPACE" \
    OUT_ROOT="$CAMPAIGN_ROOT/workloads-fixed-20k" \
    KEY_SPACE="$KEY_SPACE" \
    PARTITION_COUNT="$PARTITION_COUNT" \
    OPERATIONS_PER_THREAD="$OPERATIONS_PER_THREAD" \
    THREADS="$THREADS" \
    WORKLOADS='A B C D E F MIXGRAPH' \
    UCS_PICKER_SEED="$UCS_PICKER_SEED" \
    bash "$SCRIPT_DIR/run_existing_100g_paper_workloads.sh"

echo '[campaign 3/3] Publishing small results and paper-style figures'
cp -a "$CAMPAIGN_ROOT/workloads-fixed-20k/results" "$RESULT_ROOT/"
cp -a "$CAMPAIGN_ROOT/workloads-fixed-20k/configuration.txt" "$RESULT_ROOT/"
cp -a "$BASELINE_SOURCE/baseline_metrics.env" "$RESULT_ROOT/baseline_metrics.env"
cp -a "$VCOMP_SOURCE/load_metrics.env" "$RESULT_ROOT/vcomp_metrics.env"
cp -a "$VCOMP_SOURCE/configuration.txt" "$RESULT_ROOT/vcomp_configuration.txt"
cp -a "$CAMPAIGN_ROOT/runtime-jar.sha256" "$RESULT_ROOT/runtime-jar.sha256"
cp -a "$CAMPAIGN_ROOT/figures/cassandra_baseline_vcomp_1024g.svg" "$RESULT_ROOT/cassandra_loading.svg"
cp -a "$CAMPAIGN_ROOT/figures/cassandra_baseline_vcomp_1024g.png" "$RESULT_ROOT/cassandra_loading.png"
python3 "$REPO_ROOT/resources/plot_cassandra_paper_workloads.py" "$RESULT_ROOT"

{
    printf 'status=complete\n'
    printf 'raw_root=%s\nbaseline_source=%s\nvcomp_source=%s\n' \
        "$CAMPAIGN_ROOT" "$BASELINE_SOURCE" "$VCOMP_SOURCE"
    printf 'dataset_gib=%s\nkey_space=%s\npartition_count=%s\n' \
        "$DATASET_GIB" "$KEY_SPACE" "$PARTITION_COUNT"
    printf 'operations_per_thread=%s\nthreads=%s\n' "$OPERATIONS_PER_THREAD" "$THREADS"
    printf 'completed_at=%s\n' "$(date --iso-8601=seconds)"
} >"$RESULT_ROOT/campaign.env"
df -h /work >"$RESULT_ROOT/disk-after.txt"
touch "$RESULT_ROOT/COMPLETE"
CAMPAIGN_SUCCEEDED=true
echo "Completed Cassandra 1 TiB campaign: $CAMPAIGN_ROOT"

