#!/usr/bin/env python3
"""Explain Cassandra C/E I/O using retained evidence only; never query a database."""

import argparse
import hashlib
import json
import math
from pathlib import Path
import re


LIMITS = [
    'Client disk bytes are shared-device /proc/diskstats deltas over the workload measurement interval; they are neither unique data bytes nor Cassandra-only I/O.',
    'Daemon /proc/PID/io and node/cache counters bracket a wider interval including client setup. Chunk-cache counters are node-wide. Their per-operation ratios are diagnostic, not exact workload cache-miss rates.',
    'SSTables-per-read quantiles are table histogram snapshots, not per-request traces. Before/after quantiles cannot be subtracted or attributed exclusively to C/E.',
    'Returned scan bytes are client payload bytes as defined by the recorded generator; they exclude storage metadata, protocol framing and physical block rounding.',
    'Cgroup memory.stat file includes page cache and other file-backed charges for the whole daemon/client group. Peaks span the full workload suite and cannot identify one workload without timestamp alignment.',
    'Native chunk cache plus bounded total memory is not the paper\'s exact 5% total-cache configuration. Cache miss counts and SST-read histograms can support a mechanism but do not prove causality.',
    'Missing historical fields remain null/unavailable. This tool does not reconstruct them as zero or infer an unmeasured cause.',
]


def numeric(token):
    try:
        value = float(token)
    except (TypeError, ValueError):
        return None
    return value if math.isfinite(value) else None


def divide(value, denominator):
    if value is None or denominator is None or denominator <= 0:
        return None
    return value / denominator


def bytes_value(text):
    match = re.fullmatch(r'([\d.]+)\s*(bytes?|[KMGT]iB)', text.strip())
    if not match:
        return None
    scale = {'byte': 1, 'bytes': 1, 'KiB': 1024, 'MiB': 1024**2,
             'GiB': 1024**3, 'TiB': 1024**4}[match[2]]
    return float(match[1]) * scale


def chunk_cache(text):
    match = re.search(r'^Chunk Cache\s*:\s*entries (\d+), size ([^,]+), capacity ([^,]+), '
                      r'(\d+) misses, (\d+) requests, (\S+) recent hit rate, (\S+) (\S+) miss latency',
                      text, re.MULTILINE)
    if not match:
        return None
    return dict(entries=int(match[1]), size_bytes=bytes_value(match[2]),
                capacity_bytes=bytes_value(match[3]), misses=int(match[4]), requests=int(match[5]),
                recent_hit_rate=numeric(match[6]), miss_latency=numeric(match[7]),
                miss_latency_unit=match[8], capacity_precision='human-readable nodetool rounding')


def histograms(text):
    rows = {}
    for line in text.splitlines():
        values = line.split()
        if len(values) == 6 and re.fullmatch(r'\d+%|Min|Max', values[0]):
            rows[values[0]] = dict(read_latency_us=numeric(values[1]), write_latency_us=numeric(values[2]),
                                  sstables_per_read=numeric(values[3]), partition_bytes=numeric(values[4]),
                                  cell_count=numeric(values[5]))
    return rows or None


def process_io(text):
    return {match[1]: int(match[2]) for match in re.finditer(r'^(\w+):\s*(\d+)\s*$', text, re.MULTILINE)} or None


def counter_delta(before, after, key):
    if before is None or after is None or key not in before or key not in after:
        return None
    value = after[key] - before[key]
    return value if value >= 0 else None


class Evidence:
    def __init__(self):
        self.files = []

    def read(self, path):
        if not path.is_file():
            return None
        content = path.read_bytes()
        self.files.append(dict(path=str(path.resolve()), bytes=len(content),
                               sha256=hashlib.sha256(content).hexdigest()))
        return content.decode()

    def parsed(self, path, parser):
        text = self.read(path)
        return parser(text) if text is not None else None


def arm_diagnostics(root, workload, arm, evidence):
    record = evidence.parsed(root / 'results' / f'{workload.lower()}_{arm}.json', json.loads)
    if record is None:
        return None
    run = root / 'runs' / workload.lower() / arm
    cache = {when: evidence.parsed(run / f'node-info-{when}.txt', chunk_cache) for when in ('before', 'after')}
    tables = {when: evidence.parsed(run / f'tablehistograms-{when}.txt', histograms) for when in ('before', 'after')}
    io = {when: evidence.parsed(run / f'daemon-io-{when}.txt', process_io) for when in ('before', 'after')}
    operations, scans = record.get('operations'), record.get('scans')
    misses = counter_delta(cache['before'], cache['after'], 'misses')
    requests = counter_delta(cache['before'], cache['after'], 'requests')
    daemon_reads = counter_delta(io['before'], io['after'], 'read_bytes')
    metrics = {key: record.get(key) for key in (
        'wall_seconds', 'operations', 'throughput_ops_per_second', 'disk_read_bytes', 'disk_write_bytes',
        'point_reads', 'point_read_hits', 'point_read_misses', 'scans', 'scan_requested_rows',
        'scan_returned_rows', 'scan_returned_bytes', 'scan_cql_requests', 'empty_scans')}
    metrics.update(disk_read_bytes_per_operation=divide(record.get('disk_read_bytes'), operations),
                   disk_write_bytes_per_operation=divide(record.get('disk_write_bytes'), operations),
                   disk_read_bytes_per_scan=divide(record.get('disk_read_bytes'), scans),
                   returned_rows_per_scan=divide(record.get('scan_returned_rows'), scans),
                   returned_bytes_per_scan=divide(record.get('scan_returned_bytes'), scans),
                   device_read_bytes_per_returned_scan_byte=divide(record.get('disk_read_bytes'), record.get('scan_returned_bytes')),
                   point_hit_fraction=divide(record.get('point_read_hits'), record.get('point_reads')),
                   coarse_chunk_misses=misses, coarse_chunk_requests=requests,
                   coarse_chunk_miss_fraction=divide(misses, requests),
                   coarse_chunk_misses_per_operation=divide(misses, operations),
                   coarse_daemon_read_bytes=daemon_reads,
                   coarse_daemon_read_bytes_per_operation=divide(daemon_reads, operations))
    for percentile in ('50%', '95%', '99%', 'Max'):
        metrics['after_sstables_per_read_' + percentile.replace('%', 'pct').lower()] = (
            (tables['after'] or {}).get(percentile, {}).get('sstables_per_read'))
    return dict(generator_version=record.get('generator_version'), seed=record.get('seed'),
                measurement_mode=record.get('measurement_mode', 'unrecorded'), metrics=metrics,
                chunk_cache=cache, table_histograms=tables, daemon_io=io)


