# validate

Pre-deploy validation for a Mule application. Detects properties that have no
value **before** you push to the runtime — the usual cause of a deploy that
sits in `APPLYING` and then fails with
`Couldn't find configuration property value for key ${...}`.

```
yaac validate <app.jar|dir|mule.xml> [+mule.env=<env>] [+key=value ...]
```

Alias: `val`

## What it checks

| Severity | Check |
|----------|-------|
| `error`  | Unresolved `${...}` placeholder (no value anywhere) |
| `error`  | `config-ref` pointing to an undefined config |
| `error`  | `flow-ref` with no matching `flow` / `sub-flow` |
| `warn`   | Unknown attribute on a connector element (requires the schema cache — see [connector.md](connector.md)) |

## How a placeholder is resolved

A `${name}` placeholder counts as **resolved** when `name` is provided by any
of the following. Otherwise it is reported as `:unresolved-placeholder`.

1. **A configuration-properties file in the artifact.**
   `<configuration-properties file="config-${mule.env}.yaml"/>` is resolved
   using the `+mule.env` you pass, then the matching file inside the artifact
   is read and its keys (nested YAML flattened to `a.b.c`) are collected.
2. **A `+key=value` argument** — the same `+`-prefixed syntax `yaac deploy`
   uses for global properties. This lets you validate against exactly the
   properties you intend to pass at deploy time.
3. **An OS environment variable or JVM system property.**
4. **A Mule runtime placeholder** — `env.*`, `sys.*`, `app.*`, `mule.*`.

A leading `secure::` is stripped before lookup, so `${secure::db.password}`
resolves against the same key set.

## Targets

| Target | Scanned |
|--------|---------|
| `<app.jar>` | Bundled Mule flow XMLs + every `*.yaml` / `*.yml` / `*.properties` resource |
| `<dir>` | `src/main/mule/*.xml` + `src/main/resources/**` |
| `<mule.xml>` | The file itself + sibling and `src/main/resources` config files |

## Examples

```bash
# Validate a packaged app for the ch2 environment
yaac validate target/my-app.jar +mule.env=ch2

# Validate the current project for local
yaac validate . +mule.env=local

# Validate a single flow, supplying deploy-time properties
yaac validate src/main/mule/main.xml +mule.env=ch2 +db.host=oracle.local

# Clean app prints nothing (exit code 0); pipe to check in CI
yaac validate target/my-app.jar +mule.env=ch2 -H
```

A clean app produces no findings. When something is missing:

```
FILE                     SEVERITY  KIND                     MESSAGE
target/my-app.jar        error     :unresolved-placeholder  No value for ${db.password}
-                        error     :undefined-config-ref    config-ref 'DB_Config' is not defined
```

## Notes

- The unknown-attribute check is best-effort and only runs when the connector
  schema cache exists. Run [`yaac connector collect`](connector.md) once to
  populate it. Without it, that check is silently skipped (no false errors).
- Nested elements such as `<http:listener-connection>` or `<sftp:connection>`
  are not in the schema cache, so their attributes are not checked.
- Type / required / enum validation of property *values* is not performed —
  the schema cache carries names and descriptions only.
