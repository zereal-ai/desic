(ns user
  "Development namespace for REPL interaction"
  (:require [portal.api :as p]
            [dspy.core :as dspy]))

(defn start-portal! []
  "Start Portal and setup tap integration"
  (let [portal (p/open {:launcher :vs-code})]
    (add-tap #'p/submit)
    (tap> {:message "Portal started successfully!"
           :desic-version (dspy/version)})
    portal))

(defn stop-portal! []
  "Stop Portal and remove tap"
  (remove-tap #'p/submit)
  (p/close))

(comment
  ;; Start Portal
  (start-portal!)

  ;; Test tapping
  (tap> {:test "Hello from desic!"})

  ;; Stop Portal
  (stop-portal!))