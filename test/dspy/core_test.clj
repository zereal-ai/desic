(ns dspy.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [dspy.core :as core]))

(deftest version-test
  (testing "version returns a string"
    (is (string? (core/version)))
    (is (seq (core/version)))))