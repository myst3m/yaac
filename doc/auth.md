# yaac auth

External OAuth2 authentication flows.

## Usage

```bash
yaac auth <provider> [options] [key=value...]
```

## Options

| Flag | Long | Description |
|------|------|-------------|
| `-p` | `--port` | Listen port for OAuth2 callback |

## Subcommands

### azure

Azure AD OAuth2 authorization code flow.

```bash
yaac auth azure tenant=<id> client-id=<id> client-secret=<secret> [key=value...]
```

| Key | Description | Default |
|-----|-------------|---------|
| `tenant` | Azure tenant ID | - |
| `client-id` | Azure client ID | - |
| `client-secret` | Azure client secret | - |
| `redirect-uri` | Callback URL | `http://localhost:9180/oauth2/callback` |
| `scope` | Requested scopes | `https://graph.microsoft.com/.default` |

## Examples

```bash
yaac auth azure tenant=abc123 client-id=myapp client-secret=secret123
```

## See Also

- [login](login.md) - Login with Anypoint Connected Apps
