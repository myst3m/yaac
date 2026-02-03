;   Copyright (c) Tsutomu Miyashita. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns yaac.util
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske])
  (:import [java.io BufferedReader InputStreamReader]))

(defn json->edn
  "Parse JSON string to EDN with key transformation.
   type: :kebab (default), :camel, or nil (keyword only)"
  ([x]
   (json->edn :kebab x))
  ([type x]
   (try
     (cond->> (json/read-value x)
       (= type :kebab) (cske/transform-keys csk/->kebab-case-keyword)
       (= type :camel) (cske/transform-keys csk/->camelCase)
       :else (cske/transform-keys keyword))
     (catch Exception _e x))))

(defn edn->json
  "Convert EDN to JSON string with key transformation.
   type: :camel (default), :pascal, :kebab, :snake, :header"
  ([x]
   (edn->json :camel x))
  ([type x]
   (->> x (cske/transform-keys
           (case type
             :pascal csk/->PascalCaseString
             :kebab csk/->kebab-case-string
             :camel csk/->camelCaseString
             :snake csk/->snake_case_string
             :header csk/->HTTP-Header-Case-String
             name))
        (json/write-value-as-string))))

(def pretty-mapper
  "ObjectMapper for pretty-printing JSON"
  (json/object-mapper {:pretty true}))

(defn json-pprint
  "Pretty print data as JSON string"
  [data]
  (json/write-value-as-string data pretty-mapper))

(defn console [xs]
  (if (sequential? xs)
    (println (str/join \newline (keep identity xs)))
    (println xs)))

(defn confirm-prompt
  "削除前の確認プロンプト。itemsの各要素を表示し、y/yesで続行、それ以外で中止。"
  [message items]
  (println message)
  (doseq [item items]
    (println (format "  - %s (%s)"
                     (or (:name item) (:asset-id item) (:id item) "?")
                     (str (when-let [o (or (get-in item [:extra :org]) (:_org item))]
                            (str o "/"))
                          (or (get-in item [:extra :env]) (:_env item) "")))))
  (print "\nContinue? [y/N]: ")
  (flush)
  (let [reader (BufferedReader. (InputStreamReader. System/in))
        input (try (.readLine reader) (catch Exception _ nil))]
    (contains? #{"y" "yes"} (some-> input str/lower-case str/trim))))

(defn spin
  "Print a spinner message. Use (spin) to clear."
  ([] (print "\r\033[K") (flush))
  ([msg] (print (str "\r⠿ " msg)) (flush)))

(defmacro with-spin
  "Execute body while showing a spinner message."
  [msg & body]
  `(try
     (spin ~msg)
     ~@body
     (finally (spin))))
