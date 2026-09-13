#!/usr/bin/env python3
"""Render Cassandra baseline/VComp workload results without third-party modules."""

import argparse
import json
import math
from pathlib import Path


COLORS = {"baseline": "#6b7280", "vcomp": "#2563eb"}
DISPLAY = {"baseline": "Baseline", "vcomp": "VComp"}
ORDER = ["A", "B", "C", "D", "E", "F", "MIXGRAPH"]


def esc(value):
    return str(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def nice_axis(values):
    maximum = max(list(values) + [1e-12]) * 1.05
    rough = maximum / 5
    magnitude = 10 ** math.floor(math.log10(rough))
    factor = min((1, 2, 2.5, 5, 10), key=lambda item: abs(item - rough / magnitude))
    step = factor * magnitude
    return math.ceil(maximum / step) * step, step


def start_svg(title):
    return [
        '<svg xmlns="http://www.w3.org/2000/svg" width="1400" height="900" viewBox="0 0 1400 900">',
        '<rect width="100%" height="100%" fill="white"/>',
        '<style>text{font-family:Arial,sans-serif;fill:#111827}.title{font-size:22px;font-weight:700}.panel{font-size:17px;font-weight:700}.tick{font-size:12px;fill:#4b5563}.legend{font-size:13px}</style>',
        f'<text class="title" x="700" y="30" text-anchor="middle">{esc(title)}</text>',
    ]


def bars(svg, x, y, width, height, title, labels, values, formatter):
    plot_x, plot_y = x + 62, y + 38
    plot_w, plot_h = width - 76, height - 82
    svg.append(f'<text class="panel" x="{x+width/2}" y="{y+18}" text-anchor="middle">{esc(title)}</text>')
    maximum, step = nice_axis(value for system in COLORS for value in values[system])
    for tick in range(round(maximum / step) + 1):
        value = tick * step
        yy = plot_y + plot_h - plot_h * value / maximum
        svg.append(f'<line x1="{plot_x}" y1="{yy:.1f}" x2="{plot_x+plot_w}" y2="{yy:.1f}" stroke="#e5e7eb"/>')
        svg.append(f'<text class="tick" x="{plot_x-6}" y="{yy+4:.1f}" text-anchor="end">{esc(formatter(value))}</text>')
    group_w = plot_w / len(labels)
    bar_w = min(30, group_w * .33)
    for index, label in enumerate(labels):
        center = plot_x + group_w * (index + .5)
        for series_index, system in enumerate(COLORS):
            value = values[system][index]
            bx = center + (series_index - .5) * (bar_w + 3) - bar_w / 2
            bh = plot_h * value / maximum
            svg.append(f'<rect x="{bx:.1f}" y="{plot_y+plot_h-bh:.1f}" width="{bar_w:.1f}" height="{bh:.1f}" fill="{COLORS[system]}"/>')
        svg.append(f'<text class="tick" x="{center:.1f}" y="{plot_y+plot_h+18}" text-anchor="middle">{esc(label)}</text>')
    svg.append(f'<line x1="{plot_x}" y1="{plot_y+plot_h}" x2="{plot_x+plot_w}" y2="{plot_y+plot_h}" stroke="#111827"/>')


def load_records(root):
    records = {}
    for path in (root / "results").glob("*.json"):
        record = json.loads(path.read_text())
        stem = path.stem
        system = "baseline" if stem.endswith("_baseline") else "vcomp" if stem.endswith("_vcomp") else None
        if system:
            records[(record["workload"].upper(), system)] = record
    expected = {(workload, system) for workload in ORDER for system in COLORS}
    missing = expected - set(records)
    if missing:
        raise SystemExit(f"missing workload results: {sorted(missing)}")
    return records


def read_configuration(root):
    result = {}
    path = root / "configuration.txt"
    if path.exists():
        for line in path.read_text().splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                result[key] = value
    return result


def campaign_description(configuration):
    threads = configuration.get("threads", "?")
    operations = int(configuration.get("operations_per_thread", "0"))
    key_space = int(configuration.get("key_space", "0"))
    key_bytes = int(configuration.get("key_bytes", "0"))
    value_bytes = int(configuration.get("value_bytes", "0"))
    gib = key_space * (key_bytes + value_bytes) / 1024**3
    size = f"{gib:.0f} GiB" if gib >= 0.95 else f"{gib:.2f} GiB"
    execution = (f"{operations:,} operations/client" if operations > 0 else
                 f"{configuration.get('duration_seconds', '?')} seconds")
    return f"Cassandra — {size} requested load, {threads} clients × {execution}"


def write_svg(root, records, configuration):
    svg = start_svg(campaign_description(configuration))
    labels = ["mixg." if item == "MIXGRAPH" else item for item in ORDER]
    series = lambda field, scale=1: {
        system: [records[(workload, system)].get(field, 0) / scale for workload in ORDER]
        for system in COLORS
    }
    point_order = [item for item in ORDER if item != "E"]
    point_labels = ["mixg." if item == "MIXGRAPH" else item for item in point_order]
    point_values = {
        system: [records[(workload, system)]["point_lookup_latency_p99_us"] for workload in point_order]
        for system in COLORS
    }
    bars(svg, 20, 55, 670, 370, "Throughput (k ops/sec)", labels,
         series("throughput_ops_per_second", 1e3), lambda value: f"{value:.0f}")
    bars(svg, 710, 55, 670, 370, "Point-lookup p99 latency (µs)", point_labels,
         point_values, lambda value: f"{value:.0f}")
    bars(svg, 20, 455, 670, 370, "Disk read (GB, 5 min total)", labels,
         series("disk_read_bytes", 1e9), lambda value: f"{value:.0f}")
    bars(svg, 710, 455, 670, 370, "Disk write (MB, 5 min total)", labels,
         series("disk_write_bytes", 1e6), lambda value: f"{value:.0f}")
    for index, system in enumerate(COLORS):
        xx = 590 + index * 120
        svg.append(f'<rect x="{xx}" y="863" width="18" height="12" fill="{COLORS[system]}"/>')
        svg.append(f'<text class="legend" x="{xx+24}" y="874">{DISPLAY[system]}</text>')
    svg.append('</svg>')
    (root / "cassandra_workloads.svg").write_text("\n".join(svg))


def write_summary(root, records, configuration):
    operations = int(configuration.get("operations_per_thread", "0"))
    execution = (f"{operations:,} operations per client" if operations > 0 else
                 f"{configuration.get('duration_seconds', '?')} seconds")
    threads = configuration.get("threads", "?")
    lines = [
        "# Cassandra YCSB / MixGraph comparison", "",
        "- Inputs: preserved baseline and VComp DBs; one isolated hard-linked checkpoint per workload",
        f"- Workload: {threads} client threads, {execution}; same seed and operation count for both systems",
        "- Cache start: scoped `POSIX_FADV_DONTNEED` on each checkpoint before Cassandra startup",
        "- Disk I/O: `/proc/diskstats` delta for `md0` over the exact client interval", "",
        "| Workload | System | Throughput (ops/s) | Point p50 (µs) | Point p95 (µs) | Point p99 (µs) | Scan p99 (µs) | Read misses | Disk read (GB) | Disk write (MB) |", 
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for workload in ORDER:
        for system in COLORS:
            row = records[(workload, system)]
            lines.append(
                f"| {'MixGraph' if workload == 'MIXGRAPH' else workload} | {DISPLAY[system]} | "
                f"{row['throughput_ops_per_second']:.0f} | {row['point_lookup_latency_p50_us']:.0f} | "
                f"{row['point_lookup_latency_p95_us']:.0f} | {row['point_lookup_latency_p99_us']:.0f} | "
                f"{row['scan_latency_p99_us']:.0f} | {row['read_misses']} | "
                f"{row['disk_read_bytes']/1e9:.3f} | {row['disk_write_bytes']/1e6:.3f} |"
            )
    (root / "README.md").write_text("\n".join(lines) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("campaign", type=Path)
    args = parser.parse_args()
    records = load_records(args.campaign)
    configuration = read_configuration(args.campaign)
    write_svg(args.campaign, records, configuration)
    write_summary(args.campaign, records, configuration)
    print(args.campaign / "cassandra_workloads.svg")


if __name__ == "__main__":
    main()
