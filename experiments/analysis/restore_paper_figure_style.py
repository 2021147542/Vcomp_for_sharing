#!/usr/bin/env python3
"""Restore the original four-panel presentation without changing measurements.

Reads logs/presentation.json; changes only loading.svg, workload.svg and the two
corresponding tables in results.md. The independent io_latency.svg is untouched.
"""
import argparse
import json
import math
from pathlib import Path
import re

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Patch
from matplotlib.ticker import FormatStrFormatter
import numpy as np

COLORS = {'baseline': '#6b7280', 'vcomp': '#2563eb', 'vcomp-old': '#93c5fd', 'vcomp-new': '#2563eb'}
DISPLAY = {'baseline': 'Baseline', 'vcomp': 'VComp', 'vcomp-old': 'VComp (old)', 'vcomp-new': 'VComp (new)'}
ORDER = ['A', 'B', 'C', 'D', 'E', 'F', 'MIXGRAPH']
GIB_PER_GB = 1e9 / 2**30


def read_json(path):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return {}


def scaled(value, scale):
    return None if value is None else value * scale


def cell(value):
    if value is None:
        return 'N/A'
    return f'{value:,.6f}'.rstrip('0').rstrip('.') if value != int(value) else f'{int(value):,}'


def metadata(root, data):
    if data.get('presentation'):
        selected = data['presentation']
        return selected['title'], selected['interval']
    logs = root / 'logs'
    config = {}
    candidates = [logs / 'configuration.txt', logs / 'campaign.env', logs / 'baseline_configuration.txt', logs / 'vcomp_configuration.txt']
    candidates += sorted((logs / 'raw').glob('workloads*/configuration.txt'))
    candidates += sorted((logs / 'raw' / 'baseline').glob('*/configuration.txt'))
    candidates += sorted((logs / 'load').glob('*configuration.txt'))
    for path in candidates:
        if path.is_file():
            for line in path.read_text().splitlines():
                if '=' in line:
                    key, value = line.split('=', 1)
                    config.setdefault(key, value)
    paper = read_json(logs / 'paper_load_result.json')
    engine = 'Cassandra' if 'cassandra' in root.name.lower() else 'Pebble' if 'pebble' in root.name.lower() else 'Database'
    key = paper.get('key_size', config.get('key_bytes'))
    value = paper.get('value_size', config.get('value_bytes'))
    dataset = paper.get('dataset_bytes')
    if dataset is None and config.get('dataset_gib'):
        dataset = float(config['dataset_gib']) * 2**30
    if dataset is None and config.get('key_space') and key is not None and value is not None:
        dataset = int(config['key_space']) * (int(key) + int(value))
    size = ''
    if dataset is not None:
        gib = float(dataset) / 2**30
        size = f'{gib / 1024:g} TiB' if gib >= 1024 else f'{gib:g} GiB'
    title = engine + (' — ' + size if size else '')
    if key is not None and value is not None:
        title += f', {key} B key + {value} B value'
    actual = []
    for row in data.get('workloads', []):
        source = row.get('source', '')
        if source.startswith('logs/'):
            record = read_json(root / source)
            if record:
                actual.append(record)
    operations = int(config.get('operations_per_thread', 0))
    threads = int(config.get('threads', 0))
    recorded_modes = {r.get('measurement_mode') for r in actual}
    if operations and threads and recorded_modes != {'time'}:
        total = operations * threads
        interval = f'{total / 1000:g}k fixed ops total' if total < 1e6 else f'{total / 1e6:g}M fixed ops total'
    elif actual and all(r.get('measurement_mode') == 'fixed-operations' for r in actual):
        totals = {r.get('operations') for r in actual}
        interval = f'{next(iter(totals)):,} fixed ops total' if len(totals) == 1 and None not in totals else 'fixed-operation intervals'
    else:
        durations = {r.get('requested_duration_seconds', r.get('requested_seconds')) for r in actual}
        durations.discard(None)
        duration = next(iter(durations)) if len(durations) == 1 else config.get('duration_seconds')
        interval = f'{float(duration):g} s total' if duration is not None else 'recorded client interval'
    return title, interval


def style_axis(axis):
    axis.grid(axis='y', color='#e5e7eb')
    axis.grid(axis='x', visible=False)
    axis.set_axisbelow(True)
    axis.spines[['top', 'right']].set_visible(False)
    axis.tick_params(labelsize=11)


def save(figure, path, preview):
    figure.savefig(path, format='svg')
    # Keep the SVG portable in IDE/web renderers without a local DejaVu font.
    svg = path.read_text()
    svg = re.sub(r'(<svg\b[^>]*>)', r'\1\n<style>text{font-family:Arial,sans-serif!important}</style>', svg, count=1)
    path.write_text(svg)
    if preview is not None:
        preview.mkdir(parents=True, exist_ok=True)
        figure.savefig(preview / (path.stem + '.png'), dpi=120)
    plt.close(figure)


