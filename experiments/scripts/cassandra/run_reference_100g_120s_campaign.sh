#!/usr/bin/env bash
set -euo pipefail

# One predetermined VComp load and fourteen fresh paired workload cells.
# Qualify the final source/runtime before --execute. This runner never builds.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
REFERENCE="${BASELINE_REFERENCE:-$REPO_ROOT/resources/experiments/20260914-152956_cassandra_100g_organized_resume/logs/baseline-reference/reference.json}"
RUN_STAMP="${RUN_STAMP:-$(date +%Y%m%d-%H%M%S)}"
RAW_ROOT="${EXPERIMENT_ROOT:-/work/vcomp-pebble-1tb/cassandra-reference-100g-120s-$RUN_STAMP}"
BUNDLE_ROOT="${RESULT_ROOT:-$REPO_ROOT/resources/experiments/${RUN_STAMP}_cassandra_100g_reference_120s}"
CONTROL_ROOT="${CAMPAIGN_CONTROL_ROOT:-$REPO_ROOT/experiments/artifacts/${RUN_STAMP}_cassandra_100g_reference_120s}"
MODE="${1:---plan}"
[[ $# -le 1 && ( "$MODE" == --plan || "$MODE" == --execute || "$MODE" == --tmux ) ]] || {
    echo 'Usage: run_reference_100g_120s_campaign.sh [--plan|--execute|--tmux]' >&2; exit 2;
}
cd "$REPO_ROOT"
python3 "$SCRIPT_DIR/baseline_reference.py" validate --reference "$REFERENCE"
python3 "$SCRIPT_DIR/baseline_reference.py" guard --reference "$REFERENCE" --path "$RAW_ROOT" --path "$BUNDLE_ROOT" --path "$CONTROL_ROOT"
for path in "$RAW_ROOT" "$BUNDLE_ROOT"; do
    [[ ! -e "$path" && ! -L "$path" ]] || { echo "Output exists: $path" >&2; exit 2; }
done
printf 'Baseline: preserved100GiB, seed20260909; VComp: one fresh100GiB load.\n'
printf 'Workloads: A B C D E F MIXGRAPH, baseline then VComp, each120s,48threads.\n'
printf 'Each cell: independent checkpoint; native chunk cache5%%;20GiB cgroup,zero swap.\n'
printf 'Raw: %s\nBundle: %s\nControl: %s\n' "$RAW_ROOT" "$BUNDLE_ROOT" "$CONTROL_ROOT"
[[ "$MODE" != --plan ]] || exit 0
if [[ "$MODE" == --tmux ]]; then
    [[ ! -e "$CONTROL_ROOT" ]] || { echo 'Control output already exists' >&2; exit 2; }
    mkdir -p "$CONTROL_ROOT"
    command=(env RUN_STAMP="$RUN_STAMP" BASELINE_REFERENCE="$REFERENCE" EXPERIMENT_ROOT="$RAW_ROOT"
        RESULT_ROOT="$BUNDLE_ROOT" CAMPAIGN_CONTROL_ROOT="$CONTROL_ROOT")
    for name in BASELINE_COMPATIBILITY_REVIEW EXPECTED_RUNTIME_JAR_SHA256; do
        [[ ! -v "$name" ]] || command+=("$name=${!name}")
    done
    command+=(bash "$0" --execute)
    printf -v quoted '%q ' "${command[@]}"
    printf -v logfile '%q' "$CONTROL_ROOT/campaign.log"
    session="cassandra-120s-$RUN_STAMP"
    tmux new-session -d -s "$session" "$quoted >$logfile 2>&1"
    printf '%s\n' "$session" >"$CONTROL_ROOT/tmux-session.txt"
    printf 'tmux session: %s\n' "$session"
    exit 0
fi

exec 9>/tmp/vcomp-cassandra-storage-campaign.lock
flock -n 9 || { echo 'Another Cassandra storage campaign is active' >&2; exit 2; }
if pgrep -f 'org[.]apache[.]cassandra[.]service[.]CassandraDaemon|CassandraPaper[W]orkload|CassandraBaseline[L]oad|org[.]junit[.]runner[.]JUnitCore' >/dev/null; then
    echo 'Storage runtime/test is active; refusing overlap' >&2; exit 2
fi
mkdir -p "$CONTROL_ROOT"
[[ ! -e "$CONTROL_ROOT/frozen-source-runtime.json" ]] || { echo 'Control directory contains a previous execution' >&2; exit 2; }
printf 'seed=20260909\ndataset_gib=100\nworkloads=A B C D E F MIXGRAPH\narm_order=baseline vcomp\nduration_seconds=120\noperations_per_thread=0\nthreads=48\nrepetitions=1\nbaseline_load_repeated=false\ncheckpoint_method=independent_copy_reflink_auto\n' >"$CONTROL_ROOT/predetermined-plan.env"
phase=preflight
finish() {
    local code=$?
    printf 'exit_status=%s\nphase=%s\nfinished_at=%s\n' "$code" "$phase" "$(date --iso-8601=seconds)" >"$CONTROL_ROOT/status.env"
    if [[ -d "$BUNDLE_ROOT/logs/campaign-control" ]]; then
        cp "$CONTROL_ROOT/status.env" "$BUNDLE_ROOT/logs/campaign-control/status.env" || true
    fi
}
trap finish EXIT
RUNTIME_JAR="$REPO_ROOT/cassandra_vcomp/build/apache-cassandra-5.0.9-SNAPSHOT.jar"
[[ -f "$RUNTIME_JAR" ]] || { echo 'Qualified JAR missing; build and validate before launch' >&2; exit 2; }
if [[ -n "${EXPECTED_RUNTIME_JAR_SHA256:-}" ]]; then
    sha256sum --check --status "$EXPECTED_RUNTIME_JAR_SHA256"
fi
sha256sum "$RUNTIME_JAR" >"$CONTROL_ROOT/runtime-jar.sha256"
export EXPECTED_RUNTIME_JAR_SHA256="$CONTROL_ROOT/runtime-jar.sha256"
python3 - "$REPO_ROOT" "$CONTROL_ROOT" "$RUNTIME_JAR" <<'PYFREEZE'
import hashlib, json, subprocess, sys, zipfile
from pathlib import Path
repo, out, jar = map(Path, sys.argv[1:])
classes = repo / 'cassandra_vcomp/build/classes/main'
with zipfile.ZipFile(jar) as archive:
    count = 0
    for name in archive.namelist():
        if name.endswith('.class'):
            candidate = classes / name
            if not candidate.is_file() or candidate.read_bytes() != archive.read(name):
                raise SystemExit('JAR/classes mismatch: ' + name)
            count += 1
    if count < 1000:
        raise SystemExit('Unexpected Cassandra JAR class inventory')
paths = []
for directory in ('cassandra_vcomp/src/java', 'cassandra_vcomp/build/classes/main', 'cassandra_check/src', 'cassandra_check', 'experiments/scripts/cassandra'):
    for path in (repo / directory).rglob('*'):
        if path.is_file() and (path.suffix in ('.java', '.sh', '.py')
                or (directory == 'cassandra_vcomp/build/classes/main' and path.suffix == '.class')):
            paths.append(path)
paths = sorted(set(paths))
paths += [repo / name for name in ('experiments/analysis/publish_vcomp_reference_load.py',
    'experiments/analysis/publish_experiment_bundle.py', 'experiments/analysis/restore_paper_figure_style.py',
    'resources/plot_cassandra_baseline_compare.py')]
snapshot = {str(path):hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}
(out / 'frozen-source-runtime.json').write_text(json.dumps(snapshot, indent=2) + '\n')
(out / 'git-status.txt').write_bytes(subprocess.check_output(['git','status','--short'],cwd=repo))
(out / 'source-changes.patch').write_bytes(subprocess.check_output(['git','diff','--','cassandra_vcomp','cassandra_check','experiments/scripts/cassandra'],cwd=repo))
for path in paths:
    if path.suffix != '.class':
        target = out / 'reproduction-files' / path.relative_to(repo)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(path.read_bytes())
print('Frozen', len(snapshot), 'source/runtime files;', count, 'matching JAR classes')
PYFREEZE
check_frozen() {
    sha256sum --check --status "$EXPECTED_RUNTIME_JAR_SHA256"
    python3 - "$CONTROL_ROOT/frozen-source-runtime.json" <<'PYCHECK'
import hashlib, json, sys
from pathlib import Path
for name, expected in json.loads(Path(sys.argv[1]).read_text()).items():
    if hashlib.sha256(Path(name).read_bytes()).hexdigest() != expected:
        raise SystemExit('Frozen source/runtime changed: ' + name)
PYCHECK
}
export BASELINE_REFERENCE="$REFERENCE" SKIP_CASSANDRA_BUILD=true
phase=vcomp_load
env DATASET_GIB=100 SEED=20260909 UCS_PICKER_SEED=20260909 KEY_BYTES=24 VALUE_BYTES=1000 \
    PARTITION_COUNT=10000 BASELINE_KEYSPACE=baseline_100g VCOMP_KEYSPACE=vcomp_100g \
    BASELINE_TARGET_SSTABLE_SIZE=64MiB VCOMP_TARGET_SST_BYTES=67108864 VCOMP_SST_SIZE_MODEL=calibrated \
    RUN_TAG_PREFIX="reference-$RUN_STAMP" EXPERIMENT_ROOT="$RAW_ROOT/load" \
    bash "$REPO_ROOT/cassandra_check/run_baseline_vcomp_20g_compare.sh"
check_frozen
BASELINE_SOURCE=$(sed -n 's/^baseline_run=//p' "$RAW_ROOT/load/COMPLETE")
VCOMP_SOURCE=$(sed -n 's/^vcomp_run=//p' "$RAW_ROOT/load/COMPLETE")
export BASELINE_SOURCE VCOMP_SOURCE
python3 "$REPO_ROOT/experiments/analysis/publish_vcomp_reference_load.py" "$RAW_ROOT/load" "$BUNDLE_ROOT"
printf 'status=running\nphase=workloads\nbaseline_load_repeated=false\nworkload_duration_seconds=120\n' >"$BUNDLE_ROOT/logs/status.env"
phase=workloads
env OUT_ROOT="$RAW_ROOT/workloads" DURATION_SECONDS=120 OPERATIONS_PER_THREAD=0 THREADS=48 \
    WORKLOADS='A B C D E F MIXGRAPH' KEY_SPACE=104857600 KEY_BYTES=24 VALUE_BYTES=1000 \
    PARTITION_COUNT=10000 SEED=20260909 UCS_PICKER_SEED=20260909 \
    BASELINE_KEYSPACE=baseline_100g VCOMP_KEYSPACE=vcomp_100g DISK_DEVICE=md0 MAX_HEAP_SIZE=4G \
    bash "$SCRIPT_DIR/run_bounded_workloads.sh"
check_frozen
python3 "$SCRIPT_DIR/baseline_reference.py" validate --reference "$REFERENCE" >"$CONTROL_ROOT/baseline-reference-final.json"
phase=publish
python3 - "$RAW_ROOT" "$BUNDLE_ROOT" "$CONTROL_ROOT" <<'PYPUBLISH'
import json, shutil, sys
from pathlib import Path
raw, bundle, control = map(Path, sys.argv[1:])
logs = bundle / 'logs'
work = raw / 'workloads'
expected = {f'{w}_{s}.json' for w in ('a','b','c','d','e','f','mixgraph') for s in ('baseline','vcomp')}
actual = {p.name for p in (work / 'results').glob('*.json')}
if actual != expected or not (work / 'SUCCESS').is_file():
    raise SystemExit('Incomplete fourteen-cell campaign')
for name in sorted(expected):
    d = json.loads((work / 'results' / name).read_text())
    if d['requested_duration_seconds'] != 120 or d['operations_per_thread'] != 0 or d['threads'] != 48 or d['seed'] != 20260909 or d['keyspace_size'] != 104857600:
        raise SystemExit('Wrong workload protocol: ' + name)
shutil.copytree(work / 'results', logs / 'results')
shutil.copy2(work / 'configuration.txt', logs / 'workload-configuration.txt')
review = raw / 'load/baseline-compatibility-review.json'
if review.is_file():
    shutil.copy2(review, logs / review.name)
shutil.copytree(control, logs / 'campaign-control', ignore=shutil.ignore_patterns('campaign.log'))
for source in (work, Path(str(work) + '-control')):
    for path in source.rglob('*'):
        relative = path.relative_to(source)
        if not path.is_file() or any(part in ('data', 'classes') for part in relative.parts):
            continue
        if path.stat().st_size > 20 * 1024**2:
            continue
        target = logs / ('workloads' if source == work else 'workloads-control') / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
(logs / 'status.env').write_text('status=complete\nphase=paired_workloads\nbaseline_load_repeated=false\nworkloads_run=true\nworkload_cells=14\nworkload_duration_seconds=120\n')
(logs / 'README.md').write_text('# Preserved baseline and corrected VComp, 120-second workloads\n\n'
    'The baseline loading metrics are historical; its DB is preserved. VComp was loaded once with the same fixed seed and input definition. '
    'All fourteen workload measurements are new, each 120 seconds and 48 threads, in predetermined A–F/MixGraph order, baseline then VComp. '
    'Each cell uses a fresh independent checkpoint. Native chunk cache is 5% of logical data with a 20 GiB process-group limit and no swap; additional OS cache means this is not identical to the paper cache implementation. '
    'Time-based cells can complete different operation counts, so disk bytes describe the two-minute interval and must not be interpreted as equal-operation I/O. '
    'All measured results are retained without selecting seeds or choosing favorable repetitions.\n\n'
    f'Raw artifacts: `{raw}`. Logs above 20 MiB and database files remain there. Source/runtime copies and hashes are in `campaign-control/`.\n')
PYPUBLISH
python3 "$REPO_ROOT/experiments/analysis/publish_experiment_bundle.py" "$BUNDLE_ROOT"
phase=complete
printf 'completed_at=%s\nbundle=%s\n' "$(date --iso-8601=seconds)" "$BUNDLE_ROOT" >"$RAW_ROOT/SUCCESS"
printf 'Completed: %s/results.md\n' "$BUNDLE_ROOT"
