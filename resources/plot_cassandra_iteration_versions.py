#!/usr/bin/env python3
"""Render one self-contained baseline-vs-VComp figure per Cassandra iteration."""

from __future__ import annotations

import csv
import json
import re
from dataclasses import dataclass, field
from pathlib import Path

import matplotlib.pyplot as plt


GIB = 1024**3
MIB = 1024**2
ROOT = Path("/work/vcomp-pebble-1tb")


@dataclass(frozen=True)
class Version:
    name: str
    label: str
    baseline: Path
    vcomp: Path
    workloads: tuple[Path, ...] = field(default_factory=tuple)
    workload_names: tuple[str, ...] = field(default_factory=tuple)
    caveat: str = ""


def paired(root: str) -> tuple[Path, Path]:
    values = read_text_env(ROOT / root / "COMPLETE")
    return Path(values["baseline_run"]), Path(values["vcomp_run"])


def read_text_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text().splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return values


def numeric_env(path: Path) -> dict[str, float]:
    result: dict[str, float] = {}
    for key, value in read_text_env(path).items():
        try:
            result[key] = float(value)
        except ValueError:
            pass
    return result


def vcomp_result(path: Path) -> dict[str, float]:
    line = next(line for line in (path / "vcomp.log").read_text().splitlines()
                if line.startswith("VCOMP_RESULT "))
    return {key: float(value) for key, value in re.findall(r"(\w+)=([0-9.]+)", line)}


def workload_results(version: Version) -> dict[str, tuple[float, float]]:
    if not version.workloads:
        return {}
    results: dict[str, tuple[float, float]] = {}
    for workload in version.workload_names:
        stem = workload.lower()
        pair = next(((directory / "results" / f"{stem}_baseline.json",
                      directory / "results" / f"{stem}_vcomp.json")
                     for directory in version.workloads
                     if (directory / "results" / f"{stem}_baseline.json").exists()
                     and (directory / "results" / f"{stem}_vcomp.json").exists()), None)
        if pair is None:
            continue
        baseline_path, vcomp_path = pair
        baseline = json.loads(baseline_path.read_text())["throughput_ops_per_second"]
        vcomp = json.loads(vcomp_path.read_text())["throughput_ops_per_second"]
        results[workload] = (baseline, vcomp)
    return results


