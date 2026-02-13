# yaac describe

Get detailed information about a single resource.

**Aliases:** `desc`

## Usage

```bash
yaac describe <resource> [args] [options]
```

## Options

| Flag | Long | Description |
|------|------|-------------|
| `-g` | `--group` | Group name (Business Group) |
| `-a` | `--asset` | Asset name |
| `-v` | `--version` | Asset version |

## Subcommands

| Subcommand | Alias | Usage |
|------------|-------|-------|
| `describe org` | `desc org` | `describe org [org]` |
| `describe env` | `desc env` | `describe env [org] [env]` |
| `describe app` | `desc app` | `describe app [org] [env] <app>` |
| `describe asset` | `desc asset` | `describe asset -g <group> -a <asset>` |
| `describe api` | `desc api` | `describe api [org] [env] <api>` |
| `describe capp` | `desc connected-app` | `describe capp <name\|id>` |
| `describe cp` | `desc client-provider` | `describe cp <name\|id>` |

## Output Details

### describe app

Shows detailed application information:
- Pod status, replicas, vCores
- Public URL, internal URL
- Runtime version, Java version
- Deployment status, last modified

### describe asset

Shows Exchange asset details:
- All versions, labels, type
- Dependencies, files

### describe connected-app

Shows Connected App scope assignments:
- Basic scopes, org-level scopes, env-level scopes

## Examples

```bash
yaac describe org T1
yaac describe app T1 Production my-app
yaac describe asset -g T1 -a hello-app
yaac describe connected-app myapp
```

## See Also

- [get](get.md) - List resources
- [update](update.md) - Modify resource settings
