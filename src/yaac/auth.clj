;   Copyright (c) Tsutomu Miyashita. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns yaac.auth
  (:gen-class)
  (:require [clojure.string :as str]
            [zeph.client :as http]
            [yaac.util :refer [json->edn edn->json]]
            [taoensso.timbre :as log]
            [zeph.server :refer [run-server]]
            [yaac.util :as util]
            [yaac.error :as e]
            [clojure.core.async :as async :refer [<!! chan >!!]]
            [yaac.core :as yc]
            [reitit.http :as rh]
            [reitit.http.interceptors.parameters]
            ))

(defn- url-encode [s]
  (java.net.URLEncoder/encode (str s) "UTF-8"))

;; --- OIDC Discovery ---

(defn- discover-oidc
  "Fetch .well-known/openid-configuration from issuer URL"
  [issuer]
  (let [url (str (str/replace issuer #"/$" "") "/.well-known/openid-configuration")]
    (log/debug "OIDC discovery:" url)
    (-> @(http/get url {})
        :body
        (json->edn))))

;; --- Presets ---

(def presets
  {"azure" {:authorize-url-fn (fn [{:keys [tenant]}]
                                (str "https://login.microsoftonline.com/" tenant "/oauth2/v2.0/authorize"))
            :token-url-fn     (fn [{:keys [tenant]}]
                                (str "https://login.microsoftonline.com/" tenant "/oauth2/v2.0/token"))
            :default-scope    "https://graph.microsoft.com/.default"
            :extra-params     [:tenant]}})

(defn- resolve-endpoints
  "Resolve authorize-url and token-url from preset, issuer, or explicit params"
  [{:keys [preset issuer authorize-url token-url] :as params}]
  (cond
    ;; Explicit URLs
    (and authorize-url token-url)
    {:authorize-url (first authorize-url)
     :token-url (first token-url)}

    ;; Preset
    preset
    (let [p (get presets (first preset))]
      (when-not p
        (throw (e/invalid-arguments "Unknown preset" {:preset (first preset)
                                                      :available (keys presets)})))
      {:authorize-url ((:authorize-url-fn p) (update-vals params #(first %)))
       :token-url ((:token-url-fn p) (update-vals params #(first %)))
       :default-scope (:default-scope p)})

    ;; OIDC Discovery
    issuer
    (let [oidc (discover-oidc (first issuer))]
      {:authorize-url (:authorization-endpoint oidc)
       :token-url (:token-endpoint oidc)})

    :else nil))

;; --- Callback server for Authorization Code Flow ---

(defn- callback-app [token-url client-id client-secret redirect-uri scope pipe]
  (rh/ring-handler
   (rh/router [["/oauth2/callback" {:get (fn [{:keys [params]}]
                                           (let [{:strs [code state]} params
                                                 resp @(http/post token-url
                                                                  {:form-params {:client_id client-id
                                                                                 :scope scope
                                                                                 :code code
                                                                                 :redirect_uri redirect-uri
                                                                                 :grant_type "authorization_code"
                                                                                 :client_secret client-secret}})
                                                 body (:body resp)
                                                 parsed (json->edn body)]
                                             (>!! pipe (if (map? parsed) parsed {:error body}))
                                             {:status 200
                                              :body (if (map? parsed)
                                                      (edn->json :snake parsed)
                                                      (str body))}))}]])
   {:executor reitit.interceptor.sieppari/executor
    :interceptors [(reitit.http.interceptors.parameters/parameters-interceptor)]}))

;; --- Authorization Code Flow ---

(defn auth-code [{:keys [client-id client-secret redirect-uri scope port preset] :as opts}]
  (let [con (System/console)
        {:keys [authorize-url token-url default-scope] :as endpoints} (resolve-endpoints opts)
        _ (when-not (and authorize-url token-url)
            (throw (e/invalid-arguments
                    "Specify issuer=<url>, -p <preset>, or authorize-url=<url> token-url=<url>"
                    {:opts (dissoc opts :args :summary)})))
        port (let [p (or (first port) 9180)] (if (string? p) (parse-long p) p))
        client-id (or (first client-id) (and con (.readLine con "%s" (into-array ["client-id: "]))))
        client-secret (or (first client-secret) (and con (.readLine con "%s" (into-array ["client-secret: "]))))
        redirect-uri (or (first redirect-uri) (format "http://localhost:%d/oauth2/callback" port))
        scope (or (first scope) default-scope "openid")
        pipe (chan)
        preset-name (first preset)]

    (println
     (->> [""
           "------------------------------------------------------------"
           (str "Authorization Code Flow" (when preset-name (str " (" preset-name ")")))
           (str "Listening on " redirect-uri)
           ""
           "Open the following URL in your browser:"
           "------------------------------------------------------------"
           ""
           (str authorize-url "?"
                (->> {:client_id client-id
                      :response_type "code"
                      :redirect_uri (url-encode redirect-uri)
                      :scope (url-encode scope)
                      :state "12345"}
                     (map (fn [[k v]] (str (name k) "=" v)))
                     (str/join "&")))
           ""]
          (str/join \newline)))
    (let [server (run-server (callback-app token-url client-id client-secret redirect-uri scope pipe)
                             {:host "0.0.0.0" :port port})
          result (<!! pipe)]
      (server)
      (if (:error result)
        (throw (e/error "Token exchange failed" {:body (:error result)}))
        (yc/add-extra-fields [result]
                             :issuer (or (first (:issuer opts)) preset-name))))))

;; --- Client Credentials Flow ---

(defn auth-client [{:keys [client-id client-secret scope preset] :as opts}]
  (let [con (System/console)
        {:keys [token-url]} (resolve-endpoints opts)
        _ (when-not token-url
            (throw (e/invalid-arguments
                    "Specify issuer=<url>, -p <preset>, or token-url=<url>"
                    {:opts (dissoc opts :args :summary)})))
        client-id (or (first client-id) (and con (.readLine con "%s" (into-array ["client-id: "]))))
        client-secret (or (first client-secret) (and con (.readLine con "%s" (into-array ["client-secret: "]))))
        scope (or (first scope) (:default-scope (get presets (first preset))) "openid")]

    (log/debug "Client Credentials:" token-url)
    (let [result (-> @(http/post token-url
                                {:form-params {:client_id client-id
                                               :client_secret client-secret
                                               :grant_type "client_credentials"
                                               :scope scope}})
                     :body
                     (json->edn))]
      (yc/add-extra-fields [result]
                           :issuer (or (first (:issuer opts)) (first preset))))))

;; --- Legacy Azure handler (delegate to auth-code) ---

(defn auth-azure [{:keys [tenant] :as opts}]
  (let [tenant (or (seq tenant)
                   (let [con (System/console)]
                     (when con
                       [(.readLine con "%s" (into-array ["tenant: "]))])))]
    (when-not (seq tenant)
      (throw (e/invalid-arguments "tenant is required for azure preset" {})))
    (auth-code (assoc opts :preset ["azure"] :tenant tenant))))

;; --- Usage / Options / Routes ---

(defn usage [opts]
  (let [{:keys [summary help-all]} (if (map? opts) opts {:summary opts :help-all true})]
    (->> (concat
          ["Usage: auth <flow> [options] [key=value ...]"
           ""
           "Run OAuth2 flows to obtain tokens"
           ""
           "Options:"
           ""
           summary
           ""]
          (when help-all
            ["Flows:"
             ""
             "  code      Authorization Code Flow (browser-based)"
             "  client    Client Credentials Flow"
             "  azure     Azure Entra preset (= code -p azure)"
             ""
             "Keys:"
             "  Common:"
             "    - issuer         OIDC issuer URL (auto-discovers endpoints)"
             "    - authorize-url  Authorization endpoint (explicit)"
             "    - token-url      Token endpoint (explicit)"
             "    - client-id"
             "    - client-secret"
             "    - redirect-uri   (default: http://localhost:9180/oauth2/callback)"
             "    - scope          (default: openid)"
             ""
             "  Azure preset (-p azure):"
             "    - tenant         Azure AD tenant ID"
             ""])
          ["Examples:"
           ""
           "# Authorization code flow with OIDC discovery"
           "  > yaac auth code issuer=https://my-idp client-id=xxx client-secret=xxx"
           ""
           "# Authorization code flow with Azure preset"
           "  > yaac auth code -p azure tenant=my-tenant client-id=xxx client-secret=xxx"
           ""
           "# Client credentials flow"
           "  > yaac auth client issuer=https://my-idp client-id=xxx client-secret=xxx"
           ""
           "# Azure (legacy shorthand)"
           "  > yaac auth azure tenant=my-tenant client-id=xxx client-secret=xxx"
           ""])
         (str/join \newline))))

(def options [["-p" "--preset NAME" "Preset (azure)"]
              ["--port" "--port PORT" "Listen port for callback (default: 9180)"
               :parse-fn parse-long]])

(def result-fields [[:extra :issuer]
                    :token-type
                    :expires-in
                    :scope])

(def route
  ["auth" {:options options
           :usage usage}
   ["" {:help true}]
   ;; Authorization Code Flow
   ["|code" {:help true}]
   ["|code|{*args}" {:fields result-fields
                     :handler auth-code}]
   ;; Client Credentials Flow
   ["|client" {:help true}]
   ["|client|{*args}" {:fields result-fields
                       :handler auth-client}]
   ;; Legacy Azure shorthand
   ["|azure" {:help true}]
   ["|azure|{*args}" {:fields result-fields
                      :handler auth-azure}]])
