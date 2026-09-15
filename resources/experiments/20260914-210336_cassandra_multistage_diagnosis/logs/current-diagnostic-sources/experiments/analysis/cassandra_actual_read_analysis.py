#!/usr/bin/env python3
"""Analyze actual-read diagnostic vectors without selecting requests or passes."""
import argparse
from collections import Counter
import json
import math
from pathlib import Path

MISSING = -(1 << 63)


def quantiles(values):
    values = sorted(values)
    if not values:
        return {"count": 0, "mean_us": None, "p50_us": None, "p95_us": None, "p99_us": None}
    result = {"count": len(values), "mean_us": sum(values) / len(values) / 1000}
    for name, q in (("p50_us", .5), ("p95_us", .95), ("p99_us", .99)):
        result[name] = values[math.ceil(q * len(values)) - 1] / 1000
    return result


def scalar_summary(values):
    return {"count": len(values), "sum": sum(values) if values else None,
            "mean": sum(values) / len(values) if values else None,
            "max": max(values) if values else None}


def analyze(directory):
    directory = Path(directory)
    source = json.loads((directory / "actual-read-summary.json").read_text())
    requests = json.loads((directory / "actual-read-requests.json").read_text())
    count = source["requests_per_phase"]
    assert len(requests["keys"]) == count
    rounds = []
    accumulated = {group: {side: [] for side in ("native", "vcomp")}
                   for group in ("all", "both_hit", "native_only_hit", "vcomp_only_hit", "both_miss")}
    stable_classes = None
    accumulated_sst = {group: {side: [] for side in ("native", "vcomp")} for group in accumulated}
    for number in range(1, 5):
        phase = f"measured_{number}"
        vectors = {side: json.loads((directory / f"actual-read-{side}-{phase}.json").read_text())
                   for side in ("native", "vcomp")}
        classes = []
        timestamp_differences = 0
        sst_groups = {group: {side: [] for side in vectors} for group in accumulated}
        groups = {group: {side: [] for side in vectors} for group in accumulated}
        for index in range(count):
            hits = [vectors[side]["timestamps"][index] != MISSING for side in vectors]
            category = ("both_hit" if all(hits) else "native_only_hit" if hits[0]
                        else "vcomp_only_hit" if hits[1] else "both_miss")
            classes.append(category)
            if category == "both_hit" and vectors["native"]["timestamps"][index] != vectors["vcomp"]["timestamps"][index]:
                timestamp_differences += 1
            for side, vector in vectors.items():
                value = vector["latency_ns"][index]
                assert value >= 0
                groups["all"][side].append(value)
                groups[category][side].append(value)
                accumulated["all"][side].append(value)
                accumulated[category][side].append(value)
                if "sst_merged_iterators" in vector:
                    sst = vector["sst_merged_iterators"][index]
                    for group in ("all", category):
                        sst_groups[group][side].append(sst)
                        accumulated_sst[group][side].append(sst)
        if stable_classes is not None:
            assert classes == stable_classes, "request result changed across passes"
        stable_classes = classes
        phases = {}
        for side, vector in vectors.items():
            item = dict(vector["summary"])
            upper = item["sst_histogram_bucket_upper_bounds"]
            buckets = item["sst_histogram_bucket_counts"]
            assert len(buckets) == len(upper) + 1
            assert sum(buckets) == item["sst_histogram_count"] == count
            item["sst_histogram_nonzero_buckets"] = {str(upper[i]) if i < len(upper) else "overflow": n
                                                     for i, n in enumerate(buckets) if n}
            assert not buckets[-1], "histogram overflow"
            item["sst_per_request_bucket_upper_mean"] = sum(n * upper[i] for i, n in enumerate(buckets[:-1])) / count
            item["counters"] = dict(zip(item["counter_names"], item["counter_deltas"]))
            item.pop("sst_histogram_bucket_counts")
            item.pop("sst_histogram_bucket_upper_bounds")
            phases[side] = item
        rounds.append({"same_key_timestamp_differences": timestamp_differences,
                       "sst_counts_by_same_request_group": {group: {side: scalar_summary(values) for side, values in members.items()}
                                                            for group, members in sst_groups.items()},
                       "round": number, "first": "native" if number in (1, 4) else "vcomp",
                       "actual_read_phases": phases,
                       "same_request_groups": {group: {side: quantiles(values) for side, values in members.items()}
                                               for group, members in groups.items()}})
    key_classes = {}
    frequencies = Counter(requests["keys"])
    for key, category in zip(requests["keys"], stable_classes):
        if key in key_classes:
            assert key_classes[key] == category, "same key returned inconsistent membership"
        key_classes[key] = category
    return {
        "schema_version": 1, "diagnostic_only": True, "seed": requests["seed"], "domain": requests["domain"],
        "requests_per_round_per_side": count,
        "actual_requests_excluding_warmup": count * 8,
        "classification": "All four pre-fixed paired rounds, no selected passes; same-request hit groups computed after execution",
        "limitations": source["scope"] + " " + source["io_limits"],
        "cache_eviction_requested": source.get("cache_eviction_requested", False),
        "eviction_scope": source.get("eviction_scope", "No explicit data-page eviction"),
        "histogram_note": "SSTablesPerReadHistogram records merged SST iterators. Bucket-upper mean is exact for singleton buckets and an upper-bound approximation otherwise; not candidate count or device I/O count.",
        "unique_requested_keys": len(key_classes),
        "unique_key_membership": dict(Counter(key_classes.values())),
        "most_frequent_requests": [{"key": key, "requests_per_phase": frequency, "membership": key_classes[key]}
                                   for key, frequency in frequencies.most_common(10)],
        "paired_request_membership": {group: stable_classes.count(group) for group in accumulated if group != "all"},
        "native_sstables": source["native_sstables"], "vcomp_sstables": source["vcomp_sstables"],
        "rounds": rounds,
        "all_rounds_sst_groups": {group: {side: scalar_summary(values) for side, values in members.items()}
                                  for group, members in accumulated_sst.items()},
        "all_rounds_latency_groups": {group: {side: quantiles(values) for side, values in members.items()}
                                      for group, members in accumulated.items()},
    }


