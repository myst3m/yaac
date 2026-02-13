# yaac config

Configure CLI settings, credentials, and default context.

**Aliases:** `configure`, `cfg`

## Usage

```bash
yaac config <subcommand> [args]
```

## Subcommands

### context

Set or show the default organization, environment, and deploy target.

```bash
# Show current context
yaac config context

# Set default org and env
yaac config context T1 Production

# Set default org, env, and deploy target
yaac config context T1 Production ch2:cloudhub-ap-northeast-1
```

Output:

```
ORGANIZATION  ENVIRONMENT  DEPLOY-TARGET
T1            Production   ch2:cloudhub-ap-northeast-1
```

The context is saved in `~/.yaac/config` and used when `[org]` / `[env]` arguments are omitted.

### credential

Interactively configure Connected App credentials.

```bash
yaac config credential
```

Prompts for:
1. App name
2. Client ID
3. Client secret
4. Grant type (`client_credentials`, `authorization_code`, `password`)

Credentials are saved in `~/.yaac/credentials`. Set file permission to `600`.

### clear-cache

Clear cached organization and environment metadata.

```bash
yaac config clear-cache
```

Cache location: `~/.yaac/cache`

## Examples

```bash
# Set up credentials first
yaac config credential

# Set default context
yaac config context T1 Production

# Now commands use defaults
yaac get app          # equivalent to: yaac get app T1 Production
yaac deploy app my-app target=hy:leibniz  # org=T1, env=Production
```

## See Also

- [login](login.md) - Login with configured credentials
