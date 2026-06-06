# connector

Mule connector schema browser. Extracts connector schemas from your local
Maven repository into a cache at `~/.yaac/schema/`, then lets you browse and
search them. Fully independent of the `mulet` CLI.

```
yaac connector <command>
```

| Command | Description |
|---------|-------------|
| `collect` | Scan `~/.m2/repository` for `*-mule-plugin.jar`, keep the latest version of each, and extract `META-INF/*-extension-descriptions.xml` into `~/.yaac/schema/`. Rewrites `registry.edn`. |
| `list` | List all connectors with versions and element counts |
| `show <name>` | Show a connector's configs / operations / sources / types |
| `show <name> <element>` | Show the parameters of one element |
| `search <keyword>` | Search across connectors / elements / parameters (name + description) |
| `refresh` | Drop the parsed nippy cache (re-parsed on next use) |

## Examples

```bash
# Populate the cache from ~/.m2 (run once, or after adding connectors)
yaac connector collect

yaac connector list
yaac connector show http               # all elements
yaac connector show http request       # parameters of http:request
yaac connector search timeout
```

## Notes

- `$M2_REPO` overrides the Maven repository location (default
  `~/.m2/repository`).
- Anypoint Exchange custom connectors (UUID group ids) are skipped.
- The cache carries element/parameter **names and descriptions only** — no
  type, required, or enum information.
- To validate a Mule app against these schemas (and check for unresolved
  `${...}` properties before deploy), use [`yaac validate`](validate.md).
