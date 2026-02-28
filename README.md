# Yaac - Yet Another Anypoint CLI

![GitHub License](https://img.shields.io/github/license/myst3m/yaac)
![GitHub Release](https://img.shields.io/github/release/myst3m/yaac)

A fast, UNIX-friendly CLI for MuleSoft Anypoint Platform. Built with Clojure and GraalVM native image for ~15ms startup.

**[日本語](README.ja.md)**

## Features

- 5x faster than Anypoint CLI v4 (GraalVM native + caching + parallel API calls)
- HTTP/2 support for faster API communication
- kubectl-style output friendly with UNIX tools (`grep`, `awk`, `cut`)
- Selectable output format (short / wide / JSON / EDN / YAML)
- Short UUID display with prefix matching (`b68cabda` instead of full UUID)
- Embedded Maven for `yaac build` (no Maven installation needed)
- Manifest-based multi-app deployment

## Quick Start

```bash
# 1. Configure credentials
yaac config credential

# 2. Login
yaac login cc-app

# 3. Set default context
yaac config context T1 Production

# 4. Use!
yaac get app
yaac deploy app my-app target=hy:leibniz
```

## Command Reference

| Command | Aliases | Description | Doc |
|---------|---------|-------------|-----|
| `login` | - | Login with Connected Apps | [doc/login.md](doc/login.md) |
| `get` | `ls`, `list` | List resources (orgs, envs, apps, APIs, assets...) | [doc/get.md](doc/get.md) |
| `describe` | `desc` | Detailed info for a single resource | [doc/describe.md](doc/describe.md) |
| `create` | `new` | Create orgs, envs, APIs, Connected Apps, policies... | [doc/create.md](doc/create.md) |
| `update` | `upd` | Update app state, replicas, policies, scopes... | [doc/update.md](doc/update.md) |
| `delete` | `rm`, `del` | Delete resources with `--dry-run` / `--force` | [doc/delete.md](doc/delete.md) |
| `deploy` | `dep` | Deploy to RTF / CH2.0 / Hybrid with smart defaults | [doc/deploy.md](doc/deploy.md) |
| `upload` | `up` | Upload JAR / RAML / OAS to Exchange | [doc/upload.md](doc/upload.md) |
| `download` | `dl` | Download API proxy as JAR | [doc/download.md](doc/download.md) |
| `config` | `cfg` | Set default org/env/target, manage credentials | [doc/config.md](doc/config.md) |
| `build` | - | Run Maven goals with embedded Maven | [doc/build.md](doc/build.md) |
| `logs` | - | View CloudHub 2.0 application logs | [doc/logs.md](doc/logs.md) |
| `http` | - | Send HTTP requests to deployed apps | [doc/http.md](doc/http.md) |
| `auth` | - | OAuth2 flows (code, client credentials, Azure) | [doc/auth.md](doc/auth.md) |
| `a2a` | - | A2A (Agent-to-Agent) protocol client | [doc/a2a.md](doc/a2a.md) |
| `mcp` | - | MCP (Model Context Protocol) client | [doc/mcp.md](doc/mcp.md) |
| `clear` | - | Clear all resources in an org (except RTF/PS) | [doc/delete.md](doc/delete.md#clear) |

## Global Options

```
-o, --output-format FORMAT    Output format: short, wide, json, edn, yaml
-H, --no-header               No header (for piping to UNIX tools)
-F, --fields FIELDS           Select/add output fields (-F id,name or -F +org-type)
-V, --http-trace              Show HTTP request/response flow
-X, --http-trace-detail       Show full HTTP details (disables parallel calls)
-Z, --no-cache                Bypass cache
-1, --http1                   Force HTTP/1.1
-d, --debug                   Debug log
-h, --help                    Help
```

## Common Patterns

```bash
# JSON output for scripting
yaac get app -o json | jq '.[] | .name'

# No header for piping
yaac get app -H | awk '{print $3}'

# Wide output (extra columns)
yaac get app -o wide

# Short UUID - use prefix to identify resources
yaac get org                     # Shows: b68cabda (short ID)
yaac desc org b68cabda           # Prefix match works in arguments

# Field selection
yaac get org -F id,name
yaac get org -F +org-type        # Add extra field

# Deploy shorthand (org/asset/version auto-completed)
yaac deploy app my-app target=hy:leibniz
```

## Install

### Prerequisites
- Java 17+
- Clojure CLI tools: [https://clojure.org/guides/install_clojure]()

### Native Image (Recommended)

Build a native executable for ~15ms startup.

**Requirements:** GraalVM 25+ with native-image, `GRAALVM_HOME` env var (default: `/opt/graal`)

```bash
git clone https://github.com/myst3m/yaac && cd yaac
clj -T:build native-image
cp target/yaac-0.9.1 ~/.local/bin/yaac
```

### Uberjar

```bash
clj -T:build uber
java --enable-native-access=ALL-UNNAMED -jar target/yaac-0.9.1.jar
```

### Bash Completion

```bash
mkdir -p ~/.local/share/bash-completion/completions
cp completions/yaac.bash ~/.local/share/bash-completion/completions/yaac
```

## License

See [LICENSE](LICENSE) file.
