(ns dspy.storage.core
  "Core storage abstraction for DSPy optimization runs and metrics.

   Provides a protocol-based interface that can be implemented by different
   storage backends (SQLite, EDN files, etc.)."
  (:require [clojure.tools.logging :as log]))

(defprotocol Storage
  "Protocol for persisting optimization runs and metrics."

  (create-run! [this pipeline]
    "Create a new optimization run with the given pipeline.
     Returns a unique run ID.")

  (append-metric! [this run-id iter score payload]
    "Append a metric data point to an optimization run.

     Args:
       run-id - Unique run identifier
       iter - Iteration number (0-based)
       score - Numeric score for this iteration
       payload - Additional data (will be serialized)")

  (load-run [this run-id]
    "Load the pipeline for a given run ID.
     Returns the deserialized pipeline or nil if not found.")

  (load-history [this run-id]
    "Load the complete optimization history for a run.
     Returns a vector of metric maps with keys: :iter, :score, :payload"))

(defn make-storage
  "Create a storage implementation based on configuration.

   Config examples:
     {:type :sqlite :url \"jdbc:sqlite:dspy.db\"}
     {:type :file :dir \"./runs\"}

   If no config provided, defaults to EDN file storage in ./runs"
  ([]
   (make-storage {:type :file :dir "./runs"}))

  ([config]
   (let [storage-type (:type config :file)]
     (case storage-type
       :sqlite
       (do
         (require 'dspy.storage.sqlite)
         (let [sqlite-ns (find-ns 'dspy.storage.sqlite)
               init-sqlite (ns-resolve sqlite-ns 'init-sqlite)
               ->SQLiteStorage (ns-resolve sqlite-ns '->SQLiteStorage)
               db-spec (or (:url config)
                           (:db-spec config)
                           "jdbc:sqlite:dspy.db")
               sqlite-config (init-sqlite db-spec)]
           (->SQLiteStorage (:ds sqlite-config))))

       :file
       (do
         (require 'dspy.storage.edn)
         (let [edn-ns (find-ns 'dspy.storage.edn)
               ->EDNStorage (ns-resolve edn-ns '->EDNStorage)
               dir (:dir config "./runs")]
           (->EDNStorage dir)))

       (throw (ex-info "Unknown storage type"
                       {:type storage-type :supported [:sqlite :file]}))))))

(defn env->storage-cfg
  "Parse storage configuration from environment variables.

   Reads DSPY_STORAGE environment variable:
     \"sqlite://./dspy.db\" -> {:type :sqlite :url \"jdbc:sqlite:./dspy.db\"}
     \"file://./runs\" -> {:type :file :dir \"./runs\"}

   Returns default file config if no environment variable set."
  []
  (if-let [storage-env (System/getenv "DSPY_STORAGE")]
    (cond
      (.startsWith storage-env "sqlite://")
      {:type :sqlite
       :url (str "jdbc:sqlite:" (subs storage-env 9))}

      (.startsWith storage-env "file://")
      {:type :file
       :dir (subs storage-env 7)}

      :else
      (do
        (log/warn "Invalid DSPY_STORAGE format, using default" {:value storage-env})
        {:type :file :dir "./runs"}))

    {:type :file :dir "./runs"}))