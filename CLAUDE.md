# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Yaac (Yet Another Anypoint CLI) is a high-performance CLI tool for managing MuleSoft Anypoint Platform, built in Clojure and compiled to native binary with GraalVM. It provides 5x faster performance than the official Anypoint CLI through caching, parallel API calls, and native compilation.

## Development Commands

### Building and Running
- `clj -M -m yaac.cli` - Run the CLI directly from source
- `clj -X:uber` - Build standalone JAR file (creates target/yaac-0.7.3.jar)
- `./yaac --version` - Run the compiled native binary
- `./yaac <command>` - Run any CLI command (e.g., `./yaac get org`, `./yaac login`)

### Testing
- `clj -A:test -M -m clojure.test` - Run all tests with test dependencies
- `clj -A:runner` - Run tests using Cognitect test runner
- **CRITICAL**: Tests must NOT be run automatically - manual deployment to server is required before testing

### Native Binary Compilation
After building the JAR with `clj -X:uber`, compile to native binary using GraalVM native-image. Use the bash function `ni` from README or run:
```bash
native-image -jar target/yaac-0.7.3.jar \
  -O3 \
  --initialize-at-run-time=com.mongodb.client.internal.Crypt,com.mongodb.internal.connection.UnixSocketChannelStream,com.mongodb.internal.connection.ZstdCompressor,com.mongodb.UnixServerAddress,com.mongodb.internal.connection.SnappyCompressor,java.sql.DriverManager \
  --trace-class-initialization=clojure.lang.Compile \
  -H:+BuildReport \
  --initialize-at-build-time \
  --diagnostics-mode \
  --report-unsupported-elements-at-runtime \
  --gc=G1 \
  -Ob \
  --no-fallback \
  -J-Xmx8g \
  -march=native \
  --enable-http \
  --enable-https \
  -J-Dclojure.compiler.direct-linking=true \
  --report-unsupported-elements-at-runtime \
  --trace-object-instantiation=java.lang.Thread \
  -J-Dclojure.spec.skip-macros=true
```

## Architecture

### Core Components
- **yaac.cli** - Entry point (`-gen-class`, `-main`), command routing via Reitit router, global CLI argument parsing
- **yaac.core** - Core business logic, API client infrastructure, dynamic context vars (`*org*`, `*env*`, `*deploy-target*`, `*no-cache*`, `*no-multi-thread*`)
- **yaac.auth** - OAuth2 flows (client credentials, authorization code, password grant)
- **yaac.config** - Context management, credentials storage, configuration file handling
- **yaac.http** - HTTP utilities, request/response tracing (`-V` lite, `-X` detailed)
- **yaac.error** - Centralized error handling and formatting
- **yaac.util** - Utility functions for console output

### Command Modules (Reitit Routes)
Each module exports a `route` for the Reitit router defined in `yaac.cli`:
- **yaac.login** - Authentication flows
- **yaac.deploy** - App deployment to RTF/CloudHub 2.0
- **yaac.delete** - Resource deletion
- **yaac.upload** - Asset upload to Exchange
- **yaac.download** - Download proxies/assets from Exchange
- **yaac.create** - Resource creation
- **yaac.describe** - Resource inspection
- **yaac.update** - Resource updates
- **yaac.logs** - Log retrieval
- **yaac.analyze** - Code analysis
- **yaac.incubate** - Experimental features

### Key Design Patterns

#### Performance Optimization
- **File-based memoization**: `memoize-file` function caches org/env lookups in `~/.yaac/cache` to avoid repeated API calls (~700-800ms each)
- **Parallel API calls**: `on-threads` macro executes multiple API requests concurrently, controlled by `*no-multi-thread*` dynamic var
- **Native compilation**: GraalVM native-image for instant startup (<100ms vs 2-3s JVM startup)

#### Global Context via Dynamic Vars
- `*org*`, `*env*`, `*deploy-target*` - Current context (can be set via `yaac config context`)
- `*no-cache*` - Bypass file-based cache when true
- `*no-multi-thread*` - Disable parallel API calls (useful for debugging with `-X`)
- Scoped via `binding` in CLI command handlers

#### Authentication & Session Management
- Credentials stored in `~/.yaac/credentials` (JSON format)
- Active session stored in `~/.yaac/session` (Nippy binary serialization)
- `default-credential` var-root holds current session token
- Bearer token included in all API requests via `default-headers`

## Configuration Files

- `~/.yaac/credentials` - Connected App credentials (JSON)
- `~/.yaac/session` - Current access token (Nippy binary)
- `~/.yaac/config` - Default context settings (EDN: `:organization`, `:environment`, `:deploy-target`)
- `~/.yaac/cache` - File-based memoization cache for org/env data

## Key Dependencies

- **silvur** - Local HTTP client library at `/home/myst/projects/silvur` (`:local/root` dependency)
  - Provides `silvur.http`, `silvur.util`, `silvur.log`, `silvur.nio`
  - Changes to HTTP operations may require work in both projects
- **reitit** - Router for CLI command dispatch
- **clojure.tools.cli** - Argument parsing
- **nippy** - Binary serialization (sessions)
- **jansi-clj** - Terminal colors/formatting
- **mule.weave** - DataWeave runtime (2.9.1)
- **honeysql/next.jdbc** - Database operations
- **camel-snake-kebab** - Case conversion for API responses

## MuleSoft Anypoint Platform API

### Base URL
`https://anypoint.mulesoft.com` (stored in `global-base-url` var)
Alternative: `https://jp1.platform.mulesoft.com` (Hyperforce)

### Key Endpoints
- Authentication: `/accounts/api/me`
- Organizations: `/accounts/api/organizations`
- Environments: `/accounts/api/organizations/{org}/environments`
- Applications: `/amc/application-manager/api/v2/organizations/{org}/environments/{env}/deployments`
- Assets: `/graph/api/v2/graphql` (GraphQL)

### Response Handling
- `parse-response` converts responses using `camel-snake-kebab` (`:kebab` case)
- HTTP errors (status >= 400) throw `ex-info` via `yaac.error`
- All API calls include Bearer token from `default-credential`

## Output Formatting

- **Formats**: table (default), JSON (`-o json`), EDN (`-o edn`), YAML (`-o yaml`)
- **Field selection**: `-F field1,field2` or `-F +additional-field`
- **HTTP tracing**:
  - `-V` - Lite mode (request/response lines only)
  - `-X` - Detailed mode (full headers/bodies, disables multi-threading)

## Working with Silvur

Yaac depends on `silvur`, a local HTTP client library at `/home/myst/projects/silvur`. This is specified in `deps.edn` as:
```clojure
io.gitlab.myst3m/silvur {:local/root "/home/myst/projects/silvur"}
```

When modifying HTTP operations, you may need to:
1. Make changes in the `silvur` project
2. Test changes in `yaac` by running `clj -M -m yaac.cli`
3. Build both projects if deploying

No need to publish silvur separately - it's loaded directly from the local filesystem during development and gets bundled into the uber JAR during `clj -X:uber`.
