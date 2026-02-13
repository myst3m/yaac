# yaac build

Run Maven goals using embedded Maven. No Maven installation required.

## Usage

```bash
yaac build [dir] <goals...>
```

## Arguments

| Argument | Required | Description |
|----------|----------|-------------|
| `dir` | No | Project directory (default: current directory) |
| `goals` | Yes | Maven goals to execute |

## Auto-injected Flags

The following flags are automatically added:

| Flag | Description |
|------|-------------|
| `-DskipTests` | Skip test execution |
| `-DskipAST=true` | Skip AST analysis |
| `-Djava.home=...` | Auto-detected Java home |

## Examples

```bash
# Build Mule application
yaac build clean package

# Build with custom source attachment
yaac build clean package -DattachMuleSources

# Build specific directory
yaac build /path/to/mule-app clean package

# View dependency tree
yaac build dependency:tree

# Run tests (overrides -DskipTests)
yaac build test
```

## Notes

- Uses GraalVM native image with embedded Maven Embedder for fast startup
- All Maven plugins must be included in the uber JAR
- MUnit tests are not supported in embedded mode (use standalone Maven for MUnit)

## See Also

- [upload](upload.md) - Upload built JAR to Exchange
- [deploy](deploy.md) - Deploy to Anypoint Platform
