(ns dspy.module-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string]
            [manifold.deferred :as d]
            [dspy.module :as mod]
            [dspy.signature :as sig]))

;; Test signatures for validation
(sig/defsignature SimpleProcessing (input => output))
(sig/defsignature MathOps (x y => result))
(sig/defsignature Greeting (name => message))

(deftest fn-module-creation-test
  (testing "fn-module creation and basic properties"
    (let [simple-fn (fn [_inputs] {:processed true})
          module (mod/fn-module simple-fn)]
      (is (mod/module? module))
      (is (instance? dspy.module.FnModule module))
      (is (nil? (mod/get-signature module)))
      (is (nil? (mod/get-metadata module))))

    (let [module-with-sig (mod/fn-module
                           (fn [_inputs] {:output "processed"})
                           :signature SimpleProcessing
                           :metadata {:description "Test module"})]
      (is (= SimpleProcessing (mod/get-signature module-with-sig)))
      (is (= {:description "Test module"} (mod/get-metadata module-with-sig))))))

(deftest wrap-fn-convenience-test
  (testing "wrap-fn convenience function"
    (let [simple-fn (fn [_inputs] {:result "done"})]
      ;; No signature
      (is (mod/module? (mod/wrap-fn simple-fn)))

      ;; With signature
      (let [module (mod/wrap-fn simple-fn SimpleProcessing)]
        (is (= SimpleProcessing (mod/get-signature module))))

      ;; With signature and metadata
      (let [module (mod/wrap-fn simple-fn SimpleProcessing {:type "test"})]
        (is (= SimpleProcessing (mod/get-signature module)))
        (is (= {:type "test"} (mod/get-metadata module)))))))

(deftest basic-module-call-test
  (testing "basic module calling without signature validation"
    (let [add-module (mod/fn-module
                      (fn [{:keys [x y]}] {:result (+ x y)}))]

      ;; Test synchronous result
      (let [result @(mod/call add-module {:x 5 :y 3})]
        (is (= {:result 8} result)))

      ;; Test with different inputs
      (let [result @(mod/call add-module {:x 10 :y -2})]
        (is (= {:result 8} result))))))

(deftest async-module-call-test
  (testing "module calling with async functions"
    (let [async-module (mod/fn-module
                        (fn [_inputs]
                          (d/success-deferred {:async-result "processed"})))]

      (is (= {:async-result "processed"} @(mod/call async-module {:input "test"}))))))

(deftest signature-validation-test
  (testing "input validation with signatures"
    (let [validated-module (mod/fn-module
                            (fn [{:keys [input]}] {:output (str "Processed: " input)})
                            :signature SimpleProcessing)]

      ;; Valid input should work
      (let [result @(mod/call validated-module {:input "test" :output "placeholder"})]
        (is (= {:output "Processed: test"} result)))

      ;; Invalid input should throw exception
      (is (thrown? Exception
                   @(mod/call validated-module {:wrong-key "test"}))))))

(deftest error-handling-test
  (testing "error handling in modules"
    (let [error-module (mod/fn-module
                        (fn [_inputs]
                          (throw (RuntimeException. "Something went wrong"))))]

      ;; Should throw exception when dereferenced
      (is (thrown? RuntimeException
                   @(mod/call error-module {:input "test"}))))))

(deftest module-composition-test
  (testing "sequential module composition"
    (let [double-module (mod/fn-module
                         (fn [{:keys [x]}] {:x (* 2 x)}))
          add-one-module (mod/fn-module
                          (fn [{:keys [x]}] {:result (+ x 1)}))
          composed (mod/compose-modules double-module add-one-module)]

      (is (mod/module? composed))
      (let [result @(mod/call composed {:x 5})]
        (is (= {:result 11} result))) ; (5 * 2) + 1 = 11

      ;; Check metadata
      (let [metadata (mod/get-metadata composed)]
        (is (= :composition (:type metadata)))
        (is (= 2 (count (:modules metadata))))))))

(deftest parallel-modules-test
  (testing "parallel module execution"
    (let [module1 (mod/fn-module
                   (fn [_inputs] {:result1 "from-module-1"}))
          module2 (mod/fn-module
                   (fn [_inputs] {:result2 "from-module-2"}))
          module3 (mod/fn-module
                   (fn [_inputs] {:result3 "from-module-3"}))
          parallel (mod/parallel-modules module1 module2 module3)]

      (is (mod/module? parallel))
      (let [result @(mod/call parallel {:input "test"})]
        (is (= {:result1 "from-module-1"
                :result2 "from-module-2"
                :result3 "from-module-3"} result)))

      ;; Check metadata
      (let [metadata (mod/get-metadata parallel)]
        (is (= :parallel (:type metadata)))
        (is (= 3 (count (:modules metadata))))))))

(deftest complex-pipeline-test
  (testing "complex pipeline with composition and parallelism"
    (let [input-processor (mod/fn-module
                           (fn [{:keys [name]}] {:processed-name (str "Mr. " name)}))
          greeting-module (mod/fn-module
                           (fn [{:keys [processed-name]}] {:greeting (str "Hello, " processed-name)}))
          status-module (mod/fn-module
                         (fn [_inputs] {:status "processed"}))

          ;; First compose sequential processing
          sequential-part (mod/compose-modules input-processor greeting-module)

          ;; Then run in parallel with status
          full-pipeline (mod/parallel-modules sequential-part status-module)]

      (is (= {:greeting "Hello, Mr. John" :status "processed"} @(mod/call full-pipeline {:name "John"}))))))

(deftest module-introspection-test
  (testing "module introspection functions"
    (let [simple-fn (fn [x] x)
          not-a-module "just a string"]

      ;; module? predicate
      (is (mod/module? (mod/fn-module simple-fn)))
      (is (not (mod/module? not-a-module)))
      (is (not (mod/module? simple-fn)))

      ;; signature and metadata extraction
      (let [module (mod/fn-module simple-fn
                                  :signature SimpleProcessing
                                  :metadata {:version "1.0"})]
        (is (= SimpleProcessing (mod/get-signature module)))
        (is (= {:version "1.0"} (mod/get-metadata module)))))))

(deftest real-world-example-test
  (testing "realistic LLM module example"
    ;; Simulate a simple text processing pipeline
    (let [tokenizer (mod/fn-module
                     (fn [{:keys [text]}]
                       {:tokens (clojure.string/split text #"\s+")}))

          word-counter (mod/fn-module
                        (fn [{:keys [tokens]}]
                          {:word-count (count tokens)
                           :tokens tokens}))

          formatter (mod/fn-module
                     (fn [{:keys [word-count tokens]}]
                       {:summary (str "Text has " word-count " words")
                        :first-word (first tokens)}))

          pipeline (-> tokenizer
                       (mod/compose-modules word-counter)
                       (mod/compose-modules formatter))
          result @(mod/call pipeline {:text "Hello world from Clojure"})]
      (is (= "Text has 4 words" (:summary result)))
      (is (= "Hello" (:first-word result))))))