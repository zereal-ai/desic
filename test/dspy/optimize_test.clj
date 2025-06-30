(ns dspy.optimize-test
  (:require [clojure.test :refer [deftest is testing]]
            [manifold.deferred :as d]
            [malli.core :as m]
            [dspy.optimize :as opt]
            [dspy.optimize.beam :as beam]
            [dspy.backend.protocol :as bp]
            [dspy.module :as mod]
            [dspy.pipeline :as pipe]))

;; Test fixtures and mock data

(defrecord MockOptimizationBackend []
  bp/ILlmBackend
  (-generate [_ prompt _opts]
    (d/success-deferred {:text (str "Mock response to: " (:content prompt))}))
  (-embeddings [_ _text _opts]
    (d/success-deferred {:vector [0.1 0.2 0.3]}))
  (-stream [_ _prompt _opts]
    (d/success-deferred nil)))

(defn ->mock-optimization-backend []
  (MockOptimizationBackend.))

;; Sample training data for testing

(def sample-trainset
  [{:input {:question "What is 2+2?"}
    :expected {:answer "4"}}
   {:input {:question "What is 3+3?"}
    :expected {:answer "6"}}
   {:input {:question "What is 5+5?"}
    :expected {:answer "10"}}])

;; Sample pipeline for testing
;; Simple test pipeline using a basic module

(def sample-pipeline
  ;; Simple test pipeline using a basic module
  (let [simple-module (mod/fn-module
                       (fn [{:keys [question]}]
                         {:answer (str "Answer to: " question)}))]
    (pipe/compile-pipeline
     [(pipe/stage :solve simple-module)]
     {:name "Test math solver"})))

;; Test schema validation

(deftest test-schema-validation
  (testing "Valid trainset schema"
    (is (= true (m/validate opt/trainset-schema sample-trainset))))

  (testing "Invalid trainset schema - missing expected"
    (let [invalid-trainset [{:input {:question "test"}}]]
      (is (= false (m/validate opt/trainset-schema invalid-trainset)))))

  (testing "Valid optimization options"
    (let [valid-opts {:strategy :beam :beam-width 4 :max-iterations 5}]
      (is (= true (m/validate opt/optimization-options-schema valid-opts)))))

  (testing "Invalid optimization options - negative beam width"
    (let [invalid-opts {:beam-width -1}]
      (is (= false (m/validate opt/optimization-options-schema invalid-opts))))))

;; Test built-in metrics

(deftest test-built-in-metrics
  (testing "Exact match metric - perfect match"
    (is (= 1.0 (opt/exact-match-metric {:answer "4"} {:answer "4"}))))

  (testing "Exact match metric - no match"
    (is (= 0.0 (opt/exact-match-metric {:answer "4"} {:answer "5"}))))

  (testing "Semantic similarity metric - identical"
    (is (= 1.0 (opt/semantic-similarity-metric {:text "hello world"} {:text "hello world"}))))

  (testing "Semantic similarity metric - partial overlap"
    (let [score (opt/semantic-similarity-metric {:text "hello world"} {:text "hello there"})]
      (is (> score 0.0))
      (is (< score 1.0))))

  (testing "Semantic similarity metric - no overlap"
    (is (= 0.0 (opt/semantic-similarity-metric {:text "hello"} {:text "goodbye"})))))

;; Test pipeline evaluation

(deftest test-pipeline-evaluation
  (testing "Pipeline evaluation with mock backend"
    (let [_backend (->mock-optimization-backend) ; TODO: Add backend parameter to evaluate-pipeline when LLM modules are integrated
          metric opt/exact-match-metric
          simple-trainset [{:input {:question "test"} :expected {:text "Mock response to: test"}}]
          result @(opt/evaluate-pipeline sample-pipeline simple-trainset metric)]
      (is (number? result))
      (is (>= result 0.0))
      (is (<= result 1.0)))))

;; Test strategy compilation

(deftest test-strategy-compilation
  (testing "Compile known strategies"
    (is (fn? (opt/compile-strategy :beam)))
    (is (fn? (opt/compile-strategy :random)))
    (is (fn? (opt/compile-strategy :identity))))

  (testing "Unknown strategy throws exception"
    (is (thrown-with-msg? Exception #"Unknown optimization strategy"
                          (opt/compile-strategy :unknown)))))

;; Test identity strategy (simple baseline)

(deftest test-identity-strategy
  (testing "Identity strategy returns pipeline unchanged"
    (let [strategy-fn (opt/compile-strategy :identity)
          result @(strategy-fn sample-pipeline sample-trainset opt/exact-match-metric {})]

      (is (= sample-pipeline (:best-pipeline result)))
      (is (= 0.0 (:best-score result)))
      (is (= 1 (:total-iterations result)))
      (is (= true (:converged? result)))
      (is (vector? (:history result)))
      (is (= 1 (count (:history result)))))))

