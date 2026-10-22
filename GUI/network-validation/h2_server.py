#!/usr/bin/env python3
import argparse
import asyncio
import json
import threading
import time
from pathlib import Path

from hypercorn.asyncio import serve
from hypercorn.config import Config


class JsonLog:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.sequence = 0

    def write(self, event, scope, headers, **extra):
        with self.lock:
            self.sequence += 1
            values = {key.decode().lower(): value.decode() for key, value in headers}
            record = {
                "event_sequence": self.sequence,
                "event": event,
                "perf_counter_ns": time.perf_counter_ns(),
                "test_id": values.get("x-ti-test-id"),
                "mapping_position": values.get("x-ti-test-sequence"),
                "slot_id": values.get("x-ti-slot-id"),
                "batch_id": values.get("x-ti-test-batch"),
                "method": scope["method"],
                "path": scope["path"],
                "protocol": scope["http_version"],
                "connection": f"{scope['client'][0]}:{scope['client'][1]}",
                **extra,
            }
            with self.path.open("a", encoding="utf-8") as output:
                output.write(json.dumps(record, separators=(",", ":")) + "\n")


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5443)
    parser.add_argument("--log", required=True)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    args = parser.parse_args()
    events = JsonLog(args.log)

    async def app(scope, receive, send):
        headers = scope["headers"]
        events.write("handler_start", scope, headers)
        body = b""
        while True:
            message = await receive()
            body += message.get("body", b"")
            if not message.get("more_body", False):
                break
        events.write("body_complete", scope, headers, body_length=len(body))
        response = json.dumps({"test_id": dict(headers).get(b"x-ti-test-id", b"").decode()}).encode()
        events.write("response_start", scope, headers)
        await send({"type": "http.response.start", "status": 200, "headers": [
            (b"content-type", b"application/json"), (b"content-length", str(len(response)).encode()),
        ]})
        await send({"type": "http.response.body", "body": response})

    config = Config()
    config.bind = [f"{args.bind}:{args.port}"]
    config.certfile = args.cert
    config.keyfile = args.key
    config.alpn_protocols = ["h2", "http/1.1"]
    print(f"READY https://{args.bind}:{args.port}/probe (HTTP/2 ALPN)", flush=True)
    await serve(app, config)


if __name__ == "__main__":
    asyncio.run(main())
