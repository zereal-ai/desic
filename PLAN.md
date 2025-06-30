# desic - Clojure's take on DSPy

> **Purpose** – This file is the *complete*, self-contained engineering blueprint for a pure-Clojure rewrite of DSPy.
> Reading only this document, a dev can scaffold, code, test, package, and ship the project from an empty Git repo.

---

## 0 · Global Technical Decisions

| Aspect | Decision | Why |
|--------|----------|-----|
| **Language / Runtime** | Clojure (JDK) | Latest stable, clearer errors |
| **Build & Deps** | `deps.edn` + `tools.build` | Idiomatic, no extra DSL |
| **Async / Concurrency** | **Manifold** | Deferreds + streams with back-pressure |
| **Schema / Validation** | **Malli** | Generators, JSON-Schema export, fast |
| **LLM Client** | **openai-clojure** | JVM HTTP client, zero Python |
| **Persistence (opt.)** | SQLite via `next.jdbc` (fallback EDN) | Free, file-based; EDN for quick start |
| **REPL Tooling** | nREPL + Socket REPL + Portal | Editor-agnostic; live tapping |
| **Testing** | Kaocha | Watch mode, rich diff |
| **Static Analysis** | clj-kondo | CI enforced |
| **CI** | GitHub Actions (Ubuntu) | Free minutes, caches Maven & gitlibs |
| **Packaging** | Uberjar via tools.build | Runs anywhere with Java |

Secrets are injected via environment variables (e.g. `OPENAI_API_KEY`).

---

## 1 · Folder / Namespace Layout

```
├── build.clj               ; tools.build tasks (lint, test, uber)
├── deps.edn
├── Makefile                ; convenience cmds (repl, ci, uber)
├── README.md
├── resources/
│   └── pipeline.edn        ; sample declarative pipeline
├── src/
│   └── dspy/
│       ├── core.clj        ; public façade
│       ├── signature.clj   ; defsignature macro
│       ├── module.clj      ; ILlmModule protocol + records
│       ├── backend/
│       │   ├── protocol.clj
│       │   ├── openai.clj
│       │   └── wrappers.clj ; retry, throttle
│       ├── pipeline.clj    ; DAG compile & run
│       ├── optimize.clj    ; beam search engine
│       ├── storage.clj     ; EDN + SQLite
│       ├── util/
│       │   └── manifold.clj
│       └── tap.clj         ; Portal helpers
├── test/ …                 ; mirrors src/ tree
└── .github/workflows/ci.yml
```

---

## Milestone 0 – Scaffolding & Dev Environment

### 0-1 · Generate project skeleton

**Paths:** `deps.edn`, `build.clj`, `Makefile`

**Steps:**
1. `clj -Ttools new :template app :name dspy-clj`
2. Delete `project.clj`; add the `deps.edn` shown below
3. Add Makefile targets `repl`, `lint`, `test`, `uber`

**Tests:** `make lint`, `make test` run clean

**DoD:** Repo matches *Folder Layout*; REPL launches with Portal visible.

### 0-2 · CI bootstrap

**Paths:** `.github/workflows/ci.yml`

**Steps:** Copy YAML from *Appendix A*; enable caches for Maven & gitlibs.

**Tests:** GitHub Action green on PR

**DoD:** CI badge added to README.

### 0-3 · Editor-agnostic REPL

**Paths:** `Makefile`, `README.md`

**Steps:**
1. `make repl` ⇒ nREPL + Portal
2. Document CIDER & Calva connection strings

**Tests:** Manual: connect and `(tap> :ok)` appears in Portal.

**DoD:** Docs peer-verified.

### Required `deps.edn` skeleton

