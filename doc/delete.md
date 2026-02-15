# yaac delete

Delete resources from Anypoint Platform.

**Aliases:** `rm`, `del`, `remove`

## Usage

```bash
yaac delete <resource> [args] [options]
```

## Common Options

| Flag | Long | Description |
|------|------|-------------|
| `-g` | `--group` | Group name (Business Group) |
| `-a` | `--asset` | Asset name |
| `-v` | `--version` | Asset version |
| `-A` | `--all` | Delete all matching resources |
| | `--all-orgs` | Delete from all organizations |
| | `--dry-run` | Preview without deleting |
| | `--force` | Skip confirmation / force delete |
| | `--hard-delete` | Hard delete (assets) |

## Subcommands

### Organization

```bash
yaac delete org <org|id> [--force]
```

`--force` deletes the organization along with all its resources (including RTF clusters and Private Spaces).

### Application

```bash
yaac delete app [org] [env] <app|id>
yaac delete app [org] [env] -A              # Delete all apps
yaac delete app [org] [env] -q label1,label2  # Delete by label
```

### API Instance

```bash
yaac delete api [org] [env] <api|id>
yaac delete api [org] [env] -A [--dry-run]
```

### Exchange Asset

```bash
yaac delete asset -g <group> -a <asset> -v <version>
yaac delete asset -g <group> -a <asset> -A  # All versions
yaac delete asset -g <group> -a <asset> -v <version> --hard-delete
```

### API Contract

```bash
yaac delete contract [org] [env] <api-id> <contract-id>
```

### API Policy

```bash
yaac delete policy [org] [env] <api-id> <policy-asset-id>
```

### Alert

```bash
yaac delete alert [org] [env] <alert-id> [--type api|app|server]
```

### Connected App

```bash
yaac delete connected-app <name|client-id>
```

### Client Provider

```bash
yaac delete cp <name|id>
```

### IdP User Profile

```bash
yaac delete idp-user <email> provider=<name>
```

### Runtime Fabric

```bash
yaac delete rtf [org] <name|id> [--force]
```

### Private Space

```bash
yaac delete ps [org] <name|id> [--force]
```

### Managed Flex Gateway

```bash
yaac delete mg [org] [env] <name|id>
```

## clear

`yaac clear org` is a separate top-level command that deletes most resources in an organization without deleting the org itself. RTF clusters and Private Spaces are NOT deleted.

```bash
yaac clear org <org|id> [--dry-run]
```

Deletes in order: apps, APIs, gateways, secret groups, assets.

```bash
# Preview what would be deleted
yaac clear org T1 --dry-run

# Actually clear
yaac clear org T1
```

## Examples

```bash
# Delete single app
yaac delete app T1 Production my-app

# Delete all apps in environment (dry run first)
yaac delete app T1 Production -A --dry-run
yaac delete app T1 Production -A

# Delete asset version
yaac delete asset -g T1 -a my-app -v 1.0.0

# Force delete org with all resources
yaac delete org DevTeam --force
```

## See Also

- [get](get.md) - List resources before deleting
- [create](create.md) - Create resources
