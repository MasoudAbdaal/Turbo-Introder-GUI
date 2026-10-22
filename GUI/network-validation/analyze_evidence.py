#!/usr/bin/env python3
import argparse
import json
import shutil
import statistics
import subprocess
from collections import defaultdict
from pathlib import Path


def load_jsonl(path):
    if not path or not Path(path).exists():
        return []
    with Path(path).open(encoding="utf-8") as source:
        return [json.loads(line) for line in source if line.strip()]


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[round((len(ordered) - 1) * fraction)]


def packet_summary(pcap):
    if not pcap:
        return {"status": "not requested"}
    tshark = shutil.which("tshark")
    if not tshark:
        return {"status": "tshark unavailable"}
    command = [
        tshark, "-r", str(pcap), "-Y", "tcp", "-T", "fields",
        "-e", "frame.number", "-e", "frame.time_epoch", "-e", "tcp.stream",
        "-e", "tcp.seq", "-e", "tcp.len", "-e", "tcp.analysis.retransmission",
        "-e", "tcp.analysis.out_of_order",
    ]
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    rows = [line.split("\t") for line in result.stdout.splitlines() if line]
    return {
        "status": "parsed" if result.returncode == 0 else "failed",
        "tcp_frames": len(rows),
        "tcp_streams": len({row[2] for row in rows if len(row) > 2 and row[2]}),
        "retransmissions": sum(len(row) > 5 and bool(row[5]) for row in rows),
        "out_of_order": sum(len(row) > 6 and bool(row[6]) for row in rows),
        "tshark_error": result.stderr.strip() or None,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--client", required=True)
    parser.add_argument("--server", action="append", default=[])
    parser.add_argument("--pcap")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    client = load_jsonl(args.client)
    server = [record for path in args.server for record in load_jsonl(path)]
    queue_groups = defaultdict(list)
    for event in client:
        if event["event"] == "queue_submit":
            queue_groups[(event["endpoint"], event["scenario"], event["run"])].append(event["slot_id"])
    server_groups = defaultdict(list)
    for event in server:
        if event["event"] == "handler_start":
            server_groups[(event["protocol"], event["batch_id"])].append(int(event["mapping_position"]))
    expected = [1, 2, 3, 4, 5]
    queue_mismatches = sum(order != expected for order in queue_groups.values())
    server_mismatches = sum(order != expected for order in server_groups.values())
    server_spreads = []
    by_batch = defaultdict(list)
    for event in server:
        if event["event"] == "handler_start":
            by_batch[event["batch_id"]].append(event["perf_counter_ns"])
    for timestamps in by_batch.values():
        if len(timestamps) > 1:
            server_spreads.append((max(timestamps) - min(timestamps)) / 1_000_000)
    preparation_ms = [event["duration_ns"] / 1_000_000 for event in client if event["event"] == "prepared"]
    terminal = [event for event in client if event["event"] == "terminal"]
    summary = {
        "experiments": len(queue_groups),
        "queue_order_mismatches": queue_mismatches,
        "server_batches": len(server_groups),
        "server_order_differences": server_mismatches,
        "terminal_results": len(terminal),
        "failed_results": sum(event.get("state") != "COMPLETED" for event in terminal),
        "server_handler_spread_ms": {
            "min": min(server_spreads) if server_spreads else None,
            "median": statistics.median(server_spreads) if server_spreads else None,
            "p95": percentile(server_spreads, 0.95),
            "max": max(server_spreads) if server_spreads else None,
        },
        "request_preparation_ms": {
            "median": statistics.median(preparation_ms) if preparation_ms else None,
            "p95": percentile(preparation_ms, 0.95),
        },
        "packet_capture": packet_summary(args.pcap),
        "limitations": [
            "Server handler_start occurs after the HTTP parser has read request headers.",
            "Client and server perf_counter_ns values have independent origins and are not compared directly.",
            "Encrypted captures do not reveal HTTP/2 frames without TLS secrets.",
        ],
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
