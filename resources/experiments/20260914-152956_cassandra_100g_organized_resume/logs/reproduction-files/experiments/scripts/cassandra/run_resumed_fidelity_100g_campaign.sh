#!/usr/bin/env bash
set -euo pipefail

# Reuse completed canonical loads; restart all fourteen workload cells with a
# qualified reader binary. No load or build is performed by this runner.
# Launch in tmux or with nohup; never reuse an existing campaign directory.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../../lib/common.sh"
HARNESS="$REPO_ROOT/cassandra_check"
RUN_STAMP="${RUN_STAMP:-$(date +%Y%m%d-%H%M%S)}"
DATASET_GIB="${DATASET_GIB:-100}"
require_positive_uint DATASET_GIB
[[ "$DATASET_GIB" == 100 ]] || die 'This resumed protocol is qualified only for the preserved 100 GiB pair'
LOAD_CAMPAIGN_ROOT="${LOAD_CAMPAIGN_ROOT:-/work/vcomp-pebble-1tb/cassandra-comprehensive-100g-20260914-141045}"
LOAD_RESULT_ROOT="${LOAD_RESULT_ROOT:-$REPO_ROOT/resources/experiments/20260914-144957_cassandra_chunk_cache_repair/logs/attempts/20260914-141045_cassandra_100g_comprehensive}"
[[ -f "$LOAD_CAMPAIGN_ROOT/COMPLETE" && -f "$LOAD_RESULT_ROOT/final_state.json" ]] \
    || die 'Completed load pair and original final-state inventory are required'
CAMPAIGN_ROOT="${CAMPAIGN_ROOT:-$VCOMP_DB_ROOT/cassandra-resumed-${DATASET_GIB}g-$RUN_STAMP}"
BUNDLE_ROOT="${RESULT_ROOT:-$REPO_ROOT/resources/experiments/${RUN_STAMP}_cassandra_${DATASET_GIB}g_resumed_workloads}"
RESULT_ROOT="$BUNDLE_ROOT/logs"
PUBLISHER="$REPO_ROOT/experiments/analysis/publish_experiment_bundle.py"
KEY_SPACE=$((DATASET_GIB * 1024 * 1024))
PARTITION_COUNT=$((DATASET_GIB * 100))
RUN_TAG_PREFIX="fidelity-${DATASET_GIB}g-$RUN_STAMP"
BASELINE_KEYSPACE="baseline_${DATASET_GIB}g"
VCOMP_KEYSPACE="vcomp_${DATASET_GIB}g"
WORKLOAD_ROOT="$CAMPAIGN_ROOT/workloads-300s"
BASELINE_SOURCE=$(awk -F= '$1 == "baseline_run" {print substr($0, index($0, "=") + 1)}' "$LOAD_CAMPAIGN_ROOT/COMPLETE")
VCOMP_SOURCE=$(awk -F= '$1 == "vcomp_run" {print substr($0, index($0, "=") + 1)}' "$LOAD_CAMPAIGN_ROOT/COMPLETE")
for source in "$BASELINE_SOURCE" "$VCOMP_SOURCE"; do
    [[ -f "$source/SUCCESS" ]] || die "Successful canonical load is required: $source"
done
PHASE=preflight
CAMPAIGN_SUCCEEDED=false

[[ ! -e "$CAMPAIGN_ROOT" && ! -L "$CAMPAIGN_ROOT" ]] || die "Campaign path already exists: $CAMPAIGN_ROOT"
[[ ! -e "$BUNDLE_ROOT" && ! -L "$BUNDLE_ROOT" ]] || die "Result path already exists: $BUNDLE_ROOT"
mkdir -p "$(dirname "$CAMPAIGN_ROOT")" "$(dirname "$RESULT_ROOT")"
mkdir "$CAMPAIGN_ROOT" "$RESULT_ROOT"
mkdir "$BUNDLE_ROOT/figures"
printf '# Cassandra 100 GiB — running\n\nResults are pending. See [logs/status.env](logs/status.env).\n' >"$BUNDLE_ROOT/results.md"
exec > >(tee -a "$CAMPAIGN_ROOT/campaign.log") 2>&1

write_status() {
    local state=$1 exit_status=${2:-0}
    {
        printf 'status=%s\nphase=%s\nexit_status=%s\n' "$state" "$PHASE" "$exit_status"
        printf 'updated_at=%s\nraw_root=%s\nresult_root=%s\n' "$(date --iso-8601=seconds)" "$CAMPAIGN_ROOT" "$RESULT_ROOT"
        printf 'baseline_source=%s\nvcomp_source=%s\n' "$BASELINE_SOURCE" "$VCOMP_SOURCE"
    } >"$RESULT_ROOT/status.env.tmp"
    mv "$RESULT_ROOT/status.env.tmp" "$RESULT_ROOT/status.env"
}

