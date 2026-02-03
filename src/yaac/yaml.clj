(ns yaac.yaml
  "Simple YAML utilities using SnakeYAML directly.
   Replaces clj-yaml to avoid GraalVM native-image compatibility issues."
  (:require [clojure.walk :as walk])
  (:import [org.yaml.snakeyaml Yaml DumperOptions DumperOptions$FlowStyle]
           [java.util Map List]))

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

(defn- java->clj
  "Convert Java data structures from SnakeYAML to Clojure equivalents."
  [data]
  (cond
    (instance? Map data) (into {} (map (fn [[k v]] [(keyword k) (java->clj v)]) data))
    (instance? List data) (mapv java->clj data)
    :else data))

(defn parse-string
  "Parse YAML string to Clojure data."
  [s]
  (let [yaml (Yaml.)]
    (java->clj (.load yaml s))))

(defn parse-file
  "Parse YAML file to Clojure data."
  [path]
  (parse-string (slurp path)))

(defn generate-string
  "Convert Clojure data to YAML string."
  [data]
  (let [options (doto (DumperOptions.)
                  (.setDefaultFlowStyle DumperOptions$FlowStyle/BLOCK)
                  (.setPrettyFlow true))
        yaml (Yaml. options)]
    (.dump yaml (clj->java data))))
