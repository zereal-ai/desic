(ns dspy.signature-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [dspy.signature :as sig]))

(deftest arrow->map-test
  (testing "arrow->map converts arrow syntax to signature map"
    (is (= {:inputs [:question] :outputs [:answer]}
           (#'sig/arrow->map '(question => answer))))

    (is (= {:inputs [:document] :outputs [:summary :context]}
           (#'sig/arrow->map '(document => summary context))))

    (is (= {:inputs [:query :context] :outputs [:response]}
           (#'sig/arrow->map '(query context => response))))))

(deftest build-schema-test
  (testing "build-schema creates valid Malli schema"
    (let [sig-map {:inputs [:question] :outputs [:answer]}
          schema (#'sig/build-schema sig-map)]
      (is (= [:map
              {:closed false}
              [:question string?]
              [:answer string?]]
             schema))

      ;; Test that schema validates correctly
      (is (m/validate schema {:question "What is AI?" :answer "Artificial Intelligence"}))
      (is (not (m/validate schema {:question "What is AI?"}))) ; missing answer
      (is (not (m/validate schema {:question 123 :answer "AI"})))))) ; wrong type

(sig/defsignature QASample (question => answer) "Simple Q&A signature")

(deftest defsignature-macro-test
  (testing "defsignature creates signature with metadata"
    ;; Check that signature was created and added to registry
    (let [qa-sig (sig/get-signature 'QASample)]
      (is (some? qa-sig))
      (is (= {:inputs [:question] :outputs [:answer]} qa-sig))

      ;; Check metadata
      (let [schema (:malli/schema (meta qa-sig))]
        (is (some? schema))
        (is (m/validate schema {:question "Hi" :answer "Hello"}))))

    ;; Check registry
    (is (contains? @sig/registry 'QASample))))

(sig/defsignature TestSig (input => output))

(deftest signature-validation-test
  (testing "validate-input works correctly"
    (let [test-sig (sig/get-signature 'TestSig)]
      (is (true? (sig/validate-input test-sig {:input "test" :output "result"})))
      (is (false? (sig/validate-input test-sig {:input "test"}))) ; missing output
      (is (false? (sig/validate-input test-sig {:input 123 :output "result"}))))) ; wrong type

  (testing "generate-sample produces valid data"
    (let [test-sig (sig/get-signature 'TestSig)
          sample (sig/generate-sample test-sig)]
      (is (map? sample))
      (is (contains? sample :input))
      (is (contains? sample :output))
      (is (string? (:input sample)))
      (is (string? (:output sample)))
      (is (sig/validate-input test-sig sample)))))

(sig/defsignature Sig1 (a => b))
(sig/defsignature Sig2 (x => y z))

(deftest registry-functions-test
  (testing "registry management functions"
    ;; Check registry has our signatures
    (is (>= (count (sig/list-signatures)) 2))
    (is (contains? (set (sig/list-signatures)) 'Sig1))
    (is (contains? (set (sig/list-signatures)) 'Sig2))

    ;; Get signatures
    (is (= {:inputs [:a] :outputs [:b]} (sig/get-signature 'Sig1)))
    (is (= {:inputs [:x] :outputs [:y :z]} (sig/get-signature 'Sig2)))))

(sig/defsignature ComplexRAG
  (query context document => answer confidence sources)
  "Complex RAG signature with multiple inputs and outputs")

(deftest complex-signature-test
  (testing "complex multi-input/output signatures"
    (let [complex-rag (sig/get-signature 'ComplexRAG)]
      (is (= {:inputs [:query :context :document]
              :outputs [:answer :confidence :sources]}
             complex-rag))

      (let [valid-data {:query "What is ML?"
                        :context "Machine learning context"
                        :document "ML doc"
                        :answer "Machine Learning is..."
                        :confidence "High"
                        :sources "Various"}]
        (is (sig/validate-input complex-rag valid-data))))))