def memory_diagnostics(root, evidence):
    control = Path(str(root) + '-control')
    protocol = evidence.parsed(control / 'protocol.json', json.loads)
    trace = evidence.read(control / 'memory.jsonl')
    summary = None
    if trace is not None:
        records = [json.loads(line) for line in trace.splitlines() if line.strip()]
        if records:
            summary = dict(samples=len(records), first_unix_seconds=records[0].get('unix_seconds'),
                           last_unix_seconds=records[-1].get('unix_seconds'),
                           peak_memory_current=max(row.get('memory_current', 0) for row in records),
                           peak_file_bytes=max(row.get('memory_stat', {}).get('file', 0) for row in records),
                           peak_anon_bytes=max(row.get('memory_stat', {}).get('anon', 0) for row in records),
                           final_memory_events=records[-1].get('memory_events'))
    return dict(protocol=protocol, whole_suite_trace_summary=summary)


def formatted(value):
    if value is None:
        return 'unavailable'
    return f'{value:,.3f}' if isinstance(value, float) else f'{value:,}'


def analyze(root):
    evidence = Evidence()
    output = dict(workload_root=str(root.resolve()), completion_marker_present=any(
        (root / name).is_file() for name in ('SUCCESS', 'COMPLETE')), limits=LIMITS, workloads={})
    for workload in ('C', 'E'):
        pair = {arm: arm_diagnostics(root, workload, arm, evidence) for arm in ('baseline', 'vcomp')}
        comparisons = {}
        if all(pair.values()):
            for metric, baseline in pair['baseline']['metrics'].items():
                vcomp = pair['vcomp']['metrics'][metric]
                ratio = divide(vcomp, baseline)
                comparisons[metric] = dict(baseline=baseline, vcomp=vcomp,
                                           delta_percent=100 * (ratio - 1) if ratio is not None else None)
        output['workloads'][workload] = dict(arms=pair, comparisons=comparisons)
    output['memory'] = memory_diagnostics(root, evidence)
    output['evidence_files'] = evidence.files
    return output


def markdown(result):
    lines = ['# Cassandra C/E retained-evidence diagnostics', '',
             f"Source: `{result['workload_root']}`.", '',
             f"Completion marker present: {result['completion_marker_present']}. Missing records are unavailable, not zero.", '']
    metrics = ['operations', 'throughput_ops_per_second', 'disk_read_bytes', 'disk_read_bytes_per_operation',
               'point_hit_fraction', 'scans', 'scan_requested_rows', 'scan_returned_rows', 'scan_returned_bytes',
               'disk_read_bytes_per_scan', 'returned_rows_per_scan',
               'coarse_chunk_misses', 'coarse_chunk_miss_fraction', 'coarse_chunk_misses_per_operation',
               'coarse_daemon_read_bytes', 'coarse_daemon_read_bytes_per_operation',
               'after_sstables_per_read_50pct', 'after_sstables_per_read_95pct', 'after_sstables_per_read_99pct']
    for workload, value in result['workloads'].items():
        lines += [f'## {workload}', '', '| Metric | Baseline | VComp | Δ VComp / baseline |', '|---|---:|---:|---:|']
        for metric in metrics:
            row = value['comparisons'].get(metric)
            if row is None:
                continue
            delta = 'unavailable' if row['delta_percent'] is None else f"{row['delta_percent']:+.2f}%"
            lines.append(f"| {metric} | {formatted(row['baseline'])} | {formatted(row['vcomp'])} | {delta} |")
        if not value['comparisons']:
            lines.append('| Pair incomplete | unavailable | unavailable | unavailable |')
        lines += ['']
    lines += ['## Interpretation boundaries', ''] + [f'- {limit}' for limit in LIMITS]
    memory = result['memory']['whole_suite_trace_summary']
    lines += ['', '## Whole-suite memory', '', json.dumps(memory, indent=2) if memory else 'Memory trace unavailable.', '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('workload_root', type=Path)
    parser.add_argument('output_dir', type=Path)
    args = parser.parse_args()
    if not args.workload_root.is_dir():
        parser.error('workload_root must be an existing directory')
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for name in ('diagnostic.json', 'diagnostic.md'):
        if (args.output_dir / name).exists():
            parser.error(f'refusing to overwrite {args.output_dir / name}')
    result = analyze(args.workload_root)
    (args.output_dir / 'diagnostic.json').write_text(json.dumps(result, indent=2, allow_nan=False) + '\n')
    (args.output_dir / 'diagnostic.md').write_text(markdown(result))


if __name__ == '__main__':
    main()
