#!/usr/bin/env bash
set -euo pipefail

# Build one Cassandra jar, then run a fresh 100 GiB single-partition baseline,
# the matching VComp load, and YCSB A-F plus MixGraph against those exact DBs.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_ROOT="$SCRIPT_DIR/../cassandra_vcomp"
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
CAMPAIGN_ID="cassandra-100g-single-partition-$(date +%Y%m%d-%H%M%S)"
CAMPAIGN_ROOT="${CAMPAIGN_ROOT:-/work/vcomp-pebble-1tb/$CAMPAIGN_ID}"
BASELINE_TAG=100g-single-partition-baseline
VCOMP_TAG=100g-single-partition-vcomp
BASELINE_KEYSPACE=baseline_100g_single_partition
VCOMP_KEYSPACE=vcomp_100g_single_partition
UCS_PICKER_SEED="${UCS_PICKER_SEED:-20260909}"
CAMPAIGN_SUCCEEDED=false

mkdir -p "$CAMPAIGN_ROOT"
exec > >(tee -a "$CAMPAIGN_ROOT/campaign.log") 2>&1

on_exit() {
    local status=$?
    if [[ "$CAMPAIGN_SUCCEEDED" != true ]]; then
        printf 'failed_at=%s\nexit_status=%s\n' "$(date --iso-8601=seconds)" "$status" \
            >"$CAMPAIGN_ROOT/FAILED"
    fi
}
trap on_exit EXIT

if pgrep -f 'org[.]apache[.]cassandra[.]service[.]CassandraDaemon' >/dev/null; then
    echo 'another Cassandra daemon is already running; refusing to overlap benchmarks' >&2
    exit 2
fi

{
    printf 'campaign_id=%s\n' "$CAMPAIGN_ID"
    printf 'dataset_gib=100\npartition_count=1\n'
    printf 'key_bytes=24\nvalue_bytes=1000\n'
    printf 'baseline_keyspace=%s\nvcomp_keyspace=%s\n' "$BASELINE_KEYSPACE" "$VCOMP_KEYSPACE"
    printf 'ucs_picker_seed=%s\n' "$UCS_PICKER_SEED"
    printf 'workloads=A B C D E F MIXGRAPH\n'
    printf 'workload_duration_seconds=300\nworkload_threads=48\n'
    printf 'started_at=%s\n' "$(date --iso-8601=seconds)"
} >"$CAMPAIGN_ROOT/configuration.txt"

echo '[campaign 1/4] Building the Cassandra runtime jar'
export JAVA_HOME
source "$SOURCE_ROOT/build-env.sh"
ant -f "$SOURCE_ROOT/build.xml" jar >"$CAMPAIGN_ROOT/build-jar.log" 2>&1
RUNTIME_JAR=$(find "$SOURCE_ROOT/build" -maxdepth 1 -name 'apache-cassandra-*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)
if [[ -z "$RUNTIME_JAR" ]]; then
    echo 'Cassandra runtime jar was not produced' >&2
    exit 1
fi
sha256sum "$RUNTIME_JAR" >"$CAMPAIGN_ROOT/runtime-jar.sha256"
"$JAVA_HOME/bin/javap" -classpath "$RUNTIME_JAR" -c -private \
    org.apache.cassandra.db.compaction.unified.Controller \
    >"$CAMPAIGN_ROOT/controller-runtime.javap"
if ! rg -q 'cassandra.ucs.picker_seed' "$CAMPAIGN_ROOT/controller-runtime.javap"; then
    echo 'runtime jar does not contain the requested seeded UCS Controller' >&2
    exit 1
fi

echo '[campaign 2/4] Running the native Cassandra baseline load'
env \
    DATASET_GIB=100 \
    PARTITION_COUNT=1 \
    KEY_BYTES=24 \
    VALUE_BYTES=1000 \
    RUN_TAG="$BASELINE_TAG" \
    KEYSPACE="$BASELINE_KEYSPACE" \
    UCS_PICKER_SEED="$UCS_PICKER_SEED" \
    EXPERIMENT_ROOT="$CAMPAIGN_ROOT/baseline" \
    bash "$SCRIPT_DIR/run_baseline_20g.sh"
BASELINE_SOURCE=$(readlink -f "$CAMPAIGN_ROOT/baseline/latest-$BASELINE_TAG")

echo '[campaign 3/4] Running the matching VComp load'
env \
    DATASET_GIB=100 \
    PARTITION_COUNT=1 \
    KEY_BYTES=24 \
    VALUE_BYTES=1000 \
    RUN_TAG="$VCOMP_TAG" \
    KEYSPACE="$VCOMP_KEYSPACE" \
    UCS_PICKER_SEED="$UCS_PICKER_SEED" \
    EXPERIMENT_ROOT="$CAMPAIGN_ROOT/vcomp" \
    bash "$SCRIPT_DIR/run_pipeline_100g.sh"
VCOMP_SOURCE=$(readlink -f "$CAMPAIGN_ROOT/vcomp/latest-$VCOMP_TAG")

{
    printf 'baseline_source=%s\n' "$BASELINE_SOURCE"
    printf 'vcomp_source=%s\n' "$VCOMP_SOURCE"
} >"$CAMPAIGN_ROOT/load-sources.env"

echo '[campaign 4/4] Running YCSB A-F and MixGraph'
env \
    BASELINE_SOURCE="$BASELINE_SOURCE" \
    VCOMP_SOURCE="$VCOMP_SOURCE" \
    BASELINE_KEYSPACE="$BASELINE_KEYSPACE" \
    VCOMP_KEYSPACE="$VCOMP_KEYSPACE" \
    OUT_ROOT="$CAMPAIGN_ROOT/workloads" \
    PARTITION_COUNT=1 \
    KEY_SPACE=104857600 \
    KEY_BYTES=24 \
    VALUE_BYTES=1000 \
    DURATION_SECONDS=300 \
    THREADS=48 \
    WORKLOADS='A B C D E F MIXGRAPH' \
    UCS_PICKER_SEED="$UCS_PICKER_SEED" \
    bash "$SCRIPT_DIR/run_existing_100g_paper_workloads.sh"

printf 'completed_at=%s\n' "$(date --iso-8601=seconds)" >"$CAMPAIGN_ROOT/SUCCESS"
CAMPAIGN_SUCCEEDED=true
echo "Completed Cassandra single-partition campaign: $CAMPAIGN_ROOT"
