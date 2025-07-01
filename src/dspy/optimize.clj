(ns dspy.optimize
  "Optimization engine for systematic LLM pipeline improvement.

   This namespace provides the core optimization API that automatically searches
   for better prompt/pipeline variants using metric-driven evaluation."
  (:require [manifold.deferred :as d]
            [malli.core :as m]
            [dspy.module :as mod]
            [dspy.storage.core :as storage]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.set :as set]))

;; Schemas for optimization API

(def metric-schema
  "Schema for metric functions - takes actual and expected, returns score 0-1"
  [:fn])

(def trainset-schema
  "Schema for training dataset - non-empty vector of maps with :input and :expected"
  [:and
   [:vector {:min 1} [:map
                      [:input :map]
                      [:expected :map]]]
   [:fn (fn [v] (seq v))]])

(def optimization-options-schema
  "Schema for optimization configuration"
  [:map
   [:strategy {:optional true} [:enum :beam :random :grid :identity]]
   [:beam-width {:optional true} [:int {:min 1 :max 20}]]
   [:max-iterations {:optional true} [:int {:min 1 :max 100}]]
   [:concurrency {:optional true} [:int {:min 1 :max 50}]]
   [:checkpoint-interval {:optional true} [:int {:min 1}]]
   [:timeout-ms {:optional true} [:int {:min 1000}]]])

(def optimization-result-schema
  "Schema for optimization results"
  [:map
   [:best-pipeline :any]
   [:best-score [:double {:min 0.0 :max 1.0}]]
   [:history [:vector [:map
                       [:iteration [:int {:min 0}]]
                       [:score [:double {:min 0.0 :max 1.0}]]
                       [:pipeline :any]
                       [:timestamp :int]]]]
   [:total-iterations :int]
   [:total-time-ms :int]
   [:converged? :boolean]])

;; Strategy multimethod registry

(defmulti compile-strategy
  "Compile an optimization strategy from keyword to implementation function.

   Strategy functions have signature:
   (fn [pipeline trainset metric opts] -> deferred<optimization-result>)"
  keyword)