```edn
{:paths  ["src" "resources" "test"]
 :deps   {org.clojure/clojure {:mvn/version "RELEASE"}}
 :aliases
 {:dev   {:extra-paths ["dev"]
          :extra-deps  {com.github.jpmonettas/portal {:mvn/version "RELEASE"}
                        nrepl/nrepl                {:mvn/version "RELEASE"}}}
  :test  {:extra-deps {lambdaisland/kaocha {:mvn/version "RELEASE"}}
          :main-opts  ["-m" "kaocha.runner"]}
  :lint  {:extra-deps {clj-kondo/clj-kondo {:mvn/version "RELEASE"}}
          :main-opts  ["-m" "clj-kondo.main" "--lint" "src" "test"]}
  :build {:extra-deps {io.github.clojure/tools.build {:git/tag "RELEASE" :git/sha "…"}}
          :ns-default build}}}
```

---

## Milestone 1 – Core DSL

Goal: provide the declarative language layer (signatures, modules, pipelines) so later milestones can compile, optimize, and run AI workflows.

### 1-1 · `defsignature` Macro

**Paths:** `src/dspy/signature.clj`

**Steps:**
1. `ns` requires: `[malli.core :as m]`, `[malli.generator :as mg]`, `[clojure.string :as str]`
2. Implement helper `arrow->map` that converts forms such as `(question => answer)` into `{:inputs [:question] :outputs [:answer]}`
3. Write macro:

