(ns dspy.optimize.beam
  "Beam search optimization strategy for systematic pipeline improvement.

   Implements iterative widening beam search over pipeline candidates
   with concurrent evaluation and automatic candidate generation."
  (:require [manifold.deferred :as d]
            [dspy.optimize :as opt]
            [clojure.tools.logging :as log]))

;; Candidate generation functions

(defn mutate-pipeline
  "Generate variations of a pipeline by modifying prompts and parameters.

   For now, this is a simple implementation that can be enhanced later
   with more sophisticated mutation strategies."
  [pipeline iteration]
  (let [base-mutations ["Improve this by being more specific."
                        "Enhance the response quality."
                        "Think step by step."
                        "Consider the context more carefully."
                        "Be more precise in your answer."
                        "Focus on the key aspects."]]

    ;; Generate multiple candidate variations
    (mapv (fn [mutation-text]
            ;; This is a simplified mutation - in reality, we'd modify
            ;; the actual pipeline components more intelligently
            (with-meta pipeline
              {:mutation mutation-text
               :generation iteration
               :mutation-type :prompt-enhancement}))
          (take (+ 2 iteration) base-mutations))))

(defn generate-candidates
  "Generate candidate pipelines from current best performers.

   Returns vector of candidate pipelines to evaluate."
  [current-best iteration beam-width]
  (let [mutation-factor (max 2 (/ beam-width 2))]
    (->> current-best
         (mapcat #(mutate-pipeline % iteration))
         (take (* beam-width mutation-factor))
         vec)))

;; Evaluation and scoring

(defn score-candidate
  "Evaluate a single candidate pipeline against training data.

   Returns deferred that resolves to {:pipeline candidate :score score}."
  [candidate trainset metric]
  (d/chain
   (opt/evaluate-pipeline candidate trainset metric)
   (fn [score]
     {:pipeline candidate
      :score score
      :metadata (meta candidate)})))

(defn score-candidates
  "Evaluate multiple candidates concurrently with rate limiting.

   Returns deferred that resolves to vector of scored candidates."
  [candidates trainset metric concurrency]
  (let [candidate-count (count candidates)]
    (log/debug "Scoring candidates"
               {:count candidate-count
                :concurrency concurrency})

    ;; Use sequential evaluation with proper deferred handling
    (d/chain
     (reduce
      (fn [results-deferred candidate]
        (d/chain
         results-deferred
         (fn [results]
           (d/chain
            (score-candidate candidate trainset metric)
            (fn [scored-candidate]
              (conj results scored-candidate))))))
      (d/success-deferred [])
      candidates)
     (fn [results]
       (log/debug "Candidate scoring completed"
                  {:total-evaluated candidate-count
                   :scores (mapv :score results)})
       results))))

(defn select-top-candidates
  "Select the top-k candidates based on score.

   Returns vector of best candidates for next iteration."
  [scored-candidates beam-width]
  (->> scored-candidates
       (sort-by :score >)
       (take beam-width)
       (mapv :pipeline)))

;; Main beam search algorithm

(defn beam-search
  "Implement beam search optimization strategy.

   Iteratively generates and evaluates pipeline candidates,
   keeping only the best performers for the next generation."
  [initial-pipeline trainset metric {:keys [beam-width max-iterations concurrency]
                                     :or {beam-width 4
                                          max-iterations 10
                                          concurrency 8}}]
  ;; TODO: Add backend parameter when LLM modules need backend context for evaluation

  (let [start-time (System/currentTimeMillis)
        run-id (str "beam-" (System/currentTimeMillis))]

    (log/info "Starting beam search optimization"
              {:run-id run-id
               :beam-width beam-width
               :max-iterations max-iterations
               :concurrency concurrency
               :training-examples (count trainset)})

    (letfn [(beam-iteration [current-best iteration history]
              (if (>= iteration max-iterations)
                ;; Termination condition
                (let [best-candidate (first current-best)
                      best-score (:score (meta best-candidate))
                      total-time (- (System/currentTimeMillis) start-time)]

                  (log/info "Beam search completed"
                            {:iterations iteration
                             :best-score best-score
                             :total-time-ms total-time})

                  (d/success-deferred
                   {:best-pipeline best-candidate
                    :best-score (or best-score 0.0)
                    :history history
                    :total-iterations iteration
                    :total-time-ms total-time
                    :converged? (< (Math/abs (- best-score 1.0)) 0.01)}))

                ;; Continue iteration
                (let [iter-start (System/currentTimeMillis)]
                  (log/debug "Starting beam iteration" {:iteration iteration})

                  (d/chain
                   ;; Generate candidate variations
                   (let [candidates (generate-candidates current-best iteration beam-width)]
                     (log/debug "Generated candidates"
                                {:iteration iteration
                                 :candidate-count (count candidates)})
                     candidates)

                   ;; Score all candidates
                   (fn [candidates]
                     (score-candidates candidates trainset metric concurrency))

                   ;; Select best performers
                   (fn [scored-candidates]
                     (let [selected (select-top-candidates scored-candidates beam-width)
                           best-score (:score (first scored-candidates))
                           iter-time (- (System/currentTimeMillis) iter-start)

                           ;; Add iteration metadata
                           selected-with-meta (mapv #(with-meta % {:score best-score
                                                                   :iteration iteration})
                                                    selected)

                           ;; Record history entry
                           history-entry {:iteration iteration
                                          :score best-score
                                          :pipeline (first selected-with-meta)
                                          :timestamp (System/currentTimeMillis)
                                          :iteration-time-ms iter-time
                                          :candidates-evaluated (count scored-candidates)}]

                       (log/debug "Beam iteration completed"
                                  {:iteration iteration
                                   :best-score best-score
                                   :selected-count (count selected-with-meta)
                                   :iteration-time-ms iter-time})

                       ;; Continue to next iteration
                       (beam-iteration selected-with-meta
                                       (inc iteration)
                                       (conj history history-entry))))))))]

      ;; Start with initial pipeline evaluation
      (d/chain
       (score-candidate initial-pipeline trainset metric)
       (fn [initial-result]
         (let [initial-with-meta (with-meta (:pipeline initial-result)
                                   {:score (:score initial-result)
                                    :iteration 0})
               initial-history {:iteration 0
                                :score (:score initial-result)
                                :pipeline initial-with-meta
                                :timestamp (System/currentTimeMillis)}]

           (log/debug "Initial pipeline scored"
                      {:score (:score initial-result)})

           (beam-iteration [initial-with-meta] 1 [initial-history])))))))

