# Synchronization network validation

These development-only utilities exercise the extension's existing `THREADED` engine against
instrumented Python HTTP/HTTPS servers. They do not add runtime dependencies to the extension.

Generated certificates, keys, logs, and packet captures belong under `build/network-validation/`
and must not be committed.

The HTTP/2 server is provided as a controlled target, but the real `BURP2` client requires Burp
Suite Professional and Montoya's batch API. Run that part from Burp; do not substitute a Python
HTTP/2 client and call it an engine validation.

Suggested Windows workflow:

1. Generate a one-day localhost certificate with `generate_certificate.py`.
2. Start `controlled_server.py` on ports 5000 and 5443 (the latter with `--cert` and `--key`).
3. Start `tshark -i 10 -f "tcp port 5000 or tcp port 5443" -w <capture.pcapng>` after verifying
   that interface 10 is the Npcap loopback adapter on the current machine.
4. Run `ExternalSynchronizationValidationTest` with `TI_VALIDATION_HTTP_ENDPOINT`,
   `TI_VALIDATION_HTTPS_ENDPOINT`, `TI_VALIDATION_RUNS`, and `TI_VALIDATION_CLIENT_LOG` set.
5. Stop the capture and servers, then run `analyze_evidence.py`.

Queue order is guaranteed by the execution snapshot and synchronous calls to `queue()`. TCP write,
server handler, server processing, and response order are observations, not equivalent guarantees.
