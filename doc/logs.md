# yaac logs

View application container logs (CloudHub 2.0).

Supports two modes:
- **Standard mode**: CloudHub 2.0 platform logging API
- **JMX mode** (`-j`): Direct log retrieval via [mule-jmx-module](https://github.com/tmiya4ta/mule-jmx-module) HTTP endpoints

## Usage

```bash
yaac logs app [org] [env] <app> [options]
```

## Options

| Flag | Long | Description | Default |
|------|------|-------------|---------|
| `-f` | `--follow` | Follow log output (standard mode) | `false` |
| `-j` | `--jmx` | Use JMX module endpoint | `false` |
| `-i` | `--internal` | Use internal URL for JMX | `false` |
| `-l` | `--level LEVEL` | Log level filter: ERROR, WARN, INFO, DEBUG (JMX) | all |
| `-n` | `--lines LINES` | Number of lines (JMX) | `100` |
| `-p` | `--pattern PATTERN` | Search pattern (JMX) | - |
| `-s` | `--search` | Search log file via `/logs/search` (JMX) | `false` |
| `-t` | `--tail` | Tail log file via `/logs/tail` (JMX) | `false` |

## Keys (standard mode)

| Key | Description |
|-----|-------------|
| `length` | Number of log entries to retrieve |
| `descending` | Order: `true` (newest first) or `false` |

## Output

```
TIMESTAMP                LOG-LEVEL  MESSAGE
2026-04-15 12:33:36.637  INFO       Message source 'ToolListener' on flow 'mcp-srm-get-supplier' successfully started
2026-04-15 12:33:36.736  INFO       Started ServerConnector@7254a68a{HTTP/1.1, (http/1.1)}{127.0.0.1:7777}
```

## Examples

### Standard CH2 logs

```bash
# Get recent logs
yaac logs app T1 Production my-app

# Follow logs in real-time
yaac logs app T1 Production my-app -f
```

### JMX mode (requires mule-jmx-module in the app)

```bash
# Recent 100 lines
yaac logs app T1 Sandbox my-app -j

# Last 50 lines
yaac logs app T1 Sandbox my-app -j -n 50

# ERROR logs only
yaac logs app T1 Sandbox my-app -j -l ERROR

# Filter by pattern
yaac logs app T1 Sandbox my-app -j -p "mcp-srm"

# Tail log file (last 200 lines)
yaac logs app T1 Sandbox my-app -j -t -n 200

# Search log file for a pattern
yaac logs app T1 Sandbox my-app -j -s -p "NullPointerException"

# Use internal URL (from within the same Private Space)
yaac logs app T1 Sandbox my-app -j -i
```

## JMX Module Setup

To use `-j` mode, the Mule app must include [mule-jmx-module](https://github.com/tmiya4ta/mule-jmx-module) and expose `/logs`, `/logs/tail`, `/logs/search` HTTP endpoints. The app must also have a public endpoint (or use `-i` for internal access).

## See Also

- [get](get.md) - `get app` to list applications
- [describe](describe.md) - `describe app` for application details
- [http](http.md) - `http` for raw HTTP requests to deployed apps
