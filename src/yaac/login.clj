;; *   Yaac
;; *
;; *   Copyright (c) Tsutomu Miyashita. All rights reserved.
;; *
;; *   The use and distribution terms for this software are covered by the
;; *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; *   which can be found in the file epl-v10.html at the root of this distribution.
;; *   By using this software in any fashion, you are agreeing to be bound by
;; * 	 the terms of this license.
;; *   You must not remove this notice, or any other, from this software.


(ns yaac.login
  (:require [yaac.util :refer [json->edn edn->json json-pprint]]
            [taoensso.timbre :as log]
            [zeph.client :as http]
            [clojure.core.async :as async]
            [reitit.http :as rh]
            [reitit.interceptor.sieppari :as sieppari]
            [reitit.http.interceptors.parameters :as interceptor]
            [zeph.server :refer [run-server]]
            [reitit.core :as r]
            [yaac.core :refer [parse-response
                               default-headers
                               org->id env->id org->name load-session! parse-response set-session!
                               gen-url] :as yc]
            [yaac.config :as conf]
            [yaac.error :as e]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [yaac.incubate :as ic]
            [clojure.spec.alpha :as s]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]))


(defn usage [opts]
  (let [{:keys [summary help-all]} (if (map? opts) opts {:summary opts :help-all true})]
    (str/join \newline
              (concat
               ["Usage: yaac login <credential context> [username] [password]"
                ""
                "Login with Connected Apps."
                "This function uses the credential file stored in ~/.yaac/credentials."
                ""
                "Options:"
                ""
                summary
                ""]
               (when help-all
                 ["Credential file sample:"
                  ""
                  (json-pprint (cske/transform-keys csk/->snake_case_string
                                                    {:conn-app
                                                     {:client-id "your id"
                                                      :client-secret "your secret"
                                                      :grant-type "client_credentials"
                                                      :scope "profile edit:organization manage:exchange"}}))
                  ""])
               ["Example:"
                ""
                "# Login with client_credentials"
                "  > yaac login conn-app"
                ""
                "# Login with resource owner password"
                "  > yaac login conn-app user pass"
                ""]))))

(def options [])


;; (defn login* [{:keys [args] :as opts}]
;;   (let [{:keys [help summary]} (yc/ext-parse-opts opts login-options)]
;;     (println (login-usage summary))))


;;; login

(defmulti -login (fn [auth-method & {:keys [username password client-id cilent-secret base-url]}] (keyword auth-method)))

;; This is deprecated because MFA is mandatory used. If MFA is exemplted, it can be used as of 2023/8/28
(defmethod -login :user [_ & {:keys [username password base-url] :as cm}]
  (->> @(http/post (gen-url "/accounts/login")
                   {:form-params {"username" username
                                  "password" password}})
       ;; This API should be responded as json string "\"unauthorized\""  , but respond string "unauthorized" on error.
       ;; Therefore parse-response raised 
    parse-response
    :body
    set-session!))

(defmethod -login :client-credentials [_ & {:keys [client-id client-secret]}]
  (->> @(http/post (gen-url "/accounts/api/v2/oauth2/token")
                   {:headers {"Content-Type" "application/json"}
                    :body (edn->json :snake {:client-id client-id
                                             :client-secret client-secret
                                             :grant-type "client_credentials"})})
       (parse-response)
       :body
       (set-session!)))



(defmethod -login :password [_ & {:keys [username password client-id client-secret scope]}]
  (->> @(http/post (gen-url "/accounts/api/v2/oauth2/token")
                   {:headers {"Content-Type" "application/json"}
                    :body (edn->json :snake {:client-id client-id
                                             :client-secret client-secret
                                             :username username
                                             :password password
                                             :grant-type "password"
                                             :scope scope})})
       parse-response
       :body
       set-session!))



(defn light-oauth2-redirect-server [client-id client-secret ch]
  (letfn [(get-token [c-id c-secret ch]
            (let [done? (atom false)]
              (fn [{:keys [params] :as req}]
                (if-not @done?
                  (-> @(http/post (gen-url "/accounts/api/v2/oauth2/token")
                                 {:headers {"Content-Type" "application/json"}
                                  :body (edn->json :snake {:code (params "code")
                                                           :redirect-uri "http://localhost:9180"
                                                           :client-id c-id
                                                           :client-secret c-secret
                                                           :grant-type "authorization_code"})})
                     (parse-response)
                     :body
                     (as-> result
                         (do (async/>!! ch result)
                             (swap! done? not)
                             {:status 200 :body (edn->json result)})))
                  {:status 200 :body "Already done"}))))]
    
    (run-server (rh/ring-handler
                 (rh/router
                  [["/" {:get (get-token client-id client-secret ch)}]])
                 {:interceptors [(interceptor/parameters-interceptor)]
                  :executor sieppari/executor})
                {:port 9180})))


(defmethod -login :authorization-code [_ & {:keys [username password client-id client-secret scope]}]
  (println "Access to the following URL with a Web Browser and accept.")
  (println)
  (println "This software starts to listen on 9180/tcp and handles the redirected request to http://localhost:9180 by the browser.")
  (println)
  (println (format (gen-url "/accounts/api/v2/oauth2/authorize?client_id=%s&scope=%s&response_type=code&redirect_uri=http://localhost:9180&nonce=%s")
                   client-id
                   (or scope "read:full")
                   (rand-int 99999)))
  (let [ch (async/chan)
        srv (light-oauth2-redirect-server client-id client-secret ch)
        token (async/<!! ch)]
    (srv)
    (async/close! ch)
    (set-session! token)))  


(defmethod -login :default [auth-method & {:keys [username password client-id client-secret]}]
  (throw (e/auth-method-not-supported "Auth method not supported yet" :method auth-method)))


(defn login [{:keys [args] :as om}]
  (log/debug "Args:" args)
  (conf/clear-cache)
  (let [credential (cond 
                     (= 1 (count args))
                     (let [[connected-app] args
                           con (System/console)
                           {:keys [grant-type username password] :as ctx} (yc/load-credentials connected-app)]
                       (if (= "password" grant-type)
                         (let [u (or username (.readLine con "%s" (into-array ["username: "])))
                               p (or password (.readPassword con "%s" (into-array ["password: "])))]
                           (assoc ctx :username u :password p))
                         ctx))
                     (= 2 (count args))
                     (let [[user password] args]
                       {:username user :password password :grant-type :user})
                     (<= 3 (count args))
                     (let [[connected-app user password] args
                           {:keys [grant-type client-id client-secret scope] :as ctx} (yc/load-credentials connected-app)]
                       {:username user :password password
                        :grant-type grant-type
                        :scope scope
                        :client-id client-id
                        :client-secret client-secret}))]
    
    (log/debug "Use context:" credential)
    (if credential
      (do
        (-> (-login (csk/->kebab-case-keyword (:grant-type credential)) (merge om credential))
            (yc/store-session!)
            (dissoc :id-token)))
      (throw (e/invalid-credentials "Connected app or user/password required" {:args args})))))





(def route
  ["login" {:options options
            :usage usage
            :no-token true}
   ["" {:help true}]
   ["|{*args}" {:fields [:token-type
                         :access-token
                         :expires-in]
                :handler login}]])

