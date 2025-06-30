(ns dspy.backend.protocol
  "Protocol definitions for LLM backend implementations.

   This namespace defines the core abstraction layer that all LLM backends must implement.
   All methods return Manifold deferreds for async operations."
  (:require [manifold.deferred :as d]))

(defprotocol ILlmBackend
  "Protocol for LLM backend implementations.

   All methods are async and return Manifold deferreds containing the results.
   This enables non-blocking concurrent operations throughout the system."

  (-generate [this prompt opts]
    "Generate text completion from prompt.

     Args:
       prompt - String input to generate from
       opts   - Map of generation options:
                {:temperature 0.7
                 :max-tokens 512
                 :timeout-ms 30000
                 :model \"gpt-4o-mini\"}

     Returns:
       Deferred containing map:
       {:text \"generated response\"
        :usage {:prompt-tokens 10 :completion-tokens 25}
        :model \"gpt-4o-mini\"}")

  (-embeddings [this text opts]
    "Generate embedding vector for text.

     Args:
       text - String to embed
       opts - Map of embedding options:
              {:model \"text-embedding-3-small\"
               :timeout-ms 30000}

     Returns:
       Deferred containing map:
       {:vector [0.1 -0.2 0.3 ...]
        :model \"text-embedding-3-small\"
        :usage {:prompt-tokens 8}}")

  (-stream [this prompt opts]
    "Stream text generation (optional, can return nil if not supported).

     Args:
       prompt - String input to generate from
       opts   - Map of streaming options

     Returns:
       Deferred containing Manifold stream of partial results, or nil"))

;; Public API functions with default options

(defn generate
  "Generate text completion using the backend.

   Public wrapper that provides default options and calls the protocol method.

   Example:
     @(generate backend \"What is machine learning?\" {:temperature 0.5})"
  ([backend prompt]
   (generate backend prompt {}))
  ([backend prompt opts]
   (-generate backend prompt opts)))

(defn embeddings
  "Generate embedding vector for text.

   Public wrapper that provides default options and calls the protocol method.

   Example:
     @(embeddings backend \"hello world\")"
  ([backend text]
   (embeddings backend text {}))
  ([backend text opts]
   (-embeddings backend text opts)))

(defn stream
  "Stream text generation if supported by backend.

   Returns nil if streaming is not supported by the backend.

   Example:
     (when-let [stream @(stream backend \"Tell me a story\")]
       (manifold.stream/consume println stream))"
  ([backend prompt]
   (stream backend prompt {}))
  ([backend prompt opts]
   (-stream backend prompt opts)))

;; Backend registry for dynamic loading

(defmulti create-backend
  "Create a backend instance from configuration.

   Dispatches on the :provider key in the config map (with backward compatibility for :type).

   Example config:
     {:provider :openai
      :model \"gpt-4o-mini\"
      :api-key \"sk-...\"}

     {:provider :anthropic
      :model \"claude-3-sonnet\"
      :api-key \"sk-ant-...\"}

   Legacy config (backward compatibility):
     {:type :openai
      :model \"gpt-4o-mini\"
      :api-key \"sk-...\"}

   Returns:
     Backend instance satisfying ILlmBackend protocol"
  (fn [config] (or (:provider config) (:type config))))

(defmethod create-backend :default [config]
  (throw (ex-info "Unknown backend provider"
                  {:provider (or (:provider config) (:type config))
                   :supported-providers [:openai]
                   :config config})))

(defn get-available-providers
  "Get list of available backend providers.

   Inspects the create-backend multimethod to determine which providers are registered."
  []
  (->> (methods create-backend)
       (keys)
       (remove #(= % :default))
       (sort)
       (vec)))

;; Utility functions

(defn backend?
  "Check if object satisfies ILlmBackend protocol."
  [obj]
  (satisfies? ILlmBackend obj))

(defn with-timeout
  "Wrap a deferred with a timeout.

   Returns {:status :ok :value result} or {:status :timeout}"
  [deferred timeout-ms]
  (let [timeout-d (d/future
                    (Thread/sleep timeout-ms)
                    {:status :timeout})]
    (d/alt
     (d/chain deferred (fn [result] {:status :ok :value result}))
     timeout-d)))