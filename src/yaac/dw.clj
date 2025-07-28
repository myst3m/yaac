(ns yaac.dw
  (:import [org.mule.weave.v2.runtime DataWeaveScriptingEngine ScriptingBindings]
           [java.util HashMap])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn execute-dataweave
  "Execute a DataWeave script with given payload and variables"
  [script payload & {:keys [payload-mime-type vars]
                     :or {payload-mime-type "application/json"
                          vars {}}}]
  (try
    (let [engine (DataWeaveScriptingEngine.)
          compiled-script (.compile engine script (into-array String ["payload" "vars"]))
          bindings (doto (ScriptingBindings.)
                     (.addBinding "payload" payload payload-mime-type (HashMap.))
                     (.addBinding "vars" vars "application/java" (HashMap.)))
          result (.write compiled-script bindings)]
      (.getContentAsString result))
    (catch Exception e
      (throw (ex-info "DataWeave execution failed" 
                      {:script script :payload payload :vars vars} 
                      e)))))

(defn cli [& [_ script-path input-payload]]
  (if script-path
    (let [script (slurp script-path)]
      (println (execute-dataweave script input-payload)))
    (println (execute-dataweave "output json --- payload" 
                                (or input-payload "{}")))))

(defn dw-handler [{:keys [args]}]
  (apply cli "dw" args))

(defn usage [options-summary]
  (str/join \newline ["Usage: yaac dw [script-path] [input-payload]"
                      ""
                      "Execute DataWeave scripts with given payload."
                      ""
                      "Options:"
                      ""
                      options-summary
                      ""
                      "Examples:"
                      "  yaac dw script.dwl '{\"name\":\"John\"}'"
                      "  yaac dw  # Uses default script and payload"
                      ""]))

(def options [])

(def route
  ["dw" {:options options
         :usage usage
         :no-token true}
   ["" {:help true}]
   ["|{*args}" {:handler dw-handler}]])


