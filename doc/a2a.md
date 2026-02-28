# yaac a2a

A2A (Agent-to-Agent) protocol client for communicating with A2A-compatible agents.

## Usage

```bash
yaac a2a <command> [options]
```

## Options

| Flag | Long | Description |
|------|------|-------------|
| `-b` | `--bearer-token TOKEN` | Bearer token for Authorization header |

## Commands

### init

Connect to an A2A agent. Resolves the public URL from Anypoint Platform or uses a direct URL.

```bash
yaac a2a init [org] [env] <app> [/path]
yaac a2a init <url>
```

### console

Interactive console for conversing with an A2A agent.

```bash
yaac a2a console [org] [env] <app> [/path]
```

### send

Send a message to the connected agent.

```bash
yaac a2a send <message...>
```

### task

Get the status of a task.

```bash
yaac a2a task <task-id>
```

### cancel

Cancel a running task.

```bash
yaac a2a cancel <task-id>
```

### card

Show the agent card (capabilities, skills, etc.).

```bash
yaac a2a card
```

### session / clear

Show or clear the current session.

```bash
yaac a2a session
yaac a2a clear
```

## Examples

```bash
# Connect via app name (resolves public URL)
yaac a2a init my-a2a-app

# Connect with agent path
yaac a2a init my-a2a-app /order-fulfillment

# Connect with org/env
yaac a2a init T1 Sandbox my-a2a-app /order-fulfillment

# Connect via direct URL
yaac a2a init https://my-agent.cloudhub.io

# With bearer token
yaac a2a init https://my-agent.example.com -b eyJhbG...

# Interactive console
yaac a2a console my-a2a-app

# Send a message
yaac a2a send Hello, what can you do?

# Check task status
yaac a2a task abc-123

# Show agent card
yaac a2a card
```

## See Also

- [mcp](mcp.md) - MCP (Model Context Protocol) client
- [deploy](deploy.md) - Deploy applications
