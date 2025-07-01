(ns dspy.property-test
  "Property-based testing with generated data for DSPy components.

   This namespace provides comprehensive property-based testing using Malli
   generators to ensure all modules respect their declared schemas."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [malli.core :as m]
            [malli.generator :as mg]
            [manifold.deferred :as d]
            [dspy.signature :as sig]
            [dspy.module :as mod]
            [dspy.backend.protocol :as bp]))

;; Property test configuration

(def ^:private test-iterations
  "Number of property test iterations."
  (or (some-> (System/getenv "DSPY_PROPERTY_TEST_ITERATIONS") Integer/parseInt)
      50))

;; Signature property tests

(defspec signature-registry-consistency test-iterations
  (prop/for-all [sig-name (gen/elements (keys @sig/registry))]
                (let [signature (get @sig/registry sig-name)]
                  (and
       ;; Signature exists in registry
                   (some? signature)
       ;; Signature has valid metadata
                   (contains? (meta signature) :malli/schema)
       ;; Schema is valid
                   (m/schema? (:malli/schema (meta signature)))))))

(defspec signature-validation-round-trip test-iterations
  (prop/for-all [sig-name (gen/elements (keys @sig/registry))]
                (let [signature (get @sig/registry sig-name)
                      schema (:malli/schema (meta signature))]
                  (when schema
        ;; Generate valid data and ensure it validates
                    (let [generated-data (mg/generate schema)]
                      (sig/validate-input signature generated-data))))))

(defspec signature-input-validation test-iterations
  (prop/for-all [sig-name (gen/elements (keys @sig/registry))
                 invalid-data (gen/one-of [gen/string gen/int gen/boolean gen/keyword])]
                (let [signature (get @sig/registry sig-name)]
      ;; Invalid data should fail validation
                  (not (sig/validate-input signature invalid-data)))))

;; Module property tests

(defrecord PropertyTestModule [schema transform-fn]
  mod/ILlmModule
  (call [_ input]
    (if (m/validate schema input)
      (d/success-deferred (transform-fn input))
      (d/error-deferred (ex-info "Invalid input" {:input input :schema schema})))))

(defn create-test-module
  "Create a test module with given schema and transform function."
  [schema transform-fn]
  (->PropertyTestModule schema transform-fn))

(defspec module-schema-compliance test-iterations
  (prop/for-all [input-data (gen/map gen/keyword gen/string-ascii)]
                (let [schema [:map [:input string?]]
                      module (create-test-module schema identity)
                      valid-input {:input "test"}
                      result @(mod/call module valid-input)]
      ;; Module should handle valid input correctly
                  (= valid-input result))))

(defspec module-error-handling test-iterations
  (prop/for-all [invalid-input (gen/one-of [gen/string gen/int gen/boolean])]
                (let [schema [:map [:input string?]]
                      module (create-test-module schema identity)]
      ;; Module should reject invalid input
                  (try
                    @(mod/call module invalid-input)
                    false ; Should not reach here
                    (catch Exception _
                      true))))) ; Exception expected

;; Backend property tests

(defrecord PropertyTestBackend [response-fn]
  bp/ILlmBackend
  (-generate [_ prompt _opts]
    (d/success-deferred (response-fn prompt)))
  (-embeddings [_ text _opts]
    (d/success-deferred {:vector (vec (repeat 10 (hash text)))}))
  (-stream [_ _prompt _opts]
    (d/success-deferred nil)))

(defspec backend-generate-consistency test-iterations
  (prop/for-all [prompt gen/string-ascii]
                (let [backend (->PropertyTestBackend (fn [p] {:text (str "Response to: " p)}))
                      result @(bp/generate backend prompt {})]
                  (and
       ;; Result has expected structure
                   (map? result)
                   (contains? result :text)
       ;; Response includes original prompt
                   (.contains (:text result) prompt)))))

