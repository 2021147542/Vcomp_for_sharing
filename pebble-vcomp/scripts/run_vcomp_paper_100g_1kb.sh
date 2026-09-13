#!/usr/bin/env bash
set -euo pipefail

# A fresh, retained 100-GiB run.  The 1-TiB script is deliberately left
# untouched so its completed artefacts remain independently reproducible.
REPO=/home/dongju/vcomp/pebble-vcomp
GO=/home/dongju/vcomp/.tools/go/bin/go
RUN_ID="pebble-paper-100g-1kb-$(date +%Y%m%d-%H%M%S)"
# /work itself is root-owned; the retained experiment container is writable.
DB_ROOT="/work/vcomp-pebble-1tb/${RUN_ID}"
LOG="/work/vcomp-pebble-1tb/${RUN_ID}.log"
STATUS="/work/vcomp-pebble-1tb/${RUN_ID}.status"
WRITES=104857600
VALUE_SIZE=1000
CACHE_BYTES=34359738368
DURATION=5m
CONCURRENCY=48
DISK_DEVICE=md0

mkdir -p "$(dirname "${DB_ROOT}")"
ulimit -n 1048576
export PATH="/home/dongju/vcomp/.tools/go/bin:/usr/local/bin:/usr/bin:/bin"
export GOCACHE=/tmp/vcomp-go-cache
export GOPATH=/tmp/vcomp-gopath

exec > >(tee -a "${LOG}") 2>&1

status() {
  printf '%s %s\n' "$(date --iso-8601=seconds)" "$*" | tee "${STATUS}"
}

echo "Pebble VComp 100-GiB paper-style experiment"
echo "DB_ROOT=${DB_ROOT}"
echo "KV=24 B key + ${VALUE_SIZE} B value (1024 B total)"
echo "DATASET=100 GiB, WRITES=${WRITES}"
echo "LOAD=64 MiB memtable/flush/SST, 48 background jobs, WAL off, compression off"
echo "VCOMP_MODEL=continuous PLR, common-theta KMV, logical SST metadata"
echo "WORKLOADS=YCSB A-F + MixGraph, ${CONCURRENCY} clients, ${DURATION} each"
echo "BLOCK_CACHE=${CACHE_BYTES} bytes (32 GiB)"
echo "Canonical baseline/virtual DBs are retained; each workload checkpoint is removed after its result is saved"
df -h /work

cd "${REPO}"
status "loading baseline and VComp"
env \
  VCOMP_EXPERIMENT=1 \
  VCOMP_PAPER_MODE=1 \
  VCOMP_DISCRETE_CDF=0 \
  VCOMP_SST_SIZE_MODEL=logical \
  VCOMP_SKIP_YCSB=1 \
  VCOMP_DB_ROOT="${DB_ROOT}" \
  VCOMP_WRITES="${WRITES}" \
  VCOMP_VALUE_SIZE="${VALUE_SIZE}" \
  "${GO}" test -run '^TestVCompExperiment$' -count=1 -timeout=0 -v .

for workload in A B C D E F MIXGRAPH; do
  for system in baseline virtual; do
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
echo "RESULT=${DB_ROOT}/paper_load_result.json"
echo "WORKLOAD_RESULTS=${DB_ROOT}/paper-workloads/results"
echo "FIGURES=${DB_ROOT}/paper-figures"
