#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_ROOT="$SCRIPT_DIR/../cassandra_vcomp"
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
DATASET_GIB="${DATASET_GIB:-20}"
ROOT="${EXPERIMENT_ROOT:-/work/vcomp-pebble-1tb/cassandra-vcomp-${DATASET_GIB}g-compare}"
BASELINE_TARGET_SSTABLE_SIZE="${BASELINE_TARGET_SSTABLE_SIZE:-64MiB}"
VCOMP_TARGET_SST_BYTES="${VCOMP_TARGET_SST_BYTES:-67108864}"
PARTITION_COUNT="${PARTITION_COUNT:-$((DATASET_GIB * 100))}"
VCOMP_SST_SIZE_MODEL="${VCOMP_SST_SIZE_MODEL:-calibrated}"
RUN_TAG_PREFIX="${RUN_TAG_PREFIX:-${DATASET_GIB}g}"
BASELINE_TAG="${RUN_TAG_PREFIX}-baseline"
VCOMP_TAG="${RUN_TAG_PREFIX}-vcomp"
BASELINE_KEYSPACE="${BASELINE_KEYSPACE:-baseline_${DATASET_GIB}g}"
VCOMP_KEYSPACE="${VCOMP_KEYSPACE:-vcomp_${DATASET_GIB}g}"
LOG="$ROOT/compare-$(date +%Y%m%d-%H%M%S).log"
mkdir -p "$ROOT"
exec > >(tee -a "$LOG") 2>&1

echo '[1/4] Building and auditing the Cassandra runtime jar'
export JAVA_HOME
source "$SOURCE_ROOT/build-env.sh"
ant -f "$SOURCE_ROOT/build.xml" jar >"$ROOT/build-jar.log" 2>&1
RUNTIME_JAR=$(find "$SOURCE_ROOT/build" -maxdepth 1 -name 'apache-cassandra-*.jar' \
  ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)
if [[ -z "$RUNTIME_JAR" ]]; then
  echo 'Cassandra runtime jar was not produced' >&2
  exit 1
fi
sha256sum "$RUNTIME_JAR" >"$ROOT/runtime-jar.sha256"
"$JAVA_HOME/bin/javap" -classpath "$RUNTIME_JAR" -c -private \
  org.apache.cassandra.db.compaction.unified.Controller >"$ROOT/controller-runtime.javap"
"$JAVA_HOME/bin/javap" -classpath "$RUNTIME_JAR" -c -private \
  org.apache.cassandra.config.CassandraRelevantProperties >"$ROOT/properties-runtime.javap"
if ! rg -q 'CassandraRelevantProperties.UCS_PICKER_SEED' "$ROOT/controller-runtime.javap" \
   || ! rg -q 'cassandra.ucs.picker_seed' "$ROOT/properties-runtime.javap"; then
  echo 'runtime jar does not contain the seeded UCS Controller hook' >&2
  exit 1
fi

echo '[2/4] Measuring vanilla Cassandra UCS baseline'
env \
  DATASET_GIB="$DATASET_GIB" \
  RUN_TAG="$BASELINE_TAG" \
  EXPERIMENT_ROOT="$ROOT/baseline" \
  KEYSPACE="$BASELINE_KEYSPACE" \
  PARTITION_COUNT="$PARTITION_COUNT" \
  TARGET_SSTABLE_SIZE="$BASELINE_TARGET_SSTABLE_SIZE" \
  bash "$SCRIPT_DIR/run_baseline_20g.sh"

echo '[3/4] Measuring Cassandra VComp (starts only after baseline node exits)'
env \
  DATASET_GIB="$DATASET_GIB" \
  RUN_TAG="$VCOMP_TAG" \
  EXPERIMENT_ROOT="$ROOT/vcomp" \
  KEYSPACE="$VCOMP_KEYSPACE" \
  PARTITION_COUNT="$PARTITION_COUNT" \
  TARGET_SST_BYTES="$VCOMP_TARGET_SST_BYTES" \
  VCOMP_SST_SIZE_MODEL="$VCOMP_SST_SIZE_MODEL" \
  bash "$SCRIPT_DIR/run_pipeline_100g.sh"

baseline=$(readlink -f "$ROOT/baseline/latest-$BASELINE_TAG")
vcomp=$(readlink -f "$ROOT/vcomp/latest-$VCOMP_TAG")
baseline_rows=$(awk -F= '/^fingerprint_rows=/ { print $2 }' "$baseline/baseline_metrics.env")
baseline_hash=$(awk -F= '/^fingerprint_sha256=/ { print $2 }' "$baseline/baseline_metrics.env")
vcomp_rows=$(awk -F= '/^fingerprint_rows=/ { print $2 }' "$vcomp/load_metrics.env")
vcomp_hash=$(awk -F= '/^fingerprint_sha256=/ { print $2 }' "$vcomp/load_metrics.env")
if [[ -z "$baseline_rows" || -z "$vcomp_rows" || -z "$baseline_hash" || -z "$vcomp_hash" ]]; then
  echo 'missing full-table fingerprint evidence' >&2
  exit 1
fi
cardinality_delta=$((vcomp_rows - baseline_rows))
cardinality_error_pct=$(awk -v baseline="$baseline_rows" -v vcomp="$vcomp_rows" \
  'BEGIN { printf "%.4f", 100.0 * (vcomp - baseline) / baseline }')
fingerprint_match=false
if [[ "$baseline_hash" == "$vcomp_hash" ]]; then
  fingerprint_match=true
fi
printf 'Approximate-key accuracy: baseline rows=%s, VComp rows=%s, delta=%+d (%s%%), exact fingerprint match=%s\n' \
  "$baseline_rows" "$vcomp_rows" "$cardinality_delta" "$cardinality_error_pct" "$fingerprint_match"
echo '[4/4] Rendering measured comparison graph'
python3 "$SCRIPT_DIR/../resources/plot_cassandra_baseline_compare.py" "$baseline" "$vcomp" "$ROOT/figures"
printf 'baseline_run=%s\nvcomp_run=%s\nbaseline_fingerprint_rows=%s\nbaseline_fingerprint_sha256=%s\nvcomp_fingerprint_rows=%s\nvcomp_fingerprint_sha256=%s\ncardinality_delta=%s\ncardinality_error_pct=%s\nexact_fingerprint_match=%s\nfigures=%s\n' \
  "$baseline" "$vcomp" "$baseline_rows" "$baseline_hash" "$vcomp_rows" "$vcomp_hash" \
  "$cardinality_delta" "$cardinality_error_pct" "$fingerprint_match" "$ROOT/figures" >"$ROOT/COMPLETE"
