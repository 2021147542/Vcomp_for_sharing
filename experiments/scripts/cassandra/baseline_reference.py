#!/usr/bin/env python3
"""Register and guard an existing Cassandra baseline; never load or start a DB."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import shutil
import sys


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(8 << 20), b''):
            h.update(block)
    return h.hexdigest()


def read_env(path):
    return dict(line.split('=', 1) for line in Path(path).read_text().splitlines()
                if '=' in line and not line.startswith('#'))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def stat_record(path):
    st = Path(path).stat()
    return dict(bytes=st.st_size, device=st.st_dev, inode=st.st_ino,
                mtime_ns=st.st_mtime_ns, ctime_ns=st.st_ctime_ns)


def overlap(left, right):
    left, right = Path(left).resolve(), Path(right).resolve()
    return left == right or left in right.parents or right in left.parents


def register(bundle, output, repo):
    logs = Path(bundle).resolve() / 'logs'
    output, repo = Path(output).resolve(), Path(repo).resolve()
    require(not output.exists(), f'Reference already exists: {output}')
    before_path, after_path = (logs / f'canonical-components-{s}.json' for s in ('before', 'after'))
    before, after = (json.loads(p.read_text())['baseline'] for p in (before_path, after_path))
    require(before and before == after, 'Baseline before/after SHA256 evidence differs or is empty')
    completion = json.loads((logs / 'completion-verification.json').read_text())
    require(completion.get('canonical_component_manifests_identical') is True,
            'Campaign did not verify canonical component preservation')
    provenance = json.loads((logs / 'load-reuse-provenance.json').read_text())
    final = json.loads((logs / 'final_state.json').read_text())['baseline']
    canonical = Path(final['source']).resolve()
    require(not overlap(output.parent, canonical), 'Reference metadata must be outside the canonical DB')
    config = read_env(canonical / 'configuration.txt')
    require(config == provenance['load_configs']['baseline'], 'Canonical load configuration changed')
    require((canonical / 'SUCCESS').is_file(), 'Canonical load has no SUCCESS')
    require('values_verified=true' in (canonical / 'fingerprint.log').read_text(),
            'Full deterministic value verification is absent')
    rows = []
    table_dirs = set()
    for old in before:
        path = Path(old['path']).resolve()
        require(canonical in path.parents and not Path(old['path']).is_symlink(),
                f'Component outside canonical root or symlink: {path}')
        info = stat_record(path)
        require(info['bytes'] == old['bytes'], f'Component size changed: {path}')
        require(max(info['mtime_ns'], info['ctime_ns']) <= after_path.stat().st_mtime_ns,
                f'Component metadata newer than completed SHA256 evidence: {path}; full audit required')
        rows.append(dict(path=str(path), sha256=old['sha256'], **info))
        table_dirs.add(str(path.parent))
    expected = {r['path'] for r in rows}
    actual = {str(p.resolve()) for d in table_dirs for p in Path(d).iterdir() if p.is_file()}
    require(expected == actual, 'Live table component inventory differs from completed SHA256 evidence')
    require(sum(r['bytes'] for r in rows) == final['component_bytes'], 'Final state byte count mismatch')
    old_sources = logs / 'original-load-provenance/source-files.sha256'
    source_hashes = dict((line.split(maxsplit=1)[1], line.split(maxsplit=1)[0])
                         for line in old_sources.read_text().splitlines() if line)
    input_names = ['cassandra_check/src/CassandraBaselineLoad.java'] + [
        'cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/' + name + '.java'
        for name in ('DeterministicFixedWidthKeyCodec', 'VCompOrderedPartitionLayout',
                     'VCompCqlSstableMaterializer')]
    for name in input_names:
        require(digest(repo / name) == source_hashes[name],
                f'Input/schema source differs from original load; recover archived source: {name}')
    # No writes to the canonical database: only copy small provenance to a new directory.
    output.parent.mkdir(parents=True, exist_ok=False)
    archive = output.parent / 'evidence'
    archive.mkdir()
    evidence = []

    def preserve(source, relative):
        target = archive / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        evidence.append(dict(path=str(target.relative_to(output.parent)),
                             source=str(source), sha256=digest(target)))

    for name in ('canonical-components-before.json', 'canonical-components-after.json',
                 'completion-verification.json', 'load-reuse-provenance.json', 'final_state.json',
                 'configuration.txt', 'runtime-jar.sha256', 'runtime-archive.sha256'):
        preserve(logs / name, Path('campaign') / name)
    for p in sorted((logs / 'original-load-provenance').iterdir()):
        if p.is_file():
            preserve(p, Path('load-provenance') / p.name)
    for name in ('SUCCESS', 'configuration.txt', 'baseline_metrics.env', 'fingerprint.log',
                 'verification.log', 'sstable-sizes.tsv', 'tablestats.txt'):
        preserve(canonical / name, Path('canonical') / name)
    for p in sorted((canonical / 'conf').rglob('*')):
        if p.is_file():
            preserve(p, Path('canonical/conf') / p.relative_to(canonical / 'conf'))
    for name in input_names:
        preserve(repo / name, Path('input-source') / name)
    manifest = dict(
        version=1, reference_id='cassandra-100g-seed-' + config['seed'] + '-' + config['run_id'],
        registered_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        canonical_root=str(canonical), keyspace='baseline_' + config['dataset_gib'] + 'g',
        table_directories=sorted(table_dirs), load_config=config,
        input_identity=dict(seed=config['seed'], writes=config['writes'],
                            generator='java.util.SplittableRandom(seed).nextLong(writes)',
                            timestamp='ordinal + 1', key_bytes=config['key_bytes'],
                            value_bytes=config['value_bytes'], partition_keys=config['partition_keys'],
                            source_sha256={name: source_hashes[name] for name in input_names}),
        schema_identity=dict(source='evidence/input-source/cassandra_check/src/CassandraBaselineLoad.java',
                             method='createBaselineSchema',
                             column_definition='partition_id text, ck blob, value blob, PRIMARY KEY ((partition_id), ck)',
                             compaction={'class': 'UnifiedCompactionStrategy', 'scaling_parameters': 'T4',
                                         'target_sstable_size': config['target_sstable_size'],
                                         'base_shard_count': '1', 'sstable_growth': '0.333'}),
        original_load_jar_sha256=provenance['load_runtime_jar_sha256'],
        original_load_binary_retained=provenance['original_load_binary_file_retained'],
        previous_reader_jar_sha256=provenance['workload_reader_runtime_jar_sha256'],
        previous_reader_binary_archive=provenance['workload_reader_binary_archive'],
        final_state={k: v for k, v in final.items() if k != 'files'},
        load_metrics=provenance['load_metrics']['baseline'], components=rows, evidence=evidence,
        integrity=dict(registration_mode='prior matching full SHA256 audits plus current inventory/stat continuity',
                       new_component_content_hash_performed=False,
                       proof_limit='Stat continuity is not a new cryptographic read of component contents.',
                       prior_after_manifest_sha256=digest(after_path)),
        reuse_policy=dict(preserve_canonical=True, start_daemon_on_canonical=False,
                          workload_source='fresh disposable checkpoint for every workload',
                          independent_copy_preferred=True,
                          hardlinks='Only for immutable SST components; verify full SHA256 after campaign.',
                          old_performance_numbers='Historical only; rerun baseline reads if reader, workload, settings or host changes.',
                          seed_selection='20260909 retained from prior campaign; no outcome-based selection',
                          baseline_reload='Only if input/schema/native baseline load definition changes, or integrity fails.'))
    output.write_text(json.dumps(manifest, indent=2) + '\n')
    return dict(reference=str(output), component_count=len(rows),
                component_bytes=sum(r['bytes'] for r in rows), new_content_hash_performed=False)


def load_reference(path):
    ref = json.loads(Path(path).read_text())
    require(ref.get('version') == 1 and ref.get('components'), 'Unsupported or empty reference')
    return ref


def validate(reference, full=False):
    reference = Path(reference).resolve()
    ref = load_reference(reference)
    for row in ref['evidence']:
        require(digest(reference.parent / row['path']) == row['sha256'],
                f'Provenance evidence changed: {row["path"]}')
        # Publishers read these canonical metrics/config files directly. Their
        # archived copies alone cannot establish that the live copies are intact.
        if Path(row['path']).is_relative_to('evidence/canonical'):
            source = Path(row['source'])
            require(Path(ref['canonical_root']).resolve() in source.resolve().parents,
                    f'Canonical provenance source escaped reference root: {source}')
            require(not source.is_symlink() and digest(source) == row['sha256'],
                    f'Canonical provenance file changed: {source}')
    expected = {r['path'] for r in ref['components']}
    actual = {str(p.resolve()) for d in ref['table_directories'] for p in Path(d).iterdir() if p.is_file()}
    require(expected == actual, 'Canonical live component inventory changed')
    for row in ref['components']:
        current = stat_record(row['path'])
        require(not Path(row['path']).is_symlink(), f'Canonical component is now a symlink: {row["path"]}')
        if full:
            require(current['bytes'] == row['bytes'] and digest(row['path']) == row['sha256'],
                    f'Canonical component content changed: {row["path"]}')
            require(current == stat_record(row['path']), f'Component changed while hashing: {row["path"]}')
        else:
            require(current == {k: row[k] for k in current},
                    f'Canonical component stat changed; run validate --full: {row["path"]}')
    return dict(valid=True, mode='sha256' if full else 'stat-and-provenance',
                components=len(expected), canonical_root=ref['canonical_root'])


def guard(reference, targets):
    reference = Path(reference).resolve()
    ref = load_reference(reference)
    protected = [ref['canonical_root'], str(reference.parent)]
    # Also catch different path names sharing a protected component inode.
    inodes = {(r['device'], r['inode']) for r in ref['components']}
    for target in targets:
        path = Path(target).resolve()
        require(not any(overlap(path, p) for p in protected),
                f'Refusing canonical/reference mutation target: {target}')
        files = path.rglob('*') if path.is_dir() else [path]
        for p in files:
            if p.is_file():
                st = p.stat()
                require((st.st_dev, st.st_ino) not in inodes,
                        f'Target contains canonical hardlink alias: {p}')
    return dict(allowed=True, targets=list(targets))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    reg = commands.add_parser('register')
    reg.add_argument('--bundle', required=True)
    reg.add_argument('--output', required=True)
    reg.add_argument('--repo', default=str(Path(__file__).resolve().parents[3]))
    check = commands.add_parser('validate')
    check.add_argument('--reference', required=True)
    check.add_argument('--full', action='store_true', help='Read all SST bytes; keep outside timed measurements')
    protect = commands.add_parser('guard')
    protect.add_argument('--reference', required=True)
    protect.add_argument('--path', action='append', required=True, help='Proposed writable/delete/output path')
    args = parser.parse_args()
    try:
        if args.command == 'register':
            result = register(args.bundle, args.output, args.repo)
        elif args.command == 'validate':
            result = validate(args.reference, args.full)
        else:
            result = guard(args.reference, args.path)
        print(json.dumps(result, indent=2))
    except (ValueError, OSError, KeyError) as exc:
        parser.exit(1, f'baseline reference rejected: {exc}\n')


if __name__ == '__main__':
    main()
