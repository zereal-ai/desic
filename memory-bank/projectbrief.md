# Project Brief: desic

## Project Name
**desic** - Declarative Self-Improving Clojure

## Core Purpose
A pure-Clojure implementation of DSPy that brings systematic optimization for LLM pipelines to the JVM ecosystem, eliminating the need for Python interop and manual prompt engineering.

## Key Requirements

### Functional Requirements
1. **Declarative DSL**: Signatures define input/output contracts, modules compose into pipelines
2. **Automatic Optimization**: Beam search and metric-driven compilation improve performance
3. **Schema Validation**: Malli schemas ensure type safety throughout the pipeline
4. **Concurrent Execution**: Manifold-based async with rate limiting and backpressure
5. **Persistent Storage**: SQLite/EDN backends for resumable optimization runs
6. **Live Introspection**: Portal integration for real-time debugging and monitoring

### Non-Functional Requirements
1. **Zero Python Dependencies**: Pure JVM implementation
2. **Production Ready**: Single uberjar deployment with comprehensive error handling
3. **Extensible Architecture**: Plugin system for backends, optimizers, and storage
4. **Developer Experience**: REPL-driven development with rich tooling

## Target Users
- Clojure developers building LLM-powered applications
- Teams needing reliable, scalable AI pipelines
- Organizations wanting to avoid Python/Clojure interop complexity

## Success Criteria
1. Can replace DSPy workflows with equivalent Clojure code
2. Optimization runs complete faster than manual prompt engineering
3. Single uberjar deployment "just works" in production
4. Comprehensive test coverage and CI/CD pipeline
5. Clear documentation and examples

## Scope Boundaries
- **In Scope**: Core DSPy functionality, OpenAI backend, optimization algorithms
- **Out of Scope**: Custom model training, non-LLM ML pipelines, web UI
- **Future Scope**: Additional LLM providers, advanced optimization strategies

## Technical Constraints
- JDK 11+ compatibility
- Async-first design using Manifold
- Functional programming principles
- Zero breaking changes to deps.edn structure