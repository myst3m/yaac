# nREPL Command Re-implementation Plan

## Background

The `yaac nrepl` command was removed in commit `adf21f7` (Dec 27, 2025) to achieve GraalVM native image build success. The removal was necessary because:

1. **nrepl/nrepl dependency** - Compatibility issues with GraalVM native-image
2. **silvur.util dependencies** - JSON functions caused build failures
3. **silvur.log dependencies** - Logging functions incompatible with native-image

## Original Functionality

The nREPL command provided an interactive REPL interface to deployed Mule applications:

```bash
# Connect to app using app:// protocol
yaac nrepl app://my-app/nrepl

# Connect with explicit org/env
yaac nrepl T1 Production app://my-app/nrepl

# Connect to direct URL
yaac nrepl https://my-app.cloudhub.io/nrepl

# Use internal URL
yaac nrepl app://my-app/nrepl -i
```

### Key Features:
- **Protocol support**: `app://`, `http://`, `https://`
- **Auto-discovery**: Resolve app URL from org/env/app-name
- **Internal/External URLs**: `-i` flag for internal endpoints
- **Interactive REPL**: Read-eval-print loop with namespace tracking
- **HTTP Host header**: Custom Host header support

### Implementation Details:

**Dependencies:**
- `nrepl/nrepl` - Core nREPL library
- `silvur.util` - JSON conversion (edn->json, json->edn)
- `silvur.log` - Logging
- `yaac.describe` - Application metadata lookup

**Core Functions:**
1. `emit` - Send eval request to nREPL endpoint
2. `destination-url` - Resolve app:// to actual HTTP(S) URL
3. `cli` - Main command handler with REPL loop

## Re-implementation Strategy

### Phase 1: Investigation (Current)
- [ ] Check nrepl/nrepl v1.3.0 compatibility with GraalVM 25
- [ ] Review native-image build logs for nrepl-related errors
- [ ] Determine if reflection config is sufficient or if custom implementation needed

### Phase 2: Code Migration
- [ ] Update `nrepl.clj` dependencies:
  - `silvur.util` → `yaac.util` (already uses jsonista)
  - `silvur.log` → `taoensso.timbre`
- [ ] Fix HTTP calls: Add `@` deref for `zeph.client/post`
- [ ] Remove unused `nrepl.core` require if not using nREPL client
- [ ] Remove malli schema annotations (lines 33-39) - not critical for functionality

### Phase 3: Integration
- [ ] Add `nrepl/nrepl {:mvn/version "1.3.0"}` to `deps.edn`
- [ ] Restore nrepl route in `cli.clj`:
  ```clojure
  ;; nREPL
  (= (first args) "nrepl")
  (try
    (load-default-context!)
    (yc/load-session!)
    (binding [*org* (:organization default-context)
              *env* (:environment default-context)
              *no-cache* (:no-cache options)
              *deploy-target* (:deploy-target default-context)]
      (apply yaac.nrepl/cli (rest args)))
    (catch clojure.lang.ExceptionInfo e (print-explain e))
    (catch Exception e (print-error e)))
  ```
- [ ] Add `[yaac.nrepl]` require to `cli.clj`

### Phase 4: GraalVM Configuration
- [ ] Add reflection config if needed:
  ```json
  {
    "name": "nrepl.server.Server",
    "allDeclaredConstructors": true,
    "allPublicConstructors": true,
    "allDeclaredMethods": true,
    "allPublicMethods": true
  }
  ```
- [ ] Test uber JAR build: `clj -X:uber`
- [ ] Test native-image build: `clj -T:build native-image`
- [ ] Iterate on reflection config until build succeeds

### Phase 5: Alternative Implementation (If nrepl/nrepl fails)
If nrepl/nrepl library cannot work with native-image, implement custom nREPL protocol:

- [ ] Implement bencode encoder/decoder (nREPL wire format)
- [ ] Implement minimal nREPL client:
  - `clone` op - Create new session
  - `eval` op - Evaluate code
  - Handle `value`, `out`, `err`, `ns` response messages
- [ ] Remove nrepl/nrepl dependency
- [ ] Update reflection config for custom implementation

## Testing Plan

### Unit Tests:
1. URL resolution: `app://` → `https://...`
2. Internal vs external URL selection
3. JSON encoding/decoding

### Integration Tests:
1. Connect to local nREPL server
2. Execute simple expressions
3. Namespace switching
4. Error handling

### Native Image Tests:
1. Build succeeds without errors
2. Binary size remains reasonable (<200MB)
3. Runtime functionality matches JVM version

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| nrepl/nrepl incompatible with native-image | Implement custom bencode/nREPL protocol |
| Reflection configuration too complex | Use GraalVM tracing agent to auto-generate config |
| Binary size bloat | Profile with `--emit build-report`, remove unused features |
| Runtime performance degradation | Benchmark REPL latency before/after |

## Success Criteria

- [x] Code compiles without errors
- [ ] Native image builds successfully
- [ ] Binary size < 200MB
- [ ] Build time < 3 minutes
- [ ] nREPL command connects to deployed app
- [ ] REPL loop executes Clojure expressions
- [ ] Namespace tracking works correctly
- [ ] Error messages display properly

## Timeline Estimate

- Phase 1 (Investigation): 30 minutes
- Phase 2 (Code Migration): 1 hour
- Phase 3 (Integration): 30 minutes
- Phase 4 (GraalVM Config): 1-2 hours (depends on issues)
- Phase 5 (Alternative): 4-6 hours (if needed)

**Total: 3-10 hours** depending on native-image compatibility.
