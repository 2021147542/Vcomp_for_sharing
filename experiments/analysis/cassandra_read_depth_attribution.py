#!/usr/bin/env python3
"""Join fixed local-read observations to SST candidate depth, with all paired rounds retained."""
import argparse
import json
from collections import defaultdict
from pathlib import Path
from cassandra_actual_read_analysis import quantiles, scalar_summary, MISSING


def analyze(directory, depths_file):
    directory = Path(directory)
    trace = json.loads((directory / "actual-read-requests.json").read_text())
    depths = json.loads(Path(depths_file).read_text())["requests"]
    assert all(d["native_range_depth"] == 4 for d in depths), "This attribution requires the recorded native uniform depth-4 state"
    assert [d["key"] for d in depths] == trace["keys"]
    assert [d["request_index"] for d in depths] == list(range(len(depths)))
    groups = defaultdict(lambda: {side: {"latency": [], "sst": []} for side in ("native", "vcomp")})
    rounds = []
    for number in range(1, 5):
        vectors = {side: json.loads((directory / f"actual-read-{side}-measured_{number}.json").read_text())
                   for side in ("native", "vcomp")}
        round_groups = defaultdict(lambda: {side: {"latency": [], "sst": []} for side in vectors})
        for i, depth in enumerate(depths):
            native_hit, model_hit = (vectors[side]["timestamps"][i] != MISSING for side in vectors)
            category = "both_hit" if native_hit and model_hit else "native_only_hit" if native_hit else "vcomp_only_hit" if model_hit else "both_miss"
            for category_name in ("all", category):
                group = f'vcomp_depth_{depth["vcomp_range_depth"]}/{category_name}'
                for side in vectors:
                    for target in (groups, round_groups):
                        target[group][side]["latency"].append(vectors[side]["latency_ns"][i])
                        target[group][side]["sst"].append(vectors[side]["sst_merged_iterators"][i])
        rounds.append({"round": number, "groups": summarize(round_groups)})
    overall = summarize(groups)
    contributions = {}
    for name, sides in overall.items():
        if not name.endswith("/all"): continue
        a,b = sides["native"]["latency_us"],sides["vcomp"]["latency_us"]
        contributions[name] = (b["mean_us"]-a["mean_us"])*a["count"]/(4*len(depths))
    total_gap = sum(contributions.values())
    return {"schema_version": 1, "diagnostic_only": True,
            "weighted_mean_gap_us_contributions": contributions,
            "extra_overlap_fraction_of_observed_mean_gap": sum(value for name,value in contributions.items() if name != "vcomp_depth_4/all")/total_gap if total_gap > 0 else None, "request_source": str(directory / "actual-read-requests.json"),
            "topology_source": str(depths_file),
            "scope": "Post-execution grouping of all pre-fixed queries/rounds; paired same keys on both sides, no query/seed selection. Range-depth grouping is observational, not an intervention that changes only overlap. Process disk bytes are phase-level and cannot be allocated to these groups.",
            "rounds": rounds, "all_rounds": overall}


def summarize(groups):
    return {name: {side: {"latency_us": quantiles(samples["latency"]), "sst_iterators": scalar_summary(samples["sst"])}
                   for side, samples in members.items()} for name, members in sorted(groups.items())}


def markdown(result):
    lines = ["# Fixed-query candidate-depth attribution", "", result["scope"], "",
             "| Group, all four rounds | Requests per side | Native SST/read | VComp SST/read | Native mean µs | VComp mean µs | Mean latency difference |", "|---|---:|---:|---:|---:|---:|---:|"]
    for name, sides in result["all_rounds"].items():
        a,b = sides["native"],sides["vcomp"]
        av,bv = a["latency_us"]["mean_us"],b["latency_us"]["mean_us"]
        lines.append(f'| {name} | {a["latency_us"]["count"]} | {a["sst_iterators"]["mean"]:.4f} | '
                     f'{b["sst_iterators"]["mean"]:.4f} | {av:.3f} | {bv:.3f} | {(bv/av-1)*100:+.2f}% |')
    return "\n".join(lines) + "\n"


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory",type=Path)
    parser.add_argument("depths",type=Path)
    parser.add_argument("--output",required=True,type=Path)
    parser.add_argument("--markdown",type=Path)
    args=parser.parse_args()
    result=analyze(args.directory,args.depths)
    args.output.write_text(json.dumps(result,indent=2)+"\n")
    if args.markdown: args.markdown.write_text(markdown(result))

if __name__ == "__main__": main()
