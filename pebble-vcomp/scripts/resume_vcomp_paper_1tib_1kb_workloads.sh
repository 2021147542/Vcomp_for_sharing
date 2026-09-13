#!/usr/bin/env bash
set -euo pipefail

REPO=/home/dongju/vcomp/pebble-vcomp
GO=/home/dongju/vcomp/.tools/go/bin/go
DB_ROOT=/work/vcomp-pebble-1tb/pebble-paper-1tib-1kb-20260908
LOG=/work/vcomp-pebble-1tb/pebble-paper-1tib-1kb-20260908.log
STATUS=/work/vcomp-pebble-1tb/pebble-paper-1tib-1kb-20260908.status
WRITES=1073741824
VALUE_SIZE=1000
CACHE_BYTES=34359738368
DURATION=5m
CONCURRENCY=48
DISK_DEVICE=md0

for required in \
  "${DB_ROOT}/paper_load_result.json" \
  "${DB_ROOT}/baseline" \
  "${DB_ROOT}/virtual"; do
  if [[ ! -e "${required}" ]]; then
    echo "Required retained experiment artifact is missing: ${required}" >&2
    exit 1
  fi
done

ulimit -n 1048576
export PATH="/home/dongju/vcomp/.tools/go/bin:/usr/local/bin:/usr/bin:/bin"
export GOCACHE=/tmp/vcomp-go-cache
export GOPATH=/tmp/vcomp-gopath

exec > >(tee -a "${LOG}") 2>&1

status() {
  printf '%s %s\n' "$(date --iso-8601=seconds)" "$*" | tee "${STATUS}"
}

echo "=== Resuming retained 1 TiB workload phase ==="
echo "DB_ROOT=${DB_ROOT}"
echo "WORKLOADS=YCSB A-F + MixGraph, ${CONCURRENCY} clients, ${DURATION} each"
echo "BLOCK_CACHE=${CACHE_BYTES} bytes"
echo "Completed results are skipped; successful workload checkpoints are removed"

cd "${REPO}"
for workload in A B C D E F MIXGRAPH; do
  for system in baseline virtual; do
    result="${DB_ROOT}/paper-workloads/results/${workload}_${system}.json"
    run_db="${DB_ROOT}/paper-workloads/db/${workload}/${system}"
    if [[ -f "${result}" ]]; then
      status "skipping completed workload ${workload} on ${system}"
      continue
    fi
    if [[ -e "${run_db}" ]]; then
      echo "Incomplete checkpoint already exists; refusing to overwrite: ${run_db}" >&2
      exit 1
    fi

    status "workload ${workload} on ${system}"
    sync
    env \
      VCOMP_PAPER_WORKLOAD_EXPERIMENT=1 \
      VCOMP_EXISTING_DB_ROOT="${DB_ROOT}" \
      VCOMP_PAPER_SYSTEM="${system}" \
      VCOMP_PAPER_WORKLOAD="${workload}" \
      VCOMP_WRITES="${WRITES}" \
      VCOMP_VALUE_SIZE="${VALUE_SIZE}" \
      VCOMP_YCSB_DURATION="${DURATION}" \
      VCOMP_YCSB_CONCURRENCY="${CONCURRENCY}" \
      VCOMP_YCSB_CACHE_BYTES="${CACHE_BYTES}" \
      VCOMP_DISK_DEVICE="${DISK_DEVICE}" \
      "${GO}" test -run '^TestVCompPaperWorkloadExisting$' -count=1 -timeout=0 -v .
  done
done

status "rendering paper-style figures"
python3 /home/dongju/vcomp/resources/plot_pebble_vcomp_paper.py "${DB_ROOT}"
status "complete"
echo "WORKLOAD_RESULTS=${DB_ROOT}/paper-workloads/results"
echo "FIGURES=${DB_ROOT}/paper-figures"
