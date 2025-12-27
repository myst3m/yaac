(ns yaac.yaml
  "Simple YAML utilities using SnakeYAML directly.
   Replaces clj-yaml to avoid GraalVM native-image compatibility issues."
  (:require [clojure.walk :as walk])
  (:import [org.yaml.snakeyaml Yaml DumperOptions DumperOptions$FlowStyle]))

(defn- clj->java
  "Convert Clojure data structures to Java equivalents for SnakeYAML."
  [data]
  (cond
    (map? data) (java.util.LinkedHashMap.
                 (reduce-kv (fn [m k v]
                              (.put m (if (keyword? k) (name k) (str k)) (clj->java v))
                              m)
                            (java.util.LinkedHashMap.)
                            data))
    (sequential? data) (java.util.ArrayList. (mapv clj->java data))
    (keyword? data) (name data)
    :else data))

(defn generate-string
  "Convert Clojure data to YAML string."
  [data]
  (let [options (doto (DumperOptions.)
                  (.setDefaultFlowStyle DumperOptions$FlowStyle/BLOCK)
                  (.setPrettyFlow true))
        yaml (Yaml. options)]
    (.dump yaml (clj->java data))))
