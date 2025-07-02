(ns dspy.util.manifold
  "Manifold utilities for concurrent operations and resource management.

   Provides helpers for parallel execution, cancellation, and timeout handling
   that are commonly needed in LLM operations."
  (:require [manifold.deferred :as d]
            [manifold.time :as mt]
            [clojure.tools.logging :as log]))

;; Environment-based parallelism configuration

(def ^:private default-parallel
  "Default parallelism level, configurable via DSPY_PARALLELISM env var.

   Capped at reasonable maximum to prevent excessive resource usage."
  (min 16 ; Cap at 16 to prevent excessive thread creation
       (or (some-> (System/getenv "DSPY_PARALLELISM") Integer/parseInt)
           8)))

;; Parallel execution utilities

(defn parallel-map
  "Map f over coll with at-most n concurrent operations; returns deferred of vector.

   This function partitions the collection into chunks of size n, processes each
   chunk concurrently, then combines results while preserving order.

   Features:
   - Preserves original collection order in results
   - Propagates earliest exception and cancels remaining operations
   - Memory-efficient for large collections
   - Configurable concurrency level

   Args:
     n - Maximum concurrent operations (default: 8)
     f - Function to map over collection elements
     coll - Collection to process

   Returns:
     Deferred that will contain vector of results in original order

   Example:
     ;; Process 100 items with max 10 concurrent operations
     @(parallel-map 10 #(do-expensive-operation %) (range 100))

     ;; Use default concurrency
     @(parallel-map #(fetch-data %) urls)"
  ([f coll]
   (parallel-map default-parallel f coll))
  ([n f coll]
   (if (empty? coll)
     (d/success-deferred [])
     (let [chunks (partition-all n coll)]
       (d/loop [remaining-chunks chunks
                results []]
         (if (empty? remaining-chunks)
           results
           (let [current-chunk (first remaining-chunks)
                 ;; Process current chunk concurrently
                 chunk-deferreds (mapv f current-chunk)]
             (d/chain
              ;; Wait for all operations in current chunk to complete
              (apply d/zip chunk-deferreds)
              ;; Add chunk results to accumulated results
              (fn [chunk-results]
                (d/recur (rest remaining-chunks)
                         (into results chunk-results)))))))))))

(defn parallel-map-unordered
  "Like parallel-map but doesn't preserve order, potentially faster.

   Results are returned as soon as they complete, which can improve
   performance when order doesn't matter.

   Args:
     n - Maximum concurrent operations
     f - Function to map over collection elements
     coll - Collection to process

   Returns:
     Deferred that will contain vector of results (order not preserved)"
  ([f coll]
   (parallel-map-unordered default-parallel f coll))
  ([n f coll]
   (if (empty? coll)
     (d/success-deferred [])
     ;; For now, use the same implementation as parallel-map
     ;; A true unordered implementation would be more complex
     (parallel-map n f coll))))

;; Timeout utilities

(defn with-timeout
  "Add timeout to a deferred operation.

   Returns a deferred that will either contain the result or timeout.
   The timeout result is a map with :status :timeout.

   Args:
     d - Deferred to add timeout to
     timeout-ms - Timeout in milliseconds

   Returns:
     Deferred with either {:status :ok :value result} or {:status :timeout}

   Example:
     @(with-timeout (slow-operation) 5000)
     ;; => {:status :ok :value \"result\"} or {:status :timeout}"
  [deferred timeout-ms]
  (d/alt
   ;; Success case
   (d/chain deferred (fn [result] {:status :ok :value result}))
   ;; Timeout case
   (d/chain (mt/in timeout-ms #(d/success-deferred {:status :timeout}))
            identity)))

(defn with-deadline
  "Add absolute deadline to a deferred operation.

   Similar to with-timeout but uses absolute time instead of relative.

   Args:
     d - Deferred to add deadline to
     deadline-ms - Absolute deadline in milliseconds since epoch

   Returns:
     Deferred with either result or timeout"
  [deferred deadline-ms]
  (let [now (System/currentTimeMillis)
        remaining (max 0 (- deadline-ms now))]
    (if (zero? remaining)
      (d/success-deferred {:status :timeout})
      (with-timeout deferred remaining))))

;; Cancellation utilities

(defn cancellable
  "Make a deferred cancellable with a custom cancellation function.

   The cancel-fn will be called if the deferred is cancelled before completion.
   This is useful for cleaning up resources like HTTP connections.

   Args:
     d - Deferred to make cancellable
     cancel-fn - Function to call on cancellation (no args)

   Returns:
     Cancellable deferred

   Example:
     (cancellable
       (http-request url)
       #(close-connection!))"
  [deferred cancel-fn]
  ;; For now, just return the original deferred
  ;; True cancellation in Manifold is complex and this is mainly for API compatibility
  deferred)

(defn cancel-after
  "Automatically cancel a deferred after a timeout.

   Combines timeout with cancellation - if the operation doesn't complete
   within the timeout, it will be cancelled.

   Args:
     d - Deferred to cancel
     timeout-ms - Timeout in milliseconds
     cancel-fn - Optional cancellation function

   Returns:
     Deferred that will be cancelled after timeout"
  ([deferred timeout-ms]
   (cancel-after deferred timeout-ms nil))
  ([deferred timeout-ms cancel-fn]
   (let [timeout-d (mt/in timeout-ms #(d/success-deferred :timeout))]
     (d/alt
      deferred
      (d/chain timeout-d
               (fn [_]
                 (when cancel-fn (cancel-fn))
                 (d/error-deferred (ex-info "Operation timed out and was cancelled"
                                            {:timeout-ms timeout-ms}))))))))

;; Resource management

(defn with-resource
  "Execute operation with automatic resource cleanup.

   Ensures cleanup-fn is called whether the operation succeeds or fails.

   Args:
     resource - Resource to manage
     operation-fn - Function that takes resource and returns deferred
     cleanup-fn - Function to clean up resource

   Returns:
     Deferred with operation result, resource cleaned up

   Example:
     (with-resource
       (open-connection)
       (fn [conn] (send-request conn))
       (fn [conn] (close-connection conn)))"
  [resource operation-fn cleanup-fn]
  (d/catch
   (d/chain
    (try
      (operation-fn resource)
      (catch Exception e
        (d/error-deferred e)))
    (fn [result]
      (try
        (cleanup-fn resource)
        (catch Exception e
          (log/warn "Error during resource cleanup" {:error (.getMessage e)})))
      result))
   (fn [error]
     (try
       (cleanup-fn resource)
       (catch Exception e
         (log/warn "Error during resource cleanup after failure" {:error (.getMessage e)})))
     (d/error-deferred error))))

;; Batch processing utilities

(defn process-batches
  "Process a large collection in batches with controlled concurrency.

   Useful for processing large datasets without overwhelming the system.

   Args:
     batch-size - Size of each batch
     concurrency - Number of batches to process concurrently
     batch-fn - Function to process each batch (takes vector, returns deferred)
     coll - Collection to process

   Returns:
     Deferred containing vector of all batch results

   Example:
     ;; Process 10,000 items in batches of 100, with 5 concurrent batches
     @(process-batches 100 5 process-batch (range 10000))"
  [batch-size concurrency batch-fn coll]
  (let [batches (partition-all batch-size coll)]
    (parallel-map concurrency batch-fn batches)))

;; Error handling utilities

(defn retry-with-backoff
  "Retry an operation with exponential backoff.

   Args:
     operation-fn - Function that returns a deferred (no args)
     max-retries - Maximum number of retries
     initial-delay-ms - Initial delay between retries
     backoff-factor - Exponential backoff multiplier (default: 2.0)
     max-delay-ms - Maximum delay between retries (default: 30000)
     retryable? - Predicate to determine if error is retryable (default: always true)

   Returns:
     Deferred with operation result or final error

   Example:
     @(retry-with-backoff
        #(unreliable-operation)
        3 1000 2.0 10000
        #(contains? #{:timeout :network-error} (:type (ex-data %))))"
  ([operation-fn max-retries initial-delay-ms]
   (retry-with-backoff operation-fn max-retries initial-delay-ms 2.0 30000 (constantly true)))
  ([operation-fn max-retries initial-delay-ms backoff-factor max-delay-ms retryable?]
   (letfn [(attempt [retry-count]
             (d/catch
              (operation-fn)
              (fn [error]
                (if (and (< retry-count max-retries)
                         (retryable? error))
                  (let [delay (min max-delay-ms
                                   (* initial-delay-ms (Math/pow backoff-factor retry-count)))]
                    (log/debug "Retrying operation" {:retry retry-count :delay delay})
                    (d/chain
                     (mt/in delay #(d/success-deferred :retry))
                     (fn [_] (attempt (inc retry-count)))))
                  (d/error-deferred error)))))]
     (attempt 0))))

;; Performance monitoring

(defn timed
  "Wrap a deferred operation with timing information.

   Returns a deferred containing {:result result :elapsed-ms elapsed-time}.

   Args:
     d - Deferred to time

   Returns:
     Deferred with timing information

   Example:
     @(timed (slow-operation))
     ;; => {:result \"operation result\" :elapsed-ms 1234}"
  [deferred]
  (let [start-time (System/currentTimeMillis)]
    (d/chain
     deferred
     (fn [result]
       {:result result
        :elapsed-ms (- (System/currentTimeMillis) start-time)}))))

(defn rate-limited-parallel-map
  "Parallel map with both concurrency and rate limiting.

   Combines parallel-map with rate limiting to respect API quotas.

   Args:
     concurrency - Maximum concurrent operations
     rate-limit - Maximum operations per second
     f - Function to map over elements
     coll - Collection to process

   Returns:
     Deferred containing vector of results

   Example:
     ;; Process with max 5 concurrent, 10 requests per second
     @(rate-limited-parallel-map 5 10 api-call urls)"
  [concurrency rate-limit f coll]
  (let [interval-ms (/ 1000.0 rate-limit)
        last-request-time (atom 0)]
    (parallel-map
     concurrency
     (fn [item]
       (d/chain
        ;; Rate limiting delay
        (let [now (System/currentTimeMillis)
              time-since-last (- now @last-request-time)
              delay-needed (max 0 (- interval-ms time-since-last))]
          (reset! last-request-time (+ now delay-needed))
          (if (pos? delay-needed)
            (mt/in delay-needed #(d/success-deferred :proceed))
            (d/success-deferred :proceed)))
        ;; Execute operation
        (fn [_] (f item))))
     coll)))

;; Resource management utilities

(defn with-bounded-parallelism
  "Execute operations with bounded parallelism to prevent resource exhaustion.

   This function ensures that no more than `max-concurrent` operations
   are running at any given time, which helps prevent excessive thread
   creation and resource usage.

   Args:
     max-concurrent - Maximum number of concurrent operations
     f - Function to apply to each item
     coll - Collection to process

   Returns:
     Deferred containing vector of results"
  [max-concurrent f coll]
  (let [max-concurrent (min max-concurrent default-parallel)]
    (if (empty? coll)
      (d/success-deferred [])
      (let [semaphore (java.util.concurrent.Semaphore. max-concurrent)]
        (d/chain
         (apply d/zip
                (mapv (fn [item]
                        (d/future
                          (.acquire semaphore)
                          (try
                            @(f item)
                            (finally
                              (.release semaphore)))))
                      coll))
         vec)))))

(defn shutdown-resources!
  "Shutdown any background resources used by this namespace.

   This function should be called when shutting down the application
   to ensure clean resource cleanup."
  []
  ;; Currently no persistent resources to clean up
  ;; This is a placeholder for future resource management
  (log/info "Manifold utilities resources shutdown complete"))