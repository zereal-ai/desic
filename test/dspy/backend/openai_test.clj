(ns dspy.backend.openai-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [manifold.deferred :as d]
            [dspy.backend.protocol :as bp]
            [dspy.backend.openai :as openai]))

;; Test fixtures and helpers

(def mock-api-key "sk-test-key-123")

;; Test environment setup

(defn with-test-api-key [f]
  (with-redefs [openai/default-api-key mock-api-key]
    (f)))

(use-fixtures :each with-test-api-key)

;; Test OpenAI backend creation

(deftest test-backend-creation
  (testing "Creates backend with environment API key"
    (let [backend (openai/->backend)]
      (is (bp/backend? backend))
      (is (satisfies? bp/ILlmBackend backend))))

  (testing "Creates backend with custom options"
    (let [backend (openai/->backend {:model "gpt-4o"
                                     :embedding-model "text-embedding-ada-002"
                                     :api-key "custom-key"})]
      (is (bp/backend? backend))))

  (testing "Creates backend without error when no API key (uses mock)"
    (with-redefs [openai/default-api-key nil]
      (let [backend (openai/->backend)]
        (is (bp/backend? backend)))))

  (testing "Registry integration works"
    (let [backend (bp/create-backend {:type :openai :model "gpt-4o"})]
      (is (bp/backend? backend)))))

;; Test generation with mock backend

(deftest test-generation
  (testing "Successful generation"
    (let [backend (openai/->backend)
          result @(bp/generate backend "Hello")]
      (is (= "Mock response to: Hello" (:text result)))
      (is (= "gpt-4o-mini" (:model result)))
      (is (= {:prompt-tokens 10 :completion-tokens 25 :total-tokens 35}
             (:usage result)))
      (is (= "stop" (:finish-reason result)))))

  (testing "Generation with custom options"
    (let [backend (openai/->backend)
          result @(bp/generate backend "Hello" {:model "gpt-4o"
                                                :temperature 0.9
                                                :max-tokens 100})]
      (is (= "Mock response to: Hello" (:text result)))
      (is (= "gpt-4o" (:model result)))))

  (testing "Generation preserves request options"
    (let [backend (openai/->backend)
          result @(bp/generate backend "Test prompt" {:model "custom-model"})]
      (is (= "Mock response to: Test prompt" (:text result)))
      (is (= "custom-model" (:model result))))))

;; Test embeddings with mock backend

(deftest test-embeddings
  (testing "Successful embeddings"
    (let [backend (openai/->backend)
          result @(bp/embeddings backend "test text")]
      (is (= [0.1 0.2 0.3 0.4 0.5] (:vector result)))
      (is (= "text-embedding-3-small" (:model result)))
      (is (= {:prompt-tokens 5 :total-tokens 5} (:usage result)))))

  (testing "Embeddings with custom model"
    (let [backend (openai/->backend)
          result @(bp/embeddings backend "test" {:model "text-embedding-ada-002"})]
      (is (= [0.1 0.2 0.3 0.4 0.5] (:vector result)))
      (is (= "text-embedding-ada-002" (:model result)))))

  (testing "Embeddings return correct vector format"
    (let [backend (openai/->backend)
          result @(bp/embeddings backend "any text")]
      (is (vector? (:vector result)))
      (is (every? number? (:vector result)))
      (is (= 5 (count (:vector result)))))))

;; Test streaming (currently returns nil)

(deftest test-streaming
  (testing "Stream returns nil for now"
    (let [backend (openai/->backend)
          result @(bp/stream backend "test")]
      (is (nil? result)))))

;; Test utility functions

(deftest test-utility-functions
  (testing "Models list returns expected models"
    (let [models @(openai/models)]
      (is (= ["gpt-4o-mini" "gpt-4o" "text-embedding-3-small"] models))))

  (testing "Connection validation succeeds"
    (let [valid? @(openai/validate-connection)]
      (is (true? valid?))))

  (testing "Health check reports healthy"
    (let [health @(openai/health-check)]
      (is (true? (:models-available health)))
      (is (true? (:connection-ok health)))
      (is (= 3 (:models-count health)))
      (is (= :healthy (:status health)))))

  (testing "Health check with custom options"
    (let [health @(openai/health-check {:api-key "custom-key"})]
      (is (= :healthy (:status health))))))

;; Integration-style tests

(deftest test-backend-integration
  (testing "Backend integrates with protocol correctly"
    (let [backend (openai/->backend)]
      (is (bp/backend? backend))
      (is (satisfies? bp/ILlmBackend backend))

      ;; Test that methods exist and return deferreds
      (let [gen-result (bp/-generate backend "test" {})
            embed-result (bp/-embeddings backend "test" {})
            stream-result (bp/-stream backend "test" {})]
        (is (d/deferred? gen-result))
        (is (d/deferred? embed-result))
        (is (d/deferred? stream-result)))))

  (testing "Public API functions work"
    (let [backend (openai/->backend)]
      ;; These should succeed with mock implementation
      (is (d/deferred? (bp/generate backend "test")))
      (is (d/deferred? (bp/embeddings backend "test")))
      (is (d/deferred? (bp/stream backend "test")))

      ;; Verify actual results
      (is (string? (:text @(bp/generate backend "test"))))
      (is (vector? (:vector @(bp/embeddings backend "test"))))
      (is (nil? @(bp/stream backend "test")))))

  (testing "Backend works with different models"
    (let [backend-gpt4 (openai/->backend {:model "gpt-4o"})
          backend-mini (openai/->backend {:model "gpt-4o-mini"})]

      ;; Both should work
      (is (string? (:text @(bp/generate backend-gpt4 "test"))))
      (is (string? (:text @(bp/generate backend-mini "test"))))

      ;; Should use correct models
      (is (= "gpt-4o" (:model @(bp/generate backend-gpt4 "test"))))
      (is (= "gpt-4o-mini" (:model @(bp/generate backend-mini "test")))))))