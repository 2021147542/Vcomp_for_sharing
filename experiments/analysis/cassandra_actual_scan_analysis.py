#!/usr/bin/env python3
"""Summarize every pre-fixed E scan diagnostic pass, retaining paired request differences."""
import argparse
import json
from pathlib import Path


def analyze(directory):
    directory = Path(directory)
    summary = json.loads((directory / "actual-scan-summary.json").read_text())
    trace = json.loads((directory / "actual-scan-requests.json").read_text())
    expected_operations = [i for i, (_, length) in enumerate(trace["operations"]) if length >= 0]
    summaries, paired = [], []
    last_vectors = {}
    for number in range(1, 5):
        sides = {}
        for side in ("native", "vcomp"):
            data = json.loads((directory / f"actual-scan-{side}-measured_{number}.json").read_text())
            assert [row["operation"] for row in data["scans"]] == expected_operations
            vectors = [{k: v for k, v in row.items() if k != "latency_ns"} for row in data["scans"]]
            if side in last_vectors:
                assert last_vectors[side] == vectors, "frozen-state scan results changed between passes"
            last_vectors[side] = vectors
            item = data["summary"]
            assert sum(row["rows"] for row in data["scans"]) == item["returned_rows"]
            assert sum(row["sst_merged_iterators"] for row in data["scans"]) == item["sst_merged_iterators"]
            assert sum(row["partition_commands"] for row in data["scans"]) == item["partition_commands"]
            item["counters"] = dict(zip(item["counter_names"], item["counter_deltas"]))
            item["mean_sst_iterators_per_scan"] = item["sst_merged_iterators"] / item["scans"]
            item["mean_partition_commands_per_scan"] = item["partition_commands"] / item["scans"]
            summaries.append(item)
            sides[side] = data["scans"]
        differences = {field: 0 for field in ("rows", "partition_commands", "sst_merged_iterators", "first_key", "last_key", "row_keys_hash31")}
        for a, b in zip(sides["native"], sides["vcomp"]):
            assert a["operation"] == b["operation"]
            for field in differences:
                differences[field] += a[field] != b[field]
        paired.append({"round": number, "different_scan_counts_by_field": differences})
    assert all(item["different_scan_counts_by_field"] == paired[0]["different_scan_counts_by_field"] for item in paired)
    effective_scope = summary["measurement_scope"]
    if summary.get("cache_eviction_requested", False):
        effective_scope = effective_scope.replace("warm OS,", "best-effort Data.db eviction (index/key cache unchanged),")
    return {"schema_version": 1, "diagnostic_only": True, "seed": trace["seed"], "domain": trace["domain"],
            "generated_operations": len(trace["operations"]), "executed_scans_per_phase": len(expected_operations),
            "skipped_insert_decisions_per_phase": len(trace["operations"]) - len(expected_operations),
            "measurement_scope": effective_scope, "source_measurement_scope": summary["measurement_scope"], "io_limits": summary["io_limits"],
            "cache_eviction_requested": summary.get("cache_eviction_requested", False),
            "eviction_scope": summary.get("eviction_scope", "No explicit Data.db cache eviction"),
            "native_sstables": summary["native_sstables"], "vcomp_sstables": summary["vcomp_sstables"],
            "all_fixed_measured_phases": summaries, "paired_differences": paired,
            "exactness_limit": "First/last keys and rolling row-key hash detect differences but a matching hash alone is not a proof of complete row equality."}


def markdown(data):
    lines = ["# Actual frozen-state E scan diagnostic", "", data["measurement_scope"], "", data["io_limits"], "",
             f'Data.db eviction requested: {data["cache_eviction_requested"]}. {data["eviction_scope"]}', "",
             "| Phase | Side | Scans | Returned rows | Partition commands | Merged SST iterators | Mean µs | p99 µs | Process read bytes |",
             "|---|---|---:|---:|---:|---:|---:|---:|---:|"]
    for p in data["all_fixed_measured_phases"]:
        q = p["latency_ns_including_value_validation"]
        lines.append(f'| {p["phase"]} | {p["side"]} | {p["scans"]} | {p["returned_rows"]} | {p["partition_commands"]} | '
                     f'{p["sst_merged_iterators"]} | {q["mean"]/1000:.3f} | {q["p99"]/1000:.3f} | {p["linux_process_io_deltas"]["read_bytes"]} |')
    lines += ["", "Per-scan field mismatches (same in all four rounds):", "", "```json",
              json.dumps(data["paired_differences"][0]["different_scan_counts_by_field"], indent=2), "```", "", data["exactness_limit"]]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--markdown", type=Path)
    args = parser.parse_args()
    result = analyze(args.directory)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    if args.markdown:
        args.markdown.write_text(markdown(result))


if __name__ == "__main__":
    main()
