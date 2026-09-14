#!/usr/bin/env bash
set -euo pipefail

# Fresh paired load and seven serial, fixed-operation workload pairs.
# Launch in tmux or with nohup; never reuse an existing campaign directory.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../../lib/common.sh"
HARNESS="$REPO_ROOT/cassandra_check"
RUN_STAMP="${RUN_STAMP:-$(date +%Y%m%d-%H%M%S)}"
DATASET_GIB="${DATASET_GIB:-100}"
require_positive_uint DATASET_GIB
CAMPAIGN_ROOT="${CAMPAIGN_ROOT:-$VCOMP_DB_ROOT/cassandra-fidelity-${DATASET_GIB}g-$RUN_STAMP}"
RESULT_ROOT="${RESULT_ROOT:-$REPO_ROOT/resources/experiments/${RUN_STAMP}_cassandra_${DATASET_GIB}g_fidelity_rerun}"
KEY_SPACE=$((DATASET_GIB * 1024 * 1024))
PARTITION_COUNT=$((DATASET_GIB * 100))
RUN_TAG_PREFIX="fidelity-${DATASET_GIB}g-$RUN_STAMP"
BASELINE_KEYSPACE="baseline_${DATASET_GIB}g"
VCOMP_KEYSPACE="vcomp_${DATASET_GIB}g"
WORKLOAD_ROOT="$CAMPAIGN_ROOT/workloads-fixed-20k"
BASELINE_SOURCE=""
VCOMP_SOURCE=""
PHASE=preflight
CAMPAIGN_SUCCEEDED=false

[[ ! -e "$CAMPAIGN_ROOT" && ! -L "$CAMPAIGN_ROOT" ]] || die "Campaign path already exists: $CAMPAIGN_ROOT"
[[ ! -e "$RESULT_ROOT" && ! -L "$RESULT_ROOT" ]] || die "Result path already exists: $RESULT_ROOT"
mkdir -p "$(dirname "$CAMPAIGN_ROOT")" "$(dirname "$RESULT_ROOT")"
mkdir "$CAMPAIGN_ROOT" "$RESULT_ROOT"
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
            '.yaml', '.options', '.xml', '.properties'}
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
        archive_small || echo 'WARNING: partial evidence archival failed' >&2
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
rg -q 'GENERATOR_VERSION = "split-streams-v2"' "$HARNESS/src/CassandraPaperWorkload.java" \
    || die 'The workload client must use split-streams-v2'

