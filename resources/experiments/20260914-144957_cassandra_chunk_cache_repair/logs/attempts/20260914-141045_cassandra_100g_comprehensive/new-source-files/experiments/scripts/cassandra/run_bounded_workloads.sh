#!/usr/bin/env bash
set -euo pipefail

# Isolate the daemon and its clients together. This caps total memory, not just
# file cache; it must not be described as a 5% total-data-cache implementation.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
: "${OUT_ROOT:?OUT_ROOT must identify a fresh workload directory}"
: "${BASELINE_SOURCE:?BASELINE_SOURCE is required}"
: "${VCOMP_SOURCE:?VCOMP_SOURCE is required}"
CONTROL_ROOT="${OUT_ROOT}-control"

if [[ "${1:-}" == --inside ]]; then
    exec python3 "$SCRIPT_DIR/monitor_workload_memory.py" "$CONTROL_ROOT" \
        "$REPO_ROOT/cassandra_check/run_existing_100g_paper_workloads.sh"
fi

[[ ! -e "$OUT_ROOT" && ! -L "$OUT_ROOT" ]] || { echo "Output already exists: $OUT_ROOT" >&2; exit 1; }
[[ ! -e "$CONTROL_ROOT" && ! -L "$CONTROL_ROOT" ]] || { echo "Control output already exists: $CONTROL_ROOT" >&2; exit 1; }
mkdir -p "$(dirname "$CONTROL_ROOT")"
mkdir "$CONTROL_ROOT"
unit="vcomp-workloads-$(date +%Y%m%d-%H%M%S)-$$"
printf '%s\n' "$unit" >"$CONTROL_ROOT/unit.txt"
env_args=()
# Forward only experiment configuration, never credentials or arbitrary session
# environment. Arguments retain literal spaces without shell interpolation.
for name in OUT_ROOT BASELINE_SOURCE VCOMP_SOURCE BASELINE_KEYSPACE VCOMP_KEYSPACE \
            DATASET_GIB KEY_SPACE KEY_BYTES VALUE_BYTES PARTITION_COUNT SEED UCS_PICKER_SEED \
            THREADS DURATION_SECONDS OPERATIONS_PER_THREAD WORKLOADS DISK_DEVICE MAX_HEAP_SIZE \
            FILE_CACHE_SIZE_MIB COMPACTION_DRAIN_TIMEOUT_SECONDS SKIP_CASSANDRA_BUILD \
            EXPECTED_RUNTIME_JAR_SHA256; do
    if [[ -v "$name" ]]; then env_args+=("$name=${!name}"); fi
done
systemd-run --user --quiet --wait --pipe --collect --unit="$unit" \
    -p MemoryMax=20G -p MemorySwapMax=0 -p WorkingDirectory="$REPO_ROOT" \
    /usr/bin/env "${env_args[@]}" /bin/bash "${BASH_SOURCE[0]}" --inside
