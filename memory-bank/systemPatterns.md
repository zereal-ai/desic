# System Patterns: desic

## Architecture Overview

### Core Design Principles
1. **Protocol-First**: All major abstractions defined as protocols
2. **Async-First**: Manifold deferreds throughout for scalability
3. **Schema-Driven**: Malli schemas for runtime validation
4. **Composable**: Small, focused components that compose well
5. **Provider-Agnostic**: Configuration-driven provider selection
6. **Storage-Agnostic**: Protocol-based storage with multiple backends

## Component Architecture

### 1. Core DSL Layer
```
Signature → Module → Pipeline → Optimization
```

**Pattern**: Declarative composition with schema validation
- **Signatures**: Define input/output contracts with Malli schemas
- **Modules**: Async components implementing ILlmModule protocol
- **Pipelines**: DAG-based composition with dependency resolution
- **Optimization**: Strategy-based improvement with concurrent evaluation

### 2. Backend Integration Layer
```
Protocol → Provider → Wrapper → Client
```

**Pattern**: Provider-agnostic abstraction with middleware composition
- **ILlmBackend Protocol**: Universal async interface
- **Provider Implementations**: Concrete LLM provider integrations
- **Middleware Stack**: Wrappers for resilience and observability
- **Factory Pattern**: Configuration-driven backend creation

### 3. Optimization Engine Layer
```
Strategy → Evaluation → Metrics → History
```

**Pattern**: Pluggable optimization with concurrent assessment
- **Strategy Multimethod**: Pluggable optimization algorithms
- **Concurrent Evaluation**: Rate-limited parallel pipeline assessment
- **Built-in Metrics**: Exact matching and semantic similarity
- **Optimization History**: Complete tracking of improvement process

### 4. Concurrency & Resource Management Layer
```
Parallel Processing → Rate Limiting → Resource Management → Monitoring
```

**Pattern**: Enterprise-grade concurrency with controlled resource usage
- **Parallel Processing**: Configurable concurrency with environment variables
- **Rate Limiting**: Token-bucket throttling with burst capacity
- **Resource Management**: Exception-safe handling with guaranteed cleanup
- **Performance Monitoring**: Built-in timing and observability

### 5. Live Introspection Layer
```
Portal Integration → Instrumentation → Debugging → Monitoring
```

**Pattern**: Real-time debugging and monitoring capabilities
- **Portal Integration**: Automatic Portal detection and initialization
- **Instrumentation**: Real-time module execution and optimization tracking
- **Debugging Support**: Test utilities and manual integration capabilities
- **Error Handling**: Graceful degradation when Portal unavailable

### 6. Persistence Layer ⭐ **LATEST**
```
Storage Protocol → Backend Implementations → Integration → Configuration
```

**Pattern**: Protocol-based storage with multiple backend implementations
- **Storage Protocol**: Universal interface for optimization runs and metrics
- **SQLite Backend**: Production-grade database with migration system
- **EDN Backend**: Development-friendly file-based storage
- **Optimization Integration**: Checkpoint/resume functionality with storage binding

## Key Design Patterns

### 1. Protocol-Based Abstractions
```clojure
;; Universal interfaces that work with any implementation
(defprotocol ILlmBackend
  (generate [this prompt options])
  (embeddings [this text options]))

(defprotocol Storage
  (create-run! [this pipeline])
  (append-metric! [this run-id iter score payload])
  (load-run [this run-id])
  (load-history [this run-id]))
```

### 2. Factory Pattern with Configuration
```clojure
;; Configuration-driven creation, no provider-specific code
(def backend (bp/create-backend {:provider :openai :model "gpt-4o"}))
(def storage (storage/create-storage "sqlite://./runs.db"))
```

### 3. Middleware Composition
```clojure
;; Composable wrappers for cross-cutting concerns
(-> (create-backend {:type :openai})
    (wrap-throttle {:rps 5})
    (wrap-retry {:max-retries 3})
    (wrap-timeout {:timeout-ms 10000}))
```

### 4. Strategy Pattern for Optimization
```clojure
;; Pluggable optimization algorithms
(defmulti compile-strategy :strategy)
(defmethod compile-strategy :beam [config] ...)
(defmethod compile-strategy :random [config] ...)
```

### 5. Async-First with Manifold
```clojure
;; Non-blocking operations throughout
(defn optimize [pipeline training-data metric config]
  (d/chain
   (create-optimization-run pipeline config)
   #(evaluate-candidates % training-data metric)
   #(select-best-candidates % config)))
```

### 6. Schema-Driven Validation
```clojure
;; Runtime validation with clear error messages
(def OptimizationConfig
  [:map
   [:strategy keyword?]
   [:max-iterations pos-int?]
   [:concurrency pos-int?]])
```

