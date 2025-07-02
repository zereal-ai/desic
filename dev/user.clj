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

(defn check-system-resources
  "Check current system resource usage for debugging"
  []
  (let [runtime (Runtime/getRuntime)
        mb (* 1024 1024)]
    {:memory {:total-mb (/ (.totalMemory runtime) mb)
              :free-mb (/ (.freeMemory runtime) mb)
              :used-mb (/ (- (.totalMemory runtime) (.freeMemory runtime)) mb)
              :max-mb (/ (.maxMemory runtime) mb)}
     :threads {:active-count (Thread/activeCount)
               :daemon-count (->> (Thread/getAllStackTraces)
                                  keys
                                  (filter #(.isDaemon %))
                                  count)}
     :processors (.availableProcessors runtime)}))

(defn monitor-resources
  "Monitor system resources for a period of time"
  [duration-seconds]
  (let [start-time (System/currentTimeMillis)
        end-time (+ start-time (* duration-seconds 1000))]
    (println "Starting resource monitoring for" duration-seconds "seconds...")
    (loop [measurements []]
      (let [now (System/currentTimeMillis)
            current-resources (check-system-resources)]
        (println "Resources:" current-resources)
        (if (< now end-time)
          (do
            (Thread/sleep 1000)
            (recur (conj measurements current-resources)))
          (do
            (println "Resource monitoring complete")
            measurements))))))

(defn clean-shutdown!
  "Perform a clean shutdown of all DSPy resources"
  []
  (println "Performing clean shutdown...")
  (tap/shutdown!)
  (System/gc) ; Suggest garbage collection
  (println "Clean shutdown complete")
  (check-system-resources))

(comment
  ;; Check system resources before starting
  (check-system-resources)

  ;; Start Portal with DSPy instrumentation
  (start-portal!)

  ;; Test tapping with DSPy events
  (tap/tap-test)
  (tap/tap-performance-metric :test-metric 42 :count {:context "development"})

  ;; Monitor resources while working
  (monitor-resources 10) ; Monitor for 10 seconds

  ;; Explore signatures
  (sig/list-signatures)
  (when-let [sigs (seq (sig/list-signatures))]
    (sig/signature-info (first (vals @sig/registry))))

  ;; Generate sample data
  (when-let [sigs (seq (sig/list-signatures))]
    (sig/generate-examples (first (vals @sig/registry)) 3))

  ;; Clean shutdown when done
  (clean-shutdown!))