```clojure
(defmacro defsignature [name arrows & doc]
  (let [sig-map (arrow->map arrows)
        schema  (m/schema [:map
                           [:question string?]
                           [:answer   string?]])]   ;; build programmatically later
    `(do (def ~name (with-meta ~sig-map {:malli/schema ~schema}))
         (alter-var-root #'dspy.signature/registry assoc '~name ~name))))
```

4. Create `registry` atom that maps symbol → signature map

**Acceptance Tests:** Unit test file `test/dspy/signature_test.clj`: `(defsignature QA (question => answer))` then `(is (m/validate (:malli/schema (meta QA)) {:question "hi" :answer "hello"}))`

**DoD:** *Macro* expands to pure EDN, is readable via `pprint`, and entries appear in `registry`.

### 1-2 · `ILlmModule` Protocol & Default Record

**Paths:** `src/dspy/module.clj`

**Protocol:**

```clojure
(defprotocol ILlmModule
  (-execute [this input ctx])   ;; ctx carries backend, opts, etc.
  (-compile [this opts]))       ;; returns compiled module (often `this`)
```

**Default Record:**

```clojure
(defrecord FnModule [fn* sig]
  ILlmModule
  (-execute [_ input _] (fn* input))
  (-compile [m _] m))
```

**Utility fn:** `(defn wrap-fn [sig f] (->FnModule f sig))`

**Tests:** `wrap-fn` around `inc`: `(is (= 2 (-> (wrap-fn nil inc) (-execute 1 nil))))`

**DoD:** Protocol loads; `FnModule` passes tests; docstrings present.

### 1-3 · Pipeline Composer

**Paths:** `src/dspy/pipeline.clj`

**Data model:** A pipeline is a vector (linear) or nested vectors (branches) of `ILlmModule`s.

**Function:**

```clojure
(defn compile-pipeline [modules opts]
  (let [compiled (mapv #(-compile % opts) modules)]
    (fn run [input ctx]
      (reduce (fn [v m] (-execute m v ctx)) input compiled))))
```

**Branching:** If element is a vector, recurse and merge results via `(merge-with into)`; provide helper `branch?`.

**Tests:**
1. Linear: add-1 then add-2 returns 3
2. Branch: `[add1 [add2 add3]]` returns `{:main 2 :a 3 :b 4}` (example)

**DoD:** Handles linear + branched DAGs, 90% coverage.

### 1-4 · EDN Config Loader

**Paths:** `src/dspy/pipeline.clj` (same ns)

**Loader fn:**

```clojure
(defmulti make-module :module/type)
(defmethod make-module :fn [_ {:keys [signature body]}]
  (wrap-fn signature (eval (read-string body))))

(defn load-pipeline [path]
  (-> path slurp read-string (mapv make-module)))
```

**Config example** (`resources/pipeline.edn`):

```edn
[{:module/type :fn
  :signature  :QA
  :body       "(fn [{:keys [question]}] {:answer (str question \"!!!\")})"}]
```

**Tests:** Load sample EDN, feed `{:question "Hi"}`; expect `{:answer "Hi!!!"}`

**DoD:** Unknown `:module/type` throws `ex-info`; loader documented.

## Milestone 2 – LLM Back-End Integration

Goal: provide an extensible back-end abstraction (protocol + registry) and a first concrete implementation using **openai-clojure**. All later code (optimizer, modules) must interact only with the protocol, never the concrete client.

### 2-1 · `ILlmBackend` Protocol

**Paths:** `src/dspy/backend/protocol.clj`

**Steps:**
1. Create namespace: `(ns dspy.backend.protocol (:require [manifold.deferred :as d]))`
2. Define protocol:

```clojure
(defprotocol ILlmBackend
  (-generate  [this prompt opts]  ;; -> deferred {:text "…" :usage {:prompt 12 :completion 32}}
              "Single completion (async).")
  (-embeddings [this text opts]   ;; -> deferred {:vector [...]}
              "Return embedding vector.")
  (-stream    [this prompt opts]  ;; nullable deferred that emits pieces
              "Optional server-sent stream."))
```

3. Provide public wrapper fns in same ns: `generate`, `embeddings`, `stream` that call the protocol methods and default opts to `{}`

**Tests:** Mock record: `(defrecord EchoBackend [] ILlmBackend (-generate [_ p _] (d/success-deferred {:text p})))` `(is (= "hi" (:text @(generate (->EchoBackend) "hi"))))`

**DoD:** Protocol compiles, docstrings present, unit test passes.

### 2-2 · OpenAI Backend Implementation

**Paths:** `src/dspy/backend/openai.clj`

**Dependencies:** Add to `deps.edn` root: `com.github.openai-clojure/openai {:mvn/version "RELEASE"}`

**Steps:**
1. `(ns dspy.backend.openai (:require [dspy.backend.protocol :as bp] [openai-clojure.api :as oa] [manifold.deferred :as d]))`
2. Read key & default model: `(def ^:private api-key (or (System/getenv "OPENAI_API_KEY") (throw (ex-info "Missing OPENAI_API_KEY" {}))))`
3. Record definition:

```clojure
(defrecord OpenAIBackend [model]
  bp/ILlmBackend
  (-generate [_ prompt {:keys [temperature max-tokens] :or {temperature 0.7 max-tokens 512}}]
    (d/future
      (-> (oa/create-chat-completion {:api-key api-key
                                       :model model
                                       :messages [{:role "user" :content prompt}]
                                       :temperature temperature
                                       :max-tokens max-tokens})
          :choices first :message :content
          (hash-map :text))))
  (-embeddings [_ text _]
    (d/future
      (-> (oa/create-embeddings {:api-key api-key :model "text-embedding-3-small" :input text})
          :data first :embedding vector?)))
  (-stream [_ _ _] nil))
```

4. Constructor fn: `(defn ->backend [& [model]] (->OpenAIBackend (or model "gpt-4o-mini")))`

**Tests:** Use `clj-http.fake`: stub POST to `/chat/completions` returning minimal JSON; assert `(:text @(generate (->backend "gpt-test") "Hello"))` equals expected.

**DoD:** Fully async, no blocking I/O, handles missing key gracefully, test suite green.

### 2-3 · Backend Registry & Dynamic Loading

**Paths:** extend `dspy/backend/protocol.clj`

**Steps:**
1. Create multimethod: `(defmulti create-backend (fn [{:keys [type]}] type))`
2. `defmethod` for `:openai`: `(defmethod create-backend :openai [{:keys [model]}] (dspy.backend.openai/->backend model))`
3. Default: `(defmethod create-backend :default [cfg] (throw (ex-info "Unknown backend" cfg)))`

**Tests:** `(is (satisfies? bp/ILlmBackend (create-backend {:type :openai})))`

**DoD:** Users can register new backends via `derive` or additional `defmethod`s without touching core code.

### 2-4 · Retry & Throttle Wrappers

**Paths:** `src/dspy/backend/wrappers.clj`

**Steps:**
1. `(ns dspy.backend.wrappers (:require [manifold.deferred :as d] [manifold.stream :as s] [dspy.backend.protocol :as bp]))`
2. `wrap-throttle`: `(defn wrap-throttle [backend {:keys [rps]}] (let [gate (s/throttle rps 1000)] (reify bp/ILlmBackend (-generate [_ p o] (s/put! gate true) (bp/-generate backend p o)) ...)))`
3. `wrap-retry`: exponential back-off with jitter using recursion + `d/chain`
4. Compose helpers `(defn with-middlewares [backend cfg] (-> backend (wrap-throttle (:throttle cfg)) (wrap-retry (:retry cfg))))`

**Tests:**
1. Fake backend fails first 2 times, succeeds third; `wrap-retry` returns success
2. Throttle test: timestamp 5 calls @3 rps, ensure elapsed ≥ 2s

**DoD:** Stateless wrappers return new objects satisfying `ILlmBackend`; fully composable.

### 2-5 · Config-Driven Backend Selection

**Paths:** add to `src/dspy/pipeline.clj`

**Steps:**
1. When loading pipeline EDN root map, read key `::pipeline/backend {:type :openai :model "gpt-4o-mini" :throttle {:rps 3}}`
2. Call `(create-backend cfg)`, then `(with-middlewares backend cfg)`
3. Store in pipeline context that is threaded through every module execute

**Tests:** Sample EDN loads and `(generate backend "Hi")` works.

**DoD:** If backend not specified, default to `{:type :openai}`. Clear error if config invalid.

### 2-6 · Smoke Test Workflow

**Paths:** `test/dspy/integration_smoke_test.clj`

**Prereq:** Requires real `OPENAI_API_KEY`; guard with env var `RUN_LIVE_TESTS=true`.

**Steps:**
1. Load tiny signature/pipeline that echoes a question
2. optimize over 3 examples (metric exact-match)
3. Assert optimizer returns improved score > 0

**DoD:** Running `clj -M:test` locally with the env flag hits real API once and passes. CI skips if flag absent.

## Milestone 3 – optimizer / Teleprompter Engine

Goal: implement a metric-driven optimization layer that automatically searches for better prompt/pipeline variants. Everything below relies only on the DSL & backend abstractions from Milestones 1-2.

### 3-1 · Search API Skeleton

**Paths:** `src/dspy/optimize.clj`

**Public fn:**

```clojure
(defn optimize
  "Return {:best-pipeline compiled-pipeline
           :history       [ {:iter 0 :score 0.42 :pipeline …} … ]}"
  [pipeline trainset metric {:keys [strategy] :or {strategy :beam} :as opts}])
```

**Steps:**
1. `ns` requires `[manifold.deferred :as d]`, `[malli.core :as m]`, `[dspy.backend.protocol :as bp]`
2. Validate that `trainset` is a seq of maps fitting pipeline input spec (use Malli)
3. Dispatch to `(compile-strategy strategy)` multimethod (see ticket 3-3)

**Tests:** Stub strategy that returns pipeline unchanged; ensure `(optimize p trainset metric {})` returns same pipeline & history length ≥ 1.

**DoD:** Function documented, argument validation errors are `ex-info` with `:phase :optimize`.

### 3-2 · Beam-Search Strategy

**Paths:** `src/dspy/optimize/beam.clj` (separate ns)

**Algorithm:** Iterative widening beam search over prompt candidates produced by a *prompt-mutator* fn (plug later).

**Key Fns:** `next-candidates`, `score-candidate`, `select-top-k`.

**Concurrency:** Use `manifold.deferred/zip` to score up to N candidates in parallel (N = `(:concurrency opts)` default 8).

**Steps:**
1. Generate initial candidate list from baseline pipeline
2. For each round:
   a. Score all candidates on `trainset` (exact-match or semantic-F1 metric)
   b. Keep top-`k` (`:beam-width`, default 4)
   c. Mutate them for next round (`:depth` rounds, default 3)
3. Record `{:iter i :score s :pipeline p}` into history vec

**Tests:**
1. Dummy mutator that appends "!" to every prompt; expect final score > initial
2. Property test: every history item's score ≥ previous item's score

**DoD:** 95% branch coverage, bench shows linear speed-up with concurrency parameter.

### 3-3 · Strategy Multimethod Registry

**Paths:** `src/dspy/optimize.clj` (same ns as API)

**Code:**

```clojure
(defmulti compile-strategy keyword)
(defmethod compile-strategy :beam [_] dspy.optimize.beam/strategy)
```

**Extensibility:** Down-stream libs can `(defmethod compile-strategy :bayes [_] …)` without modifying core.

**Tests:** Unknown keyword throws `(ex-info "Unknown strategy" …)`.

**DoD:** At least two strategy keys present (`:beam`, `:identity` for stub).

### 3-4 · Checkpoint Hooks & Persistence

**Paths:** `src/dspy/tap.clj`, `src/dspy/storage.clj`

**Steps:**
1. From inside the beam loop call `(tap> {:event :optimizer/iter :data state})` so Portal users can watch progress
2. Persist after each iteration: `(storage/append-metric! run-id state)` where storage toggles EDN or SQLite
3. Provide `(resume-run run-id)` in `optimize.clj` that loads last checkpoint and continues

**Tests:**
1. Unit: after three iterations EDN file has three entries
2. Kill-resume test: interrupt optimizer at iter 2, restart with same run-id; it continues at iter 3

**DoD:** Resumable optimization; Portal shows real-time progress; storage write performance < 5 ms per iteration.

## Milestone 4 – Concurrency & Rate-Limit Management

Goal: guarantee that large batches of LLM calls respect provider rate-limits, run efficiently in parallel, and can be cancelled or timed-out cleanly.

### 4-1 · `wrap-throttle` – Rate-Limit Wrapper

**Paths:** `src/dspy/backend/wrappers.clj`

**Fn:**

```clojure
(defn wrap-throttle
  "Returns a backend that enforces :rps requests-per-second."
  [backend {:keys [rps] :or {rps 3}}])
```

**Implementation steps:**
1. Require `[manifold.stream :as s]`
2. Create token stream: `(def gate (s/throttle rps 1000))`
3. Return `reify ILlmBackend` whose `-generate` does `(s/put! gate true)`, waits on deferred, then delegates to underlying backend
4. Duplicate logic for `-embeddings` and `-stream`

**Tests:**
1. Fake backend sleeps 10 ms; call generate 6× with `rps = 3`; assert elapsed ≥ 2000 ms
2. Ensure wrapper preserves result equality

**DoD:** Measured throughput complies with configured `rps`; wrapper composes with `wrap-retry`.

### 4-2 · `parallel-map` Helper

**Paths:** `src/dspy/util/manifold.clj`

**Fn:**

```clojure
(defn parallel-map
  "Map f over coll with at-most n concurrent operations; returns deferred of vector."
  [n f coll])
```

**Implementation steps:**
1. Use `manifold.deferred/loop` + `take`, or simpler: `(->> coll (partition-all n) (mapv #(apply d/zip (map f %))))` then `d/zip`
2. Propagate earliest exception & cancel remaining deferreds
3. Provide `(def default-parallel 8)` var; obey env `DSPY_PARALLELISM` override

**Tests:**
1. Use `(range 100)`, f adds 1 after 5 ms; with n = 10, total wall time ≈ (100/10)*5 ms
2. Inject fault on 50th element; ensure returned deferred errors and remaining fns not executed

**DoD:** No deadlocks, supports large collections (>10k) without OOM, documented in README.

### 4-3 · Cancellation & Timeout Utilities

**Paths:** `src/dspy/util/manifold.clj` (same ns)

**Fns:**

```clojure
(defn with-timeout [ms d]
  (d/alt d (d/timeout ms) :timeout))

(defn cancellable [d cancel-fn]
  (d/chain' d identity :cancel cancel-fn))
```

**Implementation steps:**
1. Wrap Manifold `timeout`; return `{:status :ok :value v}` or `{:status :timeout}`
2. For any `-generate` call exceeding `:timeout-ms` in opts, use `with-timeout`
3. For streaming calls, hold a `close!` fn in ctx; on cancel propagate to HTTP client

**Tests:**
1. Backend delayed 2 s, call with 500 ms timeout; expect `:timeout`
2. Ensure resources freed: stream closed (`(s/closed? stream?)`)

**DoD:** All generate/embedding helpers accept `:timeout-ms` and `:cancel?`; memory stable under `jcmd GC.run`.

## Milestone 5 – Validation & Instrumentation

Goal: guarantee that every module respects its declared input/output schema and provide rich, live introspection via Portal (or any `tap>` viewer) during optimization and runtime.

### 5-1 · Malli Spec Registry

**Paths:** `src/dspy/signature.clj`

**Steps:**
1. Add global `registry` atom: `(def registry (atom {}))`
2. Enhance `defsignature` macro: after defining the var, `(swap! registry assoc '~name ~name)`
3. Public fns: `(defn spec-of [sig] (-> (registry sig) meta :malli/schema))` `(defn validate [sig value] (m/validate (spec-of sig) value))`

**Tests:**
1. `(defsignature QA (question => answer))` then `(is (validate :QA {:question "hi" :answer "hey"}))`
2. Negative test: wrong map fails

**DoD:** Registry returns correct schemas; validation helper used by pipeline composer to assert inputs/outputs at runtime.

### 5-2 · Portal Integration

**Paths:** `src/dspy/tap.clj`

**Steps:**
1. `(ns dspy.tap (:require [portal.api :as p]))`
2. `(defonce ^:private portal-instance (p/open))` when `:dev` alias activates
3. Provide `(defn install! [] (add-tap #'p/submit))` and call it from `user.clj` in `dev/` folder
4. Emit events:
   • from modules: `(tap> {:event :module/exec :module (type m) :in input :out result})`
   • from optimizer loop: see Milestone 3, ticket 3-4

**Tests:** Manual QA: start `make repl`, evaluate `(tap> {:foo 1})` and confirm entry appears in Portal.

**DoD:** Portal opens automatically in `:dev`; can be disabled via env `DSPY_NO_PORTAL`.

### 5-3 · Property Tests with Generated Data

**Paths:** `test/dspy/property_test.clj`

**Steps:**
1. For each signature in `registry`, build generator via `malli.generator/generator`
2. For `ILlmModule` records that declare `:sig` metadata, run 100 random inputs through `-execute`; validate output against module's output spec
3. Use `test.check` runner inside Kaocha

**Tests:** Automatically created by property suite; build should fail if any module violates its contract.

**DoD:** At least 100 trials per module; suite passes; CI target branch must have zero spec violations or merge is blocked.

## Milestone 6 – Persistence Layer

Goal: persist optimization runs, metrics, and pipeline blobs so they can be resumed or audited later. Two interchangeable back-ends are provided:

* **SQLite** via `next.jdbc` – default in production, file-based, zero server.
* **EDN file** – simplest, on by default in dev tests or where no DB is wanted.

Backend is selected by key `{:storage {:type :sqlite :url "jdbc:sqlite:dspy.db"}}` (or `{:storage {:type :file :dir "./runs"}}`) in the root EDN config.

### 6-1 · Schema Migration (SQLite)

**Paths:** `resources/sql/schema.sql`, `src/dspy/storage/sqlite.clj`

**Schema:**

```sql
CREATE TABLE IF NOT EXISTS runs (
  id            TEXT PRIMARY KEY,
  created_at    INTEGER,
  pipeline_blob TEXT
);

CREATE TABLE IF NOT EXISTS metrics (
  run_id     TEXT,
  iter       INTEGER,
  score      REAL,
  payload    TEXT,
  PRIMARY KEY (run_id, iter)
);
```

**Steps:**
1. Add dep: `org.xerial/sqlite-jdbc`
2. `(defn migrate! [ds])` reads `schema.sql`, splits on `;`, executes via `next.jdbc/execute!`
3. Hook `migrate!` in `init-sqlite` which returns `{:ds ds}` map

**Acceptance Tests:** Run `migrate!` on fresh tmp db, assert both tables exist via PRAGMA.

**DoD:** Migration idempotent (rerun no error), completes < 50 ms on empty DB.

### 6-2 · DAO Namespace

**Paths:** `src/dspy/storage/core.clj` (high-level façade); `src/dspy/storage/sqlite.clj`; `src/dspy/storage/edn.clj`

**API:**

```clojure
(defprotocol Storage
  (create-run!   [this pipeline])
  (append-metric! [this run-id iter score payload])
  (load-run      [this run-id])
  (load-history  [this run-id]))

(defn make-storage [cfg] …) ; returns implementation satisfying protocol
```

**SQLite impl:** use `next.jdbc` prepared statements; `pipeline_blob` stored as `(pr-str pipeline-edn)`.

**EDN impl:** Each run has dir `runs/<id>/`, file `pipeline.edn` + `history.edn` (vector of maps). Functions are simple `spit/slurp`.

**Tests:** Common `storage-test.clj` run twice: once with `:file` backend, once with in-memory SQLite (`jdbc:sqlite::memory:`). Ensure round-trip equality of history data.

**Performance target:** append-metric ≤ 5 ms on laptop SSD for SQLite; EDN ≤ 2 ms.

**DoD:** `make-storage` selected via config; optimizer (Milestone 3) persists through the protocol only.

### 6-3 · EDN Fallback Toggle

**Paths:** `src/dspy/storage/core.clj`

**Steps:**
1. If `cfg :type` missing, default to `:file` under `./runs`
2. Provide helper `env->storage-cfg`: reads `DSPY_STORAGE` env (`"sqlite://./dspy.db"` or `"file://./runs"`)

**Tests:**
1. No storage config given → returns EDN backend
2. `DSPY_STORAGE=sqlite://:memory:` env → returns SQLite backend

**DoD:** Fallback documented in README; optimizer resume works under both back-ends.

## Milestone 7 – Packaging & Release

Goal: produce a single self-contained **uberjar** (`dspy-clj-standalone.jar`) plus a thin CLI wrapper, and automate release artifacts on git tags.

### 7-1 · `build.clj` – Uberjar Task

**Paths:** `build.clj` (repo root)

**Dependencies:** Already declared in `deps.edn` alias `:build` (`io.github.clojure/tools.build`).

**Tasks:**

```clojure
(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.example/dspy-clj)
(def version (or (System/getenv "DSPY_VERSION")
                 (format "SNAPSHOT.%s" (b/git-count-revs nil))))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file (format "target/%s-%s-standalone.jar"
                       (name lib) version))

(defn clean [_] (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/compile-clj {:basis basis :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     basis
           :main      'dspy.cli})
  (println "Built" uber-file))
```

**Steps:**
1. Paste snippet, adjust `lib` coordinate
2. Add default target to Makefile: `make uber → clj -T:build uber`

**Tests:** `make uber` completes; `java -jar target/dspy-clj-*.jar --help` prints CLI banner.

**DoD:** Jar includes all dependencies, launches without internet; build time < 15 s on GH Actions runner.

### 7-2 · CLI Wrapper (`dspy.cli`)

**Paths:** `src/dspy/cli.clj`

**Steps:**
1. `(ns dspy.cli (:require [clojure.tools.cli :refer [parse-opts]] [dspy.core :as core]))`
2. Define opts spec: `--compile`, `--optimize`, `--config`, `--out`
3. `(-main & args)` parses, dispatches:
   * compile: `(spit out (pr-str (core/compile-pipeline cfg)))`
   * optimize: `(core/optimize pipeline trainset metric cfg)` then `spit` results
4. Add `:gen-class` so AOT main class lands in uberjar

**Help banner:**

```shell
dspy [subcommand] [options]

Subcommands:
  compile   Compile EDN pipeline to compiled.edn
  optimize  optimize pipeline against trainset

Global options:
  --config FILE   Root config EDN (default: config.edn)
  --out FILE      Output file (default: stdout)
```

**Tests:**
1. `clj -m dspy.cli compile --config resources/pipeline.edn` prints EDN
2. Same via `java -jar target/dspy-…jar`

**DoD:** Options validated; exit code 1 on invalid usage; docstring example works in README.

### 7-3 · Version Tagging & GitHub Release

**Paths:** `.github/workflows/ci.yml` (extend)

**Workflow add-on:**

```yaml
  release:
    needs: clj
    if: startsWith(github.ref, 'refs/tags/')
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@latest
      - uses: DeLaGuardo/setup-clojure@latest
        with: {cli: latest}
      - run: clojure -T:build uber
      - uses: ncipollo/release-action@latest
        with:
          artifacts: "target/*-standalone.jar"
```

**Steps:**
1. Commit & push tag `vX.Y.Z`
2. CI builds uberjar, attaches to release
3. Uberjar filename includes version via `DSPY_VERSION` env

**Tests:** Push lightweight tag on a branch in fork; verify Actions run and release appears with artifact.

**DoD:** `git tag -a vX.Y.Z -m "Release version"` then push → GitHub Release with downloadable jar.

## Milestone 8 – Quality Gates

Goal: make every commit merge-safe by enforcing linting, test coverage, benchmarks, and executable documentation.

### 8-1 · Static Analysis Gate (clj-kondo)

**Paths:** `.github/workflows/ci.yml` (lint step already present)

**Steps:**
1. Add repo-specific `clj-kondo.edn` (e.g. custom Malli macros, lint-as)
2. Pre-commit hook: add `#!/bin/sh\nclj -M:lint` to `.husky/pre-commit`

**Tests:** Introduce unused var in PR → CI fails with level `:warning`.

**DoD:** Zero `:warning` severity in default branch; failing lint blocks merge.

### 8-2 · Benchmark Harness

**Paths:** `test/dspy/bench.clj`, add `:bench` alias in `deps.edn`

**Library:** `criterium/criterium`

**Scenario:** Compare optimizer run (5 iterations, small trainset) **serial vs parallel** (`parallel-map` with 8 concurrency).

**Steps:**
1. Write `(defn bench [] (crit/quick-bench (optimize p ts m {:strategy :beam :concurrency 1})) …)`
2. Add CI step under nightly cron to run `clj -M:bench` and upload results as artifact

**Regression Rule:** If parallel run is > 10% slower than baseline of last run, job fails.

**DoD:** Nightly action passes; developers can run `make bench` locally.

### 8-3 · Executable Documentation Tests

**Paths:** `docs/*.md`, `ci/doc_test.clj`

**Tool:** `lambdaisland/docstr` or simple script parsing ```clojure blocks.

**Steps:**
1. Extract code blocks labelled `clojure`; evaluate in fresh REPL with classpath = project
2. CI step `run: clj -M:doc-test`

**Fail Conditions:** Any code snippet throws or returns `:doc/fail`.

**DoD:** All docs compile in CI; README quick-start verified automatically.

### 8-4 · Coverage Threshold

**Paths:** `ci/coverage.clj`, add to CI

**Library:** `cloverage` via `lein-exec` or `clj-native-image` plugin

**Metric:** Require ≥ 80% line coverage on `src/` excluding `tap.clj`.

**DoD:** Coverage badge in README; PR fails if threshold drops.

### 8-5 · Security Audit (dependency scan)

**Paths:** `.github/workflows/ci.yml`

**Step:** Add `github-actions-ecosystem/action-cat@latest` or `jeremylong/DependencyCheck-action@latest`.

**DoD:** CI fails on known CVEs with severity ≥ HIGH; documented suppression file for false positives.