export KEY_BYTES=24 VALUE_BYTES=1000 SEED=20260909 UCS_PICKER_SEED=20260909
export THREADS=48 OPERATIONS_PER_THREAD=20000 DISK_DEVICE=md0 MAX_HEAP_SIZE=4G
export BASELINE_TARGET_SSTABLE_SIZE=64MiB VCOMP_TARGET_SST_BYTES=67108864 VCOMP_SST_SIZE_MODEL=calibrated
export COMPACTION_DRAIN_TIMEOUT_SECONDS="${COMPACTION_DRAIN_TIMEOUT_SECONDS:-86400}"
export WORKLOADS='A B C D E F MIXGRAPH'
export DATASET_GIB PARTITION_COUNT KEY_SPACE BASELINE_KEYSPACE VCOMP_KEYSPACE RUN_TAG_PREFIX
{
    printf 'run_stamp=%s\nraw_root=%s\nresult_root=%s\n' "$RUN_STAMP" "$CAMPAIGN_ROOT" "$RESULT_ROOT"
    printf 'dataset_gib=%s\nkey_space=%s\npartition_count=%s\n' "$DATASET_GIB" "$KEY_SPACE" "$PARTITION_COUNT"
    printf 'key_bytes=24\nvalue_bytes=1000\nflush_bytes=67108864\ntarget_sst_bytes=67108864\nsst_size_model=calibrated\n'
    printf 'threads=48\noperations_per_thread=20000\nworkloads=A B C D E F MIXGRAPH\n'
    printf 'seed=20260909\nucs_picker_seed=20260909\ngenerator_version=split-streams-v2\n'
    printf 'checkpoint_method=hardlink_immutable_sstables\ncache_start=scoped_posix_fadvise_dontneed\n'
    printf 'fidelity_rule=abs(vcomp/baseline-1)<=0.10\nrepetitions=1\n'
    printf 'started_at=%s\n' "$(date --iso-8601=seconds)"
} >"$RESULT_ROOT/campaign.env"
{
    printf '# Cassandra %s GiB fidelity rerun — running\n\n' "$DATASET_GIB"
    printf 'Follow [status.env](status.env) for the current phase and terminal status. '
    printf 'The fresh baseline load runs first, then VComp, then seven serial workload pairs.\n\n'
    printf 'Raw logs and retained databases: `%s`.\n\n' "$CAMPAIGN_ROOT"
    printf 'Configuration: 24 B key + 1000 B value; 64 MiB flush/SST; %s partitions; ' "$PARTITION_COUNT"
    printf 'calibrated size model; seed 20260909; 48 workers × 20,000 operations per A–F/MixGraph cell.\n\n'
    printf 'Results will be published here automatically. Completion and fidelity are separate: '
    printf 'the target is symmetric ±10%% similarity, excluding loading time and loading write amplification. '
    printf 'See [campaign.env](campaign.env) and [command.sh](command.sh) for reproducibility.\n'
} >"$RESULT_ROOT/README.md"
printf '%s\n' "$$" >"$RESULT_ROOT/campaign.pid"
df -h "$CAMPAIGN_ROOT" >"$RESULT_ROOT/disk-before.txt"
free -h >"$RESULT_ROOT/memory-before.txt"
git -C "$REPO_ROOT" rev-parse HEAD >"$RESULT_ROOT/source-revision.txt"
git -C "$REPO_ROOT" status --short >"$RESULT_ROOT/source-status.txt"
git -C "$REPO_ROOT" diff -- cassandra_vcomp cassandra_check >"$RESULT_ROOT/source-changes.patch"
cp "${BASH_SOURCE[0]}" "$RESULT_ROOT/campaign-runner.sh"
(
    cd "$REPO_ROOT"
    rg --files cassandra_vcomp/src/java cassandra_check experiments/scripts/cassandra resources \
        | rg '(\.java$|cassandra_check/.*\.sh$|run_fidelity_100g_campaign\.sh$|resources/plot_cassandra_.*\.py$)' \
        | sort | while IFS= read -r path; do sha256sum "$path"; done
) >"$RESULT_ROOT/source-files.sha256"
check_sources() {
    (cd "$REPO_ROOT" && sha256sum --check --status "$RESULT_ROOT/source-files.sha256") \
        || die 'Sources changed after launch; paired measurements would use different code'
}
{
    printf '#!/usr/bin/env bash\n'
    printf 'cd %q\n' "$REPO_ROOT"
    printf 'env RUN_STAMP=%q DATASET_GIB=%q CAMPAIGN_ROOT=%q RESULT_ROOT=%q COMPACTION_DRAIN_TIMEOUT_SECONDS=%q bash %q\n' \
        "$RUN_STAMP" "$DATASET_GIB" "$CAMPAIGN_ROOT" "$RESULT_ROOT" "$COMPACTION_DRAIN_TIMEOUT_SECONDS" "${BASH_SOURCE[0]}"
    printf '# Use NEW paths and RUN_STAMP for a repeat. Existing paths are deliberately refused.\n'
} >"$RESULT_ROOT/command.sh"

