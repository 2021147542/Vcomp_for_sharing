#!/usr/bin/env python3
"""Publish three figures and matching tables from an archived experiment's logs/.

Only explicit result locations are considered. A presentation_sources.json
manifest can expose named pilots without treating them as full benchmarks.
Missing data are None, never invented zeroes.
"""
import argparse
import json
import math
from pathlib import Path
import re
import textwrap

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Patch
import numpy as np

from restore_paper_figure_style import restore

WORKLOADS = ['A', 'B', 'C', 'D', 'E', 'F', 'MIXGRAPH']
SYSTEMS = ('baseline', 'vcomp')
COLORS = ('#426c9b', '#df8543')
plt.rcParams.update({'svg.fonttype': 'none', 'font.family': 'DejaVu Sans',
                     'axes.spines.top': False, 'axes.spines.right': False})


def read_json(path):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return None


def number(value):
    if isinstance(value, bool):
        return None
    try:
        result = float(value)
        return result if math.isfinite(result) else None
    except (ValueError, TypeError):
        return None


def first(data, *keys):
    for key in keys:
        if key in data and number(data[key]) is not None:
            return number(data[key])
    return None


def env(path):
    if not path.is_file():
        return {}
    return dict(re.findall(r'^([A-Za-z0-9_]+)=(.*)$', path.read_text(), re.M))


def relative(path, root):
    return str(path.relative_to(root))


def cell(value):
    if value is None:
        return 'N/A'
    if value == int(value):
        return format(int(value), ',')
    return format(value, ',.6f').rstrip('0').rstrip('.')


def scaled(value, divisor):
    return None if value is None else value / divisor


