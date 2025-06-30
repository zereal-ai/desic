(ns dspy.backend.wrappers
  "Middleware wrappers for LLM backends.

   This namespace provides composable middleware for adding rate limiting,
   retry logic, and other cross-cutting concerns to any ILlmBackend implementation."
  (:require [dspy.backend.protocol :as bp]
            [manifold.deferred :as d]
            [clojure.tools.logging :as log]))

;; Rate limiting wrapper

(defn wrap-throttle
  "Wrap a backend with rate limiting.

   Creates a token bucket that limits requests per second across all methods.

   Options:
     :rps - Requests per second limit (default: 3)
     :burst - Maximum burst size (default: rps * 2)

   Example:
     (wrap-throttle backend {:rps 5 :burst 10})

   Returns:
     New backend that respects rate limits"
  [backend {:keys [rps burst] :or {rps 3}}]
  (let [_burst-size (or burst (* rps 2))
        ;; Track when each request slot becomes available
        next-slot-time (atom (System/currentTimeMillis))
        request-interval (/ 1000.0 rps)

        acquire-slot! (fn []
                        (let [now (System/currentTimeMillis)
                              ;; Get next available slot and advance it by one interval
                              my-slot-time (swap! next-slot-time
                                                  (fn [next-time]
                                                    (let [earliest-slot (max next-time now)]
                                                      (+ earliest-slot request-interval))))
                              delay-needed (max 0 (- my-slot-time now))]
                          (if (zero? delay-needed)
                            (d/success-deferred :immediate)
                            (d/future
                              (Thread/sleep (long delay-needed))
                              :delayed))))]

    (reify bp/ILlmBackend
      (-generate [_ prompt opts]
        (d/chain
         (acquire-slot!)
         (fn [_] (bp/-generate backend prompt opts))))

      (-embeddings [_ text opts]
        (d/chain
         (acquire-slot!)
         (fn [_] (bp/-embeddings backend text opts))))

      (-stream [_ prompt opts]
        (d/chain
         (acquire-slot!)
         (fn [_] (bp/-stream backend prompt opts)))))))

;; Default retryable error predicate