PHASE=paired_load
write_status running
echo "[$(date --iso-8601=seconds)] Loading fresh ${DATASET_GIB} GiB baseline, then VComp"
check_sources
env EXPERIMENT_ROOT="$CAMPAIGN_ROOT" bash "$HARNESS/run_baseline_vcomp_20g_compare.sh"
BASELINE_SOURCE=$(readlink -f "$CAMPAIGN_ROOT/baseline/latest-$RUN_TAG_PREFIX-baseline")
VCOMP_SOURCE=$(readlink -f "$CAMPAIGN_ROOT/vcomp/latest-$RUN_TAG_PREFIX-vcomp")
for source in "$BASELINE_SOURCE" "$VCOMP_SOURCE"; do
    [[ -f "$source/SUCCESS" ]] || die "Missing successful retained database: $source"
done
check_sources
sha256sum --check --status "$CAMPAIGN_ROOT/runtime-jar.sha256" || die 'Runtime jar changed after paired load'

# Count the final live files after natural compaction drain on both arms.
python3 - "$BASELINE_SOURCE" "$VCOMP_SOURCE" "$BASELINE_KEYSPACE" "$VCOMP_KEYSPACE" "$RESULT_ROOT" <<'PY'
import json
from pathlib import Path
import sys

baseline, vcomp, baseline_keyspace, vcomp_keyspace, output = sys.argv[1:]
states = {}
for arm, source, keyspace in [('baseline', baseline, baseline_keyspace), ('vcomp', vcomp, vcomp_keyspace)]:
    tables = list((Path(source) / 'data' / 'data' / keyspace).glob('kv-*'))
    assert len(tables) == 1, (arm, tables)
    files = sorted(path for path in tables[0].iterdir() if path.is_file())
    data = [path for path in files if path.name.endswith('-Data.db')]
    assert data, arm
    states[arm] = {'source': source, 'sstable_count': len(data),
                   'data_bytes': sum(path.stat().st_size for path in data),
                   'component_bytes': sum(path.stat().st_size for path in files),
                   'max_sstable_bytes': max(path.stat().st_size for path in data),
                   'files': [{'name': path.name, 'bytes': path.stat().st_size} for path in files]}
(Path(output) / 'final_state.json').write_text(json.dumps(states, indent=2) + '\n')
PY
archive_small

PHASE=workloads
write_status running
echo "[$(date --iso-8601=seconds)] Running A-F/MixGraph, 48 workers x 20,000 operations"
env BASELINE_SOURCE="$BASELINE_SOURCE" VCOMP_SOURCE="$VCOMP_SOURCE" OUT_ROOT="$WORKLOAD_ROOT" \
    bash "$HARNESS/run_existing_100g_paper_workloads.sh"
check_sources
sha256sum --check --status "$CAMPAIGN_ROOT/runtime-jar.sha256" || die 'Runtime jar changed during workloads'

