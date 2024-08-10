(ns yaac.nrepl
  (:require [silvur.http :as http]
            [silvur.util :refer [edn->json json->edn]]
            [silvur.log :as log]
            [nrepl.core :as nrepl]
            [clojure.tools.cli :refer (parse-opts)]
            [clojure.string :as str]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]))

(defn usage []
  (->> ["Usage: nrepl <uri>"]
       (str/join)))

(def ^:dynamic *nrepl-host*)

(defn emit
  ([uri code]
   (http/post uri {:headers {"Content-Type" "application/json"
                             "Host" *nrepl-host*}
                   :body (edn->json {:op "eval" :code code})}))
  ([uri code ns]
   (http/post uri {:headers {"Content-Type" "application/json"
                             "Host" *nrepl-host*}
                   :body (edn->json {:op "eval" :ns ns :code code})})))

(def nrepl-cli-options [])

(defn cli [& args]
  (log/debug args)
  (let [{:keys [options arguments summary errors] :as command-context} (parse-opts
                                                                        (map name args) ;; To string
                                                                        nrepl-cli-options)
        dst (first arguments)
        headers (update-keys (into {} (mapv #(str/split % #":") (rest arguments))) keyword)
        cns (atom "user")
        prompt (fn [ns] (print (str ns "> ")))
        dst-uri (java.net.URI. dst)
        scheme (.getScheme dst-uri)
        dst-uri (if scheme dst (str "https://" dst))
        host (.getHost (java.net.URI. dst-uri))]
    
    (when-not dst
      (println (usage))
      (System/exit 0))
    
    (binding [*nrepl-host* (or (:Host headers) host)]
      (let [{:keys [error status]} (emit dst-uri ":trial")]
        (cond
          error (println "Not connected")
          (< 200 status) (println "URI invalid or no Host field in HTTP header")))
      (try
        (prompt @cns)
        (flush)
        (loop [exp (read-line)]
          (if-not (some? exp)
            (do
              (println "Quit")
              (System/exit 0))
            (let [{:keys [status body] :or {status 9999} :as response} (emit dst-uri exp @cns)
                  result (json->edn body)]
              (log/debug "URI:" dst-uri)
              (log/debug "scheme:" scheme)

              (when-let [new-ns (:ns result)]
                (reset! cns new-ns))
              
              (log/debug response)
              (log/debug result)
              (let [{:keys [value err out]} result]
                (cond
                  (< 200 status) (println "Not connected:" dst-uri)
                  err (println err)
                  :else (do
                          (and out (print out))
                          (flush)
                          (println value)))
                (prompt @cns)
                (flush)
                (recur (read-line))))))
        (catch java.net.ConnectException e (println (ex-message e)))))))
