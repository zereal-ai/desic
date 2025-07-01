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
  (let [input-props (mapv (fn [k] [k :string]) inputs)
        output-props (mapv (fn [k] [k :string]) outputs)
        all-props (concat input-props output-props)
        schema-def (into [:map {:closed false}] all-props)]
    (m/schema schema-def)))

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

(defn spec-of
  "Get the Malli schema of a signature."
  [signature]
  (:malli/schema (meta signature)))

(defn validate
  "Validate data against a signature's schema.

   Args:
     sig - Signature (either the signature map or its name)
     value - Data to validate

   Returns:
     true if valid, false otherwise"
  [sig value]
  (let [signature (if (keyword? sig) (get-signature sig) sig)]
    (validate-input signature value)))

(defn validate-output
  "Validate output data against signature schema.

   This is the same as validate-input but provides semantic clarity."
  [signature data]
  (validate-input signature data))

(defn explain
  "Explain why validation failed.

   Args:
     signature - Signature to validate against
     data - Data that failed validation

   Returns:
     Human-readable explanation of validation failure"
  [signature data]
  (when-let [schema (:malli/schema (meta signature))]
    (m/explain schema data)))

(defn generate-examples
  "Generate multiple example data points for a signature.

   Args:
     signature - Signature to generate examples for
     n - Number of examples to generate (default: 5)

   Returns:
     Vector of generated example data"
  ([signature]
   (generate-examples signature 5))
  ([signature n]
   (when-let [schema (:malli/schema (meta signature))]
     (repeatedly n #(mg/generate schema)))))

(defn signature-info
  "Get comprehensive information about a signature.

   Returns:
     Map with signature details including inputs, outputs, schema, examples"
  [signature]
  {:inputs (:inputs signature)
   :outputs (:outputs signature)
   :schema (:malli/schema (meta signature))
   :examples (generate-examples signature 3)
   :registry-name (first (keep (fn [[k v]] (when (= v signature) k)) @registry))})