def loading_figure(root, rows, title, preview):
    multiple = len({row.get('group') for row in rows}) > 1
    rows = rows or [{'system': s, 'group': 'Primary'} for s in ('baseline', 'vcomp')]
    panels = [('Loading time (min)', 'seconds', 1 / 60),
              ('Total device write (GiB)', 'write_gb', GIB_PER_GB),
              ('Write amplification (×)', 'write_amplification', 1),
              ('Final physical DB size (GiB)', 'final_gb', GIB_PER_GB)]
    with plt.style.context('seaborn-v0_8-whitegrid'):
        figure, axes = plt.subplots(2, 2, figsize=(18, 10) if multiple else (12, 8.5), layout='constrained')
        figure.suptitle(title + '\nbaseline vs VComp' + (' — retained iteration versions' if multiple else ''),
                       fontsize=16, fontweight='bold')
        labels = [(row['group'].split('_', 1)[0] + '\n' if multiple else '') + DISPLAY.get(row['system'], row['system']) for row in rows]
        for axis, (heading, metric, factor) in zip(axes.flat, panels):
            values = [scaled(row.get(metric), factor) for row in rows]
            maximum = max([v for v in values if v is not None] + [0])
            axis.set_ylim(0, maximum * 1.18 if maximum else 1)
            for index, (row, value) in enumerate(zip(rows, values)):
                if value is None:
                    axis.text(index, .03, 'N/A', ha='center', transform=axis.get_xaxis_transform(), fontsize=9)
                else:
                    bars = axis.bar(index, value, color=COLORS.get(row['system'], '#2563eb'), width=.45)
                    axis.bar_label(bars, labels=[f'{value:.2f}'], padding=4, fontsize=7 if multiple else 10)
            axis.set_xlim(-.6, len(rows) - .4)
            axis.set_xticks(range(len(rows)), labels, rotation=45 if multiple else 0, ha='right' if multiple else 'center')
            axis.set_title(heading, fontweight='bold')
            style_axis(axis)
            if multiple: axis.tick_params(axis='x', labelsize=8)
        save(figure, root / 'figures' / 'loading.svg', preview)


def limit_axis(axis, values, formatter):
    maximum = max([value for value in values if value is not None] + [0])
    if maximum <= 0:
        maximum = 1
    rough = maximum * 1.05 / 5
    magnitude = 10 ** math.floor(math.log10(rough))
    step = min((1, 2, 2.5, 5, 10), key=lambda x: abs(x - rough / magnitude)) * magnitude
    upper = math.ceil(maximum * 1.05 / step) * step
    axis.set_ylim(0, upper)
    axis.set_yticks(np.arange(0, upper + step / 2, step))
    # Small pilot measurements need fractional ticks instead of repeated zeros.
    precision = int(re.search(r'\.(\d+)f', formatter).group(1))
    while precision < 10 and not math.isclose(step * 10**precision,
                                             round(step * 10**precision), abs_tol=1e-8):
        precision += 1
    formatter = f'%.{precision}f'
    axis.yaxis.set_major_formatter(FormatStrFormatter(formatter))


def workload_figure(root, rows, title, interval, preview, selected=False):
    lookup = {(r['workload'], r['system']): r for r in rows}
    order = list(dict.fromkeys(r['workload'] for r in rows)) if selected else ORDER
    labels = [w.replace(' / ', '\n') if selected else ('mixg.' if w == 'MIXGRAPH' else w) for w in order]
    with plt.style.context('seaborn-v0_8-whitegrid'):
        figure, axes = plt.subplots(2, 2, figsize=(max(14, len(order) * 2), 10 if selected else 9))
        figure.suptitle('Workload behavior — ' + title, fontsize=18, fontweight='bold', y=.98)
        panels = [(axes[0, 0], 'Throughput (M ops/sec)', 'throughput', 1e-6, '%.2f'),
                  (axes[1, 0], f'Disk read (GB, {interval})', 'read_gb', 1, '%.0f'),
                  (axes[1, 1], f'Disk write (MB, {interval})', 'write_gb', 1000, '%.0f')]
        for axis, heading, metric, factor, formatter in panels:
            all_values = []
            for series, system in enumerate(('baseline', 'vcomp')):
                for index, workload in enumerate(order):
                    value = scaled(lookup.get((workload, system), {}).get(metric), factor)
                    x = index + (series - .5) * .36
                    all_values.append(value)
                    if value is None:
                        axis.text(x, .02, 'N/A', rotation=90, ha='center', fontsize=8, color=COLORS[system], transform=axis.get_xaxis_transform())
                    else:
                        axis.bar(x, value, width=.33, color=COLORS[system])
            axis.set_xticks(range(len(order)), labels)
            axis.set_xlim(-.6, len(order) - .4)
            axis.set_title(heading, fontsize=15, fontweight='bold')
            limit_axis(axis, all_values, formatter)
            style_axis(axis)
        axis = axes[0, 1]
        points = [w for w in order if not any(r['workload'] == w and r.get('latency_kind') == 'scan' for r in rows)] if selected else [w for w in ORDER if w != 'E']
        maxima = []
        for series, system in enumerate(('baseline', 'vcomp')):
            for index, workload in enumerate(points):
                row = lookup.get((workload, system), {})
                values = [row.get(p) for p in ('p50', 'p95', 'p99')]
                x = index + (series - .5) * .36
                if any(v is None for v in values):
                    axis.text(x, .02, 'N/A', rotation=90, ha='center', fontsize=8, color=COLORS[system], transform=axis.get_xaxis_transform())
                    continue
                if not 0 <= values[0] <= values[1] <= values[2]:
                    raise ValueError(f'Non-monotonic recorded percentiles: {workload}/{system}')
                maxima.append(values[2])
                bottom = 0
                for top, alpha in zip(values, (1, .65, .35)):
                    axis.bar(x, top - bottom, bottom=bottom, width=.33, color=COLORS[system], alpha=alpha)
                    bottom = top
        axis.set_title('Point-lookup latency (µs): p50 / p95 / p99', fontsize=15, fontweight='bold')
        axis.set_xticks(range(len(points)), [w.replace(' / ', '\n') if selected else ('mixg.' if w == 'MIXGRAPH' else w) for w in points])
        axis.set_xlim(-.6, len(points) - .4)
        limit_axis(axis, maxima, '%.0f')
        style_axis(axis)
        figure.legend(handles=[Patch(color=COLORS[s], label=DISPLAY[s]) for s in ('baseline', 'vcomp')],
                      loc='lower center', ncol=2, frameon=False, fontsize=12, bbox_to_anchor=(.5, .01))
        figure.tight_layout(rect=(.01, .05, .99, .94), h_pad=3, w_pad=3)
        save(figure, root / 'figures' / 'workload.svg', preview)


