#!/usr/bin/env python3
"""Summarize server and packet evidence from the A/B/C experiments."""

import argparse
import csv
import json
import statistics
from collections import defaultdict
from pathlib import Path


def percentile(values, fraction):
    if not values:
        return 0
    ordered = sorted(values)
    return ordered[round((len(ordered) - 1) * fraction)]


def has_subsequence(order):
    wanted = iter("ABC")
    current = next(wanted, None)
    for item in order:
        if item == current:
            current = next(wanted, None)
            if current is None:
                return True
    return False


def spread(events, event_name):
    values = [event["perf_counter_ns"] for event in events if event["event"] == event_name]
    return (max(values) - min(values)) / 1_000_000 if values else 0.0


def handler_waits(events):
    by_request = defaultdict(dict)
    for event in events:
        key = (event["request_type"], event["copy_number"])
        by_request[key][event["event"]] = event["perf_counter_ns"]
    waits = []
    violations = 0
    expected = ("handler_entry", "body_complete", "state_transition", "response_complete")
    for lifecycle in by_request.values():
        if all(name in lifecycle for name in expected):
            stamps = [lifecycle[name] for name in expected]
            violations += stamps != sorted(stamps)
            waits.append((lifecycle["body_complete"] - lifecycle["handler_entry"]) / 1_000_000)
    return waits, violations


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server-log", required=True)
    parser.add_argument("--client-log", required=True)
    parser.add_argument("--csv", required=True)
    parser.add_argument("--json", required=True)
    args = parser.parse_args()

    events_by_id = defaultdict(list)
    for line in Path(args.server_log).read_text(encoding="utf-8").splitlines():
        event = json.loads(line)
        events_by_id[event["experiment_id"]].append(event)

    completed = {}
    for line in Path(args.client_log).read_text(encoding="utf-8").splitlines():
        event = json.loads(line)
        if event["event"] == "trial_complete":
            completed[(event["scenario"], event["trial"])] = event

    experiments = []
    for experiment_id, events in events_by_id.items():
        transitions = [event for event in events if event["event"] == "state_transition"]
        transitions.sort(key=lambda event: event["event_sequence"])
        order = "".join(event["request_type"] for event in transitions)
        waits, violations = handler_waits(events)
        experiments.append({
            "experiment_id": experiment_id,
            "scenario": events[0]["scenario"],
            "requests": len(transitions),
            "completed": sum(event["event"] == "response_complete" for event in events),
            "order": order,
            "subsequence": has_subsequence(order),
            "valid_transition": any(event.get("state_after") == "COMPLETE" for event in transitions),
            "handler_spread_ms": spread(events, "handler_entry"),
            "body_spread_ms": spread(events, "body_complete"),
            "transition_spread_ms": spread(events, "state_transition"),
            "connections": len({event["connection_id"] for event in transitions}),
            "handler_to_body_median_ms": statistics.median(waits),
            "lifecycle_order_violations": violations,
        })

    grouped = defaultdict(list)
    for experiment in experiments:
        grouped[experiment["scenario"]].append(experiment)

    rows = []
    for scenario, items in sorted(grouped.items()):
        handler = [item["handler_spread_ms"] for item in items]
        body = [item["body_spread_ms"] for item in items]
        transition = [item["transition_spread_ms"] for item in items]
        rows.append({
            "scenario": scenario,
            "experiments": len(items),
            "requests_per_experiment": sorted({item["requests"] for item in items}),
            "total_requests": sum(item["requests"] for item in items),
            "total_completed": sum(item["completed"] for item in items),
            "subsequence_trials": sum(item["subsequence"] for item in items),
            "subsequence_rate_pct": 100 * sum(item["subsequence"] for item in items) / len(items),
            "valid_trials": sum(item["valid_transition"] for item in items),
            "valid_rate_pct": 100 * sum(item["valid_transition"] for item in items) / len(items),
            "handler_spread_median_ms": statistics.median(handler),
            "handler_spread_p95_ms": percentile(handler, 0.95),
            "body_spread_median_ms": statistics.median(body),
            "body_spread_p95_ms": percentile(body, 0.95),
            "transition_spread_median_ms": statistics.median(transition),
            "connections_median": statistics.median(item["connections"] for item in items),
            "handler_to_body_median_ms": statistics.median(item["handler_to_body_median_ms"] for item in items),
            "lifecycle_order_violations": sum(item["lifecycle_order_violations"] for item in items),
            "sample_orders": [item["order"] for item in items[:3]],
        })

    Path(args.json).write_text(json.dumps({"summary": rows, "experiments": experiments}, indent=2), encoding="utf-8")
    with Path(args.csv).open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=[key for key in rows[0] if key != "sample_orders"])
        writer.writeheader()
        for row in rows:
            writer.writerow({key: value for key, value in row.items() if key != "sample_orders"})
    print(json.dumps(rows, indent=2))


if __name__ == "__main__":
    main()
