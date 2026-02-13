# yaac upload

Upload assets to Anypoint Exchange.

**Aliases:** `up`

## Usage

```bash
yaac upload asset <file> [options]
```

## Options

| Flag | Long | Description | Default |
|------|------|-------------|---------|
| `-g` | `--group` | Group name (Business Group) | From JAR pom.xml |
| `-a` | `--asset` | Asset name | From JAR pom.xml |
| `-v` | `--version` | Asset version | From JAR pom.xml |
| `-t` | `--asset-type` | Asset type: `app`, `plugin`, `raml` | `app` |
| | `--api-version` | API version for RAML/OAS | `v1` |

## Supported File Types

| File Type | Auto-detected | Asset Type |
|-----------|--------------|------------|
| `.jar` | Yes | `mule-application` |
| `.raml` | Yes | `rest-api` (RAML) |
| `.json` / `.yaml` | Yes | `rest-api` (OAS) |

For JAR files, GAV (Group, Artifact, Version) is automatically extracted from the embedded `pom.xml`.

## Examples

```bash
# Upload JAR (GAV from pom.xml)
yaac upload asset my-app.jar

# Upload JAR with custom GAV
yaac upload asset my-app.jar -g T1 -a my-large-db-app -v 0.0.5

# Upload RAML
yaac upload asset httpbin.raml -g T1 -a httpbin-api -v 2.0.0 --api-version=v2

# Upload OAS
yaac upload asset openapi.yaml -g T1 -a my-api -v 1.0.0
```

## Output

```
GROUP-ID                              ASSET-ID     NAME         TYPE  VERSION
fe1db8fb-xxxx-4b5c-a591-06fea582f980  my-test-app  my-test-app  app   0.0.5
```

## See Also

- [deploy](deploy.md) - Deploy uploaded assets
- [get](get.md) - `get asset` to verify uploaded assets
