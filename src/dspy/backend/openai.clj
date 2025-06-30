(ns dspy.backend.openai
  "OpenAI backend implementation - temporarily using mock for testing.

   This namespace provides a concrete implementation of the ILlmBackend protocol
   for OpenAI's API, with full async support using Manifold deferreds."
  (:require [dspy.backend.protocol :as bp]
            [manifold.deferred :as d]))

;; Configuration and setup

(def ^:private default-api-key
  "Default API key from environment variable."
  (System/getenv "OPENAI_API_KEY"))

(def ^:private default-models
  "Default model configurations."
  {:chat "gpt-4o-mini"
   :embeddings "text-embedding-3-small"})

(defn- get-api-key
  "Get API key from options or environment."
  [opts]
  (or (:api-key opts)
      default-api-key
      "mock-api-key-for-testing"))

;; Mock OpenAI functions (temporary)
(defn- mock-create-chat-completion
  "Mock chat completion for testing."
  [request]
  {:choices [{:message {:content (str "Mock response to: " (-> request :messages first :content))}
              :finish_reason "stop"}]
   :usage {:prompt_tokens 10
           :completion_tokens 25
           :total_tokens 35}
   :model (:model request)})

(defn- mock-create-embeddings
  "Mock embeddings for testing."
  [request]
  {:data [{:embedding [0.1 0.2 0.3 0.4 0.5]}]
   :usage {:prompt_tokens 5
           :total_tokens 5}
   :model (:model request)})

(defn- mock-list-models
  "Mock model listing for testing."
  [_request]
  {:data [{:id "gpt-4o-mini"}
          {:id "gpt-4o"}
          {:id "text-embedding-3-small"}]})

;; OpenAI Backend Implementation

(defrecord OpenAIBackend [api-key default-model default-embedding-model]
  bp/ILlmBackend

  (-generate [_this prompt opts]
    (d/future
      (try
        (let [api-key (get-api-key (assoc opts :api-key api-key))
              model (or (:model opts) default-model)
              temperature (or (:temperature opts) 0.7)
              max-tokens (or (:max-tokens opts) 512)

              ;; Using mock for now - replace with real OpenAI call later
              response (mock-create-chat-completion
                        {:api-key api-key
                         :model model
                         :messages [{:role "user" :content prompt}]
                         :temperature temperature
                         :max_tokens max-tokens})]

          (if-let [error (:error response)]
            (throw (ex-info "OpenAI API error"
                            {:error error
                             :prompt prompt
                             :model model}))

            {:text (-> response :choices first :message :content)
             :usage {:prompt-tokens (-> response :usage :prompt_tokens)
                     :completion-tokens (-> response :usage :completion_tokens)
                     :total-tokens (-> response :usage :total_tokens)}
             :model model
             :finish-reason (-> response :choices first :finish_reason)}))

        (catch Exception e
          (throw (ex-info "OpenAI generation failed"
                          {:prompt prompt
                           :options opts
                           :cause (.getMessage e)}
                          e))))))

  (-embeddings [_this text opts]
    (d/future
      (try
        (let [api-key (get-api-key (assoc opts :api-key api-key))
              model (or (:model opts) default-embedding-model)

              ;; Using mock for now - replace with real OpenAI call later
              response (mock-create-embeddings
                        {:api-key api-key
                         :model model
                         :input text})]

          (if-let [error (:error response)]
            (throw (ex-info "OpenAI embeddings API error"
                            {:error error
                             :text text
                             :model model}))

            {:vector (-> response :data first :embedding)
             :model model
             :usage {:prompt-tokens (-> response :usage :prompt_tokens)
                     :total-tokens (-> response :usage :total_tokens)}}))

        (catch Exception e
          (throw (ex-info "OpenAI embeddings failed"
                          {:text text
                           :options opts
                           :cause (.getMessage e)}
                          e))))))

  (-stream [_this _prompt _opts]
    ;; OpenAI streaming is complex and not essential for MVP
    ;; Return nil for now - can be implemented later
    (d/success-deferred nil)))

;; Public constructors

(defn ->backend
  "Create an OpenAI backend instance.

   Options:
     :api-key - OpenAI API key (defaults to OPENAI_API_KEY env var)
     :model - Default chat model (defaults to gpt-4o-mini)
     :embedding-model - Default embedding model (defaults to text-embedding-3-small)

   Example:
     (->backend)
     (->backend {:model \"gpt-4o\" :api-key \"sk-...\"})

   Returns:
     OpenAI backend instance satisfying ILlmBackend protocol"
  ([]
   (->backend {}))
  ([opts]
   (let [api-key (get-api-key opts)
         model (or (:model opts) (:chat default-models))
         embedding-model (or (:embedding-model opts) (:embeddings default-models))]
     (->OpenAIBackend api-key model embedding-model))))

;; Register with backend registry

(defmethod bp/create-backend :openai [config]
  (->backend config))

;; Utility functions

(defn models
  "Get list of available OpenAI models."
  ([]
   (models {}))
  ([opts]
   (d/future
     (try
       (let [api-key (get-api-key opts)
             ;; Using mock for now - replace with real OpenAI call later
             response (mock-list-models {:api-key api-key})]
         (if-let [error (:error response)]
           (throw (ex-info "Failed to list models" {:error error}))
           (mapv :id (:data response))))
       (catch Exception e
         (throw (ex-info "OpenAI models list failed"
                         {:options opts
                          :cause (.getMessage e)}
                         e)))))))

(defn validate-connection
  "Validate OpenAI API connection by making a simple request.

   Returns deferred containing true if connection is valid, throws otherwise."
  ([]
   (validate-connection {}))
  ([opts]
   (d/chain
    (bp/generate (->backend opts) "Hi" {:max-tokens 1})
    (constantly true))))

;; Development helpers

(defn health-check
  "Perform a health check on the OpenAI backend.

   Returns deferred containing health status map."
  ([]
   (health-check {}))
  ([opts]
   (d/chain
    (d/zip
     (d/catch
      (models opts)
      (constantly :models-failed))
     (d/catch
      (validate-connection opts)
      (constantly :connection-failed)))

    (fn [[models-result connection-result]]
      {:models-available (not= :models-failed models-result)
       :connection-ok (not= :connection-failed connection-result)
       :models-count (if (vector? models-result) (count models-result) 0)
       :status (if (and (not= :models-failed models-result)
                        (not= :connection-failed connection-result))
                 :healthy
                 :unhealthy)}))))