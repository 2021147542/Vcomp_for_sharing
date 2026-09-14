#!/usr/bin/env bash
set -euo pipefail

# Build only VComp against the preserved baseline. Workloads are a separate step.
# With no --execute this prints the concrete command and creates no experiment.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
REFERENCE="${BASELINE_REFERENCE:-$REPO_ROOT/resources/experiments/20260914-152956_cassandra_100g_organized_resume/logs/baseline-reference/reference.json}"
RUN_STAMP="${RUN_STAMP:-$(date +%Y%m%d-%H%M%S)}"
ITERATION_ROOT="${EXPERIMENT_ROOT:-/work/vcomp-pebble-1tb/cassandra-vcomp-reference-$RUN_STAMP}"
BUNDLE_ROOT="${RESULT_ROOT:-$REPO_ROOT/resources/experiments/${RUN_STAMP}_cassandra_vcomp_reference_load}"
if [[ $# -gt 1 || ( $# -eq 1 && "$1" != --execute && "$1" != --plan ) ]]; then
  echo 'Usage: run_vcomp_against_reference.sh [--plan|--execute]' >&2
  exit 2
fi
python3 "$SCRIPT_DIR/baseline_reference.py" validate --reference "$REFERENCE"
python3 "$SCRIPT_DIR/baseline_reference.py" guard --reference "$REFERENCE" --path "$ITERATION_ROOT" --path "$BUNDLE_ROOT"
[[ ! -e "$ITERATION_ROOT" && ! -L "$ITERATION_ROOT" ]] || { echo 'Iteration output already exists' >&2; exit 2; }
[[ ! -e "$BUNDLE_ROOT" && ! -L "$BUNDLE_ROOT" ]] || { echo 'Result bundle already exists' >&2; exit 2; }
command=(env BASELINE_REFERENCE="$REFERENCE" DATASET_GIB=100 SEED=20260909 UCS_PICKER_SEED=20260909
  KEY_BYTES=24 VALUE_BYTES=1000 PARTITION_COUNT=10000 BASELINE_KEYSPACE=baseline_100g VCOMP_KEYSPACE=vcomp_100g
  BASELINE_TARGET_SSTABLE_SIZE=64MiB VCOMP_TARGET_SST_BYTES=67108864 VCOMP_SST_SIZE_MODEL=calibrated
  RUN_TAG_PREFIX="reference-$RUN_STAMP" EXPERIMENT_ROOT="$ITERATION_ROOT"
  bash "$REPO_ROOT/cassandra_check/run_baseline_vcomp_20g_compare.sh")
printf 'Baseline load: preserved; VComp load: fresh; workload measurements: not reused or launched.\n'
printf '%q ' "${command[@]}"
printf '\n'
printf 'Public result bundle: %s\n' "$BUNDLE_ROOT"
if [[ "${1:---plan}" == --execute ]]; then
  exec 9>/tmp/vcomp-cassandra-storage-campaign.lock
  flock -n 9 || { echo 'Another storage campaign is running' >&2; exit 2; }
  "${command[@]}"
  python3 "$REPO_ROOT/experiments/analysis/publish_vcomp_reference_load.py" "$ITERATION_ROOT" "$BUNDLE_ROOT"
fi