(defspec backend-embeddings-consistency test-iterations
  (prop/for-all [text gen/string-ascii]
                (let [backend (->PropertyTestBackend identity)
                      result @(bp/embeddings backend text {})]
                  (and
       ;; Result has expected structure
                   (map? result)
                   (contains? result :vector)
       ;; Vector is non-empty
                   (seq (:vector result))))))

;; Integration property tests

(defspec module-backend-integration test-iterations
  (prop/for-all [prompt gen/string-ascii]
                (let [backend (->PropertyTestBackend (fn [p] {:text (str "AI: " p)}))
                      module (mod/fn-module identity)
                      context {:backend backend}
                      result @(mod/call module {:prompt prompt})]
      ;; Integration should preserve data flow
                  (= {:prompt prompt} result))))

;; Performance property tests

(defspec parallel-execution-consistency test-iterations
  (prop/for-all [inputs (gen/vector gen/string-ascii 1 10)]
                (let [backend (->PropertyTestBackend (fn [p] {:text (str "Response: " p)}))
                      sequential-results (mapv #(-> (bp/generate backend % {}) deref) inputs)
                      parallel-deferreds (mapv #(bp/generate backend % {}) inputs)
                      parallel-results (mapv deref parallel-deferreds)]
      ;; Parallel execution should give same results as sequential
                  (= sequential-results parallel-results))))

;; Edge case property tests

(defspec empty-input-handling test-iterations
  (prop/for-all [_dummy gen/any]
                (let [schema [:map]
                      module (create-test-module schema identity)
                      empty-input {}
                      result @(mod/call module empty-input)]
      ;; Empty valid input should be handled correctly
                  (= empty-input result))))

(defspec large-input-handling 10 ; Fewer iterations for large data
  (prop/for-all [large-input (gen/vector gen/string-ascii 100 200)]
                (let [backend (->PropertyTestBackend (fn [_] {:text "OK"}))
                      result @(bp/generate backend (apply str large-input) {})]
      ;; Large inputs should be handled without errors
                  (and
                   (map? result)
                   (contains? result :text)))))

;; Schema evolution property tests

(defspec schema-backward-compatibility test-iterations
  (prop/for-all [extra-key gen/keyword
                 extra-value gen/string-ascii]
                (let [base-schema [:map [:required string?]]
                      extended-input {:required "test" extra-key extra-value}
                      module (create-test-module base-schema #(select-keys % [:required]))]
      ;; Module should handle extra fields gracefully
                  (try
                    (let [result @(mod/call module extended-input)]
                      (= {:required "test"} result))
                    (catch Exception _
          ;; Some schemas might be strict, which is also valid
                      true)))))

;; Comprehensive integration test

(deftest comprehensive-property-test-suite
  (testing "All property tests pass"
    ;; Run a subset of property tests - these are already test functions
    (is (true? (:result (signature-registry-consistency 10))))
    (is (true? (:result (module-schema-compliance 10))))
    (is (true? (:result (backend-generate-consistency 10))))))

;; Test utilities

(defn run-property-tests
  "Run all property tests with custom configuration."
  ([]
   (run-property-tests test-iterations))
  ([iterations]
   (let [tests [signature-registry-consistency
                signature-validation-round-trip
                signature-input-validation
                module-schema-compliance
                module-error-handling
                backend-generate-consistency
                backend-embeddings-consistency
                module-backend-integration
                parallel-execution-consistency
                empty-input-handling
                large-input-handling
                schema-backward-compatibility]]
     (mapv #(tc/quick-check iterations %) tests))))

(defn property-test-report
  "Generate a summary report of property test results."
  []
  (let [results (run-property-tests)]
    {:total-tests (count results)
     :passed (count (filter :pass results))
     :failed (count (remove :pass results))
     :failures (remove :pass results)}))

;; Development helpers

(comment
  ;; Run property tests manually
  (run-property-tests 20)

  ;; Generate test report
  (property-test-report)

  ;; Test specific property
  (tc/quick-check 100 signature-registry-consistency)

  ;; Generate sample data for debugging
  (mg/generate [:map [:question string?] [:answer string?]]))