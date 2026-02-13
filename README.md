# Yaac - Yet Another Anypoint CLI

![GitHub License](https://img.shields.io/github/license/myst3m/yaac)
![GitHub Release](https://img.shields.io/github/release/myst3m/yaac)
![GitHub Workflow Status](https://img.shields.io/github/workflow/status/myst3m/yaac/CI)

A fast, UNIX-friendly CLI for MuleSoft Anypoint Platform. Built with Clojure and GraalVM native image for ~15ms startup.

## Features

- 5x faster than Anypoint CLI v4 (GraalVM native + caching + parallel API calls)
- HTTP/2 support for faster API communication
- kubectl-style output friendly with UNIX tools (`grep`, `awk`, `cut`)
- Selectable output format (short / JSON / EDN)
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

| Command | Aliases | Highlight | Doc |
|---------|---------|-----------|-----|
| [`login`](doc/login.md) | - | Login with Connected Apps | [doc/login.md](doc/login.md) |
| [`get`](doc/get.md) | `ls`, `list` | List orgs, envs, apps, APIs, assets, targets, metrics... | [doc/get.md](doc/get.md) |
| [`deploy`](doc/deploy.md) | `dep` | Deploy to RTF/CH2.0/Hybrid with smart defaults | [doc/deploy.md](doc/deploy.md) |
| [`upload`](doc/upload.md) | `up` | Upload JAR/RAML/OAS to Exchange | [doc/upload.md](doc/upload.md) |
| [`create`](doc/create.md) | `new` | Create orgs, envs, APIs, Connected Apps, policies... | [doc/create.md](doc/create.md) |
| [`delete`](doc/delete.md) | `rm`, `del` | Delete resources with `--dry-run` / `--force` | [doc/delete.md](doc/delete.md) |
| [`describe`](doc/describe.md) | `desc` | Detailed info for a single resource | [doc/describe.md](doc/describe.md) |
| [`update`](doc/update.md) | `upd` | Update app state, replicas, policies, scopes... | [doc/update.md](doc/update.md) |
| [`download`](doc/download.md) | `dl` | Download API proxy as JAR | [doc/download.md](doc/download.md) |
| [`config`](doc/config.md) | `cfg` | Set default org/env/target, manage credentials | [doc/config.md](doc/config.md) |
| [`build`](doc/build.md) | - | Run Maven goals with embedded Maven | [doc/build.md](doc/build.md) |
| [`logs`](doc/logs.md) | - | View CloudHub 2.0 application logs | [doc/logs.md](doc/logs.md) |
| [`http`](doc/http.md) | - | Send HTTP requests to deployed apps | [doc/http.md](doc/http.md) |
| [`auth`](doc/auth.md) | - | External OAuth2 flow (Azure AD) | [doc/auth.md](doc/auth.md) |

## Global Options

```
-o, --output-format FORMAT    Output format: short, json, edn (default: short)
-H, --no-header               No header (for piping to UNIX tools)
-F, --fields FIELDS           Select/add output fields (-F id,name or -F +org-type)
-V, --http-trace              Show HTTP request/response flow
-X, --http-trace-detail       Show full HTTP details (disables parallel calls)
-Z, --no-cache                Bypass cache
-1, --http1                   Force HTTP/1.1
-d, --debug                   Debug log
-h, --help                    Help
```

## Deploy Shorthand

`yaac deploy app` supports smart argument completion:

```bash
# Shortest: app name only (org, asset, version auto-completed)
yaac deploy app my-app target=hy:leibniz

# Org + app (env from default context)
yaac deploy app T1 my-app target=hy:leibniz

# Full specification
yaac deploy app T1 Production my-app -g T1 -a hello-app -v 0.0.1 target=ch2:cloudhub-ap-northeast-1
```

Target prefixes: `hy:` (Hybrid), `rtf:` (Runtime Fabric), `ch2:` / `ch20:` (CloudHub 2.0)

See [doc/deploy.md](doc/deploy.md) for full details.

## Install

### Prerequisites
- Java 21+ with FFM support
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

### Build Tasks

| Task | Command |
|------|---------|
| Clean | `clj -T:build clean` |
| Uberjar | `clj -T:build uber` |
| Native | `clj -T:build native-image` |

### Bash Completion

```bash
mkdir -p ~/.local/share/bash-completion/completions
cp completions/yaac.bash ~/.local/share/bash-completion/completions/yaac
```

## Architecture

| Platform | Support |
|----------|---------|
| GNU/Linux x86-64 | Native binary |
| All platforms with Java 21+ | Uberjar |

## License

See [LICENSE](LICENSE) file.
