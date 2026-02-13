# yaac login

Login and save access token to local storage using Connected Apps credentials.

## Usage

```bash
yaac login <credential-context> [username] [password]
```

## Arguments

| Argument | Required | Description |
|----------|----------|-------------|
| `credential-context` | Yes | Name of the credential defined in `~/.yaac/credentials` |
| `username` | No | Username for resource owner password grant |
| `password` | No | Password for resource owner password grant |

## Supported Grant Types

| Grant Type | Description |
|------------|-------------|
| `client_credentials` | Service-to-service authentication (recommended) |
| `authorization_code` | Interactive OAuth2 flow with browser redirect |
| `password` | Resource owner password grant (deprecated, MFA mandatory) |

## Output

```
TOKEN-TYPE  ACCESS-TOKEN                          EXPIRES-IN
bearer      0cfd1d0c-dc30-4a3a-b9ef-87bb30fe53da  3600
```

## Credential File

Credentials are stored in `~/.yaac/credentials`:

```json
{
  "cc-app": {
    "client_id": "abcdefaga2980",
    "client_secret": "0a993jgjaijfdj",
    "grant_type": "client_credentials"
  }
}
```

## Examples

```bash
# Login with client credentials
yaac login cc-app

# Login with authorization code (opens browser)
yaac login auth-app
```

## See Also

- [config](config.md) - Configure credentials with `yaac config credential`