def load_rows(root, audit):
    logs = root / 'logs'
    rows, notes = [], []
    selection = read_json(logs / 'presentation_sources.json')
    if selection:
        for item in selection.get('loading', []):
            source = root / item['source']
            data = env(source)
            if not data:
                raise ValueError('Missing selected loading metrics: ' + str(source))
            rows.append(dict(group=item['group'], system=item['system'],
                             seconds=first(data, 'load_seconds'),
                             write_gb=scaled(first(data, 'disk_write_bytes'), 1e9),
                             final_gb=scaled(first(data, 'final_db_bytes'), 1e9),
                             write_amplification=first(data, 'write_amplification'),
                             sst_count=first(data, 'sstable_count'),
                             visible_rows=first(data, 'fingerprint_rows'),
                             source=item['source'], note=''))
        return rows, selection.get('notes', [])
    explicit = read_json(logs / 'primary_loading.json')
    if isinstance(explicit, dict) and isinstance(explicit.get('rows'), list):
        return explicit['rows'], explicit.get('notes', [])
    iteration = read_json(logs / 'metrics.json')
    if isinstance(iteration, list) and iteration and 'version' in iteration[0]:
        for version in iteration:
            for system in SYSTEMS:
                rows.append({'group': version['version'], 'system': system,
                             'seconds': number(version.get(system + '_seconds')),
                             'write_gb': scaled(number(version.get(system + '_write_gib')), 1e9 / 2**30),
                             'final_gb': scaled(number(version.get(system + '_final_gib')), 1e9 / 2**30),
                             'sst_count': number(version.get(system + '_sstables')),
                             'visible_rows': number(version.get(system + '_rows')),
                             'source': 'logs/metrics.json', 'note': version.get('caveat', '')})
        notes.append('Iteration versions have different configurations/scales; each row retains its version and original caveat.')
        return rows, notes
    if audit:
        return [], ['Audit/qualification bundle: nested pilot and historical comparison measurements are not promoted to primary results.']
    paper_path = logs / 'paper_load_result.json'
    paper = read_json(paper_path)
    if isinstance(paper, dict):
        for system in SYSTEMS:
            data = paper.get(system, paper.get('virtual', {}) if system == 'vcomp' else {})
            available = paper.get(system + '_load_metrics_available', True) is not False
            available = available and data.get('load_metrics_available', True) is not False
            row = {'group': 'Primary', 'system': system,
                   'seconds': first(data, 'loading_seconds', 'load_seconds') if available else None,
                   'write_amplification': first(data, 'write_amplification') if available else None,
                   'write_gb': scaled(first(data, 'total_disk_write_bytes', 'disk_write_bytes'), 1e9) if available else None,
                   'final_gb': scaled(first(data, 'final_db_bytes'), 1e9),
                   'sst_count': first(data, 'sst_count', 'sstable_count'),
                   'visible_rows': first(data, 'visible_rows', 'fingerprint_rows'),
                   'source': relative(paper_path, root), 'note': ''}
            if not available:
                row['note'] = 'Load timing/write metrics explicitly unavailable; placeholder zeros omitted.'
            rows.append(row)
        return rows, notes
    for system in SYSTEMS:
        candidates = [logs / (system + '_metrics.env'), logs / (system + '_load_metrics.env'),
                      logs / 'load' / (system + '_metrics.env'), logs / 'load' / (system + '_load_metrics.env')]
        candidates += sorted((logs / 'raw' / system).glob('*/' + ('baseline_metrics.env' if system == 'baseline' else 'load_metrics.env')))
        data, sources = {}, []
        for candidate in candidates:
            values = env(candidate)
            if values:
                sources.append(relative(candidate, root))
                for key, value in values.items():
                    data.setdefault(key, value)
        # Materializer log metrics are distinct from settled live state.
        log_candidates = [logs / 'load' / 'vcomp.log', logs / 'vcomp.log'] if system == 'vcomp' else []
        if system == 'vcomp':
            log_candidates += sorted((logs / 'raw' / 'vcomp').glob('*/vcomp.log'))
        for candidate in log_candidates:
            if candidate.is_file():
                matches = re.findall(r'^VCOMP_RESULT (.*)$', candidate.read_text(), re.M)
                if matches:
                    values = dict(re.findall(r'(\w+)=([^ ]+)', matches[-1]))
                    data.setdefault('load_seconds', values.get('seconds'))
                    data.setdefault('final_db_bytes', values.get('physical_sst_bytes'))
                    data.setdefault('sstable_count', values.get('physical_sst_count'))
                    sources.append(relative(candidate, root))
                    break
        final_path = logs / 'final_state.json'
        final = read_json(final_path)
        if isinstance(final, dict) and system in final:
            for key, target in [('component_bytes', 'final_db_bytes'), ('sstable_count', 'sstable_count')]:
                if key in final[system]:
                    data[target] = final[system][key]
            sources.append(relative(final_path, root))
        if sources:
            rows.append({'group': 'Primary', 'system': system,
                         'seconds': first(data, 'load_seconds', 'loading_seconds'),
                         'write_amplification': first(data, 'write_amplification'),
                         'write_gb': scaled(first(data, 'disk_write_bytes', 'total_disk_write_bytes'), 1e9),
                         'final_gb': scaled(first(data, 'final_db_bytes'), 1e9),
                         'sst_count': first(data, 'sstable_count', 'sst_count'),
                         'visible_rows': first(data, 'fingerprint_rows'),
                         'source': '; '.join(dict.fromkeys(sources)), 'note': ''})
    # Explicit system-per-row loading tables are a rounded fallback only.
    # Do not parse arbitrary prose or derive measurements from chart pixels.
    markdown_candidates = sorted(logs.glob('*.md'))
    markdown_candidates += sorted((logs / 'loading_figures').glob('*.md'))
    markdown_candidates += sorted((logs / 'raw' / 'figures').glob('*.md'))
    for candidate in markdown_candidates:
        headers = None
        for line in candidate.read_text().splitlines():
            if not line.startswith('|'):
                headers = None
                continue
            fields = [part.strip() for part in line.strip('|').split('|')]
            if fields and ((fields[0].lower() == 'system' and any('time' in x.lower() for x in fields))
                           or (fields[0].lower() == 'metric' and any('baseline' in x.lower() for x in fields))):
                headers = fields
                continue
            if not headers or not fields:
                continue
            if headers[0].lower() == 'metric':
                name = fields[0].lower()
                metric = ('seconds' if name in ('completion time', 'loading time', 'load time')
                          else 'write_gb' if name in ('device writes', 'disk writes')
                          else 'final_gb' if name in ('final physical size', 'final db size')
                          else 'sst_count' if name == 'final sst count'
                          else 'visible_rows' if name in ('live rows', 'visible rows')
                          else 'write_amplification' if name in ('write amp', 'write amplification') else None)
                if metric:
                    for system, value in zip(SYSTEMS, fields[1:3]):
                        match = re.match(r'([0-9.,]+)', value)
                        parsed = number(match.group(1).replace(',', '')) if match else None
                        row = next((item for item in rows if item['system'] == system), None)
                        if row is None:
                            row = dict(group='Primary', system=system, source='', note='')
                            rows.append(row)
                        if parsed is not None and row.get(metric) is None:
                            if metric.endswith('_gb') and 'gib' in value.lower(): parsed *= 2**30 / 1e9
                            row[metric] = parsed
                            if relative(candidate, root) not in row['source']:
                                row['source'] += ('; ' if row['source'] else '') + relative(candidate, root)
                            row['note'] = 'Missing fields filled from preserved rounded loading table.'
                continue
            if fields[0].lower() not in SYSTEMS:
                continue
            system = fields[0].lower()
            row = next((item for item in rows if item['system'] == system), None)
            if row is None:
                row = dict(group='Primary', system=system, source='', note='')
                rows.append(row)
            used = False
            for header, value in zip(headers, fields):
                heading = header.lower()
                metric = ('write_amplification' if 'write amp' in heading else 'seconds' if 'time' in heading else 'write_gb' if 'write' in heading and 'amp' not in heading
                          else 'final_gb' if 'final' in heading else 'sst_count' if 'sst count' in heading else None)
                parsed = number(value.replace(',', '').replace('×', ''))
                if metric and parsed is not None and row.get(metric) is None:
                    if metric.endswith('_gb') and 'gib' in heading: parsed *= 2**30 / 1e9
                    row[metric] = parsed
                    used = True
            if used:
                row['source'] += ('; ' if row['source'] else '') + relative(candidate, root)
                row['note'] = 'Missing fields filled from preserved rounded loading table.'
    if rows:
        notes.append('Cassandra historical load windows can differ: baseline may include drain, while VComp loader timing/write bytes may end before import/settling. Consult the preserved README/configuration; these values are not silently converted into symmetric windows.')
    return rows, notes


