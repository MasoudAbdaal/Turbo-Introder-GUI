# Turbo Intruder GUI

Turbo Intruder GUI is an independent, unofficial Burp Suite extension that adds a practical Swing interface around the original Turbo Intruder engines. It helps security researchers configure HTTP request runs, investigate race conditions, perform wordlist-based testing, and analyze response differences without replacing Turbo Intruder's scripting workflow.

This project is not maintained or endorsed by PortSwigger. The official Turbo Intruder source is included as a pinned Git submodule and compiled with this project's GUI into one extension JAR.

## Main features

- Sequential and concurrent HTTP request execution
- HTTP/1 last-byte synchronization, HTTP/2 single-packet support, and supported HTTP/3 gate modes
- Multiple editable requests, identities, request mapping, drag-and-drop ordering, and repeated batches
- Streaming wordlist-based brute-force execution
- Sortable and filterable response table with JSON, header, and regular-expression extraction
- Grouping, duplicate highlighting, and raw request/response viewers
- The original Turbo Intruder context menu and Python scripting interface remain available

## Requirements

- Java 21
- Burp Suite Community or Professional
- Git with submodule support

HTTP/2 operations that use Burp's native HTTP/2 engine require Burp Suite Professional. HTTP/3 testing requires a compatible QUIC target.

## Clone and build

Clone recursively so the pinned upstream source is available:

```bash
git clone --recurse-submodules https://github.com/MasoudAbdaal/Turbo-Introder-GUI.git
cd Turbo-Introder-GUI
./gradlew build
```

On Windows:

```powershell
git clone --recurse-submodules https://github.com/MasoudAbdaal/Turbo-Introder-GUI.git
Set-Location Turbo-Introder-GUI
.\gradlew.bat build
```

If the repository was cloned without submodules, run:

```bash
git submodule update --init --recursive
```

The standalone extension is written to:

```text
GUI/dist/turbo-intruder-gui.jar
```

The default build runs the GUI tests and upstream unit/compatibility tests. It excludes upstream's Jython and MCP live-host integration tests because they send requests to a public host. Controlled local network-validation instructions are under `GUI/network-validation/` and `GUI/docs/`.

## Install and use

1. Open Burp Suite and go to **Extensions > Installed**.
2. Add a Java extension and select `GUI/dist/turbo-intruder-gui.jar`.
3. Open the **Turbo Intruder GUI** suite tab, or right-click an HTTP request and choose **Add to Turbo Intruder GUI**.
4. Configure requests and the engine, then use **Start** and inspect results in **Responses**.

The original **Send to Turbo Intruder** scripting action remains available alongside the GUI action.

## Updating Turbo Intruder

Upstream updates are deliberate rather than automatic:

```bash
git -C turbo-intruder fetch origin
git -C turbo-intruder checkout <reviewed-upstream-commit>
./gradlew build
git add turbo-intruder
git commit -m "chore: update Turbo Intruder submodule"
```

Before committing an update, compare upstream's `src/BurpExtender.kt` with `GUI/integration/burp/BurpExtender.kt`, reconcile upstream entry-point changes, and run the complete build and test suite.

## Project layout

- `GUI/src/` — independently maintained GUI implementation
- `GUI/integration/` — combined Burp extension entry point
- `GUI/test/` — GUI and engine-compatibility tests
- `GUI/network-validation/` — optional controlled validation tools
- `turbo-intruder/` — official pinned upstream submodule

## Licensing and attribution

Turbo Intruder is Copyright PortSwigger Ltd and contributors. Current upstream versions are distributed under the GNU Affero General Public License v3.0, with identified portions retaining Apache License 2.0 terms. This repository preserves `LICENSE`, `LICENSE-APACHE-2.0`, and `NOTICE`; see those files for the applicable terms and attribution.

The GUI is distributed as part of the combined AGPL-3.0 work. Complete corresponding source and build instructions are provided in this repository.
