# yaac update

Update resource configurations on Anypoint Platform.

**Aliases:** `upd`

## Usage

```bash
yaac update <resource> [args] [options] [key=value...]
```

## Common Options

| Flag | Long | Description |
|------|------|-------------|
| `-g` | `--group` | Group name |
| `-a` | `--asset` | Asset name |
| `-v` | `--version` | Asset version |

## Subcommands

### Application

```bash
yaac update app [org] [env] <app> [key=value...]
```

| Key | Description | Example |
|-----|-------------|---------|
| `v-cores` | vCores allocation | `v-cores=0.2` |
| `replicas` | Number of replicas | `replicas=2` |
| `runtime-version` | Mule runtime version | `runtime-version=4.10.1:12e` |
| `state` | Start or stop | `state=start`, `state=stop` |

### Exchange Asset

```bash
yaac update asset <asset> [key=value...]
```

Update asset labels. `-g` defaults to current org, `-v` to latest version.

### API Instance

```bash
yaac update api [org] [env] <api> [key=value...]
```

| Key | Description |
|-----|-------------|
| `asset-version` | New asset version |

### Organization Entitlements

```bash
yaac update org [org] [key=value...]
```

| Key | Description |
|-----|-------------|
| `v-cores-production` | Production vCores |
| `v-cores-sandbox` | Sandbox vCores |
| `network-connections` | Network connections |
| `static-ips` | Static IPs |
| `vpns` | VPNs |

Use `yaac get ent` to check current allocations across all child orgs before updating.

```bash
# Check current entitlements
yaac get ent T1

# Update v-cores allocation
yaac update org T1.1 v-cores-production=0.5 v-cores-sandbox=0.2

# Update multiple entitlements at once
yaac update org T1.1 static-ips=2 network-connections=1 vpns=1
```

### CloudHub 2.0 Connection

```bash
yaac update conn [org] <private-space> <connection> [key=value...]
```

| Key | Description | Example |
|-----|-------------|---------|
| `static-routes` | Add/remove routes | `+172.17.0.0/16,+192.168.11.0/24` |

### Connected App Scopes

```bash
yaac update connected-app <name> [options]
```

| Flag | Description |
|------|-------------|
| `--scopes` | Basic scopes (comma-separated) |
| `--org-scopes` | Org-level scopes (requires `--org`) |
| `--env-scopes` | Env-level scopes (requires `--org` and `--env`) |
| `--org` | Target organization |
| `--env` | Target environments (comma-separated) |

### Upstream URI

```bash
yaac update upstream [org] [env] <api-id> --upstream-uri <uri>
```

### API Policy

```bash
yaac update policy [org] [env] <api-id> <policy-asset-id> [options]
```

| Flag | Description |
|------|-------------|
| `--jwks-url` | JWKS URL for JWT validation policy |

### Client Provider

```bash
yaac update cp <name|id> [options]
```

| Flag | Description |
|------|-------------|
| `--issuer` | Token issuer |
| `--authorize-url` | Authorization endpoint |
| `--token-url` | Token endpoint |
| `--introspect-url` | Introspection endpoint |
| `--client-id` | Primary client ID |
| `--client-secret` | Primary client secret |

## Examples

```bash
# Stop/start application
yaac update app T1 Production my-app state=stop
yaac update app T1 Production my-app state=start

# Scale replicas
yaac update app T1 Production my-app replicas=3

# Update org entitlements
yaac update org T1 v-cores-production=0.5 v-cores-sandbox=0.2

# Update connected app scopes
yaac update connected-app myapp \
  --scopes profile \
  --org-scopes read:organization \
  --env-scopes read:applications \
  --org T1 --env "Production,Sandbox"

# Update API upstream
yaac update upstream T1 Sandbox 20671224 --upstream-uri http://172.23.0.9:8081/

# Update JWT policy JWKS URL
yaac update policy T1 Sandbox 20671224 jwt-validation-flex --jwks-url http://172.23.0.15:8081/jwks.json
```

## See Also

- [describe](describe.md) - View current resource state
- [deploy](deploy.md) - Redeploy applications
