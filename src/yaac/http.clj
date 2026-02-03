(ns yaac.http
  (:gen-class)
  (:import [java.net URLEncoder])
  (:require [yaac.core :as yc :refer [*org* *env* *deploy-target* *no-cache* *no-multi-thread* *console*]]
            [reitit.core :as r]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]
            [taoensso.nippy :as nippy]
            [yaac.util :refer [json->edn edn->json]]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.tools.cli :refer (parse-opts)]
            [yaac.error :as e]
            [taoensso.timbre :as timbre]
            [zeph.client :as http]
            [jsonista.core :as json]
            [yaac.util :as util]
            [yaac.describe :as desc]
            [clojure.core.async :as async]
            [jansi-clj.core :as jansi]))


(defn usage [opts]
  (let [{:keys [summary help-all]} (if (map? opts) opts {:summary opts :help-all true})]
    (->> (concat
          ["Usage: http <app> [options] [key-values]"
           ""
           "Run HTTP request to an application"
           ""
           "Options:"
           ""
           summary
           ""]
          (when help-all
            ["Keys:"
             ""
             " If ':' is used as the first character, it is handled as a header"
             " (ex. :Authorization='Bearer token...')"
             ""])
          ["Example:"
           ""
           "# GET request with Authorization header"
           "  > yaac http account-app/account :Authorization='Bearer aaaa'"
           ""
           "# POST request to internal URL"
           "  > yaac http account-app/account -i -m POST id=abc"
           ""])
         (str/join \newline))))

(def options [["-i" "--internal" "Use internal URL"]
              ["-m" "--method <GET/POST/PUT/DELETE>" "Use specified method"
               :parse-fn (comp keyword str/upper-case)]])


(defn request [{:keys [args internal  method]
                :as opts}]
  (let [app-name (last (re-find #"(app://)*([^/]+)\/*.*" (last args)))
        path (last (re-find #"(app://)*[^/]+(\/*.*)" (last args)))
        [app-context] (desc/describe-application {:args (assoc-in (vec args) [(dec (count args))] app-name)})
        {:keys [public-url internal-url]} (-> app-context :target :deployment-settings :http :inbound)
        method-fn (condp = method
                    :GET http/get
                    :POST http/post
                    :PUT http/put
                    :DELETE http/delete
                    http/get)]

    (log/debug "URL:" (str (or (and internal internal-url) public-url )
                           path))
    (log/debug "Method:" method-fn)

    (let [url (str (or (and internal internal-url)
                       public-url
                       (throw (e/app-not-found "No app found or not running CloudHub 2.0")))
                   path)
          ;; Expected array type values are given by command line.
          params (filter #(sequential? (last %)) (dissoc opts :args))
          ;; params {::a ["bbb"] :x ["zzz"]}
          header-params (->> (filter #(first (re-find #"^:.*" (name (first %)))) params)
                             (map #(vector (subs (name (first %)) 1) (str/replace (first (last %)) #"\+" " ")))
                             (into {}))
          body-params (->> (filter #(not (first (re-find #"^:.*" (name (first %))))) params)
                           (map #(let [k (name (first %))
                                       v  (first (last %))]
                                   (if-let [ak (second (re-find #"(.*):"  k))]
                                     [(subs ak (dec (count ak))) (or (parse-long v) (parse-double v))]
                                     [k v])))
                           (into {}))

          ]
      
      (->> (method-fn url {:headers header-params
                           :body (edn->json body-params)})
           (yc/parse-response :raw)
           :body))))

(def route
  ["http" {:options options
           :usage usage}
   ["" {:help true}]
   ["|{*args}" {:handler request
                :output-format :raw}]])
