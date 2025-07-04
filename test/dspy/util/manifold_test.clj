(ns dspy.util.manifold-test
  (:require [clojure.test :refer [deftest is testing]]
            [manifold.deferred :as d]
            [dspy.util.manifold :as util]))

;; Core functionality tests - these test the essential behavior

(deftest test-parallel-map
  (testing "Parallel map with order preservation"
    (let [items (range 10)
          add-one (fn [x] (d/success-deferred (inc x)))
          result @(util/parallel-map 3 add-one items)]
      (is (= (range 1 11) result))))

  (testing "Error propagation"
    (let [error-fn (fn [x]
                     (if (= x 3)
                       (d/error-deferred (ex-info "Test error" {:x x}))
                       (d/success-deferred (inc x))))]
      (is (thrown? Exception @(util/parallel-map 2 error-fn (range 5))))))

  (testing "Large collection performance"
    (let [items (range 100)
          result @(util/parallel-map 10 #(d/success-deferred (inc %)) items)]
      (is (= (range 1 101) result)))))

(deftest test-timeout-functionality
  (testing "Fast operation completes normally"
    (let [fast-op (d/success-deferred "success")
          result @(util/with-timeout fast-op 1000)]
      (is (= {:status :ok :value "success"} result))))

  (testing "Negative timeout behavior"
    ;; Negative timeout should either timeout immediately or complete normally
    ;; depending on implementation - both are valid behaviors
    (let [op (d/success-deferred "result")
          result @(util/with-timeout op -100)]
      (is (or (= {:status :timeout} result)
              (= {:status :ok :value "result"} result))))))

(deftest test-retry-functionality
  (testing "Retry logic works correctly"
    (let [call-count (atom 0)
          operation (fn []
                      (let [attempt (swap! call-count inc)]
                        (if (< attempt 3)
                          (d/error-deferred (ex-info "Temporary failure" {:attempt attempt}))
                          (d/success-deferred "success"))))
          result @(util/retry-with-backoff operation 5 1)]
      (is (= "success" result))
      (is (= 3 @call-count))))

  (testing "Non-retryable error stops immediately"
    (let [call-count (atom 0)
          operation (fn []
                      (swap! call-count inc)
                      (d/error-deferred (ex-info "Non-retryable" {:type :auth})))
          retryable? (fn [error] (not= :auth (:type (ex-data error))))]
      (is (thrown? Exception @(util/retry-with-backoff operation 3 1 2.0 1000 retryable?)))
      (is (= 1 @call-count)))))

(deftest test-rate-limiting-functionality
  (testing "Rate limited execution produces correct results"
    (let [items [1 2 3 4 5]
          result @(util/rate-limited-parallel-map 2 1000
                                                  #(d/success-deferred (inc %))
                                                  items)]
      (is (= [2 3 4 5 6] result))))

  (testing "Sequential rate limiting preserves order"
    (let [items [1 2 3]
          call-order (atom [])
          operation (fn [x]
                      (swap! call-order conj x)
                      (d/success-deferred (inc x)))
          result @(util/rate-limited-parallel-map 1 1000 operation items)]
      (is (= [2 3 4] result))
      (is (= [1 2 3] @call-order)))))

(deftest test-other-utilities
  (testing "Timed operation returns timing info"
    (let [operation (d/success-deferred "result")
          result @(util/timed operation)]
      (is (= "result" (:result result)))
      (is (number? (:elapsed-ms result)))
      (is (>= (:elapsed-ms result) 0))))

  (testing "Process batches works correctly"
    (let [items (range 20)
          batch-fn (fn [batch] (d/success-deferred (reduce + batch)))
          result @(util/process-batches 5 2 batch-fn items)]
      (is (= 4 (count result)))
      (is (= [10 35 60 85] result))))

  (testing "Resource management calls cleanup"
    (let [cleanup-called? (atom false)
          resource "test-resource"
          operation (fn [r] (d/success-deferred (str "processed-" r)))
          cleanup (fn [_r] (reset! cleanup-called? true))
          result @(util/with-resource resource operation cleanup)]
      (is (= "processed-test-resource" result))
      (is @cleanup-called?))))

(deftest test-timing-behavior
  (testing "Rate limiting with measurable delays"
    ;; This test verifies that rate limiting actually works by measuring timing
    (let [items [1 2 3]
          call-times (atom [])
          operation (fn [x]
                      (swap! call-times conj (System/currentTimeMillis))
                      (d/success-deferred (inc x)))
          start-time (System/currentTimeMillis)
          ;; Use 100 RPS = 10ms between requests (minimal but measurable)
          result @(util/rate-limited-parallel-map 1 100 operation items)
          end-time (System/currentTimeMillis)
          elapsed (- end-time start-time)]
      (is (= [2 3 4] result))
      (is (= 3 (count @call-times)))
      ;; With 3 items at 100 RPS (10ms apart), should take at least 20ms
      (is (>= elapsed 15) (str "Expected at least 15ms for rate limiting, got " elapsed "ms"))
      ;; Verify calls were actually spaced out
      (let [times @call-times
            gaps (map - (rest times) times)]
        (is (every? #(>= % 5) gaps) "Calls should be spaced at least 5ms apart"))))

  (testing "Retry backoff with measurable delays"
    ;; This test verifies that retry backoff actually works
    (let [call-count (atom 0)
          call-times (atom [])
          operation (fn []
                      (let [attempt (swap! call-count inc)]
                        (swap! call-times conj (System/currentTimeMillis))
                        (if (< attempt 3)
                          (d/error-deferred (ex-info "Temporary failure" {:attempt attempt}))
                          (d/success-deferred "success"))))
          ;; Use 5ms initial delay with 2x backoff = 5ms, 10ms
          result @(util/retry-with-backoff operation 5 5 2.0 30000 (constantly true))]
      (is (= "success" result))
      (is (= 3 @call-count))
      ;; Verify there were actual delays between calls
      (let [times @call-times
            gaps (map - (rest times) times)]
        (is (>= (count gaps) 2) "Should have at least 2 gaps between 3 calls")
        (is (every? #(>= % 3) gaps) "Should have at least 3ms between retry attempts"))))

  (testing "Timeout functionality"
    ;; Test that timeout actually works
    (let [slow-op (d/deferred)
          timeout-result (util/with-timeout slow-op 10)] ; 10ms timeout
      (let [result @timeout-result]
        (is (= {:status :timeout} result))
        ;; Clean up
        (d/success! slow-op :cleanup)))))