archive_small() {
    python3 - "$CAMPAIGN_ROOT" "$RESULT_ROOT" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys

raw, output = map(Path, sys.argv[1:])
inventory = []
# Prune before descent: databases, SSTs, classes and runtime jars stay in /work.
prune = {'data', 'sstables', 'classes', 'logs'}
suffixes = {'.env', '.txt', '.json', '.csv', '.tsv', '.svg', '.png', '.md', '.sha256', '.javap',
            '.yaml', '.options', '.xml', '.properties', '.jsonl'}
log_names = {'build.log', 'build-jar.log', 'verification.log', 'fingerprint.log',
             'compaction-enable.log', 'workload.log', 'vcomp.log', 'cassandra.stdout.log'}
for directory, dirs, files in os.walk(raw, followlinks=False):
    dirs[:] = [name for name in dirs if name not in prune and not (Path(directory) / name).is_symlink()]
    for name in files:
        path = Path(directory) / name
        if path.is_symlink() or not path.is_file():
            continue
        if path.suffix not in suffixes and name not in log_names | {'SUCCESS', 'FAILED', 'COMPLETE'}:
            continue
        relative = path.relative_to(raw)
        size = path.stat().st_size
        if size > 8 * 1024 * 1024:
            inventory.append({'source': str(path), 'bytes': size, 'archived': False})
            continue
        target = output / 'raw' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        inventory.append({'source': str(path), 'archive': str(target.relative_to(output)),
                          'bytes': size, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest(), 'archived': True})
(output / 'archive_inventory.json').write_text(json.dumps(inventory, indent=2) + '\n')
log = raw / 'campaign.log'
if log.exists():
    with log.open('rb') as stream:
        stream.seek(max(0, log.stat().st_size - 128 * 1024))
        (output / 'campaign-tail.log').write_bytes(stream.read())
PY
}

