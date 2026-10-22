#!/usr/bin/env python3
import argparse
import json
import ssl
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class EventLog:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.sequence = 0

    def write(self, event, handler, **extra):
        with self.lock:
            self.sequence += 1
            record = {
                "event_sequence": self.sequence,
                "event": event,
                "perf_counter_ns": time.perf_counter_ns(),
                "test_id": handler.headers.get("X-TI-Test-ID"),
                "mapping_position": handler.headers.get("X-TI-Test-Sequence"),
                "slot_id": handler.headers.get("X-TI-Slot-ID"),
                "batch_id": handler.headers.get("X-TI-Test-Batch"),
                "method": handler.command,
                "path": handler.path,
                "protocol": handler.request_version,
                "connection": f"{handler.client_address[0]}:{handler.client_address[1]}",
                **extra,
            }
            with self.path.open("a", encoding="utf-8") as output:
                output.write(json.dumps(record, separators=(",", ":")) + "\n")


class ProbeHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "TIValidation/1.0"

    def do_GET(self):
        self.handle_probe()

    def do_POST(self):
        self.handle_probe()

    def handle_probe(self):
        self.server.event_log.write("handler_start", self)
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length) if length else b""
        self.server.event_log.write("body_complete", self, body_length=len(body))
        response = json.dumps({
            "test_id": self.headers.get("X-TI-Test-ID"),
            "sequence": self.headers.get("X-TI-Test-Sequence"),
            "slot_id": self.headers.get("X-TI-Slot-ID"),
        }).encode("utf-8")
        self.server.event_log.write("response_start", self)
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(response)

    def log_message(self, _format, *_args):
        return


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5000)
    parser.add_argument("--log", required=True)
    parser.add_argument("--cert")
    parser.add_argument("--key")
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.bind, args.port), ProbeHandler)
    server.daemon_threads = True
    server.event_log = EventLog(args.log)
    scheme = "http"
    if args.cert or args.key:
        if not args.cert or not args.key:
            parser.error("--cert and --key must be supplied together")
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(args.cert, args.key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
        scheme = "https"
    print(f"READY {scheme}://{args.bind}:{server.server_port}/probe", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
