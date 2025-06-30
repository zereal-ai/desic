(ns dspy.backend.providers.openai
  "OpenAI backend implementation using the openai-clojure library.

   This namespace provides a concrete implementation of the ILlmBackend protocol
   for OpenAI's API, leveraging the battle-tested openai-clojure library
   instead of reimplementing HTTP calls."
  (:require [dspy.backend.protocol :as bp]
            [manifold.deferred :as d]
            [wkok.openai-clojure.api :as openai]))

;; Configuration and setup

(def ^:private default-models
  "Default model configurations."
  {:chat "gpt-4o-mini"
   :embeddings "text-embedding-3-small"})

(defn- get-api-key
  "Get API key from options or environment."
  [opts]
  (or (:api-key opts)
      (System/getenv "OPENAI_API_KEY")
      "mock-api-key-for-testing"))

(defn- prepare-openai-options
  "Prepare options for openai-clojure calls."
  [opts api-key]
  (cond-> {}
    api-key (assoc-in [:api-key] api-key)
    (:organization opts) (assoc-in [:organization] (:organization opts))))

;; OpenAI Backend Implementation

(defrecord OpenAIBackend [api-key default-model default-embedding-model organization]
  bp/ILlmBackend

  (-generate [_this prompt opts]
    (d/future
      (try
        (let [effective-api-key (or (:api-key opts) api-key)
              model (or (:model opts) default-model)
              temperature (or (:temperature opts) 0.7)
              max-tokens (or (:max-tokens opts) 512)

              ;; Prepare OpenAI API call
              openai-request {:model model
                              :messages [{:role "user" :content prompt}]
                              :temperature temperature
                              :max_tokens max-tokens}

              openai-options (prepare-openai-options opts effective-api-key)

              ;; Call OpenAI using openai-clojure library
              response (openai/create-chat-completion openai-request openai-options)]

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
        (let [effective-api-key (or (:api-key opts) api-key)
              model (or (:model opts) default-embedding-model)

              ;; Prepare OpenAI API call
              openai-request {:model model
                              :input text}

              openai-options (prepare-openai-options opts effective-api-key)

              ;; Call OpenAI using openai-clojure library
              response (openai/create-embedding openai-request openai-options)]

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
    ;; OpenAI streaming support can be added later using openai-clojure's streaming
    ;; For now, return nil to indicate streaming is not implemented
    (d/success-deferred nil)))

;; Public constructors

(defn ->backend
  "Create an OpenAI backend instance using the openai-clojure library.

   DEPRECATED: Use bp/create-backend with {:provider :openai} instead.
   This function is maintained for backward compatibility with existing code.

   Options:
     :api-key - OpenAI API key (defaults to OPENAI_API_KEY env var)
     :model - Default chat model (defaults to gpt-4o-mini)
     :embedding-model - Default embedding model (defaults to text-embedding-3-small)
     :organization - OpenAI organization (optional)

   Example:
     (->backend)
     (->backend {:model \"gpt-4o\" :api-key \"sk-...\"})

   Recommended new approach:
     (bp/create-backend {:provider :openai :model \"gpt-4o\" :api-key \"sk-...\"})

   Returns:
     OpenAI backend instance satisfying ILlmBackend protocol"
  ([]
   (->backend {}))
  ([opts]
   (let [api-key (get-api-key opts)
         model (or (:model opts) (:chat default-models))
         embedding-model (or (:embedding-model opts) (:embeddings default-models))
         organization (:organization opts)]
     (->OpenAIBackend api-key model embedding-model organization))))

;; Register with backend registry

(defmethod bp/create-backend :openai [config]
  "Create OpenAI backend from provider-agnostic configuration.

   This is the recommended way to create an OpenAI backend instance.
   The backend uses the openai-clojure library for robust API integration.

   Required config:
     :provider - Must be :openai

   Optional config:
     :api-key - OpenAI API key (defaults to OPENAI_API_KEY env var)
     :model - Default chat model (defaults to gpt-4o-mini)
     :embedding-model - Default embedding model (defaults to text-embedding-3-small)
     :organization - OpenAI organization (optional)

   Example:
     (bp/create-backend {:provider :openai})
     (bp/create-backend {:provider :openai
                         :model \"gpt-4o\"
                         :api-key \"sk-...\"})

   Returns:
     OpenAI backend instance satisfying ILlmBackend protocol"
  (let [api-key (get-api-key config)
        model (or (:model config) (:chat default-models))
        embedding-model (or (:embedding-model config) (:embeddings default-models))
        organization (:organization config)]
    (->OpenAIBackend api-key model embedding-model organization)))

;; Utility functions

(defn models
  "Get list of available OpenAI models using the openai-clojure library."
  ([]
   (models {}))
  ([opts]
   (d/future
     (try
       (let [effective-api-key (get-api-key opts)
             openai-options (prepare-openai-options opts effective-api-key)
             response (openai/list-models openai-options)]

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