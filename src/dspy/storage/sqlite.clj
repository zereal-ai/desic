(ns dspy.storage.sqlite
  "SQLite storage implementation for DSPy optimization runs and metrics."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.tools.logging :as log]
            [dspy.storage.core :as core]))

(defn- read-schema-sql
  "Read the schema.sql file from resources."
  []
  (-> "sql/schema.sql"
      io/resource
      slurp))

(defn- execute-schema-statements
  "Execute schema SQL statements, splitting on semicolons."
  [ds schema-sql]
  (let [;; Remove comments and split statements
        clean-sql (str/replace schema-sql #"--[^\n]*\n" "\n")
        statements (-> clean-sql
                       (str/split #";")
                       (->> (map str/trim)
                            (filter #(and (not (str/blank? %))
                                          (str/starts-with? (str/upper-case %) "CREATE")))))]
    (doseq [stmt statements]
      (when-not (str/blank? stmt)
        (log/debug "Executing SQL statement" {:sql stmt})
        (jdbc/execute! ds [stmt])))))

(defn migrate!
  "Run database migrations to ensure schema is up to date."
  [ds]
  (try
    (let [schema-sql (read-schema-sql)]
      (execute-schema-statements ds schema-sql)
      (log/info "Database migration completed successfully"))
    (catch Exception e
      (log/error e "Failed to run database migration")
      (throw e))))

(defn init-sqlite
  "Initialize SQLite database with the given connection string.

   Args:
     db-spec - Database specification map or connection string

   Returns:
     Database spec map with :ds key containing the datasource

   Example:
     (init-sqlite \"jdbc:sqlite:dspy.db\")
     (init-sqlite {:dbtype \"sqlite\" :dbname \"dspy.db\"})"
  [db-spec]
  (try
    (let [ds (jdbc/get-datasource db-spec)]
      ;; Test connection
      (jdbc/execute! ds ["SELECT 1"])

      ;; Run migrations
      (migrate! ds)

      (log/info "SQLite database initialized" {:db-spec db-spec})
      {:ds ds :db-spec db-spec})

    (catch Exception e
      (log/error e "Failed to initialize SQLite database" {:db-spec db-spec})
      (throw (ex-info "SQLite initialization failed"
                      {:db-spec db-spec :error (.getMessage e)}
                      e)))))

;; SQLite Storage Implementation

(defrecord SQLiteStorage [ds]
  core/Storage

  (create-run! [_ pipeline]
    (let [run-id (str (java.util.UUID/randomUUID))
          created-at (System/currentTimeMillis)
          pipeline-blob (pr-str pipeline)]
      (jdbc/execute! ds
                     ["INSERT INTO runs (id, created_at, pipeline_blob) VALUES (?, ?, ?)"
                      run-id created-at pipeline-blob])
      (log/info "Created optimization run" {:run-id run-id})
      run-id))

  (append-metric! [_ run-id iter score payload]
    (let [payload-str (pr-str payload)]
      (jdbc/execute! ds
                     ["INSERT INTO metrics (run_id, iter, score, payload) VALUES (?, ?, ?, ?)"
                      run-id iter score payload-str])
      (log/debug "Appended metric" {:run-id run-id :iter iter :score score})))

  (load-run [_ run-id]
    (when-let [row (first (jdbc/execute! ds
                                         ["SELECT pipeline_blob FROM runs WHERE id = ?" run-id]
                                         {:builder-fn rs/as-unqualified-lower-maps}))]
      (try
        (read-string (:pipeline_blob row))
        (catch Exception e
          (log/error e "Failed to deserialize pipeline" {:run-id run-id})
          (throw (ex-info "Pipeline deserialization failed"
                          {:run-id run-id :error (.getMessage e)}
                          e))))))

  (load-history [_ run-id]
    (let [rows (jdbc/execute! ds
                              ["SELECT iter, score, payload FROM metrics WHERE run_id = ? ORDER BY iter"
                               run-id]
                              {:builder-fn rs/as-unqualified-lower-maps})]
      (mapv (fn [row]
              (try
                {:iter (:iter row)
                 :score (:score row)
                 :payload (read-string (:payload row))}
                (catch Exception e
                  (log/warn e "Failed to deserialize payload" {:run-id run-id :iter (:iter row)})
                  {:iter (:iter row)
                   :score (:score row)
                   :payload nil})))
            rows))))

;; Storage protocol implementation will be added in the next file