def markdown(result):
    lines = ["# Actual local read-path diagnostic", "", result["classification"], "", result["limitations"], "",
             f'Data.db cache eviction requested: {result["cache_eviction_requested"]}. {result["eviction_scope"]}', "",
             "| Round | First | Side | Hits | Misses | SST/read upper mean | Mean command µs | p50 µs | p99 µs | Process read bytes |", "|---|---|---|---:|---:|---:|---:|---:|---:|---:|"]
    for rnd in result["rounds"]:
        for side, phase in rnd["actual_read_phases"].items():
            q = rnd["same_request_groups"]["all"][side]
            lines.append(f'| {rnd["round"]} | {rnd["first"]} | {side} | {phase["hits"]} | {phase["misses"]} | '
                         f'{phase["sst_per_request_bucket_upper_mean"]:.4f} | {q["mean_us"]:.3f} | '
                         f'{q["p50_us"]:.3f} | {q["p99_us"]:.3f} | {phase["linux_process_io_deltas"]["read_bytes"]} |')
    lines += ["", result["histogram_note"], "", "| Same-request group, all rounds | Native count | VComp count | Native mean µs | VComp mean µs |", "|---|---:|---:|---:|---:|"]
    for group, sides in result["all_rounds_latency_groups"].items():
        a, b = sides["native"], sides["vcomp"]
        lines.append(f'| {group} | {a["count"]} | {b["count"]} | {a["mean_us"]} | {b["mean_us"]} |')
    lines += ["", "| Same-request group, all rounds | Native actual merged SST/read | VComp actual merged SST/read |", "|---|---:|---:|"]
    for group, sides in result["all_rounds_sst_groups"].items():
        lines.append(f'| {group} | {sides["native"]["mean"]} | {sides["vcomp"]["mean"]} |')
    lines += ["", f'Same-key timestamp differences per round: {result["rounds"][0]["same_key_timestamp_differences"]}.']
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--markdown", type=Path)
    args = parser.parse_args()
    result = analyze(args.directory)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    if args.markdown:
        args.markdown.write_text(markdown(result))


if __name__ == "__main__":
    main()