def workload_rows(root, audit, location=None):
    selection = read_json(root / 'logs' / 'presentation_sources.json') if location is None else None
    if selection:
        rows = []
        for item in selection['workloads']:
            selected = workload_rows(root, False, root / item['directory'])
            if not selected:
                raise ValueError('Missing selected workload metrics: ' + item['directory'])
            for row in selected:
                row['original_workload'] = row['workload']
                row['workload'] = item['label'] + ' / ' + row['workload']
            rows.extend(selected)
        return rows
    if audit:
        return []
    logs = root / 'logs'
    locations = [logs / 'results', logs]
    locations += sorted((logs / 'raw').glob('workloads*/results'))
    if location is not None:
        locations = [location]
    rows = []
    for workload in WORKLOADS:
        for system in SYSTEMS:
            selected, data = None, None
            aliases = [system, 'virtual'] if system == 'vcomp' else [system]
            for folder in locations:
                for name in (workload, workload.lower()):
                    for alias in aliases:
                        path = folder / (name + '_' + alias + '.json')
                        candidate = read_json(path)
                        if isinstance(candidate, dict) and 'throughput_ops_per_second' in candidate:
                            selected, data = path, candidate
                            break
                    if data is not None:
                        break
                if data is not None:
                    break
            if data is None:
                continue
            kind = 'scan' if workload == 'E' else 'point lookup'
            prefix = 'scan_latency_' if kind == 'scan' else 'point_lookup_latency_'
            point_absent = kind != 'scan' and data.get('point_reads') == 0
            row = {'workload': workload, 'system': system,
                   'throughput': first(data, 'throughput_ops_per_second'),
                   'read_gb': scaled(first(data, 'disk_read_bytes'), 1e9),
                   'write_gb': scaled(first(data, 'disk_write_bytes'), 1e9),
                   'latency_kind': kind, 'source': relative(selected, root),
                   'disk_read_latency_avg_ms': first(data, 'disk_read_latency_avg_ms'),
                   'disk_write_latency_avg_ms': first(data, 'disk_write_latency_avg_ms'),
                   'disk_latency_scope': data.get('disk_latency_scope'),
                   'disk_read_requests': first(data, 'disk_read_requests'),
                   'disk_write_requests': first(data, 'disk_write_requests'),
                   'disk_read_time_ms': first(data, 'disk_read_time_ms'),
                   'disk_write_time_ms': first(data, 'disk_write_time_ms'),
                   'disk_device': data.get('disk_device'),
                   'wall_seconds': first(data, 'wall_seconds'),
                   'operations': first(data, 'operations'),
                   'measurement_mode': data.get('measurement_mode', 'not recorded'),
                   'generator_version': data.get('generator_version', 'not recorded')}
            for percentile in (50, 95, 99):
                value = first(data, prefix + 'p' + str(percentile) + '_us')
                # E's all-zero point histogram does not represent scan latency.
                row['p' + str(percentile)] = None if point_absent or (kind == 'scan' and data.get('scans') == 0) else value
            rows.append(row)
    if rows and location is None:
        found = {(row['workload'], row['system']): row for row in rows}
        rows = [found.get((workload, system), {
            'workload': workload, 'system': system, 'latency_kind': 'scan' if workload == 'E' else 'point lookup',
            'source': 'N/A (no primary result)'}) for workload in WORKLOADS for system in SYSTEMS]
    return rows


