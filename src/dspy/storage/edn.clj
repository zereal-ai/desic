(ns dspy.storage.edn
  "EDN file storage implementation for DSPy optimization runs and metrics."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [dspy.storage.core :as core]))

(defn- ensure-dir
  "Ensure directory exists, creating it if necessary."
  [dir]
  (let [dir-file (io/file dir)]
    (when-not (.exists dir-file)
      (.mkdirs dir-file))
    dir))

(defn- run-dir
  "Get the directory path for a specific run."
  [base-dir run-id]
  (str base-dir "/" run-id))

(defn- pipeline-file
  "Get the pipeline file path for a run."
  [base-dir run-id]
  (str (run-dir base-dir run-id) "/pipeline.edn"))

(defn- history-file
  "Get the history file path for a run."
  [base-dir run-id]
  (str (run-dir base-dir run-id) "/history.edn"))

(defn- safe-spit
  "Safely write data to a file, creating directories as needed."
  [file-path data]
  (let [file (io/file file-path)]
    (io/make-parents file)
    (spit file (pr-str data))))

(defn- safe-slurp
  "Safely read data from a file, returning nil if file doesn't exist."
  [file-path]
  (let [file (io/file file-path)]
    (when (.exists file)
      (try
        (edn/read-string (slurp file))
        (catch Exception e
          (log/error e "Failed to read EDN file" {:file file-path})
          nil)))))

(defrecord EDNStorage [base-dir]
  core/Storage

  (create-run! [_ pipeline]
    (let [run-id (str (java.util.UUID/randomUUID))]
      (ensure-dir base-dir)
      (ensure-dir (run-dir base-dir run-id))

      ;; Save pipeline
      (safe-spit (pipeline-file base-dir run-id) pipeline)

      ;; Initialize empty history
      (safe-spit (history-file base-dir run-id) [])

      (log/info "Created optimization run" {:run-id run-id :base-dir base-dir})
      run-id))

  (append-metric! [_ run-id iter score payload]
    (let [history-path (history-file base-dir run-id)
          current-history (or (safe-slurp history-path) [])
          new-entry {:iter iter :score score :payload payload}
          updated-history (conj current-history new-entry)]

      (safe-spit history-path updated-history)
      (log/debug "Appended metric" {:run-id run-id :iter iter :score score})))

  (load-run [_ run-id]
    (let [pipeline-path (pipeline-file base-dir run-id)]
      (when-let [pipeline (safe-slurp pipeline-path)]
        (log/debug "Loaded run pipeline" {:run-id run-id})
        pipeline)))

  (load-history [_ run-id]
    (let [history-path (history-file base-dir run-id)]
      (or (safe-slurp history-path) []))))