(defn default-retryable-error?
  "Default predicate for determining if an error is retryable.

   Retries on:
   - Network timeouts and connection errors
   - HTTP 5xx server errors
   - Rate limit errors (429)
   - Temporary service unavailable (503)

   Does not retry on:
   - Authentication errors (401, 403)
   - Bad request errors (400)
   - Not found errors (404)"
  [error]
  (if (instance? Throwable error)
    (let [message (.getMessage error)
          data (ex-data error)]
      (or
       ;; Network-level errors
       (re-find #"(?i)timeout|connection|network" message)

       ;; HTTP status code errors
       (when-let [status (:status data)]
         (or (>= status 500) ; Server errors
             (= status 429) ; Rate limited
             (= status 503))) ; Service unavailable

       ;; OpenAI-specific errors
       (when-let [error-type (get-in data [:error :type])]
         (contains? #{"server_error" "rate_limit_exceeded" "service_unavailable"}
                    error-type))))
    ;; If it's not a Throwable, assume it's not retryable
    false))

;; Retry wrapper with exponential backoff

(defn wrap-retry
  "Wrap a backend with retry logic and exponential backoff.

   Options:
     :max-retries - Maximum number of retry attempts (default: 3)
     :initial-delay - Initial delay in ms (default: 1000)
     :max-delay - Maximum delay in ms (default: 30000)
     :backoff-factor - Exponential backoff multiplier (default: 2.0)
     :jitter - Add random jitter to delays (default: true)
     :retryable-error? - Predicate to determine if error is retryable

   Example:
     (wrap-retry backend {:max-retries 5 :initial-delay 500})

   Returns:
     New backend that retries on failures"
  [backend {:keys [max-retries initial-delay max-delay backoff-factor jitter retryable-error?]
            :or {max-retries 3
                 initial-delay 1000
                 max-delay 30000
                 backoff-factor 2.0
                 jitter true
                 retryable-error? default-retryable-error?}}]

  (letfn [(calculate-delay [attempt]
            (let [base-delay (* initial-delay (Math/pow backoff-factor attempt))
                  capped-delay (min base-delay max-delay)
                  jittered-delay (if jitter
                                   (* capped-delay (+ 0.5 (* 0.5 (rand))))
                                   capped-delay)]
              (long jittered-delay)))

          (retry-operation [operation attempt]
            (d/catch
             (operation)
             (fn [error]
               (if (and (< attempt max-retries)
                        (retryable-error? error))
                 (do
                   (log/warn "Backend operation failed, retrying"
                             {:attempt (inc attempt)
                              :max-retries max-retries
                              :delay (calculate-delay attempt)
                              :error (.getMessage error)})
                   (d/chain
                    (d/future (Thread/sleep (calculate-delay attempt)) :done)
                    (fn [_] (retry-operation operation (inc attempt)))))
                 (d/error-deferred error)))))]

    (reify bp/ILlmBackend
      (-generate [_ prompt opts]
        (retry-operation #(bp/-generate backend prompt opts) 0))

      (-embeddings [_ text opts]
        (retry-operation #(bp/-embeddings backend text opts) 0))

      (-stream [_ prompt opts]
        (retry-operation #(bp/-stream backend prompt opts) 0)))))

;; Timeout wrapper

(defn wrap-timeout
  "Wrap a backend with timeout enforcement.

   Options:
     :timeout-ms - Timeout in milliseconds (default: 30000)

   Example:
     (wrap-timeout backend {:timeout-ms 10000})

   Returns:
     New backend that times out long-running operations"
  [backend {:keys [timeout-ms] :or {timeout-ms 30000}}]

  (reify bp/ILlmBackend
    (-generate [_ prompt opts]
      (d/chain (bp/with-timeout (bp/-generate backend prompt opts) timeout-ms)
               (fn [result]
                 (if (= (:status result) :timeout)
                   {:status :timeout}
                   (:value result)))))

    (-embeddings [_ text opts]
      (d/chain (bp/with-timeout (bp/-embeddings backend text opts) timeout-ms)
               (fn [result]
                 (if (= (:status result) :timeout)
                   {:status :timeout}
                   (:value result)))))

    (-stream [_ prompt opts]
      (d/chain (bp/with-timeout (bp/-stream backend prompt opts) timeout-ms)
               (fn [result]
                 (if (= (:status result) :timeout)
                   {:status :timeout}
                   (:value result)))))))

;; Logging wrapper

(defn wrap-logging
  "Wrap a backend with request/response logging.

   Options:
     :log-requests? - Log requests (default: true)
     :log-responses? - Log responses (default: false)
     :log-errors? - Log errors (default: true)
     :logger - Logger function (default: clojure.tools.logging/info)

   Example:
     (wrap-logging backend {:log-responses? true})

   Returns:
     New backend that logs operations"
  [backend {:keys [log-requests? log-responses? log-errors? logger]
            :or {log-requests? true
                 log-responses? false
                 log-errors? true}}]
  (let [logger (or logger (fn [msg data] (log/info msg data)))]

    (letfn [(log-request [method args]
              (when log-requests?
                (logger "Backend request" {:method method :args args})))

            (log-response [method result]
              (when log-responses?
                (logger "Backend response" {:method method :result result})))

            (log-error [method error]
              (when log-errors?
                (log/error "Backend error" {:method method :error (.getMessage error)})))]

      (reify bp/ILlmBackend
        (-generate [_ prompt opts]
          (log-request :generate [prompt opts])
          (d/catch
           (d/chain
            (bp/-generate backend prompt opts)
            (fn [result]
              (log-response :generate result)
              result))
           (fn [error]
             (log-error :generate error)
             (d/error-deferred error))))

        (-embeddings [_ text opts]
          (log-request :embeddings [text opts])
          (d/catch
           (d/chain
            (bp/-embeddings backend text opts)
            (fn [result]
              (log-response :embeddings result)
              result))
           (fn [error]
             (log-error :embeddings error)
             (d/error-deferred error))))

        (-stream [_ prompt opts]
          (log-request :stream [prompt opts])
          (d/catch
           (d/chain
            (bp/-stream backend prompt opts)
            (fn [result]
              (log-response :stream result)
              result))
           (fn [error]
             (log-error :stream error)
             (d/error-deferred error))))))))

;; Composite wrapper utility

(defn with-middlewares
  "Apply multiple middleware wrappers to a backend in order.

   Configuration map keys:
     :throttle - Rate limiting options
     :retry - Retry options
     :timeout - Timeout options
     :logging - Logging options

   Example:
     (with-middlewares backend
       {:throttle {:rps 5}
        :retry {:max-retries 3}
        :timeout {:timeout-ms 15000}
        :logging {:log-responses? true}})

   Returns:
     Backend wrapped with all specified middleware"
  [backend config]
  (cond-> backend
    (:throttle config) (wrap-throttle (:throttle config))
    (:retry config) (wrap-retry (:retry config))
    (:timeout config) (wrap-timeout (:timeout config))
    (:logging config) (wrap-logging (:logging config))))

;; Circuit breaker wrapper (advanced)

(defn wrap-circuit-breaker
  "Wrap a backend with circuit breaker pattern.

   Options:
     :failure-threshold - Number of failures before opening circuit (default: 5)
     :timeout-ms - Time to wait before half-opening circuit (default: 60000)
     :success-threshold - Successes needed to close circuit (default: 3)

   States:
     :closed - Normal operation, requests pass through
     :open - Circuit open, requests fail fast
     :half-open - Testing if service recovered

   Example:
     (wrap-circuit-breaker backend {:failure-threshold 10})

   Returns:
     New backend with circuit breaker protection"
  [backend {:keys [failure-threshold timeout-ms success-threshold]
            :or {failure-threshold 5
                 timeout-ms 60000
                 success-threshold 3}}]

  (let [state (atom {:status :closed
                     :failures 0
                     :successes 0
                     :last-failure-time nil})]

    (letfn [(should-allow-request? []
              (let [current-state @state
                    now (System/currentTimeMillis)]
                (case (:status current-state)
                  :closed true
                  :open (> (- now (:last-failure-time current-state)) timeout-ms)
                  :half-open true)))

            (record-success []
              (swap! state (fn [s]
                             (let [new-successes (inc (:successes s))]
                               (if (>= new-successes success-threshold)
                                 {:status :closed :failures 0 :successes 0 :last-failure-time nil}
                                 (assoc s :successes new-successes))))))

            (record-failure []
              (swap! state (fn [s]
                             (let [new-failures (inc (:failures s))]
                               (if (>= new-failures failure-threshold)
                                 {:status :open :failures new-failures :successes 0
                                  :last-failure-time (System/currentTimeMillis)}
                                 (assoc s :failures new-failures))))))

            (execute-with-circuit-breaker [operation]
              (if (should-allow-request?)
                (d/catch
                 (d/chain
                  (operation)
                  (fn [result]
                    (record-success)
                    result))
                 (fn [error]
                   (record-failure)
                   (d/error-deferred error)))
                (d/error-deferred
                 (ex-info "Circuit breaker is open"
                          {:circuit-breaker-state @state}))))]

      (reify bp/ILlmBackend
        (-generate [_ prompt opts]
          (execute-with-circuit-breaker #(bp/-generate backend prompt opts)))

        (-embeddings [_ text opts]
          (execute-with-circuit-breaker #(bp/-embeddings backend text opts)))

        (-stream [_ prompt opts]
          (execute-with-circuit-breaker #(bp/-stream backend prompt opts)))))))

;; Utility functions

(defn backend-info
  "Extract information about a wrapped backend.

   Returns map with backend type, middleware layers, etc."
  [backend]
  {:type (type backend)
   :satisfies-ILlmBackend? (satisfies? bp/ILlmBackend backend)
   :protocols (if (satisfies? bp/ILlmBackend backend) [:ILlmBackend] [])
   :middleware-layers (if (instance? clojure.lang.IObj backend)
                        (-> backend meta :middleware-layers)
                        [])})

(defn unwrap-backend
  "Attempt to extract the underlying backend from middleware wrappers.

   This is a best-effort operation and may not work with all wrappers."
  [backend]
  (if-let [underlying (and (instance? clojure.lang.IObj backend)
                           (-> backend meta :underlying-backend))]
    (recur underlying)
    backend))