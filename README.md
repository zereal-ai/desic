# desic
**Declarative Self-Improving Clojure**

> **A pure-Clojure implementation of DSPy** — systematic optimization for LLM pipelines without the prompt engineering chaos.

[![CI](https://github.com/zereal-ai/desic/workflows/CI/badge.svg)](https://github.com/zereal-ai/desic/actions)

⚠️ **Alpha Stage**: This project is in active development and not yet production-ready.

## What is desic?

**desic** (Declarative Self-Improving Clojure) brings DSPy's revolutionary approach to Clojure: **programming**—not prompting—language models. Instead of manually crafting fragile prompt strings, you write declarative code that gets automatically optimized for reliability and performance.

## Why desic?

### **Systematic Optimization**
Stop guessing at prompts. desic's optimizers automatically tune your LLM parameters using beam search, few-shot learning, and metric-driven compilation—often producing better results than hand-crafted prompts.

### **Provider-Agnostic Architecture**
Write your code once, run it with any LLM provider. Clean separation between abstract interfaces and concrete provider implementations means adding new LLM services requires zero changes to your application code.

### **Separation of Concerns**
Write your pipeline logic once. Let desic handle the LLM parameters (prompts, examples, weights) separately, so changes to models or data don't break your entire system.

### **Built for Concurrency**
Native async support with **Manifold** enables efficient parallel LLM calls with automatic rate limiting, retries, and backpressure—essential for production workloads.

### **Schema-First Reliability**
**Malli** schemas validate inputs/outputs at every stage. No more surprise hallucinations breaking downstream processing.

### **Live Introspection**
**Portal** integration provides real-time visibility into optimization runs, module execution, and pipeline performance—debug complex LLM behavior with ease.

### **Resumable Optimization**
Persistent storage (SQLite/EDN) means expensive optimization runs can be paused, resumed, and incrementally improved without starting over.

### **Zero-Dependency Deployment**
Compile to a single uberjar that runs anywhere with Java. No Python runtime, no virtual environments, no dependency hell.

## Core Concepts

```clojure
;; Define behavior declaratively
(defsignature QA (question => answer))

;; Provider-agnostic backend creation
(def backend (bp/create-backend {:provider :openai :model "gpt-4o"}))

;; Compose into pipelines
(defn my-rag [question]
  (-> question
      retrieve-context
      (generate-answer :sig QA)))

;; Optimize automatically
(optimize my-rag trainset exact-match-metric)
```

## Key Differentiators

- **Pure Clojure**: Leverage JVM ecosystem without Python interop complexity
- **Provider-Agnostic**: Switch between OpenAI, Anthropic, and other providers with zero code changes
- **Functional**: Immutable pipelines, composable modules, predictable behavior
- **Production-Ready Design**: Rate limiting, circuit breakers, monitoring, and deployment tools included
- **Extensible**: Plugin architecture for custom backends, optimizers, and storage layers
- **Battle-Tested Libraries**: Built on proven libraries like openai-clojure (229+ GitHub stars)

## Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Signatures    │───▶│     Modules      │───▶│   Optimizers    │
│  (what to do)   │    │  (how to do it)  │    │ (make it better)│
└─────────────────┘    └──────────────────┘    └─────────────────┘
         │                       │                       │
         ▼                       ▼                       ▼
┌─────────────────────────────────────────────────────────────────┐
│              Provider-Agnostic Backend Layer                   │
└─────────────────────────────────────────────────────────────────┘
         │                       │                       │
         ▼                       ▼                       ▼
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│  OpenAI Provider│    │Anthropic Provider│   │ Custom Providers│
│ (openai-clojure)│    │  (coming soon)   │   │ (plug-and-play) │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

Perfect for building reliable RAG systems, multi-agent workflows, and complex reasoning pipelines that need to work consistently at scale.

## Current Status

✅ **Milestones 1-3 Complete** - All tests passing (60 tests, 286 assertions, 0 failures)

🎯 **Major Architectural Achievement** - Provider-agnostic backend system implemented:
- **Clean separation**: Abstract interfaces vs concrete provider implementations
- **Professional library integration**: Using battle-tested openai-clojure library
- **Zero-impact extensibility**: Adding new providers requires zero changes to user code
- **Enterprise-grade architecture**: Provider selection purely configuration-driven

## Development

### Prerequisites
- Java 11+
- Clojure CLI tools
- Babashka (for enhanced task runner)

### Quick Start
```bash
# Install dependencies and run CI pipeline
bb ci

# Start development REPL with Portal and CIDER middleware
bb repl

# Run the full test suite
bb test

# Build a production uberjar
bb uber

# Check project status
bb status
```

### Continuous Integration

The project uses GitHub Actions for CI/CD with the following pipeline:

```bash
# Test the CI locally with act (requires Docker)
act --container-architecture linux/amd64

# The CI pipeline runs:
# 1. Java 11 + Clojure CLI setup
# 2. Dependency caching
# 3. Lint analysis with clj-kondo
# 4. Full test suite with Kaocha
# 5. Uberjar build verification
```

### Available Tasks

Run `bb help` to see all available tasks with enhanced formatting:

- **`bb repl`** - Start development REPL with Portal and CIDER middleware
- **`bb test`** - Run test suite with Kaocha
- **`bb lint`** - Run clj-kondo static analysis
- **`bb ci`** - Run full CI pipeline (lint + test) with progress tracking
- **`bb uber`** - Build standalone uberjar
- **`bb clean`** - Clean build artifacts
- **`bb status`** - Show project status and environment info
- **`bb test-watch`** - Run tests in watch mode
- **`bb repl-simple`** - Start lightweight REPL with Portal only

### REPL-Driven Development

This project follows REPL-driven development practices:

1. Start the REPL: `bb repl`
2. Connect your editor (CIDER, Calva, Cursive, etc.)
3. Evaluate code interactively
4. Use Portal for live data inspection
5. Run tests continuously with `bb test-watch`
6. Use the enhanced CI task for pre-commit verification: `bb ci`

### Environment Setup

Required environment variables:
```bash
export OPENAI_API_KEY="your-key-here"          # Required for OpenAI provider
export RUN_LIVE_TESTS="true"                   # Optional: enables integration tests
export DSPY_PARALLELISM="8"                    # Optional: concurrent request limit
```

### Provider Configuration

Create backends using the provider-agnostic interface:

```clojure
;; OpenAI backend
(def backend (bp/create-backend {:provider :openai
                                 :model "gpt-4o"
                                 :throttle {:rps 3}}))

;; Switch providers with zero code changes (coming soon)
(def backend (bp/create-backend {:provider :anthropic
                                 :model "claude-3-sonnet"}))
```

### Testing

```bash
# Run all tests
bb test

# Run tests with watch mode
bb test-watch

# Run linting
bb lint

# Run full CI pipeline locally
bb ci

# Test with act (requires Docker)
act --container-architecture linux/amd64
```

---

**Alpha Status**: Milestones 1-3 complete with comprehensive test coverage • See [PLAN.md](PLAN.md) for complete implementation roadmap and current progress