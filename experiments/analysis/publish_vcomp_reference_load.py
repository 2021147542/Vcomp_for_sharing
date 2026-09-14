#!/usr/bin/env python3
"""Archive a VComp-only load with a preserved historical baseline comparison."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess

from publish_experiment_bundle import publish


def env(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('campaign', type=Path)
    parser.add_argument('bundle', type=Path)
    args = parser.parse_args()
    raw, root = args.campaign.resolve(), args.bundle.resolve()
    if root.exists():
        raise ValueError('Result bundle already exists')
    pair = env(raw / 'COMPLETE')
    reuse = json.loads((raw / 'baseline-reuse.json').read_text())
    sources = {arm: Path(pair[arm + '_run']).resolve() for arm in ('baseline', 'vcomp')}
    if sources['baseline'] != Path(reuse['canonical_root']).resolve():
        raise ValueError('Completed baseline differs from registered reference')
    for source in sources.values():
        if not (source / 'SUCCESS').is_file():
            raise ValueError('Missing successful load: ' + str(source))
    logs = root / 'logs'
    logs.mkdir(parents=True)
    shutil.copy2(raw / 'COMPLETE', logs / 'paired-load-COMPLETE')
    for name in ('baseline-reuse.json', 'baseline-reference-after.json', 'runtime-jar.sha256'):
        shutil.copy2(raw / name, logs / name)
    # Keep the immutable reference evidence together, not a broken relative copy.
    reference = Path(reuse['reference'])
    shutil.copytree(reference.parent, logs / 'baseline-reference')
    for arm, source in sources.items():
        metrics = 'baseline_metrics.env' if arm == 'baseline' else 'load_metrics.env'
        shutil.copy2(source / metrics, logs / (arm + '_metrics.env'))
        for name in ('configuration.txt', 'fingerprint.log', 'verification.log', 'SUCCESS'):
            shutil.copy2(source / name, logs / (arm + '_' + name))
    shutil.copy2(sources['vcomp'] / 'configuration.txt', logs / 'configuration.txt')
    if (raw / 'figures').is_dir():
        shutil.copytree(raw / 'figures', logs / 'loading_figures')
    for name in ('build-jar.log', 'controller-runtime.javap', 'properties-runtime.javap'):
        if (raw / name).is_file():
            shutil.copy2(raw / name, logs / name)
    repo = Path(__file__).resolve().parents[2]
    (logs / 'source-revision.txt').write_text(subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True))
    (logs / 'source-changes.patch').write_bytes(subprocess.check_output(['git', 'diff', '--', 'cassandra_vcomp', 'cassandra_check'], cwd=repo))
    (logs / 'status.env').write_text('status=complete\nphase=vcomp_load_only\nbaseline_load_repeated=false\nworkloads_run=false\n')
    (logs / 'README.md').write_text('# VComp load against preserved baseline\n\n'
        'Only VComp was loaded in this iteration. Baseline loading metrics retain their original measurement date and binary provenance. '
        'No old workload measurements are promoted to a fresh comparison.\n\n'
        f'Raw load directory: `{raw}`. Baseline: `{sources["baseline"]}`. VComp: `{sources["vcomp"]}`.\n')
    publish(root)
    print(root / 'results.md')


if __name__ == '__main__':
    main()