def empty_figure(path, title, message):
    figure, axis = plt.subplots(figsize=(12, 4))
    axis.axis('off')
    axis.text(.5, .70, title, fontsize=18, weight='bold', ha='center', transform=axis.transAxes)
    axis.text(.5, .43, 'N/A — no primary measurement', fontsize=16, ha='center', transform=axis.transAxes)
    axis.text(.5, .20, textwrap.fill(message, 110), fontsize=10, ha='center', va='center', transform=axis.transAxes)
    figure.savefig(path, format='svg', bbox_inches='tight')
    plt.close(figure)


def grouped_figure(path, rows, group_key, groups, panels, title, footer):
    if not rows:
        empty_figure(path, title, footer)
        return
    figure, axes = plt.subplots(len(panels), 1, figsize=(max(12, len(groups) * .95), max(9, len(panels) * 2.6)), squeeze=False)
    positions = np.arange(len(groups))
    systems = list(dict.fromkeys(row['system'] for row in rows))
    if set(systems).issubset(SYSTEMS): systems = list(SYSTEMS)
    width = .8 / len(systems)
    for axis, (metric, label) in zip(axes.flat, panels):
        for index, system in enumerate(systems):
            color = COLORS[index % len(COLORS)] if index < 2 else '#609c83'
            lookup = {row[group_key]: row.get(metric) for row in rows if row['system'] == system}
            values = [lookup.get(group) for group in groups]
            for position, value in zip(positions + (index - (len(systems) - 1) / 2) * width, values):
                if value is None:
                    axis.text(position, .02, 'N/A', rotation=90, fontsize=7, ha='center',
                              color=color, transform=axis.get_xaxis_transform())
                else:
                    axis.bar(position, value, width=width * .94, color=color)
        axis.set_ylabel(label)
        axis.set_xlim(-.6, len(groups) - .4)
        axis.set_xticks(positions)
        short = [group.split('_', 1)[0] if len(group) > 24 else group for group in groups]
        axis.set_xticklabels(short)
        axis.set_ylim(bottom=0)
        axis.grid(axis='y', alpha=.18)
        axis.set_axisbelow(True)
    handles = [Patch(color=COLORS[index] if index < 2 else '#609c83', label=system.capitalize()) for index, system in enumerate(systems)]
    axes.flat[0].legend(handles=handles, loc='upper right', ncol=min(3, len(systems)))
    figure.suptitle(title, fontsize=16, y=.995)
    figure.text(.5, .01, textwrap.fill(footer, 140), ha='center', va='bottom', fontsize=9)
    figure.tight_layout(rect=(0, .06, 1, .97))
    figure.savefig(path, format='svg')
    plt.close(figure)


def markdown_table(headers, rows):
    lines = ['| ' + ' | '.join(headers) + ' |', '| ' + ' | '.join(['---'] * len(headers)) + ' |']
    lines.extend('| ' + ' | '.join(str(value).replace('|', '\\|') for value in row) + ' |' for row in rows)
    return '\n'.join(lines)


