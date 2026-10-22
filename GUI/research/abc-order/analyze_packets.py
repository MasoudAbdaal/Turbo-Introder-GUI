#!/usr/bin/env python3
"""Analyze TShark TSV evidence for gated HTTP/1.1 completion bytes."""

import argparse
import csv
import json
import re
import statistics
from collections import defaultdict
from pathlib import Path


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[round((len(ordered) - 1) * fraction)] if ordered else 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tsv", required=True)
    parser.add_argument("--retransmissions", type=int, required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    experiments = defaultdict(list)
    stream_ids = set()

    with Path(args.tsv).open(newline="", encoding="utf-8-sig") as source:
        for row in csv.reader(source, delimiter="\t"):
            if len(row) < 5:
                continue
            frame, epoch, stream, length, headers = row[:5]
            experiment = re.search(r"X-Experiment-ID: ([^\\]+)\\r\\n", headers)
            scenario = re.search(r"X-Scenario: ([^\\]+)\\r\\n", headers)
            if not experiment or not scenario:
                continue
            stream_ids.add(int(stream))
            experiments[(scenario.group(1), experiment.group(1))].append({
                "frame": int(frame),
                "epoch": float(epoch),
                "stream": int(stream),
                "length": int(length),
            })

    by_scenario = defaultdict(list)
    for (scenario, experiment), packets in experiments.items():
        epochs = [packet["epoch"] for packet in packets]
        by_scenario[scenario].append({
            "experiment_id": experiment,
            "completion_packets": len(packets),
            "distinct_streams": len({packet["stream"] for packet in packets}),
            "release_spread_ms": (max(epochs) - min(epochs)) * 1000,
            "all_one_byte": all(packet["length"] == 1 for packet in packets),
        })

    summary = []
    for scenario, items in sorted(by_scenario.items()):
        spreads = [item["release_spread_ms"] for item in items]
        summary.append({
            "scenario": scenario,
            "experiments": len(items),
            "completion_packets": sum(item["completion_packets"] for item in items),
            "all_completion_payloads_one_byte": all(item["all_one_byte"] for item in items),
            "connections_per_experiment": sorted({item["distinct_streams"] for item in items}),
            "release_spread_median_ms": statistics.median(spreads),
            "release_spread_p95_ms": percentile(spreads, 0.95),
            "release_spread_max_ms": max(spreads),
        })

    result = {
        "retransmissions": args.retransmissions,
        "gated_completion_packets": sum(len(packets) for packets in experiments.values()),
        "distinct_gated_tcp_streams": len(stream_ids),
        "summary": summary,
        "experiments": [item for items in by_scenario.values() for item in items],
    }
    Path(args.output).write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result["summary"], indent=2))


if __name__ == "__main__":
    main()