on_exit() {
    local status=$?
    trap - EXIT
    if [[ "$CAMPAIGN_SUCCEEDED" != true ]]; then
        (( status != 0 )) || status=1
        write_status failed "$status"
        cp "$RESULT_ROOT/status.env" "$RESULT_ROOT/FAILED"
        if [[ -f "$RESULT_ROOT/canonical-components-before.json" && ! -f "$RESULT_ROOT/canonical-components-after.json" ]] \
           && declare -F canonical_snapshot >/dev/null; then
            canonical_snapshot after || echo 'Canonical byte verification failed; retain both manifests for inspection' >&2
        fi
        archive_small || echo 'WARNING: partial evidence archival failed' >&2
        python3 "$PUBLISHER" "$BUNDLE_ROOT" || echo 'WARNING: partial result presentation failed' >&2
    fi
    exit "$status"
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
write_status running

# This lock coordinates this runner's instances; also reject external workloads.
exec 9>/tmp/vcomp-cassandra-storage-campaign.lock
flock -n 9 || die 'Another Cassandra storage campaign holds the experiment lock'
require_no_db_bench 'Cassandra fidelity campaign'
if pgrep -f 'org[.]apache[.]cassandra[.]service[.]CassandraDaemon|CassandraBaseline[L]oad|CassandraPaper[W]orkload|pebble.*[b]ench|pebble[.]test' >/dev/null; then
    die 'Another Cassandra/Pebble benchmark is active'
fi
if ss -H -ltn | awk '{print $4}' | rg -q ':(7000|7001|7199|9042)$'; then
    die 'A Cassandra storage, JMX, or CQL port is already listening'
fi
rg -q 'GENERATOR_VERSION = "reference-streams-v3"' "$HARNESS/src/CassandraPaperWorkload.java" \
    || die 'The workload client must use reference-streams-v3'

export KEY_BYTES=24 VALUE_BYTES=1000 SEED=20260909 UCS_PICKER_SEED=20260909
export THREADS=48 OPERATIONS_PER_THREAD=0 DURATION_SECONDS=300 DISK_DEVICE=md0 MAX_HEAP_SIZE=4G
export BASELINE_TARGET_SSTABLE_SIZE=64MiB VCOMP_TARGET_SST_BYTES=67108864 VCOMP_SST_SIZE_MODEL=calibrated
export COMPACTION_DRAIN_TIMEOUT_SECONDS="${COMPACTION_DRAIN_TIMEOUT_SECONDS:-86400}"
export WORKLOADS='A B C D E F MIXGRAPH'
export DATASET_GIB PARTITION_COUNT KEY_SPACE BASELINE_KEYSPACE VCOMP_KEYSPACE RUN_TAG_PREFIX
{
    printf 'load_reuse=true\nload_campaign_root=%s\nload_result_root=%s\n' "$LOAD_CAMPAIGN_ROOT" "$LOAD_RESULT_ROOT"
    printf 'run_stamp=%s\nraw_root=%s\nresult_root=%s\n' "$RUN_STAMP" "$CAMPAIGN_ROOT" "$RESULT_ROOT"
    printf 'dataset_gib=%s\nkey_space=%s\npartition_count=%s\n' "$DATASET_GIB" "$KEY_SPACE" "$PARTITION_COUNT"
    printf 'key_bytes=24\nvalue_bytes=1000\nflush_bytes=67108864\ntarget_sst_bytes=67108864\nsst_size_model=calibrated\n'
    printf 'threads=48\nduration_seconds=300\noperations_per_thread=0\nworkloads=A B C D E F MIXGRAPH\n'
    printf 'compaction_throughput_mib_per_second=0\nworkload_memtable_heap_mib=1024\nworkload_memtable_cleanup_threshold=0.0625\nworkload_nominal_heap_flush_trigger_mib=64\n'
    printf 'seed=20260909\nucs_picker_seed=20260909\ngenerator_version=reference-streams-v3\n'
    printf 'checkpoint_method=hardlink_immutable_sstables\ncache_start=scoped_posix_fadvise_dontneed\n'
    printf 'cache_protocol=native_chunkcache_5pct_bounded_process_group\ncache_equivalence=not_paper_equivalent_additional_OS_page_cache\n'
    printf 'fidelity_rule=abs(vcomp/baseline-1)<=0.10\nrepetitions=1\nprimary_comparison_count=47\n'
    printf 'primary_workload_metrics=throughput,read_p50,read_p95,read_p99,disk_read_bytes,disk_write_bytes\n'
    printf 'primary_final_state_metrics=fingerprint_rows,sstable_count,data_bytes,component_bytes,max_sstable_bytes\n'
    printf 'started_at=%s\n' "$(date --iso-8601=seconds)"
} >"$RESULT_ROOT/campaign.env"
{
    printf '# Cassandra %s GiB fidelity rerun — running\n\n' "$DATASET_GIB"
    printf 'Follow [status.env](status.env) for the current phase and terminal status. '
    printf 'The completed canonical loads are reused; all seven workload pairs restart from fresh checkpoints.\n\n'
    printf 'Load and workload-reader binaries have separate provenance in load-reuse-provenance.json.\n\n'
    printf 'Raw logs and retained databases: `%s`.\n\n' "$CAMPAIGN_ROOT"
    printf 'Configuration: 24 B key + 1000 B value; 64 MiB flush/SST; %s partitions; ' "$PARTITION_COUNT"
    printf 'calibrated size model; seed 20260909; 48 workers, 300 seconds per A–F/MixGraph cell (time mode).\n\n'
    printf 'Results will be published here automatically. Completion and fidelity are separate: '
    printf 'the target is symmetric ±10%% similarity, excluding loading time and loading write amplification. '
    printf 'See [campaign.env](campaign.env) and [command.sh](command.sh) for reproducibility.\n'
} >"$RESULT_ROOT/README.md"
printf '%s\n' "$$" >"$RESULT_ROOT/campaign.pid"
df -h "$CAMPAIGN_ROOT" >"$RESULT_ROOT/disk-before.txt"
free -h >"$RESULT_ROOT/memory-before.txt"
git -C "$REPO_ROOT" rev-parse HEAD >"$RESULT_ROOT/source-revision.txt"
git -C "$REPO_ROOT" status --short >"$RESULT_ROOT/source-status.txt"
git -C "$REPO_ROOT" diff -- cassandra_vcomp cassandra_check experiments/scripts/cassandra resources/plot_cassandra_baseline_compare.py resources/plot_cassandra_paper_workloads.py >"$RESULT_ROOT/source-changes.patch"
# New untracked helper files are absent from git diff; retain their exact bytes.
(
    cd "$REPO_ROOT"
    git ls-files --others --exclude-standard -- cassandra_vcomp/src cassandra_vcomp/test cassandra_check experiments/scripts/cassandra experiments/analysis/analyze_cassandra_workload_diagnostics.py experiments/analysis/publish_experiment_bundle.py \
        | while IFS= read -r path; do
            mkdir -p "$RESULT_ROOT/new-source-files/$(dirname "$path")"
            cp "$path" "$RESULT_ROOT/new-source-files/$path"
        done
)
cp "${BASH_SOURCE[0]}" "$RESULT_ROOT/campaign-runner.sh"
# Retain the exact small entry points even when tracked; the base revision plus
# patch reproduces the core, and these snapshots make the measured command concrete.
(
    cd "$REPO_ROOT"
    for path in experiments/scripts/cassandra/run_resumed_fidelity_100g_campaign.sh \
                experiments/scripts/cassandra/run_fidelity_100g_campaign.sh \
                experiments/scripts/cassandra/run_bounded_workloads.sh \
                experiments/scripts/cassandra/monitor_workload_memory.py \
                experiments/analysis/analyze_cassandra_workload_diagnostics.py experiments/analysis/publish_experiment_bundle.py \
                resources/plot_cassandra_baseline_compare.py resources/plot_cassandra_paper_workloads.py \
                cassandra_check/run_baseline_20g.sh cassandra_check/run_pipeline_100g.sh \
                cassandra_check/run_baseline_vcomp_20g_compare.sh cassandra_check/run_existing_100g_paper_workloads.sh; do
        mkdir -p "$RESULT_ROOT/reproduction-files/$(dirname "$path")"
        cp "$path" "$RESULT_ROOT/reproduction-files/$path"
    done
)
(
    cd "$REPO_ROOT"
    {
        rg --files cassandra_vcomp/src/java cassandra_check experiments/scripts/cassandra resources experiments/analysis/analyze_cassandra_workload_diagnostics.py experiments/analysis/publish_experiment_bundle.py \
            | rg '(\.java$|cassandra_check/.*\.sh$|experiments/scripts/cassandra/.*\.(sh|py)$|resources/plot_cassandra_.*\.py$|analyze_cassandra_workload_diagnostics\.py$|publish_experiment_bundle\.py$)'
        rg --files cassandra_vcomp/conf
        rg --files cassandra_vcomp/test | rg 'ChunkCache[^/]*Test\.java$'
        printf '%s\n' cassandra_vcomp/build.xml cassandra_vcomp/build-env.sh cassandra_check/logback-smoke.xml
    } | sort -u | while IFS= read -r path; do sha256sum "$path"; done
) >"$RESULT_ROOT/source-files.sha256"
check_sources() {
    (cd "$REPO_ROOT" && sha256sum --check --status "$RESULT_ROOT/source-files.sha256") \
        || die 'Sources changed after launch; paired measurements would use different code'
}
{
    printf '#!/usr/bin/env bash\n'
    printf 'cd %q\n' "$REPO_ROOT"
    printf 'env RUN_STAMP=%q DATASET_GIB=%q CAMPAIGN_ROOT=%q RESULT_ROOT=%q LOAD_CAMPAIGN_ROOT=%q LOAD_RESULT_ROOT=%q COMPACTION_DRAIN_TIMEOUT_SECONDS=%q bash %q\n' \
        "$RUN_STAMP" "$DATASET_GIB" "$CAMPAIGN_ROOT" "$BUNDLE_ROOT" "$LOAD_CAMPAIGN_ROOT" "$LOAD_RESULT_ROOT" "$COMPACTION_DRAIN_TIMEOUT_SECONDS" "${BASH_SOURCE[0]}"
    printf '# Use NEW paths and RUN_STAMP for a repeat. Existing paths are deliberately refused.\n'
} >"$RESULT_ROOT/command.sh"

PHASE=validate_reused_loads
write_status running
check_sources
# The coordinator builds/tests the reader before invoking this runner. This
# hash identifies the reader used now, not the binary that created the loads.
RUNTIME_JAR=$(find "$REPO_ROOT/cassandra_vcomp/build" -maxdepth 1 -name 'apache-cassandra-*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)
[[ -n "$RUNTIME_JAR" ]] || die 'Qualified reader JAR is missing; build and test before launch'
sha256sum "$RUNTIME_JAR" >"$CAMPAIGN_ROOT/runtime-jar.sha256"
mkdir "$CAMPAIGN_ROOT/runtime"
RETAINED_RUNTIME_JAR="$CAMPAIGN_ROOT/runtime/$(basename "$RUNTIME_JAR")"
cp "$RUNTIME_JAR" "$RETAINED_RUNTIME_JAR"
sha256sum "$RETAINED_RUNTIME_JAR" >"$CAMPAIGN_ROOT/runtime-archive.sha256"
sha256sum --check --status "$CAMPAIGN_ROOT/runtime-jar.sha256" || die 'Reader JAR changed while archiving'
[[ "$(awk '{print $1}' "$CAMPAIGN_ROOT/runtime-jar.sha256")" == "$(awk '{print $1}' "$CAMPAIGN_ROOT/runtime-archive.sha256")" ]] \
    || die 'Retained reader JAR differs from the qualified runtime'

python3 - "$LOAD_CAMPAIGN_ROOT" "$LOAD_RESULT_ROOT" "$BASELINE_SOURCE" "$VCOMP_SOURCE" "$CAMPAIGN_ROOT" "$RESULT_ROOT" <<'PY'
import hashlib
import json
from pathlib import Path
import shutil
import sys

load_root, old_result, baseline, vcomp, raw, output = map(Path, sys.argv[1:])
def env(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line)
def hash_file(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
original = json.loads((old_result / 'final_state.json').read_text())
configs = {}
metrics = {}
for arm, source in [('baseline', baseline), ('vcomp', vcomp)]:
    assert source.resolve().is_relative_to(load_root.resolve()), (arm, source)
    assert Path(original[arm]['source']).resolve() == source.resolve(), arm
    config = env(source / 'configuration.txt')
    for key, expected in {'dataset_gib': '100', 'dataset_bytes': '107374182400',
                          'writes': '104857600', 'key_bytes': '24', 'value_bytes': '1000',
                          'partition_keys': '10000', 'seed': '20260909', 'ucs_picker_seed': '20260909',
                          'flush_bytes': '67108864', 'compaction_throughput_mib_per_second': '0',
                          'compression': 'disabled', 'durable_writes': 'false'}.items():
        assert config.get(key) == expected, (arm, key, config.get(key), expected)
    if arm == 'baseline':
        assert config['target_sstable_size'] == '64MiB'
    else:
        assert config['target_sst_bytes'] == '67108864' and config['sst_size_model'] == 'calibrated'
    configs[arm] = config
    metrics_name = 'baseline_metrics.env' if arm == 'baseline' else 'load_metrics.env'
    metrics[arm] = env(source / metrics_name)
    assert metrics[arm]['load_metric_boundary'] == 'load_start_through_natural_compaction_drain', arm
    assert int(metrics[arm]['fingerprint_rows']) > 0, arm
    assert int(metrics[arm]['final_db_bytes']) == original[arm]['component_bytes'], arm
    archive = raw / 'reused-load-evidence' / arm
    archive.mkdir(parents=True)
    for name in ('SUCCESS', 'configuration.txt', metrics_name, 'verification.log', 'fingerprint.log',
                 'sstable-sizes.tsv', 'tablestats.txt', 'compactionstats.log', 'post-load-compactionstats.log',
                 'compaction-enable.log', 'materialization_metrics.env', 'post_load_compaction_metrics.env'):
        path = source / name
        if path.is_file():
            shutil.copy2(path, archive / name)
old_archive = output / 'original-load-provenance'
old_archive.mkdir()
for name in ('source-revision.txt', 'source-status.txt', 'source-changes.patch', 'source-files.sha256',
             'campaign.env', 'campaign-runner.sh', 'status.env', 'FAILED', 'final_state.json'):
    path = old_result / name
    if path.is_file():
        shutil.copy2(path, old_archive / name)
shutil.copy2(load_root / 'runtime-jar.sha256', old_archive / 'load-runtime-jar.sha256')
shutil.copy2(load_root / 'COMPLETE', old_archive / 'paired-load-COMPLETE')
old_hash = (load_root / 'runtime-jar.sha256').read_text().split()[0]
new_hash = (raw / 'runtime-jar.sha256').read_text().split()[0]
assert old_hash != new_hash, 'The failed reader binary cannot be reused; qualify the repaired JAR first'
provenance = dict(load_campaign_root=str(load_root), load_result_root=str(old_result),
                  load_runtime_jar_sha256=old_hash, workload_reader_runtime_jar_sha256=new_hash,
                  binaries_identical=old_hash == new_hash,
                  original_load_binary_file_retained=False,
                  original_load_binary_available_evidence='recorded SHA256 and source provenance only; rebuilt before binary archival',
                  workload_reader_binary_file_retained=True,
                  workload_reader_binary_archive=str(next((raw / 'runtime').glob('*.jar'))),
                  workload_reader_archive_manifest=str(raw / 'runtime-archive.sha256'),
                  load_metrics_remeasured=False, workloads_restarted='all A-F/MixGraph pairs, fresh checkpoints',
                  original_final_state_path=str(old_result / 'final_state.json'),
                  original_final_state_sha256=hash_file(old_result / 'final_state.json'),
                  load_configs=configs, load_metrics=metrics,
                  note='The original campaign failed during workload A. Its successful loads and loading measurements are reused. Reader binary provenance is separate; no claim that the new reader created these loads.')
(output / 'load-reuse-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
shutil.copy2(old_result / 'final_state.json', output / 'final_state.json')
PY

canonical_snapshot() {
    local phase=$1
    python3 - "$BASELINE_SOURCE" "$VCOMP_SOURCE" "$BASELINE_KEYSPACE" "$VCOMP_KEYSPACE" "$RESULT_ROOT" "$phase" <<'PY'
import hashlib
import json
from pathlib import Path
import sys

baseline, vcomp, baseline_keyspace, vcomp_keyspace, output, phase = sys.argv[1:]
output = Path(output)
original = json.loads((output / 'final_state.json').read_text())
snapshot = {}
for arm, source, keyspace in [('baseline', baseline, baseline_keyspace), ('vcomp', vcomp, vcomp_keyspace)]:
    tables = list((Path(source) / 'data' / 'data' / keyspace).glob('kv-*'))
    assert len(tables) == 1, (arm, tables)
    files = sorted(path for path in tables[0].iterdir() if path.is_file())
    current = [{'name': path.name, 'bytes': path.stat().st_size} for path in files]
    assert current == original[arm]['files'], (arm, 'canonical files differ from completed-load inventory')
    snapshot[arm] = []
    for index, path in enumerate(files):
        digest = hashlib.sha256()
        before = path.stat()
        with path.open('rb') as stream:
            for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b''):
                digest.update(chunk)
        after = path.stat()
        assert (before.st_size, before.st_mtime_ns) == (after.st_size, after.st_mtime_ns), path
        snapshot[arm].append(dict(path=str(path), bytes=after.st_size, sha256=digest.hexdigest()))
        if index % 64 == 0:
            print(f'Canonical SHA256 {phase}: {arm} {index + 1}/{len(files)} components', flush=True)
path = output / f'canonical-components-{phase}.json'
path.write_text(json.dumps(snapshot, indent=2) + '\n')
if phase == 'after':
    before = json.loads((output / 'canonical-components-before.json').read_text())
    assert snapshot == before, 'Canonical SST component bytes changed during workloads'
PY
}
# Full file hashes are deliberately outside every timed workload interval.
canonical_snapshot before
check_sources
sha256sum --check --status "$CAMPAIGN_ROOT/runtime-jar.sha256" || die 'Reader JAR changed before workloads'
sha256sum --check --status "$CAMPAIGN_ROOT/runtime-archive.sha256" || die 'Retained reader JAR changed before workloads'

archive_small

cp "$BASELINE_SOURCE/baseline_metrics.env" "$RESULT_ROOT/baseline_metrics.env"
cp "$VCOMP_SOURCE/load_metrics.env" "$RESULT_ROOT/vcomp_metrics.env"
python3 "$PUBLISHER" "$BUNDLE_ROOT"
PHASE=workloads
write_status running
echo "[$(date --iso-8601=seconds)] Running A-F/MixGraph, 48 workers for 300 seconds per cell"
env BASELINE_SOURCE="$BASELINE_SOURCE" VCOMP_SOURCE="$VCOMP_SOURCE" OUT_ROOT="$WORKLOAD_ROOT" \
    SKIP_CASSANDRA_BUILD=true EXPECTED_RUNTIME_JAR_SHA256="$CAMPAIGN_ROOT/runtime-jar.sha256" \
    bash "$SCRIPT_DIR/run_bounded_workloads.sh"
[[ -f "$WORKLOAD_ROOT/SUCCESS" ]] || die 'Workload service exited without completing every requested cell'
check_sources
sha256sum --check --status "$CAMPAIGN_ROOT/runtime-jar.sha256" || die 'Runtime jar changed during workloads'
sha256sum --check --status "$CAMPAIGN_ROOT/runtime-archive.sha256" || die 'Retained reader JAR changed during workloads'
PHASE=verify_canonical_bytes
write_status running
canonical_snapshot after

PHASE=publication
write_status running
python3 "$REPO_ROOT/experiments/analysis/analyze_cassandra_workload_diagnostics.py" "$WORKLOAD_ROOT" "$RESULT_ROOT/diagnostics"
cp -a "$WORKLOAD_ROOT/results" "$RESULT_ROOT/results"
cp "$WORKLOAD_ROOT/configuration.txt" "$RESULT_ROOT/configuration.txt"
cp "$CAMPAIGN_ROOT/runtime-jar.sha256" "$RESULT_ROOT/runtime-jar.sha256"
cp "$CAMPAIGN_ROOT/runtime-archive.sha256" "$RESULT_ROOT/runtime-archive.sha256"
cp "$BASELINE_SOURCE/baseline_metrics.env" "$RESULT_ROOT/baseline_metrics.env"
cp "$VCOMP_SOURCE/load_metrics.env" "$RESULT_ROOT/vcomp_metrics.env"
cp -a "$LOAD_CAMPAIGN_ROOT/figures" "$RESULT_ROOT/loading_figures"
python3 "$REPO_ROOT/resources/plot_cassandra_paper_workloads.py" "$RESULT_ROOT"
mv "$RESULT_ROOT/README.md" "$RESULT_ROOT/workload_report.md"

python3 - "$RESULT_ROOT" <<'PY'
import csv
import json
import math
from pathlib import Path
import sys

root = Path(sys.argv[1])
order = ['A', 'B', 'C', 'D', 'E', 'F', 'MIXGRAPH']
rows = []
def compare(scope, metric, baseline, vcomp, primary=False):
    assert all(math.isfinite(float(value)) and value >= 0 for value in (baseline, vcomp)), (scope, metric)
    delta = 100 * (vcomp / baseline - 1) if baseline else None
    verdict = ('within_10pct' if abs(delta) <= 10 + 1e-9 else 'outside_10pct') if delta is not None else 'undefined_zero_baseline'
    rows.append(dict(scope=scope, metric=metric, baseline=baseline, vcomp=vcomp,
                     delta_percent=delta, verdict=verdict, primary=primary))
records = {}
for workload in order:
    pair = {arm: json.loads((root / 'results' / f'{workload.lower()}_{arm}.json').read_text())
            for arm in ('baseline', 'vcomp')}
    for arm, record in pair.items():
        assert record['generator_version'] == 'reference-streams-v3', (workload, arm)
        assert record['threads'] == 48 and record['operations'] > 0 and record['seed'] == 20260909, (workload, arm)
        assert record['wall_seconds'] >= 300, (workload, arm, record['wall_seconds'])
        assert record['measurement_mode'] == 'time' and record['requested_duration_seconds'] == 300, (workload, arm)
        assert record['operations_per_thread'] == 0, (workload, arm)
        assert record['operations'] == record['scans'] + record['writes'] + record['point_reads'] - (record['writes'] if workload == 'F' else 0), (workload, arm)
        assert record['point_read_hits'] + record['point_read_misses'] == record['point_reads'], (workload, arm)
        assert record['read_misses'] == record['point_read_misses'], (workload, arm)
        record['point_hit_fraction'] = record['point_read_hits'] / record['point_reads'] if record['point_reads'] else 0
        record['disk_read_bytes_per_operation'] = record['disk_read_bytes'] / record['operations']
        record['disk_write_bytes_per_operation'] = record['disk_write_bytes'] / record['operations']
        if workload == 'E':
            assert record['scans'] > 0 and 1 <= record['scan_length_min'] <= record['scan_length_max'] <= 100, (workload, arm)
            assert record['scan_returned_rows'] <= record['scan_requested_rows'], (workload, arm)
            record['disk_read_bytes_per_scan'] = record['disk_read_bytes'] / record['scans']
            record['returned_rows_per_scan'] = record['scan_returned_rows'] / record['scans']
    for field in ('definition', 'key_distribution'):
        assert pair['baseline'][field] == pair['vcomp'][field], (workload, field)
    fields = ['throughput_ops_per_second', 'point_reads', 'writes', 'scans', 'point_read_hits',
              'point_read_misses', 'point_hit_fraction', 'disk_read_bytes', 'disk_write_bytes',
              'disk_read_bytes_per_operation', 'disk_write_bytes_per_operation',
              'scan_requested_rows', 'scan_returned_rows', 'scan_returned_bytes', 'scan_cql_requests', 'empty_scans']
    if workload == 'E':
        fields += ['disk_read_bytes_per_scan', 'returned_rows_per_scan']
    # Client histograms are numeric microseconds; device means may be null and
    # disk_latency_scope is descriptive text, not another percentile.
    fields += [name for name in pair['baseline'] if '_latency_' in name and name.endswith('_us')]
    fields += [name for name in ('disk_read_latency_avg_ms', 'disk_write_latency_avg_ms')
               if pair['baseline'].get(name) is not None and pair['vcomp'].get(name) is not None]
    for field in fields:
        latency_kind = 'scan_latency' if workload == 'E' else 'point_lookup_latency'
        primary = field in ('throughput_ops_per_second', 'disk_read_bytes', 'disk_write_bytes') or field in [f'{latency_kind}_p{p}_us' for p in (50, 95, 99)]
        compare(workload, field, pair['baseline'][field], pair['vcomp'][field], primary)
    records[workload] = pair
def read_env(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line)
load = {arm: read_env(root / f'{arm}_metrics.env') for arm in ('baseline', 'vcomp')}
compare('final_state', 'fingerprint_rows', int(load['baseline']['fingerprint_rows']), int(load['vcomp']['fingerprint_rows']), True)
states = json.loads((root / 'final_state.json').read_text())
for metric in ('sstable_count', 'data_bytes', 'component_bytes', 'max_sstable_bytes'):
    compare('final_state', metric, states['baseline'][metric], states['vcomp'][metric], True)
primary = [row for row in rows if row['primary']]
assert len(primary) == 47, len(primary)
violations = [row for row in primary if row['verdict'] != 'within_10pct']
summary = {'fidelity_rule': 'abs(vcomp / baseline - 1) <= 0.10',
           'repetitions': 1, 'primary_comparison_count': 47, 'primary_within_10pct': not violations, 'primary_violations': violations,
           'zero_baseline_rule': 'relative difference undefined; never mark as passing', 'comparisons': rows}
(root / 'fidelity.json').write_text(json.dumps(summary, indent=2) + '\n')
with (root / 'fidelity.csv').open('w', newline='') as output:
    writer = csv.DictWriter(output, fieldnames=list(rows[0]))
    writer.writeheader()
    writer.writerows(rows)
config = read_env(root / 'campaign.env')
lines = [f"# Cassandra {config['dataset_gib']} GiB fidelity rerun", '',
         'Measurement completed. Fidelity result: **' + ('all primary comparisons within ±10%' if not violations else
         f'{len(violations)} primary comparisons outside ±10% or undefined') + '**.', '',
         f"Raw data and retained databases: `{config['raw_root']}`.", '',
         'Reused completed native UCS baseline and VComp loads; 24 B key + 1000 B value, 64 MiB flush/target SST, '
         f"{config['partition_count']} ordered partitions, calibrated VComp size model, seed 20260909. "
         'A-F and MixGraph each run for 300 seconds with 48 workers. Operation counts differ naturally in time mode. '
         'Baseline and VComp run serially; each workload uses an independent hard-linked checkpoint. '
         'Canonical loaded databases are preserved and their complete application SST component bytes '
         'are SHA256-verified before and after workloads. The failed original A run is retained separately; '
         'all fourteen cells restart. Load and workload-reader binaries have separate provenance in '
         '[load-reuse-provenance.json](load-reuse-provenance.json). Loading measurements were not repeated.', '',
         'The fidelity target is similarity, with the same ±10% bound for faster and slower results. '
         'Load time and load write amplification are excluded. The 47 preregistered primary comparisons '
         'include throughput, read p50/p95/p99, and raw device read/write bytes for every workload, '
         'plus five final-state metrics. Per-operation I/O remains diagnostic. Workload I/O, operation latency, '
         'hit/miss counts, separate hit/miss latency, per-operation device I/O, and E scan row/byte counters '
         'are recorded in fidelity.csv/json. Time mode intentionally allows different operation counts; '
         'raw device bytes and bytes per operation must be interpreted together. '
         'Final-state sizes and SST counts come from live files after natural compaction drain on both arms.', '',
         '| Workload | Throughput Δ | Read p50 Δ | Read p95 Δ | Read p99 Δ |',
         '|---|---:|---:|---:|---:|']
def formatted(scope, metric):
    row = next(row for row in rows if row['scope'] == scope and row['metric'] == metric)
    return 'n/a' if row['delta_percent'] is None else f"{row['delta_percent']:+.2f}%"
for workload in order:
    latency_kind = 'scan_latency' if workload == 'E' else 'point_lookup_latency'
    metrics = ['throughput_ops_per_second'] + [f'{latency_kind}_p{p}_us' for p in (50, 95, 99)]
    lines.append('| ' + workload + ' | ' + ' | '.join(formatted(workload, metric) for metric in metrics) + ' |')
lines += ['', 'All percentages are `(VComp / baseline − 1) × 100`. E reports scan latency; other rows report point lookup latency.', '',
          'Limits: one repetition, baseline first in each pair, scoped page-cache advice rather than guaranteed '
          'cold caches, a native chunk cache sized to 5% of logical input plus additional OS page cache inside '
          'the bounded process group (not the paper cache configuration), shared-device I/O counters, '
          'model-generated approximate key membership, and the remaining '
          'difference between standalone descriptor scheduling and native task execution. '
          'A completed campaign does not imply algorithmic equivalence or statistically established fidelity.', '',
          'Reused loading time/device writes include natural compaction drain on both arms under the original load binary. Final sizes/counts '
          'describe live files after drain. Materialization-only and post-load compaction phases are separately archived under raw/.', '',
          'Files: [workload figures](paper_workloads.svg), [workload report](workload_report.md), '
          '[all comparisons](fidelity.csv), [machine-readable summary](fidelity.json), '
          '[final live-file inventory](final_state.json), [archive provenance](archive_inventory.json).', '']
(root / 'README.md').write_text('\n'.join(lines))
PY
archive_small
df -h "$CAMPAIGN_ROOT" >"$RESULT_ROOT/disk-after.txt"
PHASE=complete
write_status complete
python3 "$PUBLISHER" "$BUNDLE_ROOT"
cp "$RESULT_ROOT/status.env" "$RESULT_ROOT/COMPLETE"
CAMPAIGN_SUCCEEDED=true
echo "[$(date --iso-8601=seconds)] Completed: $RESULT_ROOT (see fidelity.json for similarity verdict)"
