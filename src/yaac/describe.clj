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


(ns yaac.describe
  (:require [silvur
             [util :refer [json->edn edn->json]]
             [http :as http]
             [log :as log]]
            [reitit.core :as r]
            [yaac.core :refer [*org* *env* parse-response default-headers
                               add-extra-fields
                               org->id env->id app->id target->id
                               org->name load-session! -get-deployed-applications] :as yc]
            [yaac.error :as e]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [yaac.incubate :as ic]
            [clojure.spec.alpha :as s]
            [camel-snake-kebab.core :as csk]
            ))


(defn usage [summary-options]
  (->> ["Usage: describe <resources> [options]"
        ""
        "Describe assets, apps and resources."
        ""
        "Options:"
        ""
        summary-options
        ""
        "Resources:"
        ""
        "  - org [org]                     If org name is not specfied, it uses default org configred by yaac config"
        "  - env [org] [env]               If org or env name is not specfied, it uses default org configred by yaac config"
        "  - app [org] [env] <app>         Required to specify group, artifact name and version."
        "  - asset -g <group> -a <asset>   Required to specify group, artifact name and version."
        ""
        "Example:"
        "# Describe the organization"
        "  yaac describe org T1"
        ""
        "# Describe the application"
        "  yaac describe app T1 Production hello-app"
        ""
        "# Describe specified asset"
        "  yaac describe asset -g T1 -a hello-api"
        ""
        ""]
       (str/join \newline)))


(def options [["-g" "--group NAME" "Group name. Normally BG name"]
              ["-a" "--asset NAME" "Asset name"]
              ["-v" "--version VERSION" "Asset version"]])

(defmulti -describe-application (fn [org env appi]
                                  (csk/->kebab-case-keyword
                                   (or (-> appi :target :type)
                                       (-> appi :target :provider)
                                       :none))))

(defmethod -describe-application :mc [org env appi]
  (let [org-id (org->id org)
        env-id (env->id org env)
        app-id (:id appi)]
    (-> (http/get (format "https://anypoint.mulesoft.com/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments/%s" org-id env-id app-id)
                  {:headers (default-headers)})
        (parse-response)
        :body
        (as-> result
            (add-extra-fields result :status (-> result :status))))))

(defmethod -describe-application :server [org env appi]
  (let [org-id (org->id org)
        env-id (env->id org env)
        app-id (:id appi)]
    (-> (http/get (format "https://anypoint.mulesoft.com/hybrid/api/v1/applications/%s" app-id)
                  {:headers (assoc (default-headers)
                                   "X-ANYPNT-ORG-ID" org-id
                                   "X-ANYPNT-ENV-ID" env-id)})
        (parse-response)
        :body
        :data
        (as-> result
            (add-extra-fields result :status (when (-> result :started) "STARTED"))))))

(defmethod -describe-application :default [org env appi]
  (throw (e/app-not-found "No application" {:org org :env env :app (:name appi)})))

(defn describe-application [{:keys [args]
                           :as opts}]
  (let [[org env app] (case (count args)
                        1 [*org* *env* (last args)]
                        3 args
                        (throw (e/invalid-arguments "Org and Env should be specified or use default context with yaac config command" {:args args})))
        ;; To specific app with target . ex t1ps/hello-app
        [app target] (reverse (str/split app #"/"))]
    (log/debug "describe-application" org env app target)
    (if-not (and org env app)
      (throw (e/invalid-arguments "Org, Env and App need to be specified" {:args args}))
      (let [org-id (org->id org)
            env-id (env->id org env)
            ;; if target is nil, return nil instead of exception
            target-id (and target (target->id org-id env-id target))
            app-id (app->id target-id org-id env-id app)
            [app] (filter #(= app-id (:id %)) (-get-deployed-applications org-id env-id))]
        (log/debug "appi:" app)
        
        (-describe-application org env app)))))

(defn describe-asset [{:keys [group asset] :as opts}]
  (if-not (and group asset)
    (throw (e/invalid-arguments "Group and asset are required" {:group group :asset asset}))
    (let [org-id (org->id group)]
      (-> (http/get (format "https://anypoint.mulesoft.com/exchange/api/v2/assets/%s/%s/asset" org-id asset)
                     {:headers (default-headers)})
          (parse-response)
          :body))))

(defn describe-api-instance [{:keys [args]}]
  (let [[api env org] (reverse args) ;; app has to be specified
        org (or org *org*)
        env (or env *env*)
        org-id (org->id org)
        env-id (env->id org env)
        api-id (yc/api->id org env api)]

    (if (and org-id env-id api-id)
      (->> (http/get (format "https://anypoint.mulesoft.com/apimanager/api/v1/organizations/%s/environments/%s/apis/%s" org-id env-id api-id)
                     {:headers (default-headers)})
           (parse-response)
           :body
)
      (throw (e/invalid-arguments "Not found organization and/or environement, or apiinstance" :org (or org-id org) :env (or env-id env) :api api))))
)

(defn describe-organization [{:keys [args]
                              [org] :args}]
  (let [org-id (org->id (or org *org*))]
    (->> (http/get (format "https://anypoint.mulesoft.com/accounts/api/organizations/%s" org-id)
                   {:headers (default-headers)})
         (parse-response)
         :body)))

(defn describe-environments [{:keys [args]
                              [org env] :args}]
  (let [org-id (org->id (or org *org*))
        env-id (env->id org-id (or env *env*))]
    
    (->> (http/get (format "https://anypoint.mulesoft.com/accounts/api/organizations/%s/environments/%s" org-id env-id)
                   {:headers (default-headers)})
         (parse-response)
         :body)))
