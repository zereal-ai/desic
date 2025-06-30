(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.example/desic)
(def version (or (System/getenv "DSPY_VERSION")
                 (format "SNAPSHOT.%s" (b/git-count-revs nil))))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file (format "target/%s-%s-standalone.jar"
                       (name lib) version))

(defn clean [_]
  (b/delete {:path "target"})
  (println "Cleaned target directory"))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/compile-clj {:basis basis :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     basis
           :main      'dspy.cli})
  (println "Built" uber-file))