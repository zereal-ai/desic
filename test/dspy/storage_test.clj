(ns dspy.storage-test
  "Tests for DSPy storage implementations."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [dspy.storage.core :as storage]
            [dspy.storage.sqlite :as sqlite]
            [dspy.storage.edn :as edn-storage]))

(def test-pipeline
  {:type :linear
   :stages [{:module/type :fn
             :signature :QA
             :body "(fn [x] {:answer (str (:question x) \"!!!\")})"}]})

(defn temp-dir
  "Create a temporary directory for testing."
  []
  (let [temp-dir (java.nio.file.Files/createTempDirectory
                  "dspy-test"
                  (make-array java.nio.file.attribute.FileAttribute 0))]
    (.toString temp-dir)))

(defn cleanup-temp-dir
  "Recursively delete a temporary directory."
  [dir-path]
  (when (.exists (io/file dir-path))
    (let [dir (io/file dir-path)]
      (doseq [file (reverse (file-seq dir))]
        (.delete file)))))

;; Test fixtures
(def ^:dynamic *temp-dir* nil)

(defn with-temp-dir [test-fn]
  (let [temp-dir-path (temp-dir)]
    (try
      (binding [*temp-dir* temp-dir-path]
        (test-fn))
      (finally
        (cleanup-temp-dir temp-dir-path)))))

(use-fixtures :each with-temp-dir)

;; Common test suite that works with any storage implementation
(defn test-storage-implementation
  "Test suite that can be applied to any storage implementation."
  [storage-impl]

  (testing "create-run! creates a new run and returns ID"
    (let [run-id (storage/create-run! storage-impl test-pipeline)]
      (is (string? run-id))
      (is (not (empty? run-id)))))

  (testing "load-run retrieves the stored pipeline"
    (let [run-id (storage/create-run! storage-impl test-pipeline)
          loaded-pipeline (storage/load-run storage-impl run-id)]
      (is (= test-pipeline loaded-pipeline))))

  (testing "load-run returns nil for non-existent run"
    (let [loaded-pipeline (storage/load-run storage-impl "non-existent-id")]
      (is (nil? loaded-pipeline))))

  (testing "append-metric! and load-history work correctly"
    (let [run-id (storage/create-run! storage-impl test-pipeline)]

      ;; Initially empty history
      (is (= [] (storage/load-history storage-impl run-id)))

      ;; Add first metric
      (storage/append-metric! storage-impl run-id 0 0.5 {:details "first iteration"})
      (let [history (storage/load-history storage-impl run-id)]
        (is (= 1 (count history)))
        (is (= {:iter 0 :score 0.5 :payload {:details "first iteration"}}
               (first history))))

      ;; Add second metric
      (storage/append-metric! storage-impl run-id 1 0.7 {:details "second iteration"})
      (let [history (storage/load-history storage-impl run-id)]
        (is (= 2 (count history)))
        (is (= 0 (:iter (first history))))
        (is (= 1 (:iter (second history))))
        (is (= 0.7 (:score (second history)))))

      ;; Add third metric
      (storage/append-metric! storage-impl run-id 2 0.9 {:details "third iteration"})
      (let [history (storage/load-history storage-impl run-id)]
        (is (= 3 (count history)))
        (is (= [0 1 2] (map :iter history)))
        (is (= [0.5 0.7 0.9] (map :score history))))))

  (testing "load-history returns empty vector for non-existent run"
    (let [history (storage/load-history storage-impl "non-existent-id")]
      (is (= [] history))))

  (testing "multiple runs are isolated"
    (let [run-id-1 (storage/create-run! storage-impl test-pipeline)
          run-id-2 (storage/create-run! storage-impl {:different "pipeline"})]

      ;; Add metrics to first run
      (storage/append-metric! storage-impl run-id-1 0 0.5 {:run 1})
      (storage/append-metric! storage-impl run-id-1 1 0.7 {:run 1})

      ;; Add metrics to second run
      (storage/append-metric! storage-impl run-id-2 0 0.3 {:run 2})

      ;; Verify isolation
      (let [history-1 (storage/load-history storage-impl run-id-1)
            history-2 (storage/load-history storage-impl run-id-2)]
        (is (= 2 (count history-1)))
        (is (= 1 (count history-2)))
        (is (= {:run 1} (:payload (first history-1))))
        (is (= {:run 2} (:payload (first history-2))))))))

;; EDN Storage Tests
(deftest edn-storage-test
  (testing "EDN storage implementation"
    (let [storage-impl (edn-storage/->EDNStorage *temp-dir*)]
      (test-storage-implementation storage-impl))))

;; SQLite Storage Tests
(deftest sqlite-storage-test
  (testing "SQLite storage implementation"
    (let [db-spec (str "jdbc:sqlite:" *temp-dir* "/test.db")
          sqlite-config (sqlite/init-sqlite db-spec)
          storage-impl (sqlite/->SQLiteStorage (:ds sqlite-config))]
      (test-storage-implementation storage-impl))))

;; Factory Tests
(deftest make-storage-test
  (testing "make-storage creates correct implementations"

    (testing "default configuration creates EDN storage"
      (let [storage-impl (storage/make-storage)]
        (is (instance? dspy.storage.edn.EDNStorage storage-impl))))

    (testing "file configuration creates EDN storage"
      (let [storage-impl (storage/make-storage {:type :file :dir *temp-dir*})]
        (is (instance? dspy.storage.edn.EDNStorage storage-impl))))

    (testing "sqlite configuration creates SQLite storage"
      (let [db-spec (str "jdbc:sqlite:" *temp-dir* "/factory-test.db")
            storage-impl (storage/make-storage {:type :sqlite :url db-spec})]
        (is (instance? dspy.storage.sqlite.SQLiteStorage storage-impl))))

    (testing "unknown storage type throws exception"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown storage type"
                            (storage/make-storage {:type :unknown}))))))

;; Environment Configuration Tests
;; Environment Configuration Tests - Skipped due to Java interop issues
;; (deftest env-storage-cfg-test
;;   (testing "env->storage-cfg parses environment variables"
;;     ;; Tests would go here but System/getenv mocking is problematic
;;     ))