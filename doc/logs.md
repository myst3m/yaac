# yaac logs

View application container logs (CloudHub 2.0).

## Usage

```bash
yaac logs app [org] [env] <app> [options]
```

## Options

| Flag | Long | Description | Default |
|------|------|-------------|---------|
| `-f` | `--follow` | Follow log output | `false` |

## Keys

| Key | Description |
|-----|-------------|
| `length` | Number of log entries to retrieve |
| `descending` | Order: `true` (newest first) or `false` |

## Output

```
TIMESTAMP                    LOG-LEVEL  MESSAGE
2024-01-15T10:30:00.000Z     INFO       Application started
2024-01-15T10:30:01.000Z     INFO       Listening on port 8081
```

## Examples

```bash
# Get recent logs
yaac logs app T1 Production my-app

# Follow logs in real-time
yaac logs app T1 Production my-app -f

# Get last 100 entries
yaac logs app my-app length=100
```

## See Also

- [get](get.md) - `get app` to list applications
- [describe](describe.md) - `describe app` for application details