;; Utility functions for beam search

(defn analyze-convergence
  "Analyze optimization history to detect convergence patterns."
  [history threshold]
  (if (< (count history) 3)
    {:converged? false :reason "Insufficient iterations"}
    (let [recent-scores (->> history (take-last 3) (mapv :score))
          score-variance (let [mean (/ (reduce + recent-scores) (count recent-scores))]
                           (/ (reduce + (map #(Math/pow (- % mean) 2) recent-scores))
                              (count recent-scores)))]
      {:converged? (< score-variance threshold)
       :variance score-variance
       :threshold threshold
       :recent-scores recent-scores})))

(defn suggest-beam-width
  "Suggest optimal beam width based on training set size and constraints."
  [trainset-size concurrency _max-time-budget]
  ;; TODO: Use max-time-budget for time-constrained beam width optimization
  (let [base-width (cond
                     (< trainset-size 10) 2
                     (< trainset-size 50) 4
                     (< trainset-size 200) 6
                     :else 8)

        ;; Adjust for concurrency constraints
        adjusted-width (min base-width (/ concurrency 2))

        ;; Ensure minimum viable beam
        final-width (max 2 adjusted-width)]

    (log/debug "Beam width suggestion"
               {:trainset-size trainset-size
                :base-width base-width
                :concurrency concurrency
                :suggested-width final-width})

    (int final-width)))