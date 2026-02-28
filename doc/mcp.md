# yaac mcp

MCP (Model Context Protocol) client for interacting with MCP-enabled applications.

## Usage

```bash
yaac mcp <command> [options]
```

## Options

| Flag | Long | Description |
|------|------|-------------|
| `-L` | `--list` | List names only (for piping) |

## Commands

### init

Initialize a session with an MCP server. Resolves the public URL from Anypoint Platform or uses a direct URL.

```bash
yaac mcp init [org] [env] <app>
yaac mcp init <url>
```

### tool

List available tools on the connected MCP server.

```bash
yaac mcp tool
yaac mcp tool -L    # Names only
```

### call

Call a tool with HTTPie-style arguments.

```bash
yaac mcp call <tool> [key=val ...]
```

**Parameter syntax:**

| Syntax | Description | Example |
|--------|-------------|---------|
| `key=value` | String value | `status=ORDERED` |
| `key:=value` | Raw JSON (number, boolean, object, array) | `count:=5` |
| `a.b=value` | Nested object | `filter.status=active` |

### session / clear

Show or clear the current session.

```bash
yaac mcp session
yaac mcp clear
```

## Examples

```bash
# Connect via app name
yaac mcp init T1 Sandbox my-mcp-app

# Connect via direct URL
yaac mcp init https://my-app.cloudhub.io/mcp

# List tools
yaac mcp tool

# List tool names only (for piping)
yaac mcp tool -L

# Call a tool
yaac mcp call list-orders status=ORDERED

# Call with JSON parameter
yaac mcp call create-item name=Widget count:=10
```

## See Also

- [a2a](a2a.md) - A2A (Agent-to-Agent) protocol client
- [deploy](deploy.md) - Deploy applications