def table(headers, rows):
    return '\n'.join(['| ' + ' | '.join(headers) + ' |', '| ' + ' | '.join(['---'] * len(headers)) + ' |']
                     + ['| ' + ' | '.join(str(c).replace('|', '\\|') for c in row) + ' |' for row in rows])


def replace_section_table(text, heading, replacement):
    section = re.search(r'(?m)^## ' + re.escape(heading) + r'\s*$', text)
    if section is None:
        return text
    end = re.search(r'(?m)^## ', text[section.end():])
    limit = section.end() + end.start() if end else len(text)
    match = re.search(r'(?m)^\|[^\n]*(?:\n\|[^\n]*)*', text[section.end():limit])
    if match is None:
        return text
    start = section.end() + match.start()
    finish = section.end() + match.end()
    return text[:start] + replacement + text[finish:]


def restore(root, preview_dir=None):
    root = Path(root).resolve()
    data = read_json(root / 'logs' / 'presentation.json')
    if not data:
        raise ValueError('Missing normalized presentation.json: ' + str(root))
    loading = data.get('loading', [])
    workloads = data.get('workloads', [])
    title, interval = metadata(root, data)
    preview = Path(preview_dir) if preview_dir is not None else None
    with plt.rc_context({'svg.fonttype': 'none', 'font.family': 'sans-serif',
                         'font.sans-serif': ['DejaVu Sans', 'Arial']}):
        loading_figure(root, loading, data.get('presentation', {}).get('loading_title', title), preview)
        workload_figure(root, workloads, title, interval, preview, bool(data.get('presentation')))
    result = root / 'results.md'
    if result.is_file():
        original = result.read_text()
        original = original.replace('GB는 10⁹ bytes이며,',
                                    'GB는 10⁹ bytes, MB는 10⁶ bytes, GiB는 2³⁰ bytes이며,')
        load_table = table(['버전', 'System', '적재 시간 (min)', 'Disk write (GiB)', '기록된 WA', '최종 DB (GiB)', 'SST 수', 'Visible rows', '원본'],
                           [[r['group'], r['system'], cell(scaled(r.get('seconds'), 1/60)), cell(scaled(r.get('write_gb'), GIB_PER_GB)), cell(r.get('write_amplification')), cell(scaled(r.get('final_gb'), GIB_PER_GB)), cell(r.get('sst_count')), cell(r.get('visible_rows')), r['source']] for r in loading] or [['N/A'] * 9])
        work_table = table(['Workload', 'System', 'Throughput (M ops/s)', 'Disk read (GB)', 'Disk write (MB)', '실행 시간 (s)', 'Operations', '원본'],
                           [[r['workload'], r['system'], cell(scaled(r.get('throughput'), 1e-6)), cell(r.get('read_gb')), cell(scaled(r.get('write_gb'), 1000)), cell(r.get('wall_seconds')), cell(r.get('operations')), r['source']] for r in workloads] or [['N/A'] * 8])
        changed = replace_section_table(replace_section_table(original, '적재', load_table), 'Workload', work_table)
        result.write_text(changed)
    return {'bundle': root.name, 'loading_rows': len(loading), 'workload_rows': len(workloads), 'io_latency_changed': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--preview-dir', type=Path)
    args = parser.parse_args()
    print(json.dumps(restore(args.bundle, args.preview_dir)))


if __name__ == '__main__':
    main()
