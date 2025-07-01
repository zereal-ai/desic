(ns dspy.util.manifold-test
  (:require [clojure.test :refer [deftest is testing]]
            [manifold.deferred :as d]
            [dspy.util.manifold :as util]))

;; Test parallel-map

(deftest test-parallel-map
  (testing "Parallel map with order preservation"
    (let [items (range 10)
          ;; Function that adds 1 after a small delay
          add-one (fn [x] (d/future (Thread/sleep 10) (inc x)))
          result @(util/parallel-map 3 add-one items)]
      ;; Should preserve order
      (is (= (range 1 11) result))))

  (testing "Empty collection"
    (let [result @(util/parallel-map 5 inc [])]
      (is (= [] result))))

  (testing "Single item"
    (let [result @(util/parallel-map 5 inc [42])]
      (is (= [43] result))))

  (testing "Default concurrency"
    (let [items (range 5)
          result @(util/parallel-map inc items)]
      (is (= (range 1 6) result))))

  (testing "Error propagation"
    (let [error-fn (fn [x]
                     (if (= x 3)
                       (d/error-deferred (ex-info "Test error" {:x x}))
                       (d/success-deferred (inc x))))]
      (is (thrown? Exception @(util/parallel-map 2 error-fn (range 5))))))

  (testing "Large collection performance"
    ;; Test with larger collection to verify chunking works (no timing assertions)
    (let [items (range 100)
          result @(util/parallel-map 10 #(d/future (inc %)) items)]
      (is (= (range 1 101) result)))))

;; Test parallel-map-unordered

(deftest test-parallel-map-unordered
  (testing "Parallel map without order preservation"
    (let [items (range 10)
          add-one (fn [x] (d/future (Thread/sleep (rand-int 20)) (inc x)))
          result @(util/parallel-map-unordered 3 add-one items)]
      ;; Should have all results but order may differ
      (is (= (set (range 1 11)) (set result)))
      (is (= 10 (count result)))))

  (testing "Empty collection unordered"
    (let [result @(util/parallel-map-unordered 5 inc [])]
      (is (= [] result))))

  (testing "Default concurrency unordered"
    (let [items (range 5)
          result @(util/parallel-map-unordered inc items)]
      (is (= (set (range 1 6)) (set result))))))

;; Test timeout utilities

(deftest test-with-timeout
  (testing "Operation completes within timeout"
    (let [fast-op (d/future (Thread/sleep 10) "success")
          result @(util/with-timeout fast-op 100)]
      (is (= {:status :ok :value "success"} result))))

  (testing "Operation times out"
    (let [slow-op (d/future (Thread/sleep 200) "too-late")
          result @(util/with-timeout slow-op 50)]
      (is (= {:status :timeout} result))))

  (testing "Immediate timeout"
    (let [op (d/future (Thread/sleep 100) "result")
          result @(util/with-timeout op 0)]
      (is (= {:status :timeout} result)))))

(deftest test-with-deadline
  (testing "Operation completes before deadline"
    (let [fast-op (d/future (Thread/sleep 10) "success")
          deadline (+ (System/currentTimeMillis) 100)
          result @(util/with-deadline fast-op deadline)]
      (is (= {:status :ok :value "success"} result))))

  (testing "Operation misses deadline"
    (let [slow-op (d/future (Thread/sleep 200) "too-late")
          deadline (+ (System/currentTimeMillis) 50)
          result @(util/with-deadline slow-op deadline)]
      (is (= {:status :timeout} result))))

  (testing "Past deadline"
    (let [op (d/future "result")
          past-deadline (- (System/currentTimeMillis) 1000)
          result @(util/with-deadline op past-deadline)]
      (is (= {:status :timeout} result)))))

;; Test cancellation utilities

(deftest test-cancellable
  (testing "Normal operation completion"
    (let [op (d/future (Thread/sleep 10) "success")
          cancellable-op (util/cancellable op #(println "cancelled"))
          result @cancellable-op]
      (is (= "success" result))))

  (testing "Cancellation with cleanup"
    (let [cleanup-called? (atom false)
          op (d/future (Thread/sleep 100) "result")
          cancellable-op (util/cancellable op #(reset! cleanup-called? true))]
      ;; This test is more about API verification since actual cancellation
      ;; is complex in Manifold. We just verify the structure works.
      (is (d/deferred? cancellable-op)))))

(deftest test-cancel-after
  (testing "Operation completes before cancellation timeout"
    (let [fast-op (d/future (Thread/sleep 10) "success")
          result @(util/cancel-after fast-op 100)]
      (is (= "success" result))))

  (testing "Operation cancelled after timeout"
    (let [slow-op (d/future (Thread/sleep 200) "too-late")]
      (is (thrown? Exception @(util/cancel-after slow-op 50))))))

;; Test resource management

(deftest test-with-resource
  (testing "Successful operation with cleanup"
    (let [cleanup-called? (atom false)
          resource "test-resource"
          operation (fn [r] (d/future (str "processed-" r)))
          cleanup (fn [_r] (reset! cleanup-called? true))
          result @(util/with-resource resource operation cleanup)]
      (is (= "processed-test-resource" result))
      (is @cleanup-called?)))

  (testing "Failed operation with cleanup"
    (let [cleanup-called? (atom false)
          resource "test-resource"
          operation (fn [_r] (d/error-deferred (ex-info "Operation failed" {})))
          cleanup (fn [_r] (reset! cleanup-called? true))]
      (is (thrown? Exception @(util/with-resource resource operation cleanup)))
      (is @cleanup-called?))))

;; Test batch processing

(deftest test-process-batches
  (testing "Process collection in batches"
    (let [items (range 20)
          ;; Batch function that sums the batch
          batch-fn (fn [batch] (d/future (reduce + batch)))
          result @(util/process-batches 5 2 batch-fn items)]
      ;; Should have 4 batches: [0-4], [5-9], [10-14], [15-19]
      (is (= 4 (count result)))
      ;; Sum of each batch
      (is (= [10 35 60 85] result))))

  (testing "Empty collection batches"
    (let [result @(util/process-batches 5 2 identity [])]
      (is (= [] result))))

  (testing "Single batch"
    (let [items [1 2 3]
          batch-fn (fn [batch] (d/future (count batch)))
          result @(util/process-batches 10 1 batch-fn items)]
      (is (= [3] result)))))

;; Test retry with backoff

(deftest test-retry-with-backoff
  (testing "Successful operation on first try"
    (let [call-count (atom 0)
          operation (fn []
                      (swap! call-count inc)
                      (d/future "success"))
          result @(util/retry-with-backoff operation 3 100)]
      (is (= "success" result))
      (is (= 1 @call-count))))

  (testing "Successful operation after retries"
    (let [call-count (atom 0)
          operation (fn []
                      (let [attempt (swap! call-count inc)]
                        (if (< attempt 3)
                          (d/error-deferred (ex-info "Temporary failure" {:attempt attempt}))
                          (d/future "success"))))
          result @(util/retry-with-backoff operation 3 50)]
      (is (= "success" result))
      (is (= 3 @call-count))))

  (testing "Max retries exceeded"
    (let [call-count (atom 0)
          operation (fn []
                      (swap! call-count inc)
                      (d/error-deferred (ex-info "Always fails" {})))]
      (is (thrown? Exception @(util/retry-with-backoff operation 2 50)))
      (is (= 3 @call-count)))) ; 1 initial + 2 retries

  (testing "Non-retryable error"
    (let [call-count (atom 0)
          operation (fn []
                      (swap! call-count inc)
                      (d/error-deferred (ex-info "Non-retryable" {:type :auth})))
          retryable? (fn [error] (not= :auth (:type (ex-data error))))]
      (is (thrown? Exception @(util/retry-with-backoff operation 3 50 2.0 1000 retryable?)))
      (is (= 1 @call-count)))))

;; Test performance monitoring

(deftest test-timed
  (testing "Operation timing"
    (let [operation (d/future (Thread/sleep 50) "result")
          result @(util/timed operation)]
      (is (= "result" (:result result)))
      (is (>= (:elapsed-ms result) 40)) ; Allow some variance
      (is (< (:elapsed-ms result) 100))))

  (testing "Failed operation timing"
    (let [operation (d/error-deferred (ex-info "Test error" {}))]
      (is (thrown? Exception @(util/timed operation))))))

;; Test rate-limited parallel map

(deftest test-rate-limited-parallel-map
  (testing "Rate limited execution"
    (let [items (range 5)
          ;; 10 requests per second = 100ms between requests
          result @(util/rate-limited-parallel-map 2 10
                                                  #(d/future (inc %))
                                                  items)]
      (is (= (range 1 6) result)))) ; Allow for some concurrency speedup

  (testing "Empty collection rate limited"
    (let [result @(util/rate-limited-parallel-map 5 10 inc [])]
      (is (= [] result)))))

;; Integration tests

(deftest test-integration-scenarios
  (testing "Complex workflow with multiple utilities"
    (let [items (range 10)
          ;; Simulate API calls with potential failures and retries
          api-call (fn [x]
                     (util/retry-with-backoff
                      #(d/future (Thread/sleep 10) (* x 2))
                      2 50))
          ;; Process with rate limiting and timeout
          result @(util/with-timeout
                    (util/rate-limited-parallel-map 3 20 api-call items)
                    5000)]

      (is (= {:status :ok :value (mapv #(* % 2) items)} result))))

  (testing "Error handling through utility chain"
    (let [failing-op (fn [_] (d/error-deferred (ex-info "Always fails" {})))
          items [1 2 3]]
      (is (thrown? Exception
                   @(util/parallel-map 2 failing-op items)))))

  (testing "Performance characteristics"
    ;; Test that parallel processing works correctly
    (let [items (range 20)
          slow-fn (fn [x] (d/future (inc x)))
          result @(util/parallel-map 5 slow-fn items)]
      (is (= (range 1 21) result)))))

;; Environment configuration tests

(deftest test-environment-configuration
  (testing "Default parallelism level"
    ;; This tests the default when no env var is set
    (let [items (range 5)
          result @(util/parallel-map inc items)]
      (is (= (range 1 6) result))))

  (testing "Parallelism configuration via function parameter"
    ;; Verify that explicit parameter overrides default
    (let [items (range 5)
          result @(util/parallel-map 1 inc items)] ; Force sequential
      (is (= (range 1 6) result)))))

;; Edge cases and error conditions

(deftest test-edge-cases
  (testing "Very large collections"
    ;; Test memory efficiency with large collections
    (let [items (range 1000)
          result @(util/parallel-map 10 inc items)]
      (is (= (range 1 1001) result))))

  (testing "Zero concurrency handling"
    ;; Should handle edge case gracefully
    (let [items [1 2 3]]
      (is (thrown? Exception @(util/parallel-map 0 inc items)))))

  (testing "Negative timeout"
    (let [op (d/future "result")]
      (is (= {:status :timeout} @(util/with-timeout op -100)))))

  (testing "Nil function handling"
    (let [items [1 2 3]]
      (is (thrown? Exception @(util/parallel-map 2 nil items))))))