# yaac download

Download resources from Anypoint Platform.

**Aliases:** `dl`

## Usage

```bash
yaac download <resource> [org] [env] <api>
```

## Subcommands

| Subcommand | Alias | Description |
|------------|-------|-------------|
| `download proxy` | - | Download API proxy as JAR file |
| `download api` | - | Download API proxy as JAR file |

## Examples

```bash
yaac download proxy T1 Production my-api
yaac download api T1 Sandbox 20671224
```

## See Also

- [get](get.md) - `get api` to list API instances
- [deploy](deploy.md) - Deploy API proxies
