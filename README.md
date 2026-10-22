# Turbo Intruder GUI

A graphical interface for Turbo Intruder that simplifies HTTP request execution, race-condition research, fuzzing, and response analysis.

This is an independent, unofficial project built around the original [PortSwigger Turbo Intruder](https://github.com/PortSwigger/turbo-intruder) engines. It is not maintained or endorsed by PortSwigger.

## Download

**[Download Turbo Intruder GUI (.JAR)](https://github.com/MasoudAbdaal/Turbo-Introder-GUI/raw/refs/heads/main/turbo-intruder-gui.jar)**

## Installation

1. Download `turbo-intruder-gui.jar` using the link above.
2. In Burp Suite, open **Extensions > Installed > Add**.
3. Select extension type **Java** and import the downloaded JAR.

The extension adds the **Turbo Intruder GUI** suite tab and an **Add to Turbo Intruder GUI** request context-menu action. The original Turbo Intruder scripting workflow remains available.

## Features

- Sequential, concurrent, HTTP/1 last-byte, HTTP/2 single-packet, and supported HTTP/3 execution modes
- Editable multi-request scenarios, identities, request mapping, drag-and-drop ordering, and repeated batches
- Streaming wordlist fuzzing and cancellation
- Sortable and filterable response analysis with custom JSON, header, and regex columns
- Grouping, duplicate highlighting, and raw request/response viewers

## Build from source

Java 21 is required.

```bash
git clone --recurse-submodules https://github.com/MasoudAbdaal/Turbo-Introder-GUI.git
cd Turbo-Introder-GUI/GUI
./gradlew build
```

On Windows, run `.\gradlew.bat build` from `GUI/`. A successful build and test run replaces `../turbo-intruder-gui.jar` with the newly verified extension.

If you cloned without submodules, initialize them from the repository root with:

```bash
git submodule update --init --recursive
```

Development source, tests, validation tools, and documentation are under [`GUI/`](GUI/).

## Credits and license

Turbo Intruder is Copyright PortSwigger Ltd and contributors. Current upstream versions are licensed under AGPL-3.0, with identified portions retaining Apache-2.0 terms. This independent combined project preserves the upstream [`LICENSE`](LICENSE), [`LICENSE-APACHE-2.0`](LICENSE-APACHE-2.0), and [`NOTICE`](NOTICE) files.
