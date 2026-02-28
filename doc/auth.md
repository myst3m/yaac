# yaac auth

OAuth2 authorization flows for obtaining tokens from external identity providers.

## Usage

```bash
yaac auth <flow> [options] [key=value...]
```

## Options

| Flag | Long | Description |
|------|------|-------------|
| `-p` | `--preset NAME` | Preset configuration (azure) |
| | `--port PORT` | Listen port for callback (default: 9180) |

## Flows

### code

Authorization Code Flow (browser-based). Opens a browser for user authentication.

```bash
yaac auth code [options] issuer=<url> client-id=<id> client-secret=<secret>
```

### client

Client Credentials Flow. Server-to-server authentication without browser.

```bash
yaac auth client issuer=<url> client-id=<id> client-secret=<secret>
```

### azure

Azure Entra preset (shorthand for `code -p azure`).

```bash
yaac auth azure tenant=<id> client-id=<id> client-secret=<secret>
```

## Keys

| Key | Description | Default |
|-----|-------------|---------|
| `issuer` | OIDC issuer URL (auto-discovers endpoints) | - |
| `authorize-url` | Authorization endpoint (explicit) | - |
| `token-url` | Token endpoint (explicit) | - |
| `client-id` | Client ID | - |
| `client-secret` | Client secret | - |
| `redirect-uri` | Callback URL | `http://localhost:9180/oauth2/callback` |
| `scope` | Requested scopes | `openid` |
| `tenant` | Azure AD tenant ID (azure preset only) | - |

## Examples

```bash
# Authorization code flow with OIDC discovery
yaac auth code issuer=https://my-idp client-id=xxx client-secret=xxx

# Authorization code flow with Azure preset
yaac auth code -p azure tenant=my-tenant client-id=xxx client-secret=xxx

# Client credentials flow
yaac auth client issuer=https://my-idp client-id=xxx client-secret=xxx

# Azure (legacy shorthand)
yaac auth azure tenant=my-tenant client-id=xxx client-secret=xxx
```

## See Also

- [login](login.md) - Login with Anypoint Connected Apps
