(ns dspy.pipeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string]
            [dspy.pipeline :as pipe]
            [dspy.module :as mod]))

;; Test modules for pipeline testing
(defn create-test-modules []
  {:tokenizer (mod/fn-module
               (fn [{:keys [text]}]
                 {:tokens (clojure.string/split text #"\s+")}))

   :word-counter (mod/fn-module
                  (fn [{:keys [tokens]}]
                    {:word-count (count tokens)
                     :tokens tokens}))

   :processor (mod/fn-module
               (fn [{:keys [tokens]}]
                 {:processed-tokens (map clojure.string/upper-case tokens)}))

   :formatter (mod/fn-module
               (fn [{:keys [word-count processed-tokens]}]
                 {:summary (str "Processed " word-count " words")
                  :result (clojure.string/join " " processed-tokens)}))

   ;; Simple formatter that works with tokens (for linear pipelines)
   :simple-formatter (mod/fn-module
                      (fn [{:keys [word-count tokens]}]
                        {:summary (str "Processed " word-count " words")
                         :result (clojure.string/join " " (map clojure.string/upper-case tokens))}))

   :doubler (mod/fn-module
             (fn [{:keys [x]}] {:x (* 2 x)}))

   :adder (mod/fn-module
           (fn [{:keys [x y]}] {:result (+ x y)}))})

(deftest stage-creation-test
  (testing "stage creation with different parameter combinations"
    (let [test-module (mod/fn-module identity)]
      ;; Basic stage
      (let [stage1 (pipe/stage :test test-module)]
        (is (= :test (:id stage1)))
        (is (= test-module (:module stage1)))
        (is (empty? (:dependencies stage1)))
        (is (empty? (:metadata stage1))))

      ;; Stage with dependencies
      (let [stage2 (pipe/stage :test test-module [:dep1 :dep2])]
        (is (= [:dep1 :dep2] (:dependencies stage2))))

      ;; Stage with dependencies and metadata
      (let [stage3 (pipe/stage :test test-module [:dep1] {:description "Test stage"})]
        (is (= [:dep1] (:dependencies stage3)))
        (is (= {:description "Test stage"} (:metadata stage3)))))))

(deftest linear-pipeline-test
  (testing "linear pipeline creation and execution"
    (let [modules (create-test-modules)
          pipeline (pipe/linear-pipeline
                    [(:tokenizer modules)
                     (:word-counter modules)
                     (:simple-formatter modules)]
                    {:name "Linear Test Pipeline"})]

      ;; Check pipeline structure
      (is (mod/module? pipeline))
      (is (instance? dspy.pipeline.Pipeline pipeline))

      ;; Check pipeline info
      (let [info (pipe/get-pipeline-info pipeline)]
        (is (= 3 (:stage-count info)))
        (is (= [:stage-0 :stage-1 :stage-2] (:stage-ids info)))
        (is (= {:name "Linear Test Pipeline"} (:metadata info))))

      ;; Test execution
      (let [result @(mod/call pipeline {:text "hello world test"})]
        (is (= "Processed 3 words" (:summary result)))
        (is (= "HELLO WORLD TEST" (:result result)))))))

(deftest compile-pipeline-test
  (testing "compile-pipeline with custom stages"
    (let [modules (create-test-modules)
          stages [(pipe/stage :tokenize (:tokenizer modules))
                  (pipe/stage :count (:word-counter modules) [:tokenize])
                  (pipe/stage :process (:processor modules) [:tokenize])
                  (pipe/stage :format (:formatter modules) [:count :process])]
          pipeline (pipe/compile-pipeline stages {:version "1.0"})]

      ;; Test pipeline info
      (let [info (pipe/get-pipeline-info pipeline)]
        (is (= 4 (:stage-count info)))
        (is (= [:tokenize :count :process :format] (:stage-ids info)))
        (is (= {:version "1.0"} (:metadata info))))

      ;; Test execution
      (let [result @(mod/call pipeline {:text "hello world"})]
        (is (= 2 (:word-count result)))
        (is (= ["HELLO" "WORLD"] (:processed-tokens result)))
        (is (= "Processed 2 words" (:summary result)))))))

(deftest branched-pipeline-test
  (testing "branched pipeline with parallel execution"
    (let [modules (create-test-modules)
          pipeline (pipe/branched-pipeline
                    {:stages {:input {:module (:tokenizer modules)}
                              :counter {:module (:word-counter modules)
                                        :dependencies [:input]}
                              :processor {:module (:processor modules)
                                          :dependencies [:input]}
                              :merger {:module (:formatter modules)
                                       :dependencies [:counter :processor]}}
                     :metadata {:name "Branched Pipeline"}})
          ;; Test execution
          result @(mod/call pipeline {:text "hello world clojure"})]
      (is (= 3 (:word-count result)))
      (is (= ["HELLO" "WORLD" "CLOJURE"] (:processed-tokens result)))
      (is (= "Processed 3 words" (:summary result))))))

(deftest pipeline-validation-test
  (testing "pipeline validation catches errors"
    (let [modules (create-test-modules)]
      ;; Missing dependency
      (is (thrown-with-msg? Exception #"missing dependencies"
                            (pipe/compile-pipeline
                             [(pipe/stage :stage1 (:tokenizer modules) [:missing-dep])])))

      ;; Circular dependency
      (is (thrown-with-msg? Exception #"cycles"
                            (pipe/compile-pipeline
                             [(pipe/stage :stage1 (:tokenizer modules) [:stage2])
                              (pipe/stage :stage2 (:word-counter modules) [:stage1])]))))))

(deftest pipeline-stats-test
  (testing "pipeline statistics calculation"
    (let [modules (create-test-modules)
          stages [(pipe/stage :input (:tokenizer modules))
                  (pipe/stage :branch1 (:word-counter modules) [:input])
                  (pipe/stage :branch2 (:processor modules) [:input])
                  (pipe/stage :merge (:formatter modules) [:branch1 :branch2])]
          pipeline (pipe/compile-pipeline stages)
          stats (pipe/pipeline-stats pipeline)]
      (is (= 4 (:total-stages stats)))
      (is (= 1 (:independent-stages stats)))
      (is (= :input (:most-depended-on stats)))
      (is (= 2 (:max-dependencies stats)))
      (is (= {:input 2, :branch1 1, :branch2 1} (:dependency-histogram stats))))))

(deftest map-reduce-pipeline-test
  (testing "map-reduce pipeline pattern"
    (let [map-module (mod/fn-module
                      (fn [{:keys [items]}]
                        {:mapped-items (map #(* % 2) items)}))
          reduce-module (mod/fn-module
                         (fn [inputs]
                           (let [all-items (mapcat :mapped-items (vals (select-keys inputs [:map-0 :map-1])))]
                             {:final-result (reduce + all-items)})))
          partitioner (fn [{:keys [numbers]}]
                        {:items numbers})
          pipeline (pipe/map-reduce-pipeline map-module reduce-module partitioner
                                             {:parallelism 2})
          ;; Note: This is a simplified test since the actual partitioning logic
          ;; would be more complex in a real implementation
          info (pipe/get-pipeline-info pipeline)]
      (is (= 4 (:stage-count info))) ; partition + 2 map + 1 reduce
      (is (= [:partition :map-0 :map-1 :reduce] (:stage-ids info))))))

(deftest conditional-pipeline-test
  (testing "conditional pipeline with branching logic"
    (let [predicate-fn (fn [{:keys [value]}] (> value 10))
          true-module (mod/fn-module
                       (fn [_inputs] {:result "high-value"}))
          false-module (mod/fn-module
                        (fn [_inputs] {:result "low-value"}))
          merge-fn (fn [inputs]
                     (or (:true-branch inputs) (:false-branch inputs)))
          pipeline (pipe/conditional-pipeline predicate-fn true-module false-module merge-fn)]

      ;; Test high value path
      (let [result @(mod/call pipeline {:value 15})]
        (is (= "high-value" (:result result))))

      ;; Test low value path
      (let [result @(mod/call pipeline {:value 5})]
        (is (= "low-value" (:result result)))))))

(deftest complex-pipeline-execution-test
  (testing "complex pipeline with multiple patterns"
    (let [modules (create-test-modules)
          ;; Create a pipeline that processes text in multiple ways
          pipeline (pipe/compile-pipeline
                    [(pipe/stage :tokenize (:tokenizer modules))
                     (pipe/stage :count-branch (:word-counter modules) [:tokenize])
                     (pipe/stage :process-branch (:processor modules) [:tokenize])
                     (pipe/stage :final-format (:formatter modules) [:count-branch :process-branch])]
                    {:name "Complex Test Pipeline"
                     :description "Multi-branch text processing"})]

      ;; Test with various inputs
      (testing "short text"
        (let [result @(mod/call pipeline {:text "hello"})]
          (is (= 1 (:word-count result)))
          (is (= "Processed 1 words" (:summary result)))))

      (testing "longer text"
        (let [result @(mod/call pipeline {:text "the quick brown fox jumps"})]
          (is (= 5 (:word-count result)))
          (is (= "THE QUICK BROWN FOX JUMPS" (:result result))))))))

(deftest pipeline-error-handling-test
  (testing "error handling in pipeline execution"
    (let [error-module (mod/fn-module
                        (fn [_inputs]
                          (throw (RuntimeException. "Stage failed"))))
          good-module (mod/fn-module
                       (fn [_inputs] {:output "success"}))
          pipeline (pipe/compile-pipeline
                    [(pipe/stage :good good-module)
                     (pipe/stage :bad error-module [:good])])]

      ;; Should propagate error
      (is (thrown? Exception @(mod/call pipeline {:input "test"}))))))

(deftest pipeline-as-module-test
  (testing "pipeline can be used as a module in other pipelines"
    (let [modules (create-test-modules)
          inner-pipeline (pipe/linear-pipeline
                          [(:tokenizer modules) (:word-counter modules)]
                          {:name "Inner Pipeline"})
          outer-pipeline (pipe/linear-pipeline
                          [inner-pipeline (:simple-formatter modules)]
                          {:name "Outer Pipeline"})
          ;; Test nested execution
          result @(mod/call outer-pipeline {:text "nested pipeline test"})]
      (is (= 3 (:word-count result))))))

(deftest real-world-text-processing-test
  (testing "realistic text processing pipeline"
    (let [;; Create a more realistic text processing pipeline
          sentence-splitter (mod/fn-module
                             (fn [{:keys [text]}]
                               {:sentences (clojure.string/split text #"\.")}))
          sentence-analyzer (mod/fn-module
                             (fn [{:keys [sentences]}]
                               {:sentence-count (count sentences)
                                :avg-length (/ (reduce + (map count sentences))
                                               (max 1 (count sentences)))
                                :sentences sentences}))
          word-analyzer (mod/fn-module
                         (fn [{:keys [sentences]}]
                           (let [all-words (mapcat #(clojure.string/split % #"\s+") sentences)]
                             {:total-words (count all-words)
                              :unique-words (count (distinct all-words))})))
          report-generator (mod/fn-module
                            (fn [{:keys [sentence-count avg-length total-words unique-words]}]
                              {:report (str "Document has " sentence-count " sentences, "
                                            "avg length " (int avg-length) " chars, "
                                            total-words " total words, "
                                            unique-words " unique words")}))

          pipeline (pipe/compile-pipeline
                    [(pipe/stage :split sentence-splitter)
                     (pipe/stage :analyze-sentences sentence-analyzer [:split])
                     (pipe/stage :analyze-words word-analyzer [:split])
                     (pipe/stage :report report-generator [:analyze-sentences :analyze-words])]
                    {:name "Document Analysis Pipeline"})
          result @(mod/call pipeline
                            {:text "This is sentence one. This is sentence two. Final sentence here."})]
      (is (clojure.string/includes? (:report result) "3 sentences"))
      (is (clojure.string/includes? (:report result) "total words"))
      (is (clojure.string/includes? (:report result) "unique words")))))