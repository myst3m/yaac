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
             [log :as log]]
            [zeph.client :as http]
            [reitit.core :as r]
            [yaac.core :refer [*org* *env* *deploy-target* parse-response default-headers
                               add-extra-fields short-uuid
                               org->id env->id app->id target->id
                               org->name env->name load-session! -get-deployed-applications
                               -enrich-application -get-client-provider
                               gen-url] :as yc]
            [yaac.error :as e]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [yaac.incubate :as ic]
            [clojure.spec.alpha :as s]
            [camel-snake-kebab.core :as csk]
            ))


(defn usage [opts]
  (let [{:keys [summary help-all]} (if (map? opts) opts {:summary opts :help-all true})]
    (->> (concat
          ["Usage: describe <resources> [options]"
           ""
           "Describe assets, apps and resources."
           ""
           "Options:"
           ""
           summary
           ""]
          (when help-all
            ["Resources:"
             ""
             "  - org [org]                     Describe organization"
             "  - env [org] [env]               Describe environment"
             "  - app [org] [env] <app>         Describe application"
             "  - asset -g <group> -a <asset>   Describe asset"
             "  - connected-app <name|id>       Show connected app scopes"
             ""])
          ["Example:"
           ""
           "# Describe organization"
           "  > yaac describe org T1"
           ""
           "# Describe application"
           "  > yaac describe app T1 Production hello-app"
           ""
           "# Describe asset"
           "  > yaac describe asset -g T1 -a hello-api"
           ""])
         (str/join \newline))))


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
    (-> @(http/get (format (gen-url "/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments/%s") org-id env-id app-id)
                  {:headers (default-headers)})
        (parse-response)
        :body
        (as-> result
            (add-extra-fields result :status (-> result :status))))))

