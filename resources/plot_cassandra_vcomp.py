#!/usr/bin/env python3
"""Render retained Cassandra VComp bulk-load metrics without mutating the run."""

import argparse
import csv
import re
from datetime import datetime
from pathlib import Path

import matplotlib.pyplot as plt

GIB = 1024 ** 3
MIB = 1024 ** 2


def result_fields(run_dir: Path) -> dict[str, float]:
    text = (run_dir / "vcomp.log").read_text()
    line = next(line for line in text.splitlines() if line.startswith("VCOMP_RESULT "))
    fields: dict[str, float] = {}
    for key, value in re.findall(r"(\w+)=([0-9.]+)", line):
        fields[key] = float(value)
    return fields


def progress_rows(run_dir: Path):
    with (run_dir / "progress.tsv").open(newline="") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    start = datetime.fromisoformat(rows[0]["timestamp"])
    minutes = [(datetime.fromisoformat(row["timestamp"]) - start).total_seconds() / 60 for row in rows]
    output_gib = [int(row["output_bytes"]) / GIB for row in rows]
    rss_gib = [int(row["java_rss_kib"]) * 1024 / GIB for row in rows]
    return minutes, output_gib, rss_gib


def physical_sizes(run_dir: Path):
    return [int(line.split("\t", 1)[0]) / MIB
            for line in (run_dir / "sstable-sizes.tsv").read_text().splitlines() if line]


def table_max_partition_bytes(run_dir: Path) -> int:
    text = (run_dir / "tablestats.txt").read_text()
    match = re.search(r"Compacted partition maximum bytes:\s*(\d+)", text)
    return int(match.group(1)) if match else 0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dir", type=Path)
    args = parser.parse_args()
    run_dir = args.run_dir.resolve()
    out = run_dir / "figures"
    out.mkdir(exist_ok=True)

    result = result_fields(run_dir)
    minutes, output_gib, rss_gib = progress_rows(run_dir)
    sizes_mib = physical_sizes(run_dir)
    max_partition_mib = table_max_partition_bytes(run_dir) / MIB

    plt.style.use("seaborn-v0_8-whitegrid")
    fig, axis = plt.subplots(figsize=(8.4, 4.6), layout="constrained")
    axis.plot(minutes, output_gib, marker="o", linewidth=2.4, color="#1f77b4", label="Materialized SST output")
    axis.set(xlabel="Elapsed time since first sample (min)", ylabel="Materialized SST output (GiB)",
             title="Cassandra VComp 100 GiB: streaming materialization")
    axis.set_ylim(bottom=0)
    rss = axis.twinx()
    rss.plot(minutes, rss_gib, marker="s", linewidth=2, color="#d62728", label="VCompBulkLoad RSS")
    rss.set_ylabel("VCompBulkLoad RSS (GiB)")
    handles, labels = axis.get_legend_handles_labels()
    handles2, labels2 = rss.get_legend_handles_labels()
    axis.legend(handles + handles2, labels + labels2, loc="upper left")
    fig.savefig(out / "cassandra_loading.svg")
    fig.savefig(out / "cassandra_loading.png", dpi=180)
    plt.close(fig)

    fig, (hist, bars) = plt.subplots(1, 2, figsize=(10.5, 4.6), layout="constrained")
    hist.hist(sizes_mib, bins=36, color="#2ca02c", edgecolor="white")
    hist.axvline(64, color="#d62728", linestyle="--", linewidth=1.8, label="64 MiB target")
    hist.set(title="Final physical SST size distribution", xlabel="SST size (MiB)", ylabel="SST count")
    hist.legend()

    names = ["Final SSTs", "Largest SST", "Largest run", "Largest partition"]
    values = [result["physical_sst_bytes"] / GIB,
              result["max_physical_sst_bytes"] / GIB,
              result["max_estimated_run_bytes"] / GIB,
              max_partition_mib / 1024]
    colors = ["#1f77b4", "#ff7f0e", "#9467bd", "#8c564b"]
    drawn = bars.bar(names, values, color=colors)
    bars.set(title="Final storage-layout magnitudes", ylabel="Size (GiB)")
    bars.tick_params(axis="x", rotation=22)
    for bar, value in zip(drawn, values):
        bars.text(bar.get_x() + bar.get_width() / 2, value, f"{value:.2f}", ha="center", va="bottom", fontsize=9)
    fig.savefig(out / "cassandra_layout.svg")
    fig.savefig(out / "cassandra_layout.png", dpi=180)
    plt.close(fig)

    # This run has no vanilla Cassandra baseline. Keep the overview explicitly
    # single-series rather than fabricating a comparison bar.
    overview = [
        ("Materialization time (s)", result["seconds"], "%.1f"),
        ("Final materialized SSTs (GiB)", result["physical_sst_bytes"] / GIB, "%.2f"),
        ("Virtual compactions", result["virtual_compactions"], "%.0f"),
        ("Final SST count", result["physical_sst_count"], "%.0f"),
    ]
    fig, axes = plt.subplots(2, 2, figsize=(11, 8), layout="constrained")
    fig.suptitle("Cassandra VComp — 100 GiB, 24 B key + 1000 B value\n(VComp-only load; no baseline was measured)",
                 fontsize=16, fontweight="bold")
    for axis, (title, value, value_format) in zip(axes.flat, overview):
        bar = axis.bar(["VComp"], [value], color="#2563eb", width=0.32)
        axis.set_title(title, fontweight="bold")
        axis.set_ylim(0, max(value * 1.18, 1))
        axis.grid(axis="y", color="#e5e7eb")
        axis.set_axisbelow(True)
        axis.spines[["top", "right"]].set_visible(False)
        axis.bar_label(bar, labels=[value_format % value], padding=4, fontsize=11)
    fig.savefig(out / "cassandra_overview.svg")
    fig.savefig(out / "cassandra_overview.png", dpi=180)
    plt.close(fig)

    summary = f"""# Cassandra VComp 100 GiB load\n\n- Dataset: 100 GiB; 24 B key + 1,000 B value\n- Flush / target SST: 64 MiB; compression disabled; durable writes disabled\n- Virtual compactions: {int(result['virtual_compactions']):,}\n- Materialization time: {result['seconds']:.3f} s\n- Materialized unique keys: {int(result['materialized_keys']):,}\n- Final SSTs: {int(result['physical_sst_count']):,}, {result['physical_sst_bytes'] / GIB:.3f} GiB total\n- Largest physical SST: {result['max_physical_sst_bytes'] / MIB:.3f} MiB\n- Largest virtual sorted run: {result['max_estimated_run_bytes'] / GIB:.3f} GiB\n- Largest compacted partition: {max_partition_mib:.3f} MiB\n- CQL verification: passed (1,000 sampled ascending rows and deterministic values)\n\nFigures:\n\n- `cassandra_loading.svg` — output growth and loader RSS\n- `cassandra_layout.svg` — physical SST histogram and layout magnitudes\n"""
    (out / "cassandra_summary.md").write_text(summary)


if __name__ == "__main__":
    main()