def version_specs() -> list[Version]:
    ordered_b, ordered_v = paired("cassandra-vcomp-1g-ordered-paper-continuous-compare")
    shard_b, shard_v = paired("cassandra-vcomp-1g-ucs-shard-fidelity-compare")
    seeded_1g_b, seeded_1g_v = paired("cassandra-vcomp-1g-seeded-common-scheduler-compare")
    shard_100g_b, shard_100g_v = paired("cassandra-vcomp-100g-ucs-shard-fidelity-compare")
    seeded_100g_b, seeded_100g_v = paired("cassandra-vcomp-100g-seeded-common-scheduler-compare")

    shard_v2 = (ROOT / "cassandra-vcomp-1g-ucs-shard-fidelity-v2" / "vcomp" /
                "cassandra-vcomp-1g-ucs-shard-fidelity-v2-vcomp-20260913-005328")
    shared_v2 = (ROOT / "cassandra-vcomp-100g-shared-scheduler-v2" /
                 "cassandra-vcomp-100g-shared-scheduler-v2-20260913-041822")
    unbiased_v3 = (ROOT / "cassandra-vcomp-100g-shared-scheduler-unbiased-v3" /
                   "cassandra-vcomp-100g-shared-scheduler-unbiased-v3-20260913-045335")
    continuous_v4 = (ROOT / "cassandra-vcomp-100g-continuous-scheduler-v4" /
                     "cassandra-vcomp-100g-continuous-scheduler-v4-20260913-053119")

    return [
        Version("01_1g_ordered_multi_partition", "1 GiB — ordered multi-partition",
                ordered_b, ordered_v),
        Version("02_1g_ucs_shard_fidelity_v1", "1 GiB — UCS shard fidelity v1",
                shard_b, shard_v),
        Version("03_1g_ucs_shard_fidelity_v2", "1 GiB — UCS shard-output v2",
                shard_b, shard_v2,
                workloads=(ROOT / "cassandra-paper-workloads-1g-ucs-shard-v2-5min-20260913",),
                workload_names=("A", "B", "C", "D", "E", "F", "MIXGRAPH")),
        Version("04_1g_seeded_common_scheduler", "1 GiB — seeded common scheduler",
                seeded_1g_b, seeded_1g_v,
                caveat="Seed requested, but baseline jar did not contain seeded Controller"),
        Version("05_100g_ucs_shard_fidelity_v1", "100 GiB — UCS shard fidelity v1",
                shard_100g_b, shard_100g_v),
        Version("06_100g_shared_scheduler_v2", "100 GiB — shared scheduler v2",
                shard_100g_b, shared_v2,
                workloads=(ROOT / "cassandra-paper-workloads-100g-shared-scheduler-v2-mixgraph-5min-20260913",),
                workload_names=("MIXGRAPH",)),
        Version("07_100g_shared_scheduler_unbiased_v3", "100 GiB — unbiased scheduler v3",
                shard_100g_b, unbiased_v3,
                workloads=(ROOT / "cassandra-paper-workloads-100g-shared-scheduler-unbiased-v3-af-5min-20260913",
                           ROOT / "cassandra-paper-workloads-100g-shared-scheduler-unbiased-v3-mixgraph-5min-20260913"),
                workload_names=("A", "B", "C", "D", "E", "F", "MIXGRAPH"),
                caveat="A and MixGraph completed; A–F campaign was interrupted before B"),
        Version("08_100g_continuous_scheduler_v4", "100 GiB — continuous scheduler v4",
                shard_100g_b, continuous_v4,
                workloads=(ROOT / "cassandra-paper-workloads-100g-continuous-scheduler-v4-mixgraph-5min-20260913",),
                workload_names=("MIXGRAPH",)),
        Version("09_100g_seeded_common_scheduler", "100 GiB — seeded common scheduler",
                seeded_100g_b, seeded_100g_v,
                caveat="Seed requested, but baseline jar did not contain seeded Controller"),
    ]


