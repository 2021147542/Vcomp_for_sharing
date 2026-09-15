#!/usr/bin/env bash
# One-partition correctness control. Keep a 64-MiB flush cadence, but do not
# split a virtual compaction output merely to hit a target SST size: native
# Cassandra UCS cannot split this oversized partition at an SST boundary.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec env \
  DATASET_GIB=1 \
  RUN_TAG_PREFIX=1g-partition-atomic \
  EXPERIMENT_ROOT=/work/vcomp-pebble-1tb/cassandra-vcomp-1g-partition-atomic \
  BASELINE_KEYSPACE=baseline_1g_partition_atomic \
  VCOMP_KEYSPACE=vcomp_1g_partition_atomic \
  BASELINE_TARGET_SSTABLE_SIZE=default \
  VCOMP_TARGET_SST_BYTES=0 \
  bash "$SCRIPT_DIR/run_baseline_vcomp_20g_compare.sh"
