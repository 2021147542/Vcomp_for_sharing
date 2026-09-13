#!/usr/bin/env python3
"""Render paper-style VComp loading/workload figures without third-party modules."""

import argparse
import json
import math
from pathlib import Path


COLORS = {"baseline": "#6b7280", "virtual": "#2563eb"}
DISPLAY = {"baseline": "Baseline", "virtual": "VComp"}


def esc(text):
    return str(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def svg_start(width, height, title):
    return [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="white"/>',
        '<style>text{font-family:Arial,sans-serif;fill:#111827}.title{font-size:22px;font-weight:700}.panel{font-size:17px;font-weight:700}.tick{font-size:12px;fill:#4b5563}.value{font-size:11px}.legend{font-size:13px}</style>',
        f'<text class="title" x="{width/2}" y="30" text-anchor="middle">{esc(title)}</text>',
    ]


def nice_axis(values, target_intervals=5, headroom=1.05):
    """Return a zero-based axis maximum and human-friendly major tick step."""
    maximum = max(list(values) + [1e-12]) * headroom
    rough_step = maximum / target_intervals
    magnitude = 10 ** math.floor(math.log10(rough_step))
    normalized = rough_step / magnitude
    factors = (1, 2, 2.5, 5, 10)
    factor = min(factors, key=lambda candidate: abs(candidate - normalized))
    step = factor * magnitude
    axis_maximum = math.ceil(maximum / step) * step
    return axis_maximum, step


def grouped_bars(svg, x, y, width, height, title, labels, series, formatter):
    plot_x, plot_y = x + 58, y + 38
    plot_w, plot_h = width - 72, height - 82
    svg.append(f'<text class="panel" x="{x+width/2}" y="{y+18}" text-anchor="middle">{esc(title)}</text>')
    maximum, tick_step = nice_axis(value for values in series.values() for value in values)
    tick_count = round(maximum / tick_step)
    for tick in range(tick_count + 1):
        value = tick_step * tick
        yy = plot_y + plot_h - plot_h * value / maximum
        svg.append(f'<line x1="{plot_x}" y1="{yy:.1f}" x2="{plot_x+plot_w}" y2="{yy:.1f}" stroke="#e5e7eb"/>')
        svg.append(f'<text class="tick" x="{plot_x-6}" y="{yy+4:.1f}" text-anchor="end">{esc(formatter(value))}</text>')
    group_w = plot_w / max(1, len(labels))
    names = list(series)
    bar_w = min(30, group_w * 0.33)
    for index, label in enumerate(labels):
        center = plot_x + group_w * (index + 0.5)
        for series_index, name in enumerate(names):
            value = series[name][index]
            bar_x = center + (series_index - (len(names)-1)/2) * (bar_w + 3) - bar_w/2
            bar_h = plot_h * value / maximum
            bar_y = plot_y + plot_h - bar_h
            svg.append(f'<rect x="{bar_x:.1f}" y="{bar_y:.1f}" width="{bar_w:.1f}" height="{bar_h:.1f}" fill="{COLORS[name]}"/>')
        svg.append(f'<text class="tick" x="{center:.1f}" y="{plot_y+plot_h+18}" text-anchor="middle">{esc(label)}</text>')
    svg.append(f'<line x1="{plot_x}" y1="{plot_y+plot_h}" x2="{plot_x+plot_w}" y2="{plot_y+plot_h}" stroke="#111827"/>')


def unavailable_baseline_panel(svg, x, y, width, height, title, vcomp_value):
    svg.append(f'<text class="panel" x="{x+width/2}" y="{y+18}" text-anchor="middle">{esc(title)}</text>')
    svg.append(f'<text class="legend" x="{x+width/2}" y="{y+125}" text-anchor="middle">Baseline: unavailable (reused DB)</text>')
    svg.append(f'<text class="legend" x="{x+width/2}" y="{y+165}" text-anchor="middle">VComp: {esc(vcomp_value)}</text>')


def latency_bars(svg, x, y, width, height, labels, results):
    title = "Point-lookup latency (µs): p50 / p95 / p99"
    plot_x, plot_y = x + 58, y + 38
    plot_w, plot_h = width - 72, height - 82
    svg.append(f'<text class="panel" x="{x+width/2}" y="{y+18}" text-anchor="middle">{title}</text>')
    maximum, tick_step = nice_axis(results[label][system]["point_lookup_latency_p99_us"]
                                   for label in labels for system in COLORS)
    tick_count = round(maximum / tick_step)
    for tick in range(tick_count + 1):
        value = tick_step * tick
        yy = plot_y + plot_h - plot_h * value / maximum
        svg.append(f'<line x1="{plot_x}" y1="{yy:.1f}" x2="{plot_x+plot_w}" y2="{yy:.1f}" stroke="#e5e7eb"/>')
        svg.append(f'<text class="tick" x="{plot_x-6}" y="{yy+4:.1f}" text-anchor="end">{value:.0f}</text>')
    group_w = plot_w / len(labels)
    bar_w = min(30, group_w * 0.33)
    opacity = (1.0, 0.65, 0.35)
    for index, label in enumerate(labels):
        center = plot_x + group_w * (index + 0.5)
        for system_index, system in enumerate(COLORS):
            record = results[label][system]
            values = [record.get("point_lookup_latency_p50_us", 0), record.get("point_lookup_latency_p95_us", 0), record.get("point_lookup_latency_p99_us", 0)]
            segments = [values[0], values[1]-values[0], values[2]-values[1]]
            bar_x = center + (system_index - 0.5) * (bar_w + 3) - bar_w/2
            bottom = plot_y + plot_h
            for segment, alpha in zip(segments, opacity):
                segment_h = plot_h * segment / maximum
                bottom -= segment_h
                svg.append(f'<rect x="{bar_x:.1f}" y="{bottom:.1f}" width="{bar_w:.1f}" height="{segment_h:.1f}" fill="{COLORS[system]}" fill-opacity="{alpha}"/>')
        svg.append(f'<text class="tick" x="{center:.1f}" y="{plot_y+plot_h+18}" text-anchor="middle">{esc(label)}</text>')
    svg.append(f'<line x1="{plot_x}" y1="{plot_y+plot_h}" x2="{plot_x+plot_w}" y2="{plot_y+plot_h}" stroke="#111827"/>')


def add_legend(svg, x, y):
    for index, system in enumerate(COLORS):
        xx = x + index * 120
        svg.append(f'<rect x="{xx}" y="{y-12}" width="18" height="12" fill="{COLORS[system]}"/>')
        svg.append(f'<text class="legend" x="{xx+24}" y="{y-1}">{DISPLAY[system]}</text>')


def dataset_label(load):
    """Derive captions from the result metadata, rather than a prior run size."""
    data_bytes = load["dataset_bytes"]
    tib = 2**40
    gib = 2**30
    if data_bytes % tib == 0:
        return f"{data_bytes // tib} TiB"
    if data_bytes % gib == 0:
        return f"{data_bytes // gib} GiB"
    return f"{data_bytes / gib:.3f} GiB"


def write_loading(root, load):
    systems = ["baseline", "virtual"]
    labels = [DISPLAY[name] for name in systems]
    states = {"baseline": load["baseline"], "virtual": load["vcomp"]}
    dataset = dataset_label(load)
    svg = svg_start(1200, 760, f"Pebble VComp — {dataset}, {load['key_size']} B key + {load['value_size']} B value")
    single = lambda field, scale=1: {"baseline": [states["baseline"][field]/scale], "virtual": [states["virtual"][field]/scale]}
    if load.get("baseline_load_metrics_available", True):
        grouped_bars(svg, 20, 55, 570, 315, "Loading time (hours)", [dataset], single("loading_seconds", 3600), lambda v: f"{v:.1f}")
        grouped_bars(svg, 610, 55, 570, 315, "Total SST write (TB)", [dataset], single("total_disk_write_bytes", 1e12), lambda v: f"{v:.1f}")
        grouped_bars(svg, 20, 395, 570, 315, "Write amplification (×)", [dataset], single("write_amplification"), lambda v: f"{v:.1f}")
    else:
        unavailable_baseline_panel(svg, 20, 55, 570, 315, "Loading time (hours)", f"{states['virtual']['loading_seconds']/3600:.2f} h")
        unavailable_baseline_panel(svg, 610, 55, 570, 315, "Total SST write (TB)", f"{states['virtual']['total_disk_write_bytes']/1e12:.3f} TB")
        unavailable_baseline_panel(svg, 20, 395, 570, 315, "Write amplification (×)", f"{states['virtual']['write_amplification']:.2f}×")
    grouped_bars(svg, 610, 395, 570, 315, "Final DB size (GB)", [dataset], single("final_db_bytes", 1e9), lambda v: f"{v:.0f}")
    add_legend(svg, 490, 748)
    svg.append('</svg>')
    (root / "paper_loading.svg").write_text("\n".join(svg))


def write_workloads(root, load, records):
    order = ["A", "B", "C", "D", "E", "F", "MIXGRAPH"]
    labels = ["mixg." if item == "MIXGRAPH" else item for item in order]
    by_workload = {workload: {system: records[(workload, system)] for system in COLORS} for workload in order}
    series = lambda field, scale=1: {system: [by_workload[name][system][field]/scale for name in order] for system in COLORS}
    svg = svg_start(1400, 900, f"Workload behavior on retained {dataset_label(load)} Pebble databases")
    grouped_bars(svg, 20, 55, 670, 370, "Throughput (M ops/sec)", labels, series("throughput_ops_per_second", 1e6), lambda v: f"{v:.2f}")
    latency_order = [name for name in order if name != "E"]
    latency_labels = ["mixg." if item == "MIXGRAPH" else item for item in latency_order]
    latency_bars(svg, 710, 55, 670, 370, latency_labels, {label: by_workload[name] for label, name in zip(latency_labels, latency_order)})
    grouped_bars(svg, 20, 455, 670, 370, "Disk read (GB, 5 min total)", labels, series("disk_read_bytes", 1e9), lambda v: f"{v:.0f}")
    grouped_bars(svg, 710, 455, 670, 370, "Disk write (MB, 5 min total)", labels, series("disk_write_bytes", 1e6), lambda v: f"{v:.0f}")
    add_legend(svg, 590, 875)
    svg.append('</svg>')
    (root / "paper_workloads.svg").write_text("\n".join(svg))


def write_levels(root, load):
    levels = [f"L{i}" for i in range(7)]
    states = {"baseline": load["baseline"], "virtual": load["vcomp"]}
    sizes = {system: [entry["bytes"]/1e9 for entry in states[system]["levels"]] for system in COLORS}
    counts = {system: [entry["tables"] for entry in states[system]["levels"]] for system in COLORS}
    svg = svg_start(1200, 430, "Final LSM-tree structure")
    grouped_bars(svg, 20, 55, 570, 320, "Level size (GB)", levels, sizes, lambda v: f"{v:.0f}")
    grouped_bars(svg, 610, 55, 570, 320, "SST count", levels, counts, lambda v: f"{v:.0f}")
    add_legend(svg, 490, 415)
    svg.append('</svg>')
    (root / "paper_levels.svg").write_text("\n".join(svg))


def write_summary(root, load, records):
    lines = [
        "# Pebble VComp paper-style experiment",
        "",
        f"- Dataset: {dataset_label(load)} ({load['dataset_bytes'] / 2**40:.3f} TiB)",
        f"- KV: {load['key_size']} B key + {load['value_size']} B value",
        f"- Workload: 48 clients, 5 minutes each, 32 GiB block cache (host-memory cap)",
        "- WAL/compression: disabled",
        "",
        "## Loading and final state",
        "",
        "| System | Load time (s) | SST write (TB) | Write amp | Final DB (GB) | SST count | Avg SST (MB) |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for system, key in (("Baseline", "baseline"), ("VComp", "vcomp")):
        row = load[key]
        if key == "baseline" and not load.get("baseline_load_metrics_available", True):
            lines.append(f"| {system} | — (reused DB) | — | — | {row['final_db_bytes']/1e9:.3f} | {row['sst_count']} | {row['average_sst_bytes']/1e6:.3f} |")
        else:
            lines.append(f"| {system} | {row['loading_seconds']:.1f} | {row['total_disk_write_bytes']/1e12:.3f} | {row['write_amplification']:.2f}× | {row['final_db_bytes']/1e9:.3f} | {row['sst_count']} | {row['average_sst_bytes']/1e6:.3f} |")
    lines += ["", "## YCSB A–F and MixGraph", "", "| Workload | System | Throughput (ops/s) | p50 (µs) | p95 (µs) | p99 (µs) | Disk read (GB) | Disk write (MB) |", "|---|---|---:|---:|---:|---:|---:|---:|"]
    for workload in ["A", "B", "C", "D", "E", "F", "MIXGRAPH"]:
        for system in ("baseline", "virtual"):
            row = records[(workload, system)]
            lat = [row.get(f"point_lookup_latency_{p}_us") for p in ("p50", "p95", "p99")]
            lat_text = ["—" if value is None else f"{value:.0f}" for value in lat]
            lines.append(f"| {'MixGraph' if workload == 'MIXGRAPH' else workload} | {DISPLAY[system]} | {row['throughput_ops_per_second']:.0f} | {lat_text[0]} | {lat_text[1]} | {lat_text[2]} | {row['disk_read_bytes']/1e9:.3f} | {row['disk_write_bytes']/1e6:.3f} |")
    (root / "paper_summary.md").write_text("\n".join(lines) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("db_root", type=Path)
    args = parser.parse_args()
    load = json.loads((args.db_root / "paper_load_result.json").read_text())
    result_files = list((args.db_root / "paper-workloads" / "results").glob("*.json"))
    records = {}
    for path in result_files:
        record = json.loads(path.read_text())
        records[(record["workload"].upper(), record["system"])] = record
    expected = {(workload, system) for workload in ["A", "B", "C", "D", "E", "F", "MIXGRAPH"] for system in COLORS}
    missing = expected - set(records)
    if missing:
        raise SystemExit(f"missing workload results: {sorted(missing)}")
    output = args.db_root / "paper-figures"
    output.mkdir(exist_ok=True)
    write_loading(output, load)
    write_workloads(output, load, records)
    write_levels(output, load)
    write_summary(output, load, records)
    print(output)


if __name__ == "__main__":
    main()
