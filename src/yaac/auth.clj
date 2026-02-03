;   Copyright (c) Tsutomu Miyashita. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns yaac.auth
  (:gen-class)
  (:import [java.util.zip ZipInputStream ZipEntry]
           [java.io FileInputStream]
           [java.nio.charset Charset])
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.cli :refer (parse-opts)]
            [clojure.zip :as z]
            [clojure.tools.cli :refer [parse-opts]]
            [muuntaja.core :as m]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [taoensso.nippy :as nippy]
            [zeph.client :as http]
            [silvur.nio :as nio]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [yaac.util :refer [json->edn edn->json]]
            [taoensso.timbre :as log]
            [zeph.server :refer [run-server]]
            [jsonista.core :as json]
            [yaac.util :as util]
            [yaac.error :as e]
            [clojure.data.xml :as dx]
            [clojure.set :as set]
            [yaac.yaml :as yaml]
            [clojure.core.async :as async :refer [go <!! chan >!!]]
            [yaac.core :as yc]
            [reitit.http :as rh]
            [reitit.http.interceptors.parameters]
            [reitit.http.interceptors.muuntaja]
            ))

(defn- url-encode [s]
  (java.net.URLEncoder/encode (str s) "UTF-8"))

(defn usage [opts]
  (let [{:keys [summary help-all]} (if (map? opts) opts {:summary opts :help-all true})]
    (->> (concat
          ["Usage: auth <idp-service> [key-values]"
           ""
           "Run OAuth2 authorization code flow"
           ""
           "Options:"
           ""
           summary
           ""]
          (when help-all
            ["Resources:"
             ""
             "  - azure key=val       ..."
             ""
             "Keys:"
             "  azure"
             "    - tenant"
             "    - client-id"
             "    - client-secret"
             "    - redirect-uri    (default: http://localhost:9180/oauth2/callback)"
             "    - scope           (default: https://graph.microsoft.com/.default)"
             ""])
          ["Example:"
           ""
           "# Azure Entra authorization flow"
           "  > yaac auth azure tenant=your-tenant client-id=zzz client-secret=zzz"
           ""])
         (str/join \newline))))

(def options [["-p" "--port <number>" "Listen port to receive redirected response from IdP"
               :parse-fn parse-long]])

(defn app [tenant client-id client-secret redirect-uri scope pipe]
  (rh/ring-handler
   (rh/router [["/oauth2/callback" {:get (fn [{:keys [params] :as req}]
                                           (let [{:strs [code state]} params]
                                             (-> @(http/post (str "https://login.microsoftonline.com/" tenant "/oauth2/v2.0/token")
                                                            (doto {:form-params {:client_id client-id
                                                                            :scope scope
                                                                            :code code
                                                                            :redirect_uri redirect-uri
                                                                            :grant_type "authorization_code"
                                                                            :client_secret client-secret}}
                                                              prn))
                                                 :body
                                                 (json->edn)
                                                 (as-> x (do (>!! pipe x) x)
                                                   (assoc {:status 200} :body (edn->json :snake x))))))}]])
   ;; (reitit.ring/routes
   ;;  (reitit.ring/create-default-handler))
   {:executor reitit.interceptor.sieppari/executor
    :interceptors [(reitit.http.interceptors.parameters/parameters-interceptor)]}))



(defn auth-azure [{:keys [tenant client-id client-secret redirect-uri scope port]}]
  (let [con (System/console)]
    (let [port (or (first port) 9180)
          tenant (or (first tenant) (.readLine con "%s" (into-array ["tenant: "])))
          client-id (or (first client-id) (.readLine con "%s" (into-array ["client id: "])))
          client-secret (or (first client-secret) (.readLine con "%s" (into-array ["client secret: "])))
          redirect-uri (or (first redirect-uri) (format "http://localhost:%d/oauth2/callback" port))
          scope (or (first scope) "https://graph.microsoft.com/.default")
          pipe (chan)]
      
      (println
       (->> [""
             "------------------------------------------------------------"
             (str "Start authorization code flow with Azure. Listening on" )
             (str "-- " redirect-uri)
             (str "as callback URL registerred on Azure")
             ""
             (str "Go to the following site with copying to your browser.")
             "------------------------------------------------------------"
             ""
             (str "https://login.microsoftonline.com/" tenant "/oauth2/v2.0/authorize?"
                  (->> {:client_id client-id
                        :response_type "code"
                        :redirect_uri (url-encode redirect-uri)
                        :scope (url-encode scope)
                        :state "12345"}
                       (map (fn [[k v] ] (str (name k) "=" v)))
                       (str/join "&")))
             ""]
            (str/join \newline)))
      (let [token-client (run-server (app tenant client-id client-secret redirect-uri scope pipe) {:host "0.0.0.0" :port 9180})
            result (<!! pipe)]
        (token-client)
        (yc/add-extra-fields [result] :tenant tenant)))))


(def route
  ["auth" {:options options
           :usage usage}
   ["" {:help true}]
   ["|azure" {:help true}]
   ["|azure|{*args}" {:fields [[:extra :tenant]
                               :token-type
                               :expires-in
                               :scope]
                      :handler auth-azure}]])
