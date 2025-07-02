(ns dspy.backend.protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [manifold.deferred :as d]
            [dspy.backend.protocol :as bp]))

;; Mock backend for testing

(defrecord MockBackend [responses]
  bp/ILlmBackend
  (-generate [_ prompt opts]
    (d/success-deferred
     {:text (str "Generated: " prompt)
      :usage {:prompt-tokens 5 :completion-tokens 10}
      :model (:model opts "mock-model")}))

  (-embeddings [_ _text opts]
    (d/success-deferred
     {:vector [0.1 0.2 0.3 0.4 0.5]
      :model (:model opts "mock-embedding-model")
      :usage {:prompt-tokens 3}}))

  (-stream [_ _prompt _opts]
    (d/success-deferred nil))) ; Mock doesn't support streaming

(defn ->mock-backend
  "Create a mock backend for testing."
  []
  (->MockBackend {}))

;; Test backend protocol implementation

(deftest test-backend-protocol
  (testing "Mock backend satisfies protocol"
    (let [backend (->mock-backend)]
      (is (bp/backend? backend))
      (is (satisfies? bp/ILlmBackend backend))))

  (testing "Generate method works"
    (let [backend (->mock-backend)
          result @(bp/-generate backend "test prompt" {:model "test-model"})]
      (is (= "Generated: test prompt" (:text result)))
      (is (= "test-model" (:model result)))
      (is (= {:prompt-tokens 5 :completion-tokens 10} (:usage result)))))

  (testing "Embeddings method works"
    (let [backend (->mock-backend)
          result @(bp/-embeddings backend "test text" {:model "test-embed"})]
      (is (= [0.1 0.2 0.3 0.4 0.5] (:vector result)))
      (is (= "test-embed" (:model result)))
      (is (= {:prompt-tokens 3} (:usage result)))))

  (testing "Stream method works (returns nil for mock)"
    (let [backend (->mock-backend)
          result @(bp/-stream backend "test prompt" {})]
      (is (nil? result)))))

;; Test public API functions

(deftest test-public-api
  (testing "generate function with defaults"
    (let [backend (->mock-backend)
          result @(bp/generate backend "hello")]
      (is (= "Generated: hello" (:text result)))
      (is (= "mock-model" (:model result)))))

  (testing "generate function with options"
    (let [backend (->mock-backend)
          result @(bp/generate backend "hello" {:model "custom-model"})]
      (is (= "Generated: hello" (:text result)))
      (is (= "custom-model" (:model result)))))

  (testing "embeddings function with defaults"
    (let [backend (->mock-backend)
          result @(bp/embeddings backend "hello")]
      (is (= [0.1 0.2 0.3 0.4 0.5] (:vector result)))
      (is (= "mock-embedding-model" (:model result)))))

  (testing "embeddings function with options"
    (let [backend (->mock-backend)
          result @(bp/embeddings backend "hello" {:model "custom-embed"})]
      (is (= [0.1 0.2 0.3 0.4 0.5] (:vector result)))
      (is (= "custom-embed" (:model result)))))

  (testing "stream function"
    (let [backend (->mock-backend)
          result @(bp/stream backend "hello")]
      (is (nil? result)))))

;; Test backend registry

(deftest test-backend-registry
  (testing "Unknown backend provider throws exception"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unknown backend provider"
         (bp/create-backend {:type :unknown}))))

  (testing "Exception contains helpful error data"
    (try
      (bp/create-backend {:type :unknown :model "test"})
      (catch Exception e
        (let [data (ex-data e)]
          (is (= :unknown (:provider data)))
          (is (vector? (:supported-providers data)))
          (is (contains? data :config))))))

  (testing "Can extend registry with new backend"
    (defmethod bp/create-backend :test [_config]
      (->mock-backend))

    (let [backend (bp/create-backend {:provider :test})]
      (is (bp/backend? backend))
      (is (satisfies? bp/ILlmBackend backend)))))

;; Test utility functions

(deftest test-utility-functions
  (testing "backend? predicate"
    (is (bp/backend? (->mock-backend)))
    (is (not (bp/backend? {})))
    (is (not (bp/backend? nil))))

  (testing "with-timeout utility"
    (testing "completes before timeout"
      (let [quick-deferred (d/success-deferred {:result "ok"})
            result @(bp/with-timeout quick-deferred 1000)]
        (is (= {:status :ok :value {:result "ok"}} result))))

    (testing "times out for non-completing operations"
      (let [never-completes (d/deferred)
            result @(bp/with-timeout never-completes 50)]
        (is (= {:status :timeout} result))))))

;; Test error handling

(defrecord FailingBackend []
  bp/ILlmBackend
  (-generate [_ _ _]
    (d/error-deferred (ex-info "Generation failed" {:reason :test-failure})))
  (-embeddings [_ _ _]
    (d/error-deferred (ex-info "Embeddings failed" {:reason :test-failure})))
  (-stream [_ _ _]
    (d/error-deferred (ex-info "Streaming failed" {:reason :test-failure}))))

(deftest test-error-handling
  (testing "Generate errors are propagated"
    (let [backend (->FailingBackend)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Generation failed"
           @(bp/generate backend "test")))))

  (testing "Embeddings errors are propagated"
    (let [backend (->FailingBackend)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Embeddings failed"
           @(bp/embeddings backend "test")))))

  (testing "Stream errors are propagated"
    (let [backend (->FailingBackend)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Streaming failed"
           @(bp/stream backend "test"))))))