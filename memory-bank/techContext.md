# Tech Context: desic

## Technology Stack

### Core Language & Runtime
- **Clojure 1.12.1**: Latest stable with improved error messages
- **JDK 11+**: Minimum version for deployment compatibility
- **deps.edn**: Native Clojure dependency management

### Key Dependencies

#### Async & Concurrency
- **Manifold 0.4.3**: Deferreds, streams, backpressure management
- Core choice for non-blocking I/O and pipeline composition

#### Schema & Validation
- **Malli 0.19.1**: Runtime validation, schema generation, JSON Schema export
- Provides type safety throughout the pipeline

#### LLM Integration
- **openai-clojure 0.22.0**: Pure Clojure OpenAI API client
- Zero Python dependencies, native JVM HTTP client

#### Persistence
- **next.jdbc 1.3.1048**: Modern JDBC wrapper for SQLite
- **SQLite JDBC 3.50.1.0**: Embedded database, no server required

#### CLI & Build
- **tools.cli 1.1.230**: Command-line argument parsing
- **tools.build 0.10.9**: Modern Clojure build tool

### Development Tools

#### REPL & Debugging
- **Portal 0.59.1**: Live data visualization and debugging
- **nREPL**: Editor integration for CIDER, Calva, etc.

#### Testing
- **Kaocha 1.91.1392**: Modern test runner with watch mode
- **test.check 1.1.2**: Property-based testing with generators
- **clj-http-fake 1.0.4**: HTTP mocking for backend tests

#### Code Quality
- **clj-kondo 2025.06.05**: Static analysis and linting
- **criterium 0.4.6**: JVM benchmarking

## Development Environment

### REPL Setup
```bash
# Start development REPL with Portal
make repl

# Or manually
clj -M:dev -m nrepl.cmdline --interactive --middleware '[cider.nrepl/cider-middleware]'
```

### Editor Integration
- **CIDER** (Emacs): Connect to nREPL on port shown in `.nrepl-port`
- **Calva** (VS Code): Use "Connect to Running REPL"
- **Cursive** (IntelliJ): Import as deps.edn project

### Portal Integration
```clojure
;; In REPL
(require '[portal.api :as p])
(add-tap #'p/submit)

;; Now all (tap> data) calls appear in Portal
(tap> {:message "Hello Portal!"})
```

## Build & Deployment

### Local Development
```bash
make lint     # Run clj-kondo
make test     # Run full test suite
make repl     # Start development REPL
make uber     # Build standalone JAR
```

### CI/CD Pipeline
- **GitHub Actions**: Automated testing and building
- **Maven Central**: Dependency caching for faster builds
- **Artifact Upload**: Automatic uberjar releases on git tags

### Deployment Options
1. **Standalone JAR**: Single file deployment
2. **Docker**: Multi-stage build with JRE base image
3. **GraalVM Native**: Future consideration for startup speed

## Technical Constraints

### JVM Compatibility
- **Minimum JDK 11**: Required for HTTP client features
- **Maximum JDK 21**: Tested compatibility ceiling
- **Memory**: Minimum 512MB heap for optimization runs

### Concurrency Limits
- **Thread Pool**: Configurable via `DSPY_PARALLELISM` environment variable
- **Rate Limiting**: Respect OpenAI API limits (default 3 RPS)
- **Connection Pool**: HTTP client connection reuse

### Storage Requirements
- **SQLite**: Single file database, no server setup
- **EDN Fallback**: Plain text files, human-readable
- **Disk Space**: ~10MB per 1000 optimization iterations

## Security Considerations

### API Key Management
- **Environment Variables**: Never commit keys to source
- **Runtime Injection**: Keys loaded at startup only
- **Local Development**: Use `.env` files (gitignored)

### Network Security
- **HTTPS Only**: All LLM API calls encrypted
- **Certificate Validation**: Full certificate chain verification
- **Proxy Support**: Honor system HTTP proxy settings

### Data Privacy
- **No Telemetry**: Zero data sent to third parties
- **Local Storage**: All optimization data stays local
- **Audit Trail**: Complete history of all API calls for compliance

## Performance Characteristics

### Expected Throughput
- **Sequential**: ~3 requests/second (OpenAI rate limit)
- **Parallel**: ~24 requests/second (8 concurrent with rate limiting)
- **Optimization**: ~100 iterations/hour for typical pipeline

### Resource Usage
- **CPU**: Low when idle, spikes during optimization
- **Memory**: ~100MB base + ~10MB per optimization candidate
- **Network**: Depends on prompt size, typically 1-10KB per request

### Scaling Limits
- **Single JVM**: Up to 1000 concurrent optimization candidates
- **Disk I/O**: SQLite handles ~10,000 writes/second
- **Network**: Limited by LLM provider rate limits, not client

## Monitoring & Observability

### Built-in Metrics
- Portal dashboards for optimization progress
- JVM metrics via standard tooling (JVisualVM, etc.)
- SQL query logging for debugging storage issues

### Integration Points
- **Prometheus**: JVM metrics export
- **Grafana**: Custom dashboards for optimization metrics
- **APM Tools**: Standard JVM profiling compatible