## Storage Architecture ⭐ **LATEST**

### Storage Protocol Design
```clojure
(defprotocol Storage
  "Universal storage interface for optimization runs and metrics."
  (create-run! [this pipeline] "Create new optimization run")
  (append-metric! [this run-id iter score payload] "Save optimization metric")
  (load-run [this run-id] "Retrieve stored pipeline")
  (load-history [this run-id] "Get complete optimization history"))
```

### Backend Implementations

#### SQLite Backend
- **Schema**: `runs` and `metrics` tables with proper relationships
- **Migration**: Automatic database initialization and schema management
- **Serialization**: Proper Clojure data serialization/deserialization
- **Transactions**: Safe concurrent access with transaction support
- **Configuration**: Support for both file-based and in-memory databases

#### EDN Backend
- **Structure**: Directory-per-run organization (`./runs/{run-id}/`)
- **Files**: `pipeline.edn` and `metrics.edn` for each run
- **Serialization**: Pure Clojure data serialization (no external dependencies)
- **Simplicity**: No database setup required for development
- **Inspection**: Easy debugging and manual inspection of stored data

### Factory Pattern
```clojure
;; Environment-based configuration
(create-storage) ; Uses DSPY_STORAGE env var or defaults to EDN

;; URL-based configuration
(create-storage "sqlite://./optimization.db")
(create-storage "file://./custom-runs")

;; Dynamic backend loading to avoid circular dependencies
(defn make-storage [config]
  (case (:type config)
    :sqlite (sqlite/->SQLiteStorage (:url config))
    :file (edn-storage/->EDNStorage (:dir config))))
```

### Integration with Optimization Engine
```clojure
;; Dynamic storage binding in optimization context
(defn optimize [pipeline training-data metric config]
  (let [storage (:storage config (create-storage))]
    (binding [*storage* storage]
      (create-optimization-run pipeline config))))

;; Checkpoint saving in beam search
(defn beam-search [pipeline training-data metric config]
  (let [checkpoint-interval (:checkpoint-interval config 10)]
    (doseq [iter (range max-iterations)]
      (when (zero? (mod iter checkpoint-interval))
        (save-checkpoint! *storage* run-id iter pipeline)))))
```

## Error Handling Patterns

### 1. Structured Exception Handling
```clojure
;; Clear error contexts with data
(throw (ex-info "Invalid optimization configuration"
                {:config config
                 :errors validation-errors}))
```

### 2. Graceful Degradation
```clojure
;; Fallback patterns when optional features unavailable
(when (portal-available?)
  (install-portal-tap!)
  (log/info "Portal integration enabled"))
```

### 3. Resource Safety
```clojure
;; Guaranteed cleanup with try/finally
(try
  (process-data resource data)
  (finally
    (cleanup-resource resource)))
```

## Configuration Patterns

### 1. Environment Variables
```clojure
;; Sensible defaults with environment override
(def default-config
  {:parallelism (or (System/getenv "DSPY_PARALLELISM") 4)
   :storage (or (System/getenv "DSPY_STORAGE") "file://./runs")})
```

### 2. URL-Based Configuration
```clojure
;; Flexible configuration via URLs
"sqlite://./optimization.db" → {:type :sqlite :url "jdbc:sqlite:./optimization.db"}
"file://./custom-runs" → {:type :file :dir "./custom-runs"}
```

### 3. Nested Configuration
```clojure
;; Hierarchical configuration structure
{:optimization
 {:strategy :beam
  :beam-width 4
  :max-iterations 10}
 :backend
 {:provider :openai
  :model "gpt-4o"}
 :storage
 {:type :sqlite
  :url "jdbc:sqlite:./runs.db"}}
```

## Testing Patterns

### 1. Protocol Compliance Testing
```clojure
;; Common test suite applied to all implementations
(defn test-storage-implementation [storage-impl]
  (testing "create-run! creates a new run and returns ID"
    (let [run-id (storage/create-run! storage-impl test-pipeline)]
      (is (string? run-id))))
  ;; ... more tests
  )
```

### 2. Mock Implementations
```clojure
;; Clear intent with underscore prefixes
(defrecord MockBackend []
  ILlmBackend
  (generate [_ _prompt _options] (d/success-deferred "mock response"))
  (embeddings [_ _text _options] (d/success-deferred [0.1 0.2 0.3])))
```

### 3. Temporary Resource Management
```clojure
;; Safe test isolation with cleanup
(defn with-temp-dir [test-fn]
  (let [temp-dir-path (temp-dir)]
    (try
      (binding [*temp-dir* temp-dir-path]
        (test-fn))
      (finally
        (cleanup-temp-dir temp-dir-path)))))
```

