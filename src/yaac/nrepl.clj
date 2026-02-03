(ns yaac.nrepl
  (:require [zeph.client :as http]
            [yaac.util :refer [edn->json json->edn]]
            [taoensso.timbre :as log]
            [nrepl.core :as nrepl]
            [clojure.tools.cli :refer (parse-opts)]
            [clojure.string :as str]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [yaac.core :as yc :refer [*org* *env*]]
            [yaac.describe :as yd]))

(def ^:dynamic *nrepl-host*)

(defn emit
  ([uri code]
   @(http/post uri {:headers {"Content-Type" "application/json"
                              "Host" *nrepl-host*}
                    :body (edn->json {:op "eval" :code code})}))
  ([uri code ns]
   @(http/post uri {:headers {"Content-Type" "application/json"
                              "Host" *nrepl-host*}
                    :body (edn->json {:op "eval" :ns ns :code code})})))

(def nrepl-cli-options [["-i" "--internal" "Use internal URL"]
                        ["-h" "--help" "This help"]])

(defn usage [opts]
  (let [{:keys [summary help-all]} (if (map? opts) opts {:summary opts :help-all true})]
    (->> (concat
          ["Usage: nrepl [org] [env] <app> [options]"
           ""
           "Connect to a deployed Mule application's nREPL endpoint."
           ""
           "Options:"
           ""
           summary
           ""]
          (when help-all
            ["URI Formats:"
             "  app://app-name/nrepl       Auto-resolve URL from application deployment"
             "  https://host/nrepl         Direct HTTPS connection"
             "  http://host/nrepl          Direct HTTP connection"
             ""])
          ["Example:"
           ""
           "# Connect to app in current org/env"
           "  > yaac nrepl app://hello-api/nrepl"
           ""
           "# Connect with explicit org/env"
           "  > yaac nrepl T1 Production app://hello-api/nrepl"
           ""
           "# Use internal URL"
           "  > yaac nrepl app://hello-api/nrepl -i"
           ""])
         (str/join \newline))))


(defn destination-url [org env app-uri & options]
  (let [app-uri (java.net.URI. app-uri)]
    (condp = (.getScheme app-uri)
      "http"  (str app-uri)
      "https" (str app-uri)
      "app"  (do
               (or (cond-> (yd/describe-application {:args [org env (.getHost app-uri)]})
                     (not (:internal options)) (-> first :target :deployment-settings :http :inbound :public-url (str (.getPath app-uri)))
                     (:internal options) (-> first :target :deployment-settings :http :inbound :internal-url (str (.getPath app-uri))))
                   (str (.getHost app-uri))))
      (println "Need to give URI format as app://app-name/nrepl or https://app-host/nrepl"))))

(defn cli [& args]
  (log/debug args)
  (let [{:keys [options arguments summary errors] :as command-context} (parse-opts
                                                                        (map name args) ;; To string
                                                                        nrepl-cli-options)
        [app-uri env org] (reverse arguments)
        env (or env *env*)
        org (or org *org*)]

    (when (:help options)
      (println)
      (println (usage summary))
      (println)
      (System/exit 0))

    (when-not app-uri
      (throw (ex-info "No app specified" {})))

    (let [dst (destination-url org env app-uri options)
          headers (->> arguments
                       (filter #(re-find #"^[A-Z]+:[^/]+" %))
                       (mapv #(str/split % #":"))
                       (map #(update-in % [0] keyword))
                       (into {}))
          cns (atom "user")
          prompt (fn [ns] (print (str ns "> ")))
          dst-uri (java.net.URI. dst)
          scheme (.getScheme dst-uri)
          dst-uri (if scheme dst (str "https://" dst))
          host (.getHost (java.net.URI. dst-uri))]
      (binding [*nrepl-host* (or (:Host headers) host)]
        (let [{:keys [error status]} (emit dst-uri ":trial")]
          (cond
            error (do
                    (log/debug error)
                    (println "Could not connect"))
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
          (catch java.net.ConnectException e (println (ex-message e))))))))
