# yaac create

Create new resources on Anypoint Platform.

**Aliases:** `new`

## Usage

```bash
yaac create <resource> [args] [options] [key=value...]
```

## Common Options

| Flag | Long | Description |
|------|------|-------------|
| `-g` | `--group` | Group name (Business Group) |
| `-a` | `--asset` | Asset name |
| `-v` | `--version` | Asset version |
| `-p` | `--parent` | Parent organization |
| `-e` | `--email` | Email address (for invitations) |
| `-n` | `--name` | Resource name |

## Subcommands

### Organization

```bash
yaac create org <name> [-p parent] [v-cores=prod:sandbox:design]
```

| Key | Default | Description |
|-----|---------|-------------|
| `v-cores` | `0.1:0.1:0` | vCores allocation (production:sandbox:design) |

### Environment

```bash
yaac create env <org> <name> [type=sandbox]
```

| Key | Default | Description |
|-----|---------|-------------|
| `type` | `sandbox` | `production`, `sandbox`, or `design` |

### API Instance

```bash
yaac create api [org] [env] <asset-name> [options] [key=value...]
```

| Flag | Description |
|------|-------------|
| `-g` | Group (org) for the asset |
| `-a` | Asset name |
| `-v` | Asset version |
| `--uri` | Upstream URI |
| `--deployment-type` | `mule4` or `flexGateway` |

| Key | Description |
|-----|-------------|
| `technology` | `mule4` or `flexGateway` (default: `mule4`) |
| `uri` | Upstream URL |
| `target` | `fg:<gateway-name>` for Flex Gateway |

### API Policy

```bash
yaac create policy [org] [env] <api-id> <policy-asset-id> [key=value...]
```

### Alert

```bash
yaac create alert [org] [env] [options]
```

| Flag | Description | Default |
|------|-------------|---------|
| `--type` | `api`, `app`, or `server` | - |
| `--api` | API name or ID | - |
| `--app` | Application ID | - |
| `--severity` | `info`, `warning`, `critical` | `critical` |
| `--threshold` | Threshold value | - |
| `--interval` | Check interval (minutes) | `5` |
| `--metric-type` | `message-count`, `cpu`, `memory`, `response-time`, `error-count` | - |
| `--operator` | `above` or `below` | `above` |
| `--aggregation` | `sum`, `avg`, `max`, `min` | `sum` |
| `--subject` | Email subject template | - |
| `--message` | Email message template | - |

### Connected App

```bash
yaac create connected-app --name <name> --grant-types <type> --scopes <scopes> --redirect-uris <uris> [options]
```

| Flag | Description |
|------|-------------|
| `--name` | App name |
| `--grant-types` | `client_credentials` or `authorization_code` |
| `--scopes` | Comma-separated scopes |
| `--redirect-uris` | Comma-separated redirect URIs |
| `--audience` | `internal` or `everyone` |
| `--public` | Public client flag |
| `--org-scopes` | Org-level scopes |
| `--env-scopes` | Env-level scopes |
| `--org` | Org for scopes |
| `--env` | Envs for env-scopes (comma-separated) |

### Client Provider

```bash
yaac create cp --name <name> --issuer <url> --authorize-url <url> --token-url <url> [options]
```

| Flag | Description |
|------|-------------|
| `--issuer` | Token issuer identifier |
| `--authorize-url` | Authorization endpoint URL |
| `--token-url` | Token endpoint URL |
| `--introspect-url` | Token introspection URL |
| `--client-id` | Primary client ID |
| `--client-secret` | Primary client secret |

### Private Space

```bash
yaac create ps [org] <name> --region <region> --cidr-block <cidr> [options]
```

| Flag | Description |
|------|-------------|
| `--region` | AWS region (e.g., `ap-northeast-1`) |
| `--cidr-block` | CIDR block (e.g., `10.0.0.0/24`) |
| `--reserved-cidrs` | Reserved CIDRs (comma-separated) |

### Managed Flex Gateway

```bash
yaac create mg [org] [env] --name <name> --target <target> [options]
```

| Flag | Description | Default |
|------|-------------|---------|
| `--target` | `ps:<name>` or `rtf:<name>` | - |
| `--channel` | `edge` or `lts` | `lts` |
| `--size` | `small` or `large` | `small` |

### Invitation

```bash
yaac create invite -e <email> [-t team:role,...] [--team-id <name>] [--membership-type member|maintainer]
```

## Examples

```bash
# Create business group
yaac create org DevTeam -p MuleSoft v-cores=0.2:0.1:0

# Create environment
yaac create env T1 Staging type=sandbox

# Create API instance
yaac create api T1 Production -g T1 -a my-api -v 1.0.0 --uri http://backend:8081

# Create connected app
yaac create connected-app --name myapp --grant-types client_credentials \
  --scopes profile --redirect-uris http://localhost \
  --org-scopes read:organization,edit:organization

# Invite user
yaac create invite -e user@example.com --team-id DevTeam --membership-type member
```

## See Also

- [delete](delete.md) - Delete created resources
- [describe](describe.md) - View resource details
- [update](update.md) - Modify resource settings
