(ns dspy.module
  "Core module abstraction for LLM components."
  (:require [manifold.deferred :as d]
            [dspy.signature :as sig]))

(defprotocol ILlmModule
  "Protocol for all LLM modules that can be composed into pipelines."
  (call [module inputs]
    "Call the module with given inputs. Returns a deferred that resolves to outputs.
     Inputs and outputs should conform to the module's signature if one is defined."))

;; Default implementation that wraps a function as an LLM module.
(defrecord FnModule [f signature metadata]
  ILlmModule
  (call [this inputs]
    (try
      ;; Validate inputs if signature is provided
      (when signature
        (when-not (sig/validate-input signature inputs)
          (throw (ex-info "Input validation failed"
                          {:signature signature
                           :inputs inputs
                           :module this}))))

      ;; Call the function and wrap result in deferred
      (let [result (f inputs)]
        (if (d/deferred? result)
          result
          (d/success-deferred result)))

      (catch Exception e
        (d/error-deferred e)))))

(defn fn-module
  "Create a module from a function.

  Options:
  - :signature - signature for input/output validation
  - :metadata - additional metadata about the module

  Usage:
    (fn-module (fn [inputs] {:result \"processed\"})
               :signature MySignature
               :metadata {:description \"A simple processor\"})"
  [f & {:keys [signature metadata]}]
  (->FnModule f signature metadata))

(defn wrap-fn
  "Convenience function to wrap a regular function as an LLM module.
   Automatically handles async conversion and validation."
  ([f] (fn-module f))
  ([f signature] (fn-module f :signature signature))
  ([f signature metadata] (fn-module f :signature signature :metadata metadata)))

(defn module?
  "Check if an object implements the ILlmModule protocol."
  [obj]
  (satisfies? ILlmModule obj))

(defn get-signature
  "Get the signature from a module if it has one."
  [module]
  (when (instance? FnModule module)
    (:signature module)))

(defn get-metadata
  "Get metadata from a module if it has any."
  [module]
  (when (instance? FnModule module)
    (:metadata module)))

(defn compose-modules
  "Compose two modules sequentially. Output of first becomes input of second.
   Returns a new module that represents the composition."
  [module1 module2]
  (fn-module
   (fn [inputs]
     (d/chain
      (call module1 inputs)
      #(call module2 %)))
   :metadata {:type :composition
              :modules [module1 module2]}))

(defn parallel-modules
  "Run multiple modules in parallel with the same inputs.
   Returns a new module that merges all outputs."
  [& modules]
  (fn-module
   (fn [inputs]
     (let [deferreds (map #(call % inputs) modules)]
       (d/chain
        (apply d/zip deferreds)
        (fn [results]
          (apply merge results)))))
   :metadata {:type :parallel
              :modules (vec modules)}))