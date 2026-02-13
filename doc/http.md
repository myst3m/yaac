# yaac http

Send HTTP requests to deployed applications.

## Usage

```bash
yaac http <app-url> [options] [key=value...]
```

## Options

| Flag | Long | Description | Default |
|------|------|-------------|---------|
| `-m` | `--method` | HTTP method (`GET`, `POST`, `PUT`, `DELETE`) | `GET` |
| `-i` | `--internal` | Use internal URL | `false` |

## Key=Value Conventions

| Prefix | Meaning | Example |
|--------|---------|---------|
| `:key` | HTTP header | `:Authorization='Bearer token'` |
| `key` | Body parameter | `id=abc` |

## Examples

```bash
# GET request
yaac http account-app/accounts

# GET with auth header
yaac http account-app/account :Authorization='Bearer aaaa'

# POST with body
yaac http account-app/account -m POST id=abc name=test

# Use internal URL
yaac http account-app/health -i
```

## See Also

- [get](get.md) - `get app` to find application URLs
- [describe](describe.md) - `describe app` for application endpoint details
