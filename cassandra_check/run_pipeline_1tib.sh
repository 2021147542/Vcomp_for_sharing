#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export DATASET_GIB=1024
export KEY_BYTES="${KEY_BYTES:-24}"
export VALUE_BYTES="${VALUE_BYTES:-1000}"
# This harness has exactly one Cassandra partition. Cassandra cannot split an
# oversized partition across native SSTable output boundaries, so 0 selects
# VComp's partition-atomic materialization mode (Long.MAX_VALUE internally).
export TARGET_SST_BYTES="${TARGET_SST_BYTES:-0}"
export RUN_TAG="1tib-${KEY_BYTES}b-key"
export KEYSPACE="vcomp_1tib_${KEY_BYTES}b"
exec "$SCRIPT_DIR/run_pipeline_100g.sh"
