# Turbo Intruder GUI network-order validation

Validated on 2026-10-21 with Java 21.0.2 and the real `ThreadedRequestEngine`.
Every controlled request carried `X-TI-Test-Sequence: N`; the temporary servers
echoed `N` in `X-Probe-Sequence`, allowing response association to be checked.

| Protocol / technique | Configured and queued | Server-observed arrival | Result |
|---|---|---|---|
| HTTP/1.1 sequential | 1..6 | 1, 2, 3, 4, 5, 6 | Ordered |
| HTTP/1.1 concurrent | 1..12 | 1, 3, 2, 6, 4, 5, 7, 8, 9, 10, 11, 12 | All associated; arrival reordered |
| HTTP/1.1 last-byte | 1..8 | 8, 1, 2, 7, 3, 4, 6, 5 | Gate opened and all associated; arrival reordered |
| HTTPS/HTTP/1.1 sequential | 1..5 | 1, 2, 3, 4, 5 | Ordered |
| HTTPS/HTTP/1.1 concurrent | 1..10 | 10, 1, 3, 6, 2, 7, 8, 5, 4, 9 | All associated; arrival reordered |

The queue-order unit test also uses interleaved target endpoints and two batches.
It observed submission order `1, 2, 3, 4, 5, 6`, matching the mapping order.

## Packet evidence

A tshark capture on the Npcap loopback adapter confirmed the plain-HTTP test.
Sequential request frames carried sequence values `1, 2, 3, 4, 5, 6`. A separate
concurrent run was visible on the wire as `3, 4, 1, 2, 7, 6, 5, 10, 8, 9, 12, 11`.
The capture is a generated build artifact at `build/gui-loopback-validation.pcapng`.

The supplied `http://localhost:5000/probe` returned HTTP 200 from Kestrel. A TLS
request to `https://localhost:5000/probe` failed during TLS negotiation, so HTTPS
validation used a controlled self-signed TLS server instead. HTTPS correctness was
measured at the server and through echoed response identifiers, not inferred from
encrypted packet contents.

## Interpretation and limitations

The GUI now guarantees deterministic queue submission in displayed mapping order.
Sequential HTTP/1.1 also preserved the observed write and server-arrival order in
these tests. Concurrent connections and synchronization gates intentionally do not
guarantee packet, final-byte, server-arrival, server-processing, or response order.
The observed reorderings demonstrate that distinction; every response nevertheless
remained associated with its originating request slot.

HTTP/2 Single-Packet behavior still requires Burp's HTTP/2 engine and an HTTP/2
endpoint. That runtime was not available to the command-line test harness, and the
provided endpoint exposed HTTP/1.1 only, so no claim is made about HTTP/2 wire or
stream order. Existing BURP2 queue/gate compatibility is covered by focused unit
tests and should be verified manually in Burp against an HTTP/2-capable target.
HTTP/3 was not tested because no local QUIC endpoint was available.

Run the repeatable controlled validation with:

```powershell
$env:JAVA_HOME='D:\Applications\jdk-21.0.2'
.\gradlew.bat test --tests "burp.gui.ThreadedNetworkIntegrationTest"
```
