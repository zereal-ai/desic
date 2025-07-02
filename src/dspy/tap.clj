(ns dspy.tap
  "Portal integration and tap> utilities for live debugging and instrumentation.

   This namespace provides utilities for integrating with Portal and other tap>
   viewers to provide rich, live introspection during optimization and runtime."
  (:require [clojure.tools.logging :as log]
            [dspy.signature]))

;; Portal integration (conditional on dev environment)

(defn portal-available?
  "Check if Portal is available on the classpath."
  []
  (try
    (require 'portal.api)
    true
    (catch Exception _
      false)))

(defonce ^:private portal-instance (atom nil))

(defn start-portal!
  "Start Portal if available and not already running.

   Can be disabled by setting DSPY_NO_PORTAL=true environment variable."
  []
  (when (and (portal-available?)
             (not (System/getenv "DSPY_NO_PORTAL"))
             (not @portal-instance))
    (try
      (let [portal-api (resolve 'portal.api/open)]
        ;; Start Portal with minimal resource configuration
        (reset! portal-instance (portal-api {:port 0 ; Use random available port
                                             :launcher false ; Don't auto-open browser
                                             :theme :portal.colors/nord}))
        (log/info "Portal started successfully" {:port (some-> @portal-instance meta :port)}))
      (catch Exception e
        (log/warn "Failed to start Portal" {:error (.getMessage e)})))))

(defn stop-portal!
  "Stop Portal if running."
  []
  (when @portal-instance
    (try
      (let [portal-close (resolve 'portal.api/close)]
        (portal-close @portal-instance)
        (reset! portal-instance nil)
        (log/info "Portal stopped"))
      (catch Exception e
        (log/warn "Failed to stop Portal" {:error (.getMessage e)})))))

(defn install-tap!
  "Install Portal as a tap> target if available."
  []
  (when (and (portal-available?) @portal-instance)
    (try
      (let [portal-submit (resolve 'portal.api/submit)]
        (add-tap portal-submit)
        (log/info "Portal tap installed"))
      (catch Exception e
        (log/warn "Failed to install Portal tap" {:error (.getMessage e)})))))

(defn uninstall-tap!
  "Remove Portal as a tap> target."
  []
  (when (portal-available?)
    (try
      (let [portal-submit (resolve 'portal.api/submit)]
        (remove-tap portal-submit)
        (log/info "Portal tap removed"))
      (catch Exception e
        (log/warn "Failed to remove Portal tap" {:error (.getMessage e)})))))

;; Instrumentation utilities

(defn tap-module-execution
  "Tap module execution details for debugging.

   Args:
     module - The module being executed
     input - Input data to the module
     output - Output data from the module
     elapsed-ms - Execution time in milliseconds

   Emits:
     {:event :module/exec
      :module module-type
      :input input
      :output output
      :elapsed-ms elapsed-ms
      :timestamp timestamp}"
  [module input output elapsed-ms]
  (tap> {:event :module/exec
         :module (type module)
         :input input
         :output output
         :elapsed-ms elapsed-ms
         :timestamp (System/currentTimeMillis)}))

(defn tap-optimization-iteration
  "Tap optimization iteration details.

   Args:
     iteration - Current iteration number
     score - Current best score
     pipeline - Current best pipeline
     candidates - Number of candidates evaluated

   Emits:
     {:event :optimization/iteration
      :iteration iteration
      :score score
      :pipeline pipeline
      :candidates candidates
      :timestamp timestamp}"
  [iteration score pipeline candidates]
  (tap> {:event :optimization/iteration
         :iteration iteration
         :score score
         :pipeline pipeline
         :candidates candidates
         :timestamp (System/currentTimeMillis)}))

(defn tap-backend-request
  "Tap backend request details.

   Args:
     backend-type - Type of backend (e.g., :openai)
     method - Backend method (:generate, :embeddings, :stream)
     prompt - Request prompt/input
     options - Request options

   Emits:
     {:event :backend/request
      :backend-type backend-type
      :method method
      :prompt prompt
      :options options
      :timestamp timestamp}"
  [backend-type method prompt options]
  (tap> {:event :backend/request
         :backend-type backend-type
         :method method
         :prompt prompt
         :options options
         :timestamp (System/currentTimeMillis)}))

(defn tap-backend-response
  "Tap backend response details.

   Args:
     backend-type - Type of backend
     method - Backend method
     response - Response data
     elapsed-ms - Request duration

   Emits:
     {:event :backend/response
      :backend-type backend-type
      :method method
      :response response
      :elapsed-ms elapsed-ms
      :timestamp timestamp}"
  [backend-type method response elapsed-ms]
  (tap> {:event :backend/response
         :backend-type backend-type
         :method method
         :response response
         :elapsed-ms elapsed-ms
         :timestamp (System/currentTimeMillis)}))

(defn tap-validation-error
  "Tap validation error details.

   Args:
     signature - Signature that failed validation
     data - Data that failed validation
     error - Validation error details

   Emits:
     {:event :validation/error
      :signature signature
      :data data
      :error error
      :timestamp timestamp}"
  [signature data error]
  (tap> {:event :validation/error
         :signature signature
         :data data
         :error error
         :timestamp (System/currentTimeMillis)}))

(defn tap-performance-metric
  "Tap performance metric data.

   Args:
     metric-name - Name of the metric
     value - Metric value
     unit - Metric unit (e.g., :ms, :count, :bytes)
     context - Additional context

   Emits:
     {:event :performance/metric
      :metric metric-name
      :value value
      :unit unit
      :context context
      :timestamp timestamp}"
  [metric-name value unit context]
  (tap> {:event :performance/metric
         :metric metric-name
         :value value
         :unit unit
         :context context
         :timestamp (System/currentTimeMillis)}))

;; Convenience functions

(defn init!
  "Initialize Portal integration for development.

   This function:
   1. Starts Portal if available
   2. Installs Portal as a tap> target
   3. Logs initialization status

   Safe to call multiple times."
  []
  (start-portal!)
  (install-tap!)
  (tap> {:event :dspy/init
         :message "DSPy instrumentation initialized"
         :portal-available? (portal-available?)
         :portal-running? (some? @portal-instance)
         :timestamp (System/currentTimeMillis)}))

(defn shutdown!
  "Shutdown Portal integration and clean up resources.

   This function:
   1. Removes Portal as a tap> target
   2. Stops Portal if running
   3. Logs shutdown status
   4. Cleans up any background threads"
  []
  (uninstall-tap!)
  (stop-portal!)
  ;; Clean up any background resources
  (when (resolve 'dspy.util.manifold/shutdown-resources!)
    ((resolve 'dspy.util.manifold/shutdown-resources!)))
  (log/info "DSPy instrumentation shutdown complete"))

;; Development helpers

(defn tap-test
  "Send a test message to verify tap> is working."
  []
  (tap> {:event :dspy/test
         :message "Test message from dspy.tap"
         :timestamp (System/currentTimeMillis)
         :test-data {:numbers [1 2 3]
                     :strings ["hello" "world"]
                     :nested {:key "value"}}}))

;; Test signature for validation examples
(dspy.signature/defsignature TestQA (question => answer))