# yaac deploy

Deploy applications, API proxies, and manifests to Anypoint Platform.

**Aliases:** `dep`

## Usage

```bash
yaac deploy app [org] [env] [app] target=<prefix:name> [key=value...]
yaac deploy proxy [org] [env] <api> target=<prefix:name>
yaac deploy manifest <file.yaml> [--dry-run] [--only app1,app2]
```

## Options

| Flag | Long | Description |
|------|------|-------------|
| `-g` | `--group` | Group name (defaults to org) |
| `-a` | `--asset` | Asset name (defaults to app name) |
| `-v` | `--version` | Asset version (defaults to latest on Exchange) |
| `-q` | `--search-term` | Query string |

## Target Prefixes

| Prefix | Type | Example |
|--------|------|---------|
| `hy:` | Hybrid / On-premise server | `target=hy:leibniz` |
| `rtf:` | Runtime Fabric cluster | `target=rtf:k1` |
| `ch2:` | CloudHub 2.0 region | `target=ch2:cloudhub-ap-northeast-1` |
| `ch20:` | CloudHub 2.0 (alias) | `target=ch20:rootps` |

## Argument Patterns

| Args | Pattern | Description |
|------|---------|-------------|
| 0 | `deploy app target=...` | Use default context for all |
| 1 | `deploy app <app> target=...` | App name only, org/env from context |
| 2 | `deploy app <org> <app> target=...` | Org + app, env from context |
| 3 | `deploy app <org> <env> <app> target=...` | Full specification |

## Default Completion

When `-g`, `-a`, `-v` are omitted:

- **`-g`**: Defaults to the resolved `org`
- **`-a`**: Defaults to the `app` name
- **`-v`**: Auto-selects latest version from Exchange

This means the shortest deploy command is:

```bash
yaac deploy app my-app target=hy:leibniz
```

## deploy app

### RTF-Specific Keys

| Key | Description | Example |
|-----|-------------|---------|
| `cpu` | Request,limit | `cpu=450m,550m` |
| `mem` | Request,limit | `mem=1200Mi,1200Mi` |
| `replicas` | Number of replicas | `replicas=2` |
| `runtime-version` | Mule runtime version | `runtime-version=4.10.1:12e` |
| `java-version` | Java version | `java-version=17` |

### CloudHub 2.0 Keys

| Key | Description | Example |
|-----|-------------|---------|
| `instance-type` | Instance size | `instance-type=small` (`nano`, `small`, `small.mem`) |
| `v-cores` | vCores (classic pricing) | `v-cores=0.1` |
| `replicas` | Number of replicas | `replicas=2` |
| `runtime-version` | Mule runtime version | `runtime-version=4.10.1:12e` |
| `node-port` | Node port | `node-port=30500` |
| `target-port` | Target port | `target-port=8081` |

### Application Properties

Prefix with `+` to set application properties:

```bash
yaac deploy app my-app target=rtf:k1 +http.port=8081 +db.host=oracle.local
```

## deploy proxy

Deploy an API proxy to Flex Gateway.

```bash
yaac deploy proxy T1 Production my-api target=fg:my-gateway
```

## deploy manifest

Deploy multiple applications from a YAML manifest file.

```bash
# Deploy all apps
yaac deploy manifest deploy.yaml

# Dry run
yaac deploy manifest deploy.yaml --dry-run

# Deploy specific apps only
yaac deploy manifest deploy.yaml --only customer-sapi,order-sapi

# Generate manifest from directory scan
yaac deploy manifest --scan ./system-apis
yaac deploy manifest --scan . --config-properties src/main/resources/env/local.yaml
```

### Manifest YAML Schema

```yaml
organization: T1
environment: Production

targets:
  cloudhub-ap-northeast-1:
    type: ch2
    instance-type: small

  my-rtf-cluster:
    type: rtf
    cpu: [500m, 1000m]
    mem: [1200Mi, 1200Mi]

  leibniz:
    type: hybrid

apps:
  - name: customer-sapi
    asset: T1:customer-sapi:1.0.0
    target: my-rtf-cluster
    properties:
      http.port: 8081
      db.host: oracle.internal

  - name: order-papi
    asset: T1:order-papi:1.0.0
    target: cloudhub-ap-northeast-1
    connects-to:
      - customer-sapi
```

### Target Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | string | Yes | `rtf`, `ch2`, or `hybrid` |
| `cpu` | list | No | RTF: `[reserved, limit]` |
| `mem` | list | No | RTF: `[reserved, limit]` |
| `instance-type` | string | No | CH2: `nano`, `small`, `small.mem` |
| `v-cores` | number | No | CH2: vCores (legacy pricing) |

### App Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Deployed application name |
| `asset` | string | Yes | `group:artifact:version` |
| `target` | string | Yes | Target name (must exist in `targets`) |
| `replicas` | int | No | Number of replicas (default: 1) |
| `properties` | map | No | Application properties |
| `connects-to` | list | No | App names to auto-generate connection URLs |

### connects-to URL Generation

| Target Type | Generated URL Pattern |
|-------------|----------------------|
| RTF | `http://<app>.svc.cluster.local:<port>` |
| CloudHub 2.0 | `https://<app>.cloudhub.io` |
| Hybrid | `http://<app>:<port>` |

## Examples

```bash
# Shortest: app name only (org/asset/version auto-completed)
yaac deploy app hello-world target=hy:leibniz

# Org + app (env from default context)
yaac deploy app T1 hello-world target=hy:leibniz

# Full specification
yaac deploy app T1 Production my-app -g T1 -a hello-app -v 0.0.1 target=ch2:cloudhub-ap-northeast-1

# RTF with resource limits
yaac deploy app T1 Production my-app target=rtf:k1 cpu=500m,1000m mem=1200Mi,1200Mi replicas=2

# With application properties
yaac deploy app my-app target=hy:leibniz +http.port=8081 +db.host=oracle.local
```

## See Also

- [upload](upload.md) - Upload assets to Exchange before deploying
- [get](get.md) - `get rtt` to find available targets
- [config](config.md) - Set default org/env/target
