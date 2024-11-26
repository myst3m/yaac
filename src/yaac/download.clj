;   Copyright (c) Tsutomu Miyashita. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns yaac.download
  (:gen-class)
  (:import [java.util.zip ZipInputStream ZipEntry]
           [java.io FileInputStream]
           [java.nio.charset Charset])
  (:require [clojure.tools.cli :refer (parse-opts)]
            [clojure.zip :as z]
            [clojure.tools.cli :refer [parse-opts]]
            [muuntaja.core :as m]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [taoensso.nippy :as nippy]
            [silvur.http :as http]
            [silvur.nio :as nio]
            [org.httpkit.client :refer [url-encode]]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [silvur.util :refer [json->edn edn->json]]
            [silvur.log :as log]
            [silvur.http]
            [org.httpkit.server :refer [run-server]]
            [clojure.data.json :as json]
            [org.httpkit.client :as hk]
            [yaac.util :as util]
            [yaac.error :as e]
            [clojure.data.xml :as dx]
            [clojure.set :as set]
            [clj-yaml.core :as yaml]
            [clojure.core.async :as async]
            [yaac.core :refer [*org* *env* org->id env->id api->id parse-response default-headers gen-url] :as yc]))


(defn usage [summary-options]
  (->> ["Usage: download <resources> [options]"
        ""
        "Delete assets, apps and resources."
        ""
        "Options:"
        ""
        summary-options
        ""
        "Resources:"
        ""
        "  - proxy [org] [env] <api>                         Download proxy as a Jar file"
        ""
        "Example:"
        ""
        "# Download hello-api's proxy running on T1 business group and Production environment"
        "  > yaac download proxy T1 Production hello-api"
        ""
        ""]
    (str/join \newline)))

(def options [])

(defn download-api-proxies [{:keys [args]}]
  (let [[api env org] (reverse args) ;; app has to be specified
        org (or org *org*)           ;; If specified, use it
        env (or env *env*)
        api-id (api->id org env api)]
    (if-not (and org env api-id)
      (throw (e/invalid-arguments "Org, Env and Api need to be specified" {:args args}))
      (let [org-id (org->id org)
            env-id (env->id org env)]
        (-> (http/get (format (gen-url "/proxies/xapi/v1/organizations/%s/environments/%s/apis/%s/proxy") org-id env-id api-id)
                         {:headers (default-headers)})
            (parse-response)
            ;; body is ByteInputStream
            (as-> result
                (let [jar-path (str api "-" api-id ".jar")
                      {:keys [status body]} result]
                  (.write (io/output-stream jar-path) (.bytes body))
                  {:status status :path jar-path})))))))



(def route
  ["download" {:options options
               :usage usage}
   ["" {:help true}]   
   ["|-h" {:help true}]
   ["|proxy" {:help true}] 
   ["|proxy|{*args}" {:fields [:status]
                      :handler download-api-proxies}]
   ["|api" {:help true}]
   ["|api|{*args}" {:fields [:status :path]
                    :handler download-api-proxies}]])
