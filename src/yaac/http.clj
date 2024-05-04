(ns yaac.http
  (:gen-class)
  (:import [java.net URLEncoder])
  (:require [yaac.core :as yc :refer [*org* *env* *deploy-target* *no-cache* *no-multi-thread* *console*]]
            [reitit.core :as r]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [silvur.log :as log]
            [taoensso.nippy :as nippy]
            [silvur.util :refer [json->edn edn->json]]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.tools.cli :refer (parse-opts)]
            [yaac.error :as e]
            [taoensso.timbre :as timbre]
            [silvur.http :as http]
            [clojure.data.json :as json]
            [yaac.util :as util]
            [yaac.describe :as desc]
            [clojure.core.async :as async]
            [jansi-clj.core :as jansi]))


(defn usage [summary-options]
  (->> ["Usage: http <app> [options] [key-values]"
        ""
        "Run HTTP request to an application"
        ""
        "Options:"
        ""
        summary-options
        ""
        "Keys:"
        "  path   Resource path (ex. path=/account"
        ""
        "Example:"
        ""
        "# Run HTTP GET request to public URL of account-app with /account "
        "  > yaac http account-app path=/account"
        ""
        "# Run HTTP GET request to internal URL of account-app with /account "
        " > yaac http account-app path=/account -i"
        ""
        "# Run HTTP POST request to internal URL of account-app with /account "
        ""
        " > yaac http account-app path=/account -i -m POST id=abc"
        ""]
       (str/join \newline)))

(def options [["-i" "--internal" "Use internal URL"]
              ["-m" "--method <GET/POST/PUT/DELETE>" "Use specified method"
               :parse-fn (comp keyword str/upper-case)]])


(defn request [{:keys [args internal path method]
                :as opts}]
  (let [[app-context] (desc/describe-application {:args args})
        {:keys [public-url internal-url]} (-> app-context :target :deployment-settings :http :inbound)
        method-fn (condp = method
                    :GET http/get
                    :POST http/post
                    :PUT http/put
                    :DELETE http/delete
                    http/get)
        ]

    (log/debug "URL:" (str (or (and internal internal-url) public-url (throw (e/app-not-found)))
                           (first path)))
    (log/debug "Body:" (dissoc opts :args :interanl :path :method))

    (log/debug "Method:" method-fn)

    (->> (method-fn (str (or (and internal internal-url) public-url (throw (e/app-not-found)))
                        (first path))
                   {:body (edn->json (dissoc opts :args :interanl :path :method))})
         (yc/parse-response :raw)
         :body)))
