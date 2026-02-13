# yaac get

List and retrieve resources from Anypoint Platform.

**Aliases:** `ls`, `list`, `get`

## Usage

```bash
yaac get <resource> [org] [env] [options] [key=value...]
```

## Options

| Flag | Long | Description |
|------|------|-------------|
| `-g` | `--group` | Group name (Business Group) for asset queries |
| `-a` | `--asset` | Asset name |
| `-v` | `--version` | Asset version |
| `-q` | `--search-term` | Query string (comma-separated) |
| `-A` | `--all` | Query all organizations / all applications |
| `-F` | `--fields` | Select output fields (comma-separated, kebab-case) |

## Subcommands

### Organizations & Environments

| Subcommand | Alias | Description |
|------------|-------|-------------|
| `get org` | `organization` | List business groups |
| `get env` | `environment` | List environments |
| `get ent` | `entitlement` | Organization entitlements (v-cores, IPs, VPNs) |
| `get user` | - | List users in organization |
| `get team` | - | List teams |

```bash
yaac get org
yaac get env T1
yaac get ent T1
```

### Applications & Deployments

| Subcommand | Alias | Description |
|------------|-------|-------------|
| `get app` | `application` | List deployed applications |
| `get rtt` | `runtime-target` | List all runtime targets |
| `get rtf` | `runtime-fabric` | List Runtime Fabric clusters |
| `get serv` | `server` | List on-premise servers |
| `get ps` | `private-space` | List CloudHub 2.0 Private Spaces |

```bash
yaac get app T1 Production
yaac get rtt T1 Production
yaac get serv T1 Production
```

### API Management

| Subcommand | Alias | Description |
|------------|-------|-------------|
| `get api` | `api-instance` | List API Manager instances |
| `get proxy` | - | List API proxies |
| `get gw` | `gateway` | List Flex Gateways (standalone + managed) |
| `get cont` | `contract` | List API contracts |
| `get policy` | `pol` | List API policies |
| `get np` | `node-port` | Available node ports in Private Spaces |

```bash
yaac get api T1 Production
yaac get policy T1 Sandbox 20671224
```

### Exchange Assets

| Subcommand | Description |
|------------|-------------|
| `get asset` | List Exchange assets |

```bash
yaac get asset T1
yaac get asset -q account types=rest-api
yaac get asset -g T1 -a my-app -v 1.0.0
```

**Query Keys:**

| Key | Description |
|-----|-------------|
| `search-term` | Search pattern |
| `types` | Filter: `app`, `rest-api`, `soap-api`, `example`, `template` |
| `labels` | Comma-separated labels |
| `public` | `true` / `false` |
| `offset` | Result offset |
| `limit` | Result limit (default: 30, max: 250) |

### Security & Identity

| Subcommand | Alias | Description |
|------------|-------|-------------|
| `get capp` | `connected-app` | List Connected Apps |
| `get scopes` | `scope` | List available OAuth scopes |
| `get idp` | - | List external Identity Providers |
| `get cp` | `client-provider` | List OpenID Connect client providers |
| `get sg` | `secret-group` | List secret groups |
| `get conn` | `connection` | List CloudHub 2.0 connections (VPN, transit gateway) |

### Monitoring

| Subcommand | Description |
|------------|-------------|
| `get metrics` | Get metrics from Anypoint Monitoring |
| `get alert` | List alerts for APIs/apps/servers |

**Metrics Options:**

| Flag | Description |
|------|-------------|
| `--type` | Predefined type: `app-inbound`, `app-inbound-response-time`, `app-outbound`, `api-path`, `api-summary` |
| `--describe` | Describe metric structure |
| `--query` | Raw AMQL query |
| `--start` | Start timestamp (Unix ms or ISO8601) |
| `--end` | End timestamp |
| `--from` | Relative start (e.g., `1h`, `30m`, `1d`) |
| `--duration` | Duration from start |
| `--aggregation` | `count`, `sum`, `avg`, `max`, `min` |
| `--app-id` | Filter by application ID |
| `--api-id` | Filter by API ID |
| `--group-by` | Group by dimensions (comma-separated) |

## Output Customization

```bash
# Select specific fields
yaac get org -F id,name

# Add extra field with '+'
yaac get org -F +org-type

# JSON output
yaac get org -o json

# No header (for piping)
yaac get app T1 Production -H
```

## See Also

- [describe](describe.md) - Get detailed information about a single resource
- [config](config.md) - Set default org/env context
