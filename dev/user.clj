(ns user
  "Development namespace for REPL interaction"
  (:require [portal.api :as p]
            [dspy.core :as dspy]
            [dspy.tap :as tap]
            [dspy.signature :as sig]))

(defn start-portal!
  "Start Portal and setup tap integration using dspy.tap"
  []
  (tap/init!)
  (tap/tap-test)
  (println "Portal integration initialized!"))

(defn stop-portal!
  "Stop Portal and remove tap using dspy.tap"
  []
  (tap/shutdown!)
  (println "Portal integration shutdown!"))

(comment
  ;; Start Portal with DSPy instrumentation
  (start-portal!)

  ;; Test tapping with DSPy events
  (tap/tap-test)
  (tap/tap-performance-metric :test-metric 42 :count {:context "development"})

  ;; Explore signatures
  (sig/list-signatures)
  (when-let [sigs (seq (sig/list-signatures))]
    (sig/signature-info (first (vals @sig/registry))))

  ;; Generate sample data
  (when-let [sigs (seq (sig/list-signatures))]
    (sig/generate-examples (first (vals @sig/registry)) 3))

  ;; Stop Portal
  (stop-portal!))