;; Test main optimization API

(deftest test-optimize-api
  (testing "Optimize with identity strategy"
    (let [result @(opt/optimize sample-pipeline
                                sample-trainset
                                opt/exact-match-metric
                                {:strategy :identity})]

      (is (map? result))
      (is (contains? result :best-pipeline))
      (is (contains? result :best-score))
      (is (contains? result :history))
      (is (contains? result :total-iterations))
      (is (contains? result :total-time-ms))
      (is (contains? result :converged?))))

  (testing "Optimize with invalid trainset throws exception"
    (is (thrown-with-msg? Exception #"Invalid training dataset"
                          (opt/optimize sample-pipeline
                                        [{}] ; Invalid trainset
                                        opt/exact-match-metric
                                        {:strategy :identity}))))

  (testing "Optimize with invalid options throws exception"
    (is (thrown-with-msg? Exception #"Invalid optimization options"
                          (opt/optimize sample-pipeline
                                        sample-trainset
                                        opt/exact-match-metric
                                        {:beam-width -1}))))) ; Invalid option

;; Test beam search components

(deftest test-beam-search-components
  (testing "Generate candidate variations"
    (let [candidates (beam/mutate-pipeline sample-pipeline 1)]
      (is (vector? candidates))
      (is (> (count candidates) 0))
      (doseq [candidate candidates]
        (is (= sample-pipeline candidate)) ; Pipeline unchanged, metadata added
        (is (map? (meta candidate))))))

  (testing "Generate candidates from multiple pipelines"
    (let [pipelines [sample-pipeline sample-pipeline]
          candidates (beam/generate-candidates pipelines 1 4)]
      (is (vector? candidates))
      (is (> (count candidates) 0))))

  (testing "Suggest beam width"
    (is (= 2 (beam/suggest-beam-width 5 8 60000))) ; Small dataset
    (is (= 4 (beam/suggest-beam-width 25 8 60000))) ; Medium dataset
    (is (>= (beam/suggest-beam-width 100 4 60000) 2)))) ; Concurrency constraint

;; Integration tests

(deftest test-optimization-integration
  (testing "End-to-end optimization with identity strategy"
    (let [trainset [{:input {:question "What is 1+1?"}
                     :expected {:answer "2"}}]

          result @(opt/optimize sample-pipeline
                                trainset
                                opt/exact-match-metric
                                {:strategy :identity
                                 :max-iterations 2})]

      ;; Verify result structure
      (is (= sample-pipeline (:best-pipeline result)))
      (is (number? (:best-score result)))
      (is (vector? (:history result)))
      (is (pos? (:total-time-ms result)))
      (is (boolean? (:converged? result)))))

  (testing "Optimization with semantic similarity metric"
    (let [trainset [{:input {:question "Hello"}
                     :expected {:text "Hello there"}}]

          result @(opt/optimize sample-pipeline
                                trainset
                                opt/semantic-similarity-metric
                                {:strategy :identity})]

      (is (>= (:best-score result) 0.0))
      (is (<= (:best-score result) 1.0)))))

;; Performance and edge case tests

(deftest test-optimization-edge-cases
  (testing "Empty trainset handling"
    (is (thrown? Exception
                 @(opt/optimize sample-pipeline
                                [] ; Empty trainset
                                opt/exact-match-metric
                                {:strategy :identity}))))

  (testing "Single example trainset"
    (let [single-example [{:input {:question "test"} :expected {:answer "test"}}]
          result @(opt/optimize sample-pipeline
                                single-example
                                opt/exact-match-metric
                                {:strategy :identity})]

      (is (map? result))
      (is (number? (:best-score result)))))

  (testing "Large beam width gets adjusted"
    (let [large-width (beam/suggest-beam-width 10 4 60000)]
      ;; Should be constrained by concurrency
      (is (<= large-width 4)))))

;; Test concurrent evaluation capabilities

(deftest test-concurrent-evaluation
  (testing "Concurrent candidate scoring"
    (let [_backend (->mock-optimization-backend) ; TODO: Add backend parameter to score-candidates when LLM modules are integrated
          candidates [sample-pipeline sample-pipeline sample-pipeline]
          metric opt/exact-match-metric
          concurrency 2
          results @(beam/score-candidates candidates sample-trainset metric concurrency)]
      (is (= 3 (count results)))
      (doseq [result results]
        (is (contains? result :pipeline))
        (is (contains? result :score))
        (is (number? (:score result)))))))