(defmethod compile-strategy :beam [_]
  (fn [pipeline trainset metric opts]
    (require 'dspy.optimize.beam)
    ((resolve 'dspy.optimize.beam/beam-search) pipeline trainset metric opts)))

(defmethod compile-strategy :random [_]
  "Random strategy placeholder - not yet implemented"
  (fn [_pipeline _trainset _metric _opts]
    ;; TODO: Implement proper random search optimization
    ;; For now, throw clear error indicating incomplete implementation
    (throw (ex-info "Random strategy not yet implemented"
                    {:phase :optimize
                     :error :unimplemented-strategy
                     :strategy :random
                     :suggestion "Use :beam or :identity strategy instead"}))))

(defmethod compile-strategy :identity [_]
  "Identity strategy for testing - returns pipeline unchanged"
  (fn [pipeline _trainset _metric _opts]
    (let [start-time (System/currentTimeMillis)]
      ;; Add small delay to ensure measurable time for tests
      (Thread/sleep 1)
      (d/success-deferred
       {:best-pipeline pipeline
        :best-score 0.0
        :history [{:iteration 0 :score 0.0 :pipeline pipeline :timestamp start-time}]
        :total-iterations 1
        :total-time-ms (max 1 (- (System/currentTimeMillis) start-time))
        :converged? true}))))

(defmethod compile-strategy :default [strategy]
  (throw (ex-info "Unknown optimization strategy"
                  {:phase :optimize
                   :error :unknown-strategy
                   :strategy strategy
                   :available-strategies [:beam :random :identity]})))

;; Core optimization API

(defn get-strategy-fn
  "Get the strategy function for the given strategy keyword."
  [strategy]
  (compile-strategy strategy))

(defn optimize
  "Optimize a pipeline using metric-driven search.

   Arguments:
     pipeline - Initial pipeline to optimize
     trainset - Training data (vector of {:input {...} :expected {...}})
     metric - Scoring function (fn [actual expected] -> score 0-1)
     opts - Optimization options

   Options:
     :strategy - Optimization strategy (:beam, :random, :grid)
     :beam-width - Number of candidates to keep per iteration (default: 4)
     :max-iterations - Maximum optimization iterations (default: 10)
     :concurrency - Parallel evaluation limit (default: 8)
     :checkpoint-interval - Save progress every N iterations (default: 5)
     :timeout-ms - Total optimization timeout (default: 300000)

   Returns:
     Deferred that resolves to optimization results map

   Example:
     (optimize my-pipeline training-data exact-match-metric
               {:strategy :beam :beam-width 6 :max-iterations 20})"
  [pipeline trainset metric {:keys [strategy] :or {strategy :beam} :as opts}]

  ;; Validate inputs
  (when-not (m/validate trainset-schema trainset)
    (throw (ex-info "Invalid training dataset"
                    {:phase :optimize
                     :error :invalid-trainset
                     :expected trainset-schema
                     :actual trainset})))

  (when-not (m/validate optimization-options-schema opts)
    (throw (ex-info "Invalid optimization options"
                    {:phase :optimize
                     :error :invalid-options
                     :expected optimization-options-schema
                     :actual opts})))

  ;; Dispatch to strategy implementation
  (let [strategy-fn (get-strategy-fn strategy)]
    (d/chain
     (strategy-fn pipeline trainset metric opts)
     (fn [result]
       (log/info "Optimization completed"
                 {:strategy strategy
                  :iterations (:total-iterations result)
                  :best-score (:best-score result)
                  :converged? (:converged? result)})
       result))))

;; Utility functions

(defn evaluate-pipeline
  "Evaluate a pipeline against training data using the given metric.

   Returns deferred that resolves to average score across all examples."
  [pipeline trainset metric]
  (let [start-time (System/currentTimeMillis)]
    (d/chain
     (d/zip (map (fn [{:keys [input expected]}]
                   (d/chain
                    (mod/call pipeline input)
                    (fn [actual] (metric actual expected))))
                 trainset))
     (fn [scores]
       (let [score-vec (vec scores)
             numeric-scores (filter number? score-vec)
             avg-score (if (empty? numeric-scores)
                         0.0
                         (/ (reduce + numeric-scores) (count numeric-scores)))
             eval-time (- (System/currentTimeMillis) start-time)]
         (log/debug "Pipeline evaluation completed"
                    {:score avg-score
                     :examples (count trainset)
                     :eval-time-ms eval-time})
         avg-score)))))

(defn exact-match-metric
  "Built-in metric that checks for exact equality of outputs.

   Returns 1.0 if actual equals expected, 0.0 otherwise."
  [actual expected]
  (if (= actual expected) 1.0 0.0))

(defn semantic-similarity-metric
  "Built-in metric that computes semantic similarity between text outputs.

   Uses simple word overlap for now - can be enhanced with embeddings later."
  [actual expected]
  (let [actual-text (str (:text actual (:answer actual actual)))
        expected-text (str (:text expected (:answer expected expected)))
        actual-words (set (str/split (str/lower-case actual-text) #"\s+"))
        expected-words (set (str/split (str/lower-case expected-text) #"\s+"))
        intersection (set/intersection actual-words expected-words)
        union (set/union actual-words expected-words)]
    (if (empty? union)
      0.0
      (double (/ (count intersection) (count union))))))

;; Checkpoint and persistence helpers

;; Global storage instance - can be configured via environment
(def ^:dynamic *storage*
  "Dynamic storage instance for optimization persistence.
   Can be rebound for testing or different storage configurations."
  (delay (storage/make-storage (storage/env->storage-cfg))))

(defn save-checkpoint
  "Save optimization progress to persistent storage.

   Args:
     run-id - Unique run identifier
     iteration - Current iteration number
     result - Optimization result map containing :best-pipeline, :best-score, etc.

   The checkpoint includes the full optimization state needed for resumption."
  [run-id iteration result]
  (try
    (let [storage-impl @*storage*
          checkpoint-data {:iteration iteration
                           :best-pipeline (:best-pipeline result)
                           :best-score (:best-score result)
                           :timestamp (System/currentTimeMillis)
                           :result result}]
      (storage/append-metric! storage-impl run-id iteration
                              (:best-score result) checkpoint-data)
      (log/debug "Checkpoint saved" {:run-id run-id :iteration iteration :score (:best-score result)})
      result)
    (catch Exception e
      (log/warn e "Failed to save checkpoint" {:run-id run-id :iteration iteration})
      result)))

(defn load-checkpoint
  "Load optimization checkpoint from persistent storage.

   Args:
     run-id - Unique run identifier

   Returns:
     Checkpoint map with :iteration, :best-pipeline, :best-score, etc.
     or nil if no checkpoint exists."
  [run-id]
  (try
    (let [storage-impl @*storage*
          history (storage/load-history storage-impl run-id)]
      (when (seq history)
        (let [latest-checkpoint (last history)]
          (log/debug "Checkpoint loaded" {:run-id run-id
                                          :iteration (:iter latest-checkpoint)
                                          :score (:score latest-checkpoint)})
          (:payload latest-checkpoint))))
    (catch Exception e
      (log/warn e "Failed to load checkpoint" {:run-id run-id})
      nil)))

(defn create-optimization-run
  "Create a new optimization run with persistent storage.

   Args:
     pipeline - Initial pipeline to optimize
     opts - Optimization options (may include :run-id)

   Returns:
     Run ID for tracking this optimization"
  [pipeline opts]
  (try
    (let [storage-impl @*storage*
          run-id (or (:run-id opts) (str (java.util.UUID/randomUUID)))]
      (storage/create-run! storage-impl
                           {:pipeline pipeline
                            :opts opts
                            :created-at (System/currentTimeMillis)})
      (log/info "Created optimization run" {:run-id run-id})
      run-id)
    (catch Exception e
      (log/warn e "Failed to create optimization run, continuing without persistence")
      (str (java.util.UUID/randomUUID)))))

(defn resume-optimization
  "Resume optimization from saved checkpoint.

   Args:
     run-id - Run identifier to resume
     pipeline - Fallback pipeline if no checkpoint exists
     trainset - Training dataset
     metric - Scoring metric function
     opts - Optimization options

   Continues from last saved iteration with same configuration."
  [run-id pipeline trainset metric opts]
  (if-let [checkpoint (load-checkpoint run-id)]
    (do (log/info "Resuming optimization from checkpoint"
                  {:run-id run-id
                   :iteration (:iteration checkpoint)
                   :score (:best-score checkpoint)})
        (optimize (:best-pipeline checkpoint) trainset metric
                  (assoc opts
                         :run-id run-id
                         :start-iteration (:iteration checkpoint))))
    (do (log/info "No checkpoint found, starting fresh optimization" {:run-id run-id})
        (optimize pipeline trainset metric (assoc opts :run-id run-id)))))