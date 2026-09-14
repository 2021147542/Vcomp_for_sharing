#!/usr/bin/env python3
"""Plot a measured vanilla-Cassandra baseline against the paired VComp run."""

import argparse
import re
from pathlib import Path

import matplotlib.pyplot as plt

GIB = 1024 ** 3


def env(path: Path) -> dict[str, float]:
    values: dict[str, float] = {}
    for line in path.read_text().splitlines():
        if not line or "=" not in line:
            continue
        key, value = line.split("=", 1)
        try:
            values[key] = float(value)
        except ValueError:
            # Fingerprint hashes are deliberately carried beside numeric
            # metrics, but do not belong in bar-chart arithmetic.
            pass
    return values


def setting(path: Path, name: str) -> str:
    for line in path.read_text().splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1]
    raise KeyError(f"missing {name} in {path}")


def vcomp_result(path: Path) -> dict[str, float]:
    line = next(line for line in (path / "vcomp.log").read_text().splitlines()
                if line.startswith("VCOMP_RESULT "))
    return {key: float(value) for key, value in re.findall(r"(\w+)=([0-9.]+)", line)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline", type=Path)
    parser.add_argument("vcomp", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    baseline = env(args.baseline / "baseline_metrics.env")
    vcomp = vcomp_result(args.vcomp)
    vcomp_disk = env(args.vcomp / "load_metrics.env")
    final_fields = {"load_seconds", "final_db_bytes", "sstable_count", "max_sstable_bytes"}
    symmetric = final_fields.issubset(vcomp_disk)
    boundary_note = (
        "Both systems include final compaction drain; sizes describe final live SST components."
        if symmetric else
        "Historical asymmetric boundaries: baseline includes drain; VComp time and SST sizes "
        "describe materialization. These are not equivalent loading/final-state measurements."
    )
    dataset_gib = int(setting(args.baseline / "configuration.txt", "dataset_gib"))
    partition_count = int(setting(args.baseline / "configuration.txt", "partition_keys"))
    baseline_rows = int(baseline["fingerprint_rows"])
    vcomp_rows = int(vcomp_disk["fingerprint_rows"])
    cardinality_delta = vcomp_rows - baseline_rows
    cardinality_error = 100.0 * cardinality_delta / baseline_rows

    data = {
        "Baseline": {
            "time": baseline["load_seconds"],
            "disk": baseline["disk_write_bytes"],
            "final": baseline["final_db_bytes"],
            "sstables": baseline["sstable_count"],
            "largest": baseline["max_sstable_bytes"],
        },
        "VComp": {
            "time": vcomp_disk["load_seconds"] if symmetric else vcomp["seconds"],
            "disk": vcomp_disk["disk_write_bytes"],
            "final": vcomp_disk["final_db_bytes"] if symmetric else vcomp["physical_sst_bytes"],
            "sstables": vcomp_disk["sstable_count"] if symmetric else vcomp["physical_sst_count"],
            "largest": vcomp_disk["max_sstable_bytes"] if symmetric else vcomp["max_physical_sst_bytes"],
        },
    }
    for row in data.values():
        row["wa"] = row["disk"] / row["final"]

    args.output.mkdir(parents=True, exist_ok=True)
    plt.style.use("seaborn-v0_8-whitegrid")
    panels = [
        ("Loading + compaction drain (min)" if symmetric else "Historical load phases (min; unequal boundaries)", "time", 60, "%.2f"),
        ("Total device write (GiB)", "disk", GIB, "%.2f"),
        ("Write amplification (×)", "wa", 1, "%.2f"),
        ("Final physical DB size (GiB)" if symmetric else "Historical SST size (GiB; unequal phases)", "final", GIB, "%.2f"),
    ]
    colors = ["#6b7280", "#2563eb"]
    fig, axes = plt.subplots(2, 2, figsize=(12, 8.5), layout="constrained")
    fig.suptitle(f"Cassandra — {dataset_gib} GiB, 24 B key + 1000 B value\nvanilla UCS(T4) baseline vs VComp",
                 fontsize=16, fontweight="bold")
    for axis, (title, key, divisor, number_format) in zip(axes.flat, panels):
        values = [data[name][key] / divisor for name in data]
        bars = axis.bar(list(data), values, color=colors, width=0.45)
        axis.set_title(title, fontweight="bold")
        axis.set_ylim(0, max(values) * 1.18 if max(values) else 1)
        axis.grid(axis="y", color="#e5e7eb")
        axis.set_axisbelow(True)
        axis.spines[["top", "right"]].set_visible(False)
        axis.bar_label(bars, labels=[number_format % value for value in values], padding=4, fontsize=10)
    stem = f"cassandra_baseline_vcomp_{dataset_gib}g"
    fig.savefig(args.output / f"{stem}.svg")
    fig.savefig(args.output / f"{stem}.png", dpi=180)
    plt.close(fig)

    lines = [
        f"# Cassandra {dataset_gib} GiB: measured baseline vs VComp",
        "",
        f"- Schema: {partition_count:,} token-ordered partition buckets, one blob clustering key, "
        "and one regular value column.",
        f"- Input: identical {dataset_gib} GiB synthetic stream (24 B key + 1,000 B value, seed 20260909).",
        "- Baseline: native CQL writes, 64 MiB explicit flushes, UCS T4 enabled, automatic compaction drain.",
        "- VComp: descriptor-only compaction with native UCS selection, partition-boundary output splitting, "
        "then one final materialization/import.",
        f"- Approximate-key accuracy: baseline {baseline_rows:,} rows; VComp {vcomp_rows:,} rows; "
        f"delta {cardinality_delta:+,} ({cardinality_error:+.3f}%).",
        f"- Measurement boundaries: {boundary_note}",
        "",
        "| System | Time (s) | Device write (GiB) | Write amp | Final DB (GiB) | SST count | Largest SST (MiB) |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for name, row in data.items():
        lines.append(f"| {name} | {row['time']:.3f} | {row['disk']/GIB:.3f} | {row['wa']:.3f}× "
                     f"| {row['final']/GIB:.3f} | {int(row['sstables'])} | {row['largest']/2**20:.3f} |")
    (args.output / f"{stem}.md").write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