## Performance Patterns

### 1. Concurrent Evaluation
```clojure
;; Rate-limited parallel processing
(defn evaluate-candidates [candidates training-data metric]
  (rate-limited-parallel-map
   concurrency rate-limit
   #(evaluate-pipeline % training-data metric)
   candidates))
```

### 2. Batch Processing
```clojure
;; Memory-efficient large dataset processing
(defn process-batches [batch-size concurrency batch-fn coll]
  (->> (partition-all batch-size coll)
       (parallel-map concurrency batch-fn)
       (apply concat)))
```

### 3. Resource Pooling
```clojure
;; Efficient resource reuse
(defn with-resource [resource operation-fn cleanup-fn]
  (try
    (operation-fn resource)
    (finally
      (cleanup-fn resource))))
```

## Extension Patterns

### 1. Adding New LLM Providers
```clojure
;; Implement ILlmBackend protocol
(defrecord AnthropicBackend [config]
  ILlmBackend
  (generate [this prompt options] ...)
  (embeddings [this text options] ...))

;; Register with multimethod
(defmethod bp/create-backend :anthropic [config]
  (->AnthropicBackend config))
```

### 2. Adding New Storage Backends
```clojure
;; Implement Storage protocol
(defrecord PostgreSQLStorage [ds]
  Storage
  (create-run! [this pipeline] ...)
  (append-metric! [this run-id iter score payload] ...)
  (load-run [this run-id] ...)
  (load-history [this run-id] ...))

;; Add to factory
(defmethod make-storage :postgresql [config]
  (->PostgreSQLStorage (:ds config)))
```

### 3. Adding New Optimization Strategies
```clojure
;; Implement strategy multimethod
(defmethod compile-strategy :genetic [config]
  (fn [pipeline training-data metric]
    (genetic-algorithm pipeline training-data metric config)))
```

## Security Patterns

### 1. API Key Management
```clojure
;; Environment-based secrets
(def api-key (or (System/getenv "OPENAI_API_KEY")
                 (System/getProperty "openai.api.key")))
```

### 2. Input Validation
```clojure
;; Schema-based validation
(defn validate-input [schema data]
  (when-not (m/validate schema data)
    (throw (ex-info "Invalid input" {:schema schema :data data}))))
```

### 3. Resource Limits
```clojure
;; Configurable limits to prevent abuse
(def max-concurrency (or (System/getenv "DSPY_MAX_CONCURRENCY") 10))
(def max-iterations (or (System/getenv "DSPY_MAX_ITERATIONS") 100))
```

## Monitoring Patterns

### 1. Structured Logging
```clojure
;; Context-rich logging
(log/info "Optimization iteration completed"
          {:iteration iter
           :score score
           :candidates-count (count candidates)})
```

### 2. Performance Metrics
```clojure
;; Built-in timing and monitoring
(defn timed [deferred]
  (let [start (System/currentTimeMillis)]
    (d/chain deferred
             (fn [result]
               {:result result
                :elapsed-ms (- (System/currentTimeMillis) start)}))))
```

### 3. Health Checks
```clojure
;; System health monitoring
(defn health-check []
  {:status :healthy
   :timestamp (System/currentTimeMillis)
   :version "1.0.0"
   :components {:backend (backend-health-check)
                :storage (storage-health-check)}})
```

## Deployment Patterns

### 1. Configuration Management
```clojure
;; Environment-specific configuration
(defn load-config []
  (merge default-config
         (when (= (System/getenv "ENV") "production")
           production-config)
         (when (= (System/getenv "ENV") "development")
           development-config)))
```

### 2. Resource Initialization
```clojure
;; Proper startup sequence
(defn init-system! []
  (init-logging!)
  (init-storage!)
  (init-backend!)
  (init-portal!))
```

### 3. Graceful Shutdown
```clojure
;; Clean resource cleanup
(defn shutdown-system! []
  (shutdown-portal!)
  (shutdown-backend!)
  (shutdown-storage!)
  (shutdown-logging!))
```

## Summary

The desic system follows **protocol-first, async-first, schema-driven** design principles with:

- **Clean abstractions** through protocols and factory patterns
- **Composable components** that work together seamlessly
- **Provider-agnostic design** enabling easy extension
- **Storage-agnostic persistence** with multiple backend options
- **Enterprise-grade concurrency** with controlled resource usage
- **Comprehensive error handling** with graceful degradation
- **Extensive testing** with clear patterns and isolation
- **Production-ready patterns** for deployment and monitoring

This architecture provides a solid foundation for systematic LLM pipeline optimization while maintaining flexibility for future enhancements and different deployment scenarios.