(ns dspy.signature
  "Declarative signature definitions for LLM modules."
  (:require [malli.core :as m]
            [malli.generator :as mg]))

(def registry
  "Registry of all defined signatures"
  (atom {}))

(defn- arrow->map
  "Converts arrow forms like (question => answer) into signature map."
  [arrows]
  (let [arrow-idx (.indexOf arrows '=>)
        inputs (take arrow-idx arrows)
        outputs (drop (inc arrow-idx) arrows)]
    {:inputs (mapv keyword inputs)
     :outputs (mapv keyword outputs)}))

(defn- build-schema
  "Build Malli schema from signature inputs/outputs."
  [{:keys [inputs outputs]}]
  (let [input-props (mapv (fn [k] [k string?]) inputs)
        output-props (mapv (fn [k] [k string?]) outputs)
        all-props (concat input-props output-props)]
    (into [:map {:closed false}] all-props)))

(defmacro defsignature
  "Define a signature for LLM module input/output contracts.

  Usage:
    (defsignature QA (question => answer))
    (defsignature Summarize (document => summary context))

  Creates a signature map with :inputs and :outputs vectors,
  plus Malli schema metadata for validation."
  [name arrows & doc]
  (let [sig-map (arrow->map arrows)
        schema (build-schema sig-map)
        doc-string (when (string? (first doc)) (first doc))]
    `(do
       (def ~name
         ~@(when doc-string [doc-string])
         (with-meta ~sig-map {:malli/schema ~schema}))
       (swap! registry assoc '~name ~name)
       (var ~name))))

(defn get-signature
  "Get signature by name from registry."
  [sig-name]
  (get @registry sig-name))

(defn list-signatures
  "List all registered signatures."
  []
  (keys @registry))

(defn validate-input
  "Validate input data against signature schema."
  [signature data]
  (when-let [schema (:malli/schema (meta signature))]
    (m/validate schema data)))

(defn generate-sample
  "Generate sample data for signature (useful for testing)."
  [signature]
  (when-let [schema (:malli/schema (meta signature))]
    (mg/generate schema)))