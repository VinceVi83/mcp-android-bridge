# mcp-android-bridge

`mcp-android-bridge` is a small monorepo that connects an Android client to a Python-based MCP service over HTTP(S). The Android application provides a simple interface for text and microphone input; the server exposes the corresponding tools behind an authenticated `/call` endpoint.

The project is intended for local-network or private development environments. Replace the placeholder configuration and tool implementations before using it in production.

## Components

- **Android client**
  - Presents the available tools from `actions.json`.
  - Sends text requests directly to the server.
  - Records microphone input locally as M4A and sends it as Base64 for audio tools.
  - Can copy selected results to the Android clipboard.
  - Supports Android process-text/shared-text integration in the newer client variants.

- **Python MCP server**
  - Implemented in `mcpserver.py` with Starlette and Uvicorn.
  - Provides an authenticated `POST /call` endpoint.
  - Registers the same operations as MCP tools when a compatible MCP package is installed.
  - Includes text operations, audio-input operations, and placeholder responses for future integrations.

## Repository layout

A typical checkout should contain the following files or directories:

```text
mcp-android-bridge/
├── android/                 # Android application module
│   └── app/src/main/
│       ├── java/.../MainActivity.java
│       └── assets/
│           ├── actions.json
│           └── config.json
├── server/
│   └── mcpserver.py
├── actions.json             # Tool definitions used by the Android client
├── config.yaml              # Human-managed server configuration, if used
└── README.md
```

The exact Gradle project files, Android manifest, layouts, and dependency lock files are not included in the supplied source snapshot. Add them in the Android module before attempting a Gradle build.

## Available tools

The supplied `actions.json` defines these tool identifiers:

| Tool | Input | Result handling |
| --- | --- | --- |
| `tool_ask_multiroom` | Audio or text | No result body expected by the action definition |
| `tool_ask_mcp` | Audio or text | Toast/notification-style result |
| `tool_stt` | Audio | Copy result to clipboard |
| `tool_dicte_translate` | Audio | Copy result to clipboard; target language defaults to `en` |
| `tool_correct` | Text | Copy result to clipboard |
| `tool_translate` | Text | Copy result to clipboard; target language defaults to `en` |
| `tool_check_info` | Text | Toast/notification-style result |
| `tool_check_page` | Text | Toast/notification-style result |

The current Python implementation returns template responses for several operations. Connect these functions to the desired speech-to-text, translation, correction, information, or multi-room integrations as needed.

## Server setup

### Requirements

- Python 3.9 or newer
- `starlette`
- `uvicorn`
- An MCP implementation compatible with the import used by `mcpserver.py`, such as `fastmcp` or the fallback package used by the project
- TLS certificate and private key files when running HTTPS

Install the Python dependencies in a virtual environment using the dependency declaration maintained by your checkout. If no dependency file exists yet, install the packages required by the imports, for example:

```bash
python -m venv .venv
. .venv/bin/activate
python -m pip install starlette uvicorn fastmcp
```

### Configuration

The server source currently uses these defaults:

- Bind address: `0.0.0.0`
- Port: `8443`
- Certificate: `cert.pem`
- Private key: `key.pem`
- Request header: `X-Auth-Token`
- Development token in the source: `token`

Create or provide a certificate and key in the server working directory, then start the service:

```bash
python mcpserver.py
```

The server expects JSON requests at `/call` with this shape:

```json
{
  "tool": "tool_translate",
  "args": {
    "text": "Hello",
    "target_lang": "en"
  }
}
```

Include the configured token in the request header:

```bash
curl --insecure https://127.0.0.1:8443/call \
  -H 'Content-Type: application/json' \
  -H 'X-Auth-Token: token' \
  -d '{"tool":"tool_translate","args":{"text":"Hello","target_lang":"en"}}'
```

`--insecure` is appropriate only for a local test certificate. Use a certificate trusted by the Android device and remove that option for normal operation.