def plot_version(version: Version, output: Path) -> dict[str, object]:
    baseline = numeric_env(version.baseline / "baseline_metrics.env")
    vcomp_disk = numeric_env(version.vcomp / "load_metrics.env")
    vcomp = vcomp_result(version.vcomp)
    configuration = read_text_env(version.vcomp / "configuration.txt")
    dataset_gib = int(configuration["dataset_gib"])
    partitions = int(configuration["partition_keys"])
    baseline_rows = int(baseline["fingerprint_rows"])
    vcomp_rows = int(vcomp_disk["fingerprint_rows"])
    row_delta_pct = 100.0 * (vcomp_rows - baseline_rows) / baseline_rows

    values = {
        "time": [baseline["load_seconds"], vcomp["seconds"]],
        "disk": [baseline["disk_write_bytes"], vcomp_disk["disk_write_bytes"]],
        "final": [baseline["final_db_bytes"], vcomp["physical_sst_bytes"]],
        "sstables": [baseline["sstable_count"], vcomp["physical_sst_count"]],
        "largest": [baseline["max_sstable_bytes"], vcomp["max_physical_sst_bytes"]],
    }
    values["wa"] = [values["disk"][i] / values["final"][i] for i in range(2)]
    workloads = workload_results(version)

    plt.style.use("seaborn-v0_8-whitegrid")
    fig, axes = plt.subplots(2, 3, figsize=(15, 9), layout="constrained")
    subtitle = (f"{dataset_gib} GiB, {partitions:,} partitions, UCS T4 | "
                f"visible rows: {baseline_rows:,} vs {vcomp_rows:,} ({row_delta_pct:+.3f}%)")
    title = f"Cassandra baseline vs F2Load\n{version.label}\n{subtitle}"
    if version.caveat:
        title += "\nCaveat: " + version.caveat
    fig.suptitle(title, fontsize=15, fontweight="bold")
    panels = [
        ("Completion time (min)", "time", 60.0, ".2f"),
        ("Device writes (GiB)", "disk", GIB, ".2f"),
        ("Write amplification (×)", "wa", 1.0, ".2f"),
        ("Final physical size (GiB)", "final", GIB, ".2f"),
        ("Final SST count", "sstables", 1.0, ".0f"),
    ]
    colors = ["#6b7280", "#2563eb"]
    for axis, (title, key, divisor, number_format) in zip(axes.flat[:5], panels):
        plotted = [value / divisor for value in values[key]]
        bars = axis.bar(["Baseline", "F2Load"], plotted, color=colors, width=0.52)
        axis.set_title(title, fontweight="bold")
        axis.set_ylim(0, max(plotted) * 1.22 if max(plotted) else 1)
        axis.bar_label(bars, labels=[format(value, number_format) for value in plotted], padding=4)
        axis.spines[["top", "right"]].set_visible(False)
        axis.grid(axis="y", color="#e5e7eb")

    workload_axis = axes.flat[5]
    workload_axis.set_title("Workload throughput delta", fontweight="bold")
    if workloads:
        names = list(workloads)
        deltas = [100.0 * (workloads[name][1] - workloads[name][0]) / workloads[name][0]
                  for name in names]
        bars = workload_axis.bar(names, deltas,
                                 color=["#16a34a" if value >= 0 else "#dc2626" for value in deltas])
        workload_axis.axhline(0, color="#374151", linewidth=0.8)
        workload_axis.bar_label(bars, labels=[f"{value:+.1f}%" for value in deltas], padding=3)
        bound = max(5.0, max(abs(value) for value in deltas) * 1.35)
        workload_axis.set_ylim(-bound, bound)
        workload_axis.set_ylabel("F2Load vs baseline (%)")
    else:
        workload_axis.text(0.5, 0.5, "No workload result\nfor this exact version",
                           ha="center", va="center", fontsize=12, color="#6b7280",
                           transform=workload_axis.transAxes)
        workload_axis.set_xticks([])
        workload_axis.set_yticks([])
    workload_axis.spines[["top", "right"]].set_visible(False)

    fig.savefig(output / f"{version.name}.svg")
    fig.savefig(output / f"{version.name}.png", dpi=170)
    plt.close(fig)

    return {
        "version": version.name,
        "label": version.label,
        "dataset_gib": dataset_gib,
        "partitions": partitions,
        "baseline_seconds": values["time"][0],
        "vcomp_seconds": values["time"][1],
        "baseline_write_gib": values["disk"][0] / GIB,
        "vcomp_write_gib": values["disk"][1] / GIB,
        "baseline_final_gib": values["final"][0] / GIB,
        "vcomp_final_gib": values["final"][1] / GIB,
        "final_size_delta_pct": 100.0 * (values["final"][1] - values["final"][0]) / values["final"][0],
        "baseline_sstables": int(values["sstables"][0]),
        "vcomp_sstables": int(values["sstables"][1]),
        "baseline_rows": baseline_rows,
        "vcomp_rows": vcomp_rows,
        "row_delta_pct": row_delta_pct,
        "workload_deltas_pct": {
            name: 100.0 * (pair[1] - pair[0]) / pair[0] for name, pair in workloads.items()
        },
        "baseline_source": str(version.baseline),
        "vcomp_source": str(version.vcomp),
        "caveat": version.caveat,
    }


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    rows = [plot_version(version, args.output) for version in version_specs()]
    (args.output / "metrics.json").write_text(json.dumps(rows, indent=2) + "\n")
    with (args.output / "metrics.csv").open("w", newline="") as handle:
        fields = [key for key in rows[0] if key != "workload_deltas_pct"]
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow({key: row[key] for key in fields})


if __name__ == "__main__":
    main()
