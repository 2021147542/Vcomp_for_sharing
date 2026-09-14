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
BASELINE_REFERENCE="${BASELINE_REFERENCE:-}"
REFERENCE_TOOL="$SCRIPT_DIR/../experiments/scripts/cassandra/baseline_reference.py"
REUSED_BASELINE=""
# A fixed baseline is a recorded database, not merely a seed. Validate its
# identity and the requested input settings before building or creating output.
if [[ -n "$BASELINE_REFERENCE" ]]; then
  REUSED_BASELINE=$(python3 - "$REFERENCE_TOOL" "$BASELINE_REFERENCE" "$ROOT" \
    "$DATASET_GIB" "$PARTITION_COUNT" "$BASELINE_TARGET_SSTABLE_SIZE" "$BASELINE_KEYSPACE" \
    "${KEY_BYTES:-24}" "${VALUE_BYTES:-1000}" "${UCS_PICKER_SEED:-20260909}" <<'PY'
import importlib.util
from pathlib import Path
import sys
tool, reference, output, gib, partitions, target, keyspace, key_bytes, value_bytes, picker_seed = sys.argv[1:]
spec = importlib.util.spec_from_file_location('baseline_reference', tool)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.validate(reference)
module.guard(reference, [output])
record = module.load_reference(reference)
expected = dict(dataset_gib=gib, partition_keys=partitions, target_sstable_size=target,
                key_bytes=key_bytes, value_bytes=value_bytes, seed='20260909',
                ucs_picker_seed=picker_seed, flush_bytes='67108864',
                compaction='UnifiedCompactionStrategy T4', compression='disabled')
for field, value in expected.items():
    if record['load_config'].get(field) != value:
        raise SystemExit(f'Baseline input/config mismatch: {field}')
if record['keyspace'] != keyspace:
    raise SystemExit('Baseline keyspace mismatch')
repo = Path(tool).resolve().parents[3]
for name, sha in record['input_identity']['source_sha256'].items():
    if module.digest(repo / name) != sha:
        raise SystemExit('Input/schema helper changed; verify its input semantics before baseline reuse: ' + name)
print(record['canonical_root'])
PY
  )
fi
if pgrep -f 'org[.]apache[.]cassandra[.]service[.]CassandraDaemon|CassandraPaper[W]orkload|CassandraBaseline[L]oad' >/dev/null \
   || ss -H -ltn | awk '{print $4}' | rg -q ':(7000|7001|7199|9042)$'; then
  echo 'A Cassandra process/listener is active; refusing to rebuild its runtime' >&2
  exit 2
fi
for previous in "$ROOT/COMPLETE" "$ROOT/baseline/latest-$BASELINE_TAG" "$ROOT/vcomp/latest-$VCOMP_TAG"; do
  [[ ! -e "$previous" && ! -L "$previous" ]] || { echo "Existing comparison output: $previous" >&2; exit 2; }
done
LOG="$ROOT/compare-$(date +%Y%m%d-%H%M%S).log"
mkdir -p "$ROOT"
exec > >(tee -a "$LOG") 2>&1
if [[ -n "$BASELINE_REFERENCE" ]]; then
  python3 - "$BASELINE_REFERENCE" "$ROOT/baseline-reuse.json" <<'PY'
import hashlib, json, sys
from pathlib import Path
reference, output = map(Path, sys.argv[1:])
data = json.loads(reference.read_text())
output.write_text(json.dumps(dict(reference=str(reference.resolve()),
    reference_sha256=hashlib.sha256(reference.read_bytes()).hexdigest(),
    canonical_root=data['canonical_root'], baseline_load_repeated=False,
    original_load_jar_sha256=data['original_load_jar_sha256'],
    note='Original loading metrics are historical. Reader/workload measurements require their own matching protocol.'), indent=2) + '\n')
PY
fi

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

if [[ -n "$REUSED_BASELINE" ]]; then
  echo '[2/4] Reusing preserved baseline; no baseline load or compaction is run'
  mkdir -p "$ROOT/baseline"
  # No -f: never replace an existing baseline pointer.
  ln -s "$REUSED_BASELINE" "$ROOT/baseline/latest-$BASELINE_TAG"
else
echo '[2/4] Measuring vanilla Cassandra UCS baseline'
env \
  DATASET_GIB="$DATASET_GIB" \
  RUN_TAG="$BASELINE_TAG" \
  EXPERIMENT_ROOT="$ROOT/baseline" \
  KEYSPACE="$BASELINE_KEYSPACE" \
  PARTITION_COUNT="$PARTITION_COUNT" \
  TARGET_SSTABLE_SIZE="$BASELINE_TARGET_SSTABLE_SIZE" \
  bash "$SCRIPT_DIR/run_baseline_20g.sh"
fi

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
if [[ -n "$BASELINE_REFERENCE" ]]; then
  python3 "$REFERENCE_TOOL" validate --reference "$BASELINE_REFERENCE" >"$ROOT/baseline-reference-after.json"
fi

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