def publish(root):
    root = root.resolve()
    logs = root / 'logs'
    if not logs.is_dir():
        raise ValueError('Bundle must already contain logs/: ' + str(root))
    audit = any(term in root.name.lower() for term in ('fidelity_audit', 'settings_audit', 'preflight', 'repair', 'diagnosis', 'certificate'))
    # A primary result explicitly imported by the organizer overrides name-based audit handling.
    if (logs / 'paper_load_result.json').is_file():
        audit = False
    loading, notes = load_rows(root, audit)
    workloads = workload_rows(root, audit)
    selection = read_json(logs / 'presentation_sources.json') or {}
    groups = list(dict.fromkeys(row['workload'] for row in workloads)) if selection else WORKLOADS
    figures = root / 'figures'
    figures.mkdir(exist_ok=True)
    physical = 'Physical disk I/O latency and write-only latency were not measured. E uses scan latency; other workloads use client point-lookup latency.'
    device_available = any(row.get(key) is not None for row in workloads
                           for key in ('disk_read_latency_avg_ms', 'disk_write_latency_avg_ms'))
    device_note = ('Device-layer averages = diskstats accumulated milliseconds / completed requests; '
                   'includes all processes and in-flight boundary effects. Not NVMe physical latency percentiles.')
    latency_panels = [('p50', 'Client p50 (microseconds)'), ('p95', 'Client p95 (microseconds)'),
                      ('p99', 'Client p99 (microseconds)')]
    if device_available:
        latency_panels += [('disk_read_latency_avg_ms', 'Device average read (ms)'),
                           ('disk_write_latency_avg_ms', 'Device average write (ms)')]
    grouped_figure(figures / 'io_latency.svg', workloads, 'workload', groups, latency_panels,
                   (selection.get('title', '') + '\n' if selection else '') +
                   ('DB client and device-layer latency (separate metrics)' if device_available
                    else 'DB read / scan latency (client), not physical disk I/O'),
                   (device_note + ' Client: E scan, others point lookup.' if device_available else physical) + ' N/A is not zero.')
    status = env(logs / 'status.env')
    fidelity = read_json(logs / 'fidelity.json')
    fidelity_summary = None
    if isinstance(fidelity, dict) and isinstance(fidelity.get('primary_within_10pct'), bool):
        count = first(fidelity, 'primary_comparison_count')
        comparisons = fidelity.get('comparisons')
        if count is None and isinstance(comparisons, list) and comparisons and all(isinstance(item.get('primary'), bool) for item in comparisons):
            count = sum(item['primary'] for item in comparisons)
        violations = fidelity.get('primary_violations')
        violation_count = len(violations) if isinstance(violations, list) else number(violations)
        fidelity_summary = {'primary_within_10pct': fidelity['primary_within_10pct'],
                            'primary_comparison_count': count, 'primary_violation_count': violation_count,
                            'source': 'logs/fidelity.json'}
    normalized = {'fidelity_summary': fidelity_summary, 'status': status, 'bundle': root.name, 'audit_only': audit and not loading and not workloads, 'loading': loading, 'workloads': workloads,
                  'presentation': selection,
                  'notes': notes, 'physical_io_latency': None,
                  'physical_io_latency_note': device_note if device_available else physical,
                  'device_latency_available': device_available, 'device_latency_note': device_note,
                  'units': {'volume': 'GB = 10^9 bytes', 'client_latency': 'microseconds', 'device_average_latency': 'milliseconds'}}
    (logs / 'presentation.json').write_text(json.dumps(normalized, ensure_ascii=False, indent=2) + '\n')
    lines = ['# ' + root.name, '',
             '그래프와 아래 표는 동일한 [정규화 값](logs/presentation.json)을 사용합니다. 원본 측정값은 `logs/`에 보존했습니다. GB는 10⁹ bytes이며, N/A는 미측정·누락·주 결과 없음입니다. N/A를 0으로 그리지 않습니다.', '',
             '[적재 그래프](figures/loading.svg) · [Workload 그래프](figures/workload.svg) · [읽기/scan latency 그래프](figures/io_latency.svg)', '']
    if fidelity_summary is not None:
        verdict = '통과' if fidelity_summary['primary_within_10pct'] else '미통과'
        lines += ['**원본의 ±10% 유사성 판정: ' + verdict + '.** 주 비교 ' + cell(fidelity_summary['primary_comparison_count'])
                  + '개 중 위반 ' + cell(fidelity_summary['primary_violation_count'])
                  + '개입니다. [기존 판정과 비교 항목](logs/fidelity.json)을 그대로 표시했으며 판정을 다시 계산하지 않았습니다.', '']
    if status:
        lines += ['## 실행 상태', '', markdown_table(['항목', '기록된 값'], [[key, status[key]] for key in ('status', 'phase', 'exit_status', 'updated_at') if key in status]), '', '출처: [status.env](logs/status.env). 완료 표시는 실행 완료를 뜻하며 baseline 유사성 통과를 뜻하지 않습니다.', '']
    if selection:
        lines += ['**' + selection['description'] + '**', '']
    elif audit:
        lines += ['이 폴더는 감사·검증 자료입니다. 내부 pilot과 과거 비교 결과를 이 폴더의 주 벤치마크로 사용하지 않았습니다.', '']
    if (logs / 'README.md').is_file():
        lines += ['실행 상태, 오류, 설정 차이와 해석 제한: [보존된 README](logs/README.md).', '']
    if (logs / 'FAILED').is_file():
        lines += ['**원본 FAILED 표식이 있습니다. 아래에 존재하는 값은 완료된 전체 벤치마크를 뜻하지 않습니다.**', '']
    lines += ['## 적재', '', markdown_table(
        ['버전', 'System', '적재 시간 (s)', 'Disk write (GB)', '기록된 WA', '최종 DB (GB)', 'SST 수', 'Visible rows', '원본'],
        [[row['group'], row['system'], *[cell(row.get(key)) for key in ('seconds', 'write_gb', 'write_amplification', 'final_gb', 'sst_count', 'visible_rows')], row['source']] for row in loading]
        or [['N/A'] * 9]), '']
    lines += ['WA는 원본에 기록된 정의와 값을 유지합니다. 분모나 측정 구간이 다른 실험의 WA를 동일 정의로 간주하지 않습니다.', '']
    for note in notes + [row['group'] + ': ' + row['note'] for row in loading if row.get('note')]:
        lines += [note, '']
    lines += ['## Workload', '', markdown_table(
        ['Workload', 'System', 'Throughput (ops/s)', 'Disk read (GB)', 'Disk write (GB)', '실행 시간 (s)', 'Operations', '원본'],
        [[row['workload'], row['system'], *[cell(row.get(key)) for key in ('throughput', 'read_gb', 'write_gb', 'wall_seconds', 'operations')], row['source']] for row in workloads]
        or [['N/A'] * 8]), '', '## 읽기 / scan latency', '',
        ('아래 client percentile과 별도 장치 평균 latency는 서로 다른 지표입니다. 장치 평균은 아래 독립 표에 표시합니다.' if device_available
         else '**물리적 disk I/O latency와 write-only latency는 측정되지 않았습니다(N/A).**') + ' 아래 값은 DB 호출의 client latency입니다. E는 scan, 나머지는 point lookup이며, E의 비어 있는 point histogram을 0 µs로 표시하지 않습니다.', '',
        markdown_table(['Workload', 'System', '종류', 'p50 (µs)', 'p95 (µs)', 'p99 (µs)', '원본'],
                       [[row['workload'], row['system'], row['latency_kind'], *[cell(row.get(key)) for key in ('p50', 'p95', 'p99')], row['source']] for row in workloads]
                       or [['N/A'] * 7]), '',
        'Device read/write bytes는 측정 구간의 장치 누적 I/O입니다. 고유 데이터 크기나 DB에만 귀속된 I/O를 뜻하지 않습니다. 실행 시간이 다른 실험의 누적 bytes는 같은 작업량 비교가 아닙니다.', '']
    if device_available:
        lines += ['## 장치 계층의 평균 I/O latency', '',
                  '각 workload 측정 구간에서 `/proc/diskstats`의 누적 read/write milliseconds 증가량을 완료 요청 수 증가량으로 나눈 평균(ms)입니다. md0 등 기록된 장치 계층의 모든 프로세스 I/O를 포함하며, 구간 경계의 진행 중 요청 영향을 받습니다. NVMe의 물리적 latency percentile이나 client write-only latency가 아닙니다. 완료 요청이 없거나 값이 없으면 N/A입니다.', '',
                  markdown_table(['Workload', 'System', '장치', 'Read 평균 (ms)', 'Write 평균 (ms)', 'Read 요청 수', 'Write 요청 수', 'Read 누적 (ms)', 'Write 누적 (ms)', 'Scope', '원본'],
                                 [[row['workload'], row['system'], row.get('disk_device') or 'N/A',
                                   cell(row.get('disk_read_latency_avg_ms')), cell(row.get('disk_write_latency_avg_ms')),
                                   *[cell(row.get(key)) for key in ('disk_read_requests', 'disk_write_requests', 'disk_read_time_ms', 'disk_write_time_ms')],
                                   row.get('disk_latency_scope') or 'N/A', row['source']] for row in workloads]), '']
    (root / 'results.md').write_text('\n'.join(lines))
    restore(root)
    return normalized


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    args = parser.parse_args()
    result = publish(args.bundle)
    print(json.dumps({'bundle': result['bundle'], 'loading_rows': len(result['loading']),
                      'workload_rows': len(result['workloads']), 'audit_only': result['audit_only']}))


if __name__ == '__main__':
    main()
