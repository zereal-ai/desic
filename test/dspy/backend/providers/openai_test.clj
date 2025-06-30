(ns dspy.backend.providers.openai-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [manifold.deferred :as d]
            [dspy.backend.protocol :as bp]
            [dspy.backend.providers.openai :as openai]
            [wkok.openai-clojure.api :as openai-api]))

;; Test fixtures and helpers

(def mock-api-key "sk-test-key-123")

;; Mock functions for openai-clojure library that respect model parameters
(defn mock-create-chat-completion [request _options]
  {:choices [{:message {:content "Mock response to: test prompt"}
              :finish_reason "stop"}]
   :usage {:prompt_tokens 10
           :completion_tokens 25
           :total_tokens 35}
   :model (:model request)}) ; Use the requested model

(defn mock-create-embedding [request _options]
  {:data [{:embedding [0.1 0.2 0.3 0.4 0.5]}]
   :usage {:prompt_tokens 5
           :total_tokens 5}
   :model (:model request)}) ; Use the requested model

(defn mock-list-models [_options]
  {:data [{:id "gpt-4o-mini"}
          {:id "gpt-4o"}
          {:id "text-embedding-3-small"}]})

;; Test environment setup with mocked openai-clojure functions

(defn with-mocked-openai [f]
  (with-redefs [openai-api/create-chat-completion mock-create-chat-completion
                openai-api/create-embedding mock-create-embedding
                openai-api/list-models mock-list-models]
    (f)))

(use-fixtures :each with-mocked-openai)

;; Test OpenAI backend creation

(deftest test-backend-creation
  (testing "Provider-agnostic backend creation with defaults"
    (let [backend (bp/create-backend {:provider :openai})]
      (is (bp/backend? backend))
      (is (satisfies? bp/ILlmBackend backend))))

  (testing "Provider-agnostic backend creation with custom options"
    (let [backend (bp/create-backend {:provider :openai
                                      :model "gpt-4o"
                                      :embedding-model "text-embedding-ada-002"
                                      :api-key "custom-key"
                                      :organization "org-123"})]
      (is (bp/backend? backend))))

  (testing "Creates backend without error when no API key (uses mock)"
    (let [backend (bp/create-backend {:provider :openai})]
      (is (bp/backend? backend))))

  (testing "Backward compatibility - legacy :type key still works"
    (let [backend (bp/create-backend {:type :openai :model "gpt-4o"})]
      (is (bp/backend? backend))))

  (testing "Legacy ->backend constructor still works (deprecated)"
    (let [backend (openai/->backend {:model "gpt-4o"
                                     :embedding-model "text-embedding-ada-002"
                                     :api-key "custom-key"
                                     :organization "org-123"})]
      (is (bp/backend? backend)))))

;; Test generation with mocked openai-clojure

(deftest test-generation
  (testing "Successful generation"
    (let [backend (bp/create-backend {:provider :openai})
          result @(bp/generate backend "Hello")]
      (is (= "Mock response to: test prompt" (:text result)))
      (is (= "gpt-4o-mini" (:model result)))
      (is (= {:prompt-tokens 10 :completion-tokens 25 :total-tokens 35}
             (:usage result)))
      (is (= "stop" (:finish-reason result)))))

  (testing "Generation with custom options"
    (let [backend (bp/create-backend {:provider :openai})
          result @(bp/generate backend "Hello" {:model "gpt-4o"
                                                :temperature 0.9
                                                :max-tokens 100})]
      (is (= "Mock response to: test prompt" (:text result)))
      (is (= "gpt-4o" (:model result))))) ; Mock respects requested model

  (testing "Generation preserves request options in call"
    (let [backend (bp/create-backend {:provider :openai})
          ;; Test that the function doesn't throw - the mock will handle response
          result @(bp/generate backend "Test prompt" {:model "custom-model"})]
      (is (= "Mock response to: test prompt" (:text result))))))

;; Test embeddings with mocked openai-clojure

(deftest test-embeddings
  (testing "Successful embeddings"
    (let [backend (bp/create-backend {:provider :openai})
          result @(bp/embeddings backend "test text")]
      (is (= [0.1 0.2 0.3 0.4 0.5] (:vector result)))
      (is (= "text-embedding-3-small" (:model result)))
      (is (= {:prompt-tokens 5 :total-tokens 5} (:usage result)))))

  (testing "Embeddings with custom model"
    (let [backend (bp/create-backend {:provider :openai})
          result @(bp/embeddings backend "test" {:model "text-embedding-ada-002"})]
      (is (= [0.1 0.2 0.3 0.4 0.5] (:vector result)))
      (is (= "text-embedding-ada-002" (:model result))))) ; Mock respects requested model

  (testing "Embeddings return correct vector format"
    (let [backend (bp/create-backend {:provider :openai})
          result @(bp/embeddings backend "any text")]
      (is (vector? (:vector result)))
      (is (every? number? (:vector result)))
      (is (= 5 (count (:vector result)))))))

;; Test streaming (currently returns nil)

(deftest test-streaming
  (testing "Stream returns nil for now"
    (let [backend (bp/create-backend {:provider :openai})
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
    (let [backend (bp/create-backend {:provider :openai})]
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
    (let [backend (bp/create-backend {:provider :openai})]
      ;; These should succeed with mock implementation
      (is (d/deferred? (bp/generate backend "test")))
      (is (d/deferred? (bp/embeddings backend "test")))
      (is (d/deferred? (bp/stream backend "test")))

      ;; Verify actual results
      (is (string? (:text @(bp/generate backend "test"))))
      (is (vector? (:vector @(bp/embeddings backend "test"))))
      (is (nil? @(bp/stream backend "test")))))

  (testing "Backend works with different models"
    (let [backend-gpt4 (bp/create-backend {:provider :openai :model "gpt-4o"})
          backend-mini (bp/create-backend {:provider :openai :model "gpt-4o-mini"})]

      ;; Both should work
      (is (string? (:text @(bp/generate backend-gpt4 "test"))))
      (is (string? (:text @(bp/generate backend-mini "test"))))

      ;; Mock returns consistent results regardless of model requested
      (is (= "Mock response to: test prompt" (:text @(bp/generate backend-gpt4 "test"))))
      (is (= "Mock response to: test prompt" (:text @(bp/generate backend-mini "test")))))))

;; Test error handling

(deftest test-error-handling
  (testing "Generation handles API errors"
    (with-redefs [openai-api/create-chat-completion (fn [_request _options]
                                                      {:error {:message "API key invalid"}})]
      (let [backend (bp/create-backend {:provider :openai})]
        (is (thrown-with-msg? Exception #"OpenAI generation failed"
                              @(bp/generate backend "test"))))))

  (testing "Embeddings handles API errors"
    (with-redefs [openai-api/create-embedding (fn [_request _options]
                                                {:error {:message "Rate limit exceeded"}})]
      (let [backend (bp/create-backend {:provider :openai})]
        (is (thrown-with-msg? Exception #"OpenAI embeddings failed"
                              @(bp/embeddings backend "test"))))))

  (testing "Models list handles API errors"
    (with-redefs [openai-api/list-models (fn [_options]
                                           {:error {:message "Unauthorized"}})]
      (is (thrown-with-msg? Exception #"OpenAI models list failed"
                            @(openai/models))))))