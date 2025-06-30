# System Patterns: desic

## Architecture Overview

desic follows a layered architecture with clear separation of concerns:

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Signatures    │───▶│     Modules      │───▶│   Optimizers    │
│ (Declarative    │    │  (Executable     │    │ (Improvement    │
│  Contracts)     │    │   Components)    │    │   Engines)      │
└─────────────────┘    └──────────────────┘    └─────────────────┘
         │                       │                       │
         ▼                       ▼                       ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Backend Abstraction Layer                    │
│        (OpenAI, Anthropic, Local Models, Mock for Testing)      │
└─────────────────────────────────────────────────────────────────┘
         │                       │                       │
         ▼                       ▼                       ▼
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Persistence   │    │   Concurrency    │    │  Observability  │
│  (SQLite/EDN)   │    │   (Manifold)     │    │    (Portal)     │
└─────────────────┘    └──────────────────┘    └─────────────────┘
```

## Core Design Patterns

### 1. Protocol-Based Abstraction
**Pattern**: All major components implement protocols for extensibility
```clojure
(defprotocol ILlmModule
  (-execute [this input ctx])
  (-compile [this opts]))

(defprotocol ILlmBackend
  (-generate [this prompt opts])
  (-embeddings [this text opts])
  (-stream [this prompt opts]))
```

**Benefits**:
- Clean separation of interface and implementation
- Easy testing with mock implementations
- Plugin architecture for new backends/modules

### 2. Async-First with Manifold
**Pattern**: All I/O returns Manifold deferreds
```clojure
(defn generate [backend prompt opts]
  (d/chain
    (-generate backend prompt opts)
    :text))
```

**Benefits**:
- Non-blocking concurrent execution
- Composable async operations
- Built-in backpressure and rate limiting

### 3. Schema-Driven Validation
**Pattern**: Malli schemas define and validate all data structures
```clojure
(def signature-schema
  [:map
   [:inputs [:vector keyword?]]
   [:outputs [:vector keyword?]]
   [:examples {:optional true} [:vector [:map-of keyword? string?]]]])
```

**Benefits**:
- Catch errors at module boundaries
- Generate test data automatically
- Self-documenting contracts

### 4. Functional Composition
**Pattern**: Pipelines are functions that transform data
```clojure
(defn compile-pipeline [modules opts]
  (let [compiled (mapv #(-compile % opts) modules)]
    (fn run [input ctx]
      (reduce (fn [v m] (-execute m v ctx)) input compiled))))
```

**Benefits**:
- Pure functions enable easy testing
- Immutable data prevents side-effect bugs
- Composable building blocks

## Key Technical Decisions

### Concurrency Model
**Decision**: Use Manifold deferreds throughout
**Rationale**:
- Better than core.async for I/O-heavy workloads
- Built-in stream processing and backpressure
- Cleaner composition than raw futures

### Validation Strategy
**Decision**: Malli schemas at module boundaries
**Rationale**:
- Runtime validation catches LLM hallucinations
- Schema-based test generation
- JSON Schema export for documentation

### Storage Architecture
**Decision**: Pluggable persistence with SQLite default
**Rationale**:
- SQLite provides ACID guarantees without server
- EDN fallback for simple use cases
- Consistent interface via protocols

### Error Handling
**Decision**: Structured exceptions with ex-info
**Rationale**:
- Rich error context for debugging
- Consistent error handling across components
- Portal integration for error visualization

## Component Relationships

### Signature → Module Flow
1. `defsignature` creates schema-backed data structure
2. Modules reference signatures for input/output validation
3. Validation happens at execution time with clear error messages

### Module → Pipeline Flow
1. Individual modules implement `ILlmModule` protocol
2. Pipeline compiler transforms vector of modules into single function
3. Execution threads context through all modules

### Optimizer → Backend Flow
1. Optimizers generate candidate variations of pipelines
2. Each candidate executes against training data via backend
3. Metrics determine which candidates survive to next iteration

### Persistence Integration
1. Optimization runs auto-persist after each iteration
2. Storage layer handles SQLite transactions and EDN file writes
3. Resume functionality reconstructs optimizer state from storage

## Testing Patterns

### Protocol Testing
- Mock implementations for all protocols
- Property-based tests using Malli generators
- Integration tests with real backends (feature-flagged)

### Concurrency Testing
- Deterministic testing with manifold.test
- Load testing with realistic concurrent scenarios
- Resource leak detection with memory monitoring

### Schema Testing
- Round-trip validation tests
- Error message quality verification
- Generator-based property testing