(defmethod -describe-application :server [org env appi]
  (let [org-id (org->id org)
        env-id (env->id org env)
        app-id (:id appi)]
    (-> @(http/get (format (gen-url "/hybrid/api/v1/applications/%s") app-id)
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
        target *deploy-target*
        ;; [app target] (reverse (str/split app #"/"))
        ]
    
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
      (-> @(http/get (format (gen-url "/exchange/api/v2/assets/%s/%s/asset") org-id asset)
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
      (->> @(http/get (format (gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s") org-id env-id api-id)
                     {:headers (default-headers)})
           (parse-response)
           :body
)
      (throw (e/invalid-arguments "Not found organization and/or environement, or apiinstance" :org (or org-id org) :env (or env-id env) :api api))))
)

(defn describe-organization [{:keys [args]
                              [org] :args}]
  (let [org-id (org->id (or org *org*))
        body (->> @(http/get (format (gen-url "/accounts/api/organizations/%s") org-id)
                             {:headers (default-headers)})
                  (parse-response)
                  :body)]
    (assoc body :extra {:owner (get-in body [:owner :email])
                        :envs (count (:environments body))
                        :idp (:idprovider-id body)})))

(defn describe-environments [{:keys [args]
                              [org env] :args}]
  (let [org (or org *org*)
        env (or env *env*)
        org-id (org->id org)
        env-id (env->id org-id env)
        env-info (->> @(http/get (format (gen-url "/accounts/api/organizations/%s/environments/%s") org-id env-id)
                                 {:headers (default-headers)})
                      (parse-response)
                      :body)
        ;; デプロイ済みアプリのvCoreサマリ取得
        apps (try (->> (-get-deployed-applications org env)
                       (pmap #(-enrich-application org env %)))
                  (catch Exception _ []))
        total-vcores (/ (Math/round (* (reduce + 0 (keep #(get-in % [:application :v-cores]) apps)) 10000.0)) 10000.0)
        total-replicas (reduce + 0 (keep #(get-in % [:target :replicas]) apps))
]
    (assoc env-info :extra {:apps (count apps)
                            :total-v-cores total-vcores
                            :total-replicas total-replicas})))

(defn describe-client-provider [{:keys [args]
                                  [cp-name-or-id] :args}]
  (when-not cp-name-or-id
    (throw (e/invalid-arguments "Client provider name or ID is required" :args args)))
  (if-let [cp (-get-client-provider cp-name-or-id)]
    [cp]
    (throw (e/no-item "Client provider not found" {:name cp-name-or-id}))))

(defn describe-connected-app [{:keys [args]
                                [app-name-or-id] :args}]
  (when-not app-name-or-id
    (throw (e/invalid-arguments "Connected app name or client-id is required" :args args)))
  (let [client-id (yc/connected-app->id app-name-or-id)
        scopes (yc/get-connected-app-scopes client-id)
        ;; Collect unique org-ids and build env cache per org
        org-ids (distinct (keep #(get-in % [:context-params :org]) scopes))
        env-cache (into {}
                        (for [oid org-ids]
                          (let [org-nm (try (org->name oid) (catch Exception _ nil))]
                            (when org-nm
                              [oid (try
                                     (->> (yc/-get-environments org-nm)
                                          (map (fn [e] [(:id e) (:name e)]))
                                          (into {}))
                                     (catch Exception _ {}))]))))]
    (map (fn [{:keys [scope context-params]}]
           (let [org-id (:org context-params)
                 env-id (:env-id context-params)
                 org-name (when org-id (try (org->name org-id) (catch Exception _ org-id)))
                 env-name (when (and org-id env-id)
                            (get-in env-cache [org-id env-id] env-id))]
             {:scope scope
              :org org-name
              :env env-name}))
         scopes)))

(def route
  (for [op ["describe" "desc"]]
    [op {:options options
         :usage usage}
     ["" {:help true}]   
     ["|-h" {:help true}]

     ["|org" {:handler describe-organization
               :fields [:name [:id :fmt short-uuid]
                        [:extra :owner] [:extra :envs]
                        [:entitlements :v-cores-production :assigned :as "production"]
                        [:entitlements :v-cores-sandbox :assigned :as "sandbox"]
                        [:entitlements :static-ips :assigned :as "static-ips"]
                        [:entitlements :network-connections :assigned :as "connections"]
                        [:entitlements :vpns :assigned :as "vpns"]
                        [:entitlements :managed-gateway-large :assigned :as "gw-large"]
                        [:entitlements :managed-gateway-small :assigned :as "gw-small"]
                        [:extra :idp]]}]
     ["|organization" {:handler describe-organization
                        :fields [:name [:id :fmt short-uuid]
                                 [:extra :owner] [:extra :envs]
                                 [:entitlements :v-cores-production :assigned :as "production"]
                                 [:entitlements :v-cores-sandbox :assigned :as "sandbox"]
                                 [:entitlements :static-ips :assigned :as "static-ips"]
                                 [:entitlements :network-connections :assigned :as "connections"]
                                 [:entitlements :vpns :assigned :as "vpns"]
                                 [:entitlements :managed-gateway-large :assigned :as "gw-large"]
                                 [:entitlements :managed-gateway-small :assigned :as "gw-small"]
                                 [:extra :idp]]}]
     ["|org|{*args}" {:handler describe-organization
                       :fields [:name [:id :fmt short-uuid]
                                [:extra :owner] [:extra :envs]
                                [:entitlements :v-cores-production :assigned :as "production"]
                                [:entitlements :v-cores-sandbox :assigned :as "sandbox"]
                                [:entitlements :static-ips :assigned :as "static-ips"]
                                [:entitlements :network-connections :assigned :as "connections"]
                                [:entitlements :vpns :assigned :as "vpns"]
                                [:entitlements :managed-gateway-large :assigned :as "gw-large"]
                                [:entitlements :managed-gateway-small :assigned :as "gw-small"]
                                [:extra :idp]]}]
     ["|organization|{*args}" {:handler describe-organization
                                :fields [:name [:id :fmt short-uuid]
                                         [:extra :owner] [:extra :envs]
                                         [:entitlements :v-cores-production :assigned :as "production"]
                                         [:entitlements :v-cores-sandbox :assigned :as "sandbox"]
                                         [:entitlements :static-ips :assigned :as "static-ips"]
                                         [:entitlements :network-connections :assigned :as "connections"]
                                         [:entitlements :vpns :assigned :as "vpns"]
                                         [:entitlements :managed-gateway-large :assigned :as "gw-large"]
                                         [:entitlements :managed-gateway-small :assigned :as "gw-small"]
                                         [:extra :idp]]}]

     ["|env" {:handler describe-environments
               :fields [:name :id :type
                        [:extra :apps :as "apps"]
                        [:extra :total-v-cores :as "v-cores"]
                        [:extra :total-replicas :as "replicas"]]}]
     ["|env|{*args}" {:handler describe-environments
                       :fields [:name :id :type
                                [:extra :apps :as "apps"]
                                [:extra :total-v-cores :as "v-cores"]
                                [:extra :total-replicas :as "replicas"]]}]
     ["|environment" {:handler describe-environments
                       :fields [:name :id :type
                                [:extra :apps :as "apps"]
                                [:extra :total-v-cores :as "v-cores"]
                                [:extra :total-replicas :as "replicas"]]}]
     ["|environment|{*args}" {:handler describe-environments
                               :fields [:name :id :type
                                        [:extra :apps :as "apps"]
                                        [:extra :total-v-cores :as "v-cores"]
                                        [:extra :total-replicas :as "replicas"]]}]
     
     ["|app" {:help true}]
     ["|application" {:help true}]
     ["|app|{*args}" {:fields [[:id :fmt short-uuid] :name
                               [:extra :status]
                               [:application :status :as "pod"]
                               [:application :ref :version :as "version"]
                               [:application :v-cores]
                               [:target :replicas]
                               [:target :deployment-settings :http :inbound :public-url]
                               [:target :deployment-settings :http :inbound :internal-url]]
                      :handler describe-application}]
     ["|application|{*args}" {:handler describe-application}]
     ["|asset" {:help true}]
     ["|asset|{*args}" {:handler describe-asset}]
     ["|api" {:help true}]
     ["|api|{*args}" {:handler describe-api-instance}]
     ["|connected-app" {:help true}]
     ["|connected-app|{*args}" {:fields [:scope :org :env]
                                 :handler describe-connected-app}]
     ["|capp" {:help true}]
     ["|capp|{*args}" {:fields [:scope :org :env]
                       :handler describe-connected-app}]

     ;; Client Providers
     ["|cp" {:help true}]
     ["|cp|{*args}" {:handler describe-client-provider
                     :output-format :json}]
     ["|client-provider" {:help true}]
     ["|client-provider|{*args}" {:handler describe-client-provider
                                   :output-format :json}]
     ]))
