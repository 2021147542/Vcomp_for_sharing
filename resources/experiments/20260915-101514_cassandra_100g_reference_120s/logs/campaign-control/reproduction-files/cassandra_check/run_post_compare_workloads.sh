#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$SCRIPT_DIR/.."
COMPARE_ROOT="${COMPARE_ROOT:?set COMPARE_ROOT to the baseline/VComp comparison root}"
DATASET_GIB="${DATASET_GIB:-100}"
RUN_TAG_PREFIX="${RUN_TAG_PREFIX:-faithful-v2-${DATASET_GIB}g}"
BASELINE_TAG="${RUN_TAG_PREFIX}-baseline"
VCOMP_TAG="${RUN_TAG_PREFIX}-vcomp"
BASELINE_KEYSPACE="${BASELINE_KEYSPACE:-baseline_${DATASET_GIB}g}"
VCOMP_KEYSPACE="${VCOMP_KEYSPACE:-vcomp_${DATASET_GIB}g}"
KEY_SPACE="${KEY_SPACE:-$((DATASET_GIB * 1024 * 1024))}"
PARTITION_COUNT="${PARTITION_COUNT:-$((DATASET_GIB * 100))}"
OPERATIONS_PER_THREAD="${OPERATIONS_PER_THREAD:-20000}"
THREADS="${THREADS:-48}"
WAIT_TIMEOUT_SECONDS="${WAIT_TIMEOUT_SECONDS:-43200}"
POLL_SECONDS="${POLL_SECONDS:-60}"
WORKLOAD_ROOT="${WORKLOAD_ROOT:-/work/vcomp-pebble-1tb/cassandra-faithful-v2-workloads-${DATASET_GIB}g-$(date +%Y%m%d-%H%M%S)}"
PUBLISH_DIR="${PUBLISH_DIR:-$REPOSITORY_ROOT/resources/experiments/$(date +%Y%m%d-%H%M%S)_cassandra_faithful_v2_${DATASET_GIB}g}"

if [[ ! "$WAIT_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ || ! "$POLL_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
    echo 'WAIT_TIMEOUT_SECONDS and POLL_SECONDS must be positive integers' >&2
    exit 2
fi

echo "Waiting for load comparison: $COMPARE_ROOT"
deadline=$((SECONDS + WAIT_TIMEOUT_SECONDS))
while [[ ! -f "$COMPARE_ROOT/COMPLETE" ]]; do
    failed=$(find "$COMPARE_ROOT" -name FAILED -type f -print -quit 2>/dev/null || true)
    if [[ -n "$failed" ]]; then
        echo "Load comparison failed: $failed" >&2
        exit 1
    fi
    if (( SECONDS >= deadline )); then
        echo "Timed out waiting for load comparison after ${WAIT_TIMEOUT_SECONDS}s" >&2
        exit 1
    fi
    sleep "$POLL_SECONDS"
done

baseline_source=$(readlink -f "$COMPARE_ROOT/baseline/latest-$BASELINE_TAG")
vcomp_source=$(readlink -f "$COMPARE_ROOT/vcomp/latest-$VCOMP_TAG")
for source in "$baseline_source" "$vcomp_source"; do
    if [[ ! -f "$source/SUCCESS" ]]; then
        echo "Successful retained database is missing: $source" >&2
        exit 1
    fi
done

echo "Starting workloads: $WORKLOAD_ROOT"
env \
  BASELINE_SOURCE="$baseline_source" \
  VCOMP_SOURCE="$vcomp_source" \
  BASELINE_KEYSPACE="$BASELINE_KEYSPACE" \
  VCOMP_KEYSPACE="$VCOMP_KEYSPACE" \
  OUT_ROOT="$WORKLOAD_ROOT" \
  OPERATIONS_PER_THREAD="$OPERATIONS_PER_THREAD" \
  THREADS="$THREADS" \
  KEY_SPACE="$KEY_SPACE" \
  PARTITION_COUNT="$PARTITION_COUNT" \
  bash "$SCRIPT_DIR/run_existing_100g_paper_workloads.sh"

python3 "$REPOSITORY_ROOT/resources/plot_cassandra_paper_workloads.py" "$WORKLOAD_ROOT"

mkdir -p "$PUBLISH_DIR/results"
cp "$WORKLOAD_ROOT/README.md" \
   "$WORKLOAD_ROOT/configuration.txt" \
   "$WORKLOAD_ROOT/paper_workloads.svg" \
   "$WORKLOAD_ROOT/cassandra_workloads.svg" \
   "$PUBLISH_DIR/"
cp "$WORKLOAD_ROOT"/results/*.json "$PUBLISH_DIR/results/"
cp "$COMPARE_ROOT"/figures/* "$PUBLISH_DIR/"
cp "$COMPARE_ROOT/COMPLETE" "$PUBLISH_DIR/load_COMPLETE.env"
cp "$COMPARE_ROOT/runtime-jar.sha256" "$PUBLISH_DIR/"
{
    printf 'compare_root=%s\n' "$COMPARE_ROOT"
    printf 'baseline_source=%s\n' "$baseline_source"
    printf 'vcomp_source=%s\n' "$vcomp_source"
    printf 'workload_root=%s\n' "$WORKLOAD_ROOT"
    printf 'published_at=%s\n' "$(date --iso-8601=seconds)"
} >"$PUBLISH_DIR/source_runs.env"

echo "Workloads and publication completed: $PUBLISH_DIR"
