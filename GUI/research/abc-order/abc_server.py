#!/usr/bin/env python3
"""Instrumented local HTTP/1.1 server for A/B/C ordering research."""

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class Recorder:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text("", encoding="utf-8")
        self.lock = threading.Lock()
        self.sequence = 0
        self.states = {}

    def event(self, handler, event, **extra):
        experiment = handler.headers.get("X-Experiment-ID", "missing")
        request_type = handler.headers.get("X-Request-Type", "?")
        copy_number = handler.headers.get("X-Copy-Number", "?")
        now = time.perf_counter_ns()
        with self.lock:
            self.sequence += 1
            record = {
                "event_sequence": self.sequence,
                "event": event,
                "perf_counter_ns": now,
                "experiment_id": experiment,
                "scenario": handler.headers.get("X-Scenario"),
                "request_type": request_type,
                "copy_number": copy_number,
                "connection_id": f"{handler.client_address[0]}:{handler.client_address[1]}",
                "http_version": handler.request_version,
                "stream_id": None,
                **extra,
            }
            with self.path.open("a", encoding="utf-8") as output:
                output.write(json.dumps(record, separators=(",", ":")) + "\n")
            return now

    def transition(self, handler):
        experiment = handler.headers.get("X-Experiment-ID", "missing")
        request_type = handler.headers.get("X-Request-Type", "?")
        now = time.perf_counter_ns()
        with self.lock:
            before = self.states.get(experiment, "INIT")
            if before == "INIT" and request_type == "A":
                after = "GOT_A"
            elif before == "GOT_A" and request_type == "B":
                after = "GOT_B"
            elif before == "GOT_B" and request_type == "C":
                after = "COMPLETE"
            elif before == "COMPLETE":
                after = "COMPLETE"
            else:
                after = "INVALID"
            self.states[experiment] = after
            self.sequence += 1
            record = {
                "event_sequence": self.sequence,
                "event": "state_transition",
                "perf_counter_ns": now,
                "experiment_id": experiment,
                "scenario": handler.headers.get("X-Scenario"),
                "request_type": request_type,
                "copy_number": handler.headers.get("X-Copy-Number", "?"),
                "connection_id": f"{handler.client_address[0]}:{handler.client_address[1]}",
                "http_version": handler.request_version,
                "stream_id": None,
                "state_before": before,
                "state_after": after,
            }
            with self.path.open("a", encoding="utf-8") as output:
                output.write(json.dumps(record, separators=(",", ":")) + "\n")
            return after


class RaceHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "ABCResearch/1.0"

    def do_POST(self):
        if self.path not in ("/race/a", "/race/b", "/race/c"):
            self.send_error(404)
            return
        recorder = self.server.recorder
        recorder.event(self, "handler_entry")
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length) if length else b""
        recorder.event(self, "body_complete", body_length=len(body))
        state = recorder.transition(self)
        payload = json.dumps({"state": state}).encode("ascii")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)
        self.wfile.flush()
        recorder.event(self, "response_complete", response_length=len(payload))

    def log_message(self, _format, *_args):
        pass


class RaceServer(ThreadingHTTPServer):
    # The standard-library default is 5. A larger local listen backlog prevents the server's
    # accept queue from becoming the variable under test in a 60-connection client experiment.
    request_queue_size = 256


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5081)
    parser.add_argument("--log", required=True)
    args = parser.parse_args()
    server = RaceServer((args.bind, args.port), RaceHandler)
    server.daemon_threads = True
    server.recorder = Recorder(args.log)
    print(f"READY http://{args.bind}:{server.server_port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