PHASE=publication
write_status running
cp -a "$WORKLOAD_ROOT/results" "$RESULT_ROOT/results"
cp "$WORKLOAD_ROOT/configuration.txt" "$RESULT_ROOT/configuration.txt"
cp "$CAMPAIGN_ROOT/runtime-jar.sha256" "$RESULT_ROOT/runtime-jar.sha256"
cp "$BASELINE_SOURCE/baseline_metrics.env" "$RESULT_ROOT/baseline_metrics.env"
cp "$VCOMP_SOURCE/load_metrics.env" "$RESULT_ROOT/vcomp_metrics.env"
cp -a "$CAMPAIGN_ROOT/figures" "$RESULT_ROOT/loading_figures"
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
        assert record['generator_version'] == 'split-streams-v2', (workload, arm)
        assert record['threads'] == 48 and record['operations'] == 960000 and record['seed'] == 20260909, (workload, arm)
        assert record['point_read_hits'] + record['point_read_misses'] == record['point_reads'], (workload, arm)
        assert record['read_misses'] == record['point_read_misses'], (workload, arm)
        record['point_hit_fraction'] = record['point_read_hits'] / record['point_reads'] if record['point_reads'] else 0
    for field in ('operations', 'point_reads', 'writes', 'scans', 'definition', 'key_distribution'):
        assert pair['baseline'][field] == pair['vcomp'][field], (workload, field)
    fields = ['throughput_ops_per_second', 'point_reads', 'writes', 'scans', 'point_read_hits',
              'point_read_misses', 'point_hit_fraction', 'disk_read_bytes', 'disk_write_bytes']
    fields += [name for name in pair['baseline'] if '_latency_' in name]
    for field in fields:
        latency_kind = 'scan_latency' if workload == 'E' else 'point_lookup_latency'
        primary = field == 'throughput_ops_per_second' or field in [f'{latency_kind}_p{p}_us' for p in (50, 95, 99)]
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
violations = [row for row in primary if row['verdict'] != 'within_10pct']
summary = {'fidelity_rule': 'abs(vcomp / baseline - 1) <= 0.10',
           'repetitions': 1, 'primary_within_10pct': not violations, 'primary_violations': violations,
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
         'One fresh native UCS baseline and one VComp load; 24 B key + 1000 B value, 64 MiB flush/target SST, '
         f"{config['partition_count']} ordered partitions, calibrated VComp size model, seed 20260909. "
         'A-F and MixGraph each execute 960,000 operations (48 workers × 20,000), using split-streams-v2. '
         'Baseline and VComp run serially; each workload uses an independent hard-linked checkpoint. '
         'Canonical loaded databases are preserved.', '',
         'The fidelity target is similarity, with the same ±10% bound for faster and slower results. '
         'Load time and load write amplification are excluded. Workload I/O, operation latency, '
         'hit/miss counts and separate hit/miss latency are recorded in fidelity.csv/json. '
         'Final-state sizes and SST counts come from live files after natural compaction drain on both arms.', '',
         '| Workload | Throughput Δ | Point p50 Δ | Point p95 Δ | Point p99 Δ |',
         '|---|---:|---:|---:|---:|']
def formatted(scope, metric):
    row = next(row for row in rows if row['scope'] == scope and row['metric'] == metric)
    return 'n/a' if row['delta_percent'] is None else f"{row['delta_percent']:+.2f}%"
for workload in order:
    metrics = ['throughput_ops_per_second'] + [f'point_lookup_latency_p{p}_us' for p in (50, 95, 99)]
    lines.append('| ' + workload + ' | ' + ' | '.join(formatted(workload, metric) for metric in metrics) + ' |')
lines += ['', 'All percentages are `(VComp / baseline − 1) × 100`. E uses scans, so point latency is not applicable.', '',
          'Limits: one repetition, baseline first in each pair, scoped page-cache advice rather than guaranteed '
          'cold caches, shared-device I/O counters, model-generated approximate key membership, and the remaining '
          'difference between standalone descriptor scheduling and native task execution. '
          'A completed campaign does not imply algorithmic equivalence or statistically established fidelity.', '',
          'The legacy loading figures retain their original metric boundaries: baseline includes compaction '
          'drain while VComp timing/device writes stop before post-import compaction; VComp plotted physical '
          'SST size/count describe materialization outputs. Use final_state.json and fidelity.csv for the '
          'symmetric final-state comparison. Post-load compaction metrics are archived under raw/.', '',
          'Files: [workload figures](paper_workloads.svg), [workload report](workload_report.md), '
          '[all comparisons](fidelity.csv), [machine-readable summary](fidelity.json), '
          '[final live-file inventory](final_state.json), [archive provenance](archive_inventory.json).', '']
(root / 'README.md').write_text('\n'.join(lines))
PY
archive_small
df -h "$CAMPAIGN_ROOT" >"$RESULT_ROOT/disk-after.txt"
PHASE=complete
write_status complete
cp "$RESULT_ROOT/status.env" "$RESULT_ROOT/COMPLETE"
CAMPAIGN_SUCCEEDED=true
echo "[$(date --iso-8601=seconds)] Completed: $RESULT_ROOT (see fidelity.json for similarity verdict)"
