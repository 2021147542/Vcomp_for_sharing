#!/usr/bin/env bash
set -euo pipefail

# This wrapper consumes no benchmark resources while waiting. It only starts
# Cassandra after the exact Pebble run has rendered its figures and recorded a
# successful terminal status.
PEBBLE_SESSION="${PEBBLE_SESSION:?set PEBBLE_SESSION}"
PEBBLE_STATUS="${PEBBLE_STATUS:?set PEBBLE_STATUS}"
LOG="${SEQUENCE_LOG:?set SEQUENCE_LOG}"

exec > >(tee -a "${LOG}") 2>&1
echo "waiting for Pebble session ${PEBBLE_SESSION}"
while tmux has-session -t "${PEBBLE_SESSION}" 2>/dev/null; do
    sleep 60
done

if [[ ! -f "${PEBBLE_STATUS}" ]] || ! grep -q ' complete$' "${PEBBLE_STATUS}"; then
    echo "Pebble did not complete successfully; Cassandra will not start." >&2
    exit 1
fi

echo "Pebble completed at $(date --iso-8601=seconds); launching Cassandra load-only run"
exec env \
    DATASET_GIB=100 \
    KEY_BYTES=24 \
    VALUE_BYTES=1000 \
    RUN_TAG=100g-1kb \
    EXPERIMENT_ROOT=/work/vcomp-pebble-1tb/cassandra-vcomp-100g \
    bash /home/dongju/vcomp/cassandra_check/run_pipeline_100g.sh
