(ns dspy.pipeline
  "High-level pipeline composition and execution engine."
  (:require [manifold.deferred :as d]
            [dspy.module :as mod]
            [clojure.set :as set]))

(declare execute-pipeline)

(defrecord Pipeline [stages dependencies metadata]
  mod/ILlmModule
  (call [this inputs]
    (execute-pipeline this inputs)))

;; Pipeline Stage Definition
(defrecord Stage [id module dependencies metadata])

(defn stage
  "Create a pipeline stage with dependencies.

  Usage:
    (stage :tokenizer tokenizer-module)
    (stage :processor processor-module [:tokenizer])
    (stage :formatter formatter-module [:processor] {:description \"Final formatting\"})"
  ([id module] (->Stage id module [] {}))
  ([id module dependencies] (->Stage id module dependencies {}))
  ([id module dependencies metadata] (->Stage id module dependencies metadata)))

(defn- validate-pipeline
  "Validate pipeline structure for cycles and missing dependencies."
  [stages]
  (let [stage-ids (set (map :id stages))
        all-deps (mapcat :dependencies stages)]

    ;; Check for missing dependencies
    (let [missing-deps (set/difference (set all-deps) stage-ids)]
      (when (seq missing-deps)
        (throw (ex-info "Pipeline has missing dependencies"
                        {:missing-dependencies missing-deps}))))

    ;; Simple cycle detection using topological sort attempt
    (loop [remaining stages
           resolved #{}
           iterations 0]
      (if (> iterations (count stages))
        (throw (ex-info "Pipeline contains cycles"
                        {:remaining-stages (map :id remaining)}))
        (let [resolvable (filter #(set/subset? (set (:dependencies %)) resolved) remaining)]
          (if (empty? resolvable)
            (if (empty? remaining)
              true
              (throw (ex-info "Pipeline contains cycles or unresolvable dependencies"
                              {:remaining-stages (map :id remaining)})))
            (recur (remove (set resolvable) remaining)
                   (set/union resolved (set (map :id resolvable)))
                   (inc iterations))))))))

(defn- topological-sort
  "Sort pipeline stages in dependency order."
  [stages]
  (loop [remaining stages
         resolved []
         resolved-ids #{}]
    (if (empty? remaining)
      resolved
      (let [resolvable (filter #(set/subset? (set (:dependencies %)) resolved-ids) remaining)]
        (if (empty? resolvable)
          (throw (ex-info "Cannot resolve stage dependencies"
                          {:remaining-stages (map :id remaining)}))
          (recur (remove (set resolvable) remaining)
                 (concat resolved resolvable)
                 (set/union resolved-ids (set (map :id resolvable)))))))))

(defn- build-execution-plan
  "Build execution plan with parallelization opportunities."
  [sorted-stages]
  (loop [remaining sorted-stages
         plan []
         completed #{}]
    (if (empty? remaining)
      plan
      (let [parallel-batch (filter #(set/subset? (set (:dependencies %)) completed) remaining)]
        (recur (remove (set parallel-batch) remaining)
               (conj plan parallel-batch)
               (set/union completed (set (map :id parallel-batch))))))))

(defn- execute-stage-batch
  "Execute a batch of stages in parallel."
  [stages stage-outputs inputs]
  (let [stage-deferreds
        (into {}
              (map (fn [stage]
                     (let [;; Merge original inputs with outputs from dependency stages
                           stage-inputs (reduce (fn [acc dep-id]
                                                  (merge acc (get stage-outputs dep-id {})))
                                                inputs
                                                (:dependencies stage))]
                       [(:id stage) (mod/call (:module stage) stage-inputs)]))
                   stages))]

    ;; Wait for all stages in batch to complete
    (d/chain
     (apply d/zip (vals stage-deferreds))
     (fn [results]
       (into {} (map vector (keys stage-deferreds) results))))))

(defn- execute-pipeline
  "Execute pipeline with proper dependency resolution and parallelization."
  [pipeline inputs]
  (try
    (validate-pipeline (:stages pipeline))
    (let [sorted-stages (topological-sort (:stages pipeline))
          execution-plan (build-execution-plan sorted-stages)
          ;; Execute each batch sequentially, stages within a batch run in parallel
          stage-outputs (loop [remaining-batches execution-plan
                               accumulated-outputs {}] ; Track outputs by stage ID
                          (if (empty? remaining-batches)
                            accumulated-outputs
                            (let [current-batch (first remaining-batches)
                                  batch-result @(execute-stage-batch current-batch accumulated-outputs inputs)]
                              (recur (rest remaining-batches)
                                     (merge accumulated-outputs batch-result)))))]
      ;; Merge all stage outputs with original inputs  
      (d/success-deferred
       (reduce (fn [acc [_stage-id stage-output]]
                 (merge acc stage-output))
               inputs
               stage-outputs)))

    (catch Exception e
      (d/error-deferred (ex-info "Pipeline execution failed"
                                 {:pipeline pipeline
                                  :inputs inputs
                                  :cause e})))))

(defn compile-pipeline
  "Compile a set of stages into an executable pipeline.

  Usage:
    (compile-pipeline
      [(stage :tokenizer tokenizer-module)
       (stage :embedder embedder-module [:tokenizer])
       (stage :classifier classifier-module [:embedder])]
      {:name \"Text Classification Pipeline\"
       :version \"1.0\"})"
  ([stages] (compile-pipeline stages {}))
  ([stages metadata]
   (validate-pipeline stages)
   (->Pipeline stages
               (into {} (map (fn [stage] [(:id stage) (:dependencies stage)]) stages))
               metadata)))

(defn linear-pipeline
  "Create a simple linear pipeline from a sequence of modules.
  Each module's output becomes the next module's input.

  Usage:
    (linear-pipeline [tokenizer embedder classifier]
                     {:name \"Simple Chain\"})"
  ([modules] (linear-pipeline modules {}))
  ([modules metadata]
   (let [stages (map-indexed
                 (fn [idx module]
                   (stage (keyword (str "stage-" idx))
                          module
                          (if (zero? idx) [] [(keyword (str "stage-" (dec idx)))])))
                 modules)]
     (compile-pipeline stages metadata))))

(defn branched-pipeline
  "Create a pipeline with branching and merging.

  Pipeline definition format:
  {:stages {stage-id {:module module :dependencies [dep1 dep2]}}
   :metadata {...}}

  Usage:
    (branched-pipeline
      {:stages {:input {:module input-processor}
                :branch1 {:module branch1-module :dependencies [:input]}
                :branch2 {:module branch2-module :dependencies [:input]}
                :merge {:module merge-module :dependencies [:branch1 :branch2]}}
       :metadata {:name \"Branched Processing\"}})"
  [{:keys [stages metadata]}]
  (let [pipeline-stages
        (map (fn [[stage-id {:keys [module dependencies metadata]}]]
               (stage stage-id module (or dependencies []) (or metadata {})))
             stages)]
    (compile-pipeline pipeline-stages (or metadata {}))))

(defn get-pipeline-info
  "Get information about a compiled pipeline."
  [pipeline]
  {:stage-count (count (:stages pipeline))
   :stage-ids (map :id (:stages pipeline))
   :dependency-graph (:dependencies pipeline)
   :metadata (:metadata pipeline)})

(defn pipeline-stats
  "Get execution statistics for pipeline stages."
  [pipeline]
  (let [stages (:stages pipeline)
        dep-counts (frequencies (mapcat :dependencies stages))]
    {:total-stages (count stages)
     :independent-stages (count (filter #(empty? (:dependencies %)) stages))
     :most-depended-on (when (seq dep-counts)
                         (key (apply max-key val dep-counts)))
     :max-dependencies (apply max 0 (map #(count (:dependencies %)) stages))
     :dependency-histogram dep-counts}))

;; Convenience functions for common pipeline patterns

(defn map-reduce-pipeline
  "Create a map-reduce style pipeline.

  Usage:
    (map-reduce-pipeline map-module reduce-module partitioner
                         {:parallelism 4})"
  ([map-module reduce-module] (map-reduce-pipeline map-module reduce-module identity {}))
  ([map-module reduce-module partitioner] (map-reduce-pipeline map-module reduce-module partitioner {}))
  ([map-module reduce-module partitioner {:keys [parallelism] :or {parallelism 2} :as metadata}]
   (let [map-stages (for [i (range parallelism)]
                      (stage (keyword (str "map-" i)) map-module [:partition]))
         partition-stage (stage :partition (mod/fn-module partitioner))
         reduce-stage (stage :reduce reduce-module (map :id map-stages))]
     (compile-pipeline (concat [partition-stage] map-stages [reduce-stage])
                       (merge {:type :map-reduce :parallelism parallelism} metadata)))))

(defn conditional-pipeline
  "Create a pipeline with conditional branching based on predicate.

  Usage:
    (conditional-pipeline predicate-fn
                          true-branch-module
                          false-branch-module
                          merge-fn)"
  [predicate-fn true-module false-module merge-fn]
  (let [router-module (mod/fn-module
                       (fn [inputs]
                         (assoc inputs :route (if (predicate-fn inputs) :true :false))))
        ;; Conditional modules that only execute if their route matches
        true-branch-conditional (mod/fn-module
                                 (fn [inputs]
                                   (if (= (:route inputs) :true)
                                     @(mod/call true-module inputs)
                                     {})))
        false-branch-conditional (mod/fn-module
                                  (fn [inputs]
                                    (if (= (:route inputs) :false)
                                      @(mod/call false-module inputs)
                                      {})))
        true-branch (stage :true-branch true-branch-conditional [:router])
        false-branch (stage :false-branch false-branch-conditional [:router])
        merger (stage :merge (mod/fn-module merge-fn) [:true-branch :false-branch])
        router (stage :router router-module)]
    (compile-pipeline [router true-branch false-branch merger]
                      {:type :conditional})))