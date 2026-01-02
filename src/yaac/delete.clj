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


(ns yaac.delete
  (:require [silvur
             [util :refer [json->edn edn->json]]
             [log :as log]]
            [zeph.client :as http]
            [reitit.core :as r]
            [yaac.core :refer [*org* *env* *no-multi-thread* parse-response
                               default-headers
                               org->id
                               env->id
                               app->id
                               org->name
                               env->name
                               user->id
                               provider->id
                               client-provider->id
                               load-session!
                               -get-root-organization
                               -get-user
                               gen-url] :as yc]
            [yaac.error :as e]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [yaac.incubate :as ic]
            [clojure.spec.alpha :as s]
            [camel-snake-kebab.core :as csk]))


(defn usage [summary-options]
  (->> ["Usage: delete <resources> [options]"
        ""
        "Delete assets, apps and resources."
        ""
        "Options:"
        ""
        summary-options
        ""
        "Resources:"
        ""
        "  - org <org|id>                                     Delete business group."
        "  - asset -g GROUP -a ASSET -v VERSION [options]     Delete asset. Required to specify group, artifact name and version."
        "  - app [org] [env] <app|id>                         Delete deployed application."
        "  - api [org] [env] <api|id>                         Delete deployed api instances."
        "  - idp-user <email> provider=<name>                 Delete IdP user profile."
        "  - connected-app <name|client-id>                   Delete connected app."
        "  - client-provider <name|id>                        Delete client provider (cp)."
        
        ""
        "Example:"
        ""
        "# Delete organization"
        "  > yaac delete org T1"
        ""
        "# Delete asset whose artifact id is hello-api and the specified group id and version "
        "  > yaac delete asset -a hello-api -g T1 -v 0.0.1"
        ""
        "# Delete asset whose artifact id is hello-api all versions"
        "  > yaac delete asset -a hello-api -g T1 -A"
        ""
        "# Delete all application that the name match given regexp in the default org and env"
        "  > yaac delete app hello.*"
        ""
        "# Delete the application. It is possible omit Org/Env if you set as default context"
        "  > yaac delete app hello-api"
        ""
        "# Delete the IdP user profile"
        "  > yaac delete idp-user my-external-user provider=openid-provider-localhost"
        ""
        "# Delete connected app by name"
        "  > yaac delete connected-app MyApp"
        ""
        "# Delete client provider by name"
        "  > yaac delete cp MyProvider"
        ""
        ""]
    (str/join \newline)))


(def options [["-g" "--group NAME" "Group name. Normally BG name"]
              ["-a" "--asset NAME" "Asset name"]
              ["-v" "--version VERSION" "Asset version"]
              ["-A" "--all" "Delete all asset versions"]
              [nil  "--hard-delete"  "Hard delete for assets"]])


(defmulti -delete-application (fn [org env appi]
                                (csk/->kebab-case-keyword
                                 (or (-> appi :target :type)
                                     (-> appi :target :provider)
                                     :none))))

;; RTF/CH2
(defmethod -delete-application :mc [org env appi]
  (let [org-id (org->id org)
        env-id (env->id org env)
        app-id (:id appi)]
    (-> @(http/delete (format (gen-url "/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments/%s") org-id env-id app-id)
                     {:headers (default-headers)})
        (parse-response)
        ;; body is nil on HTTP 204
        (dissoc :body)
        (yc/add-extra-fields :org (org->name org) :env (env->name org env) :app (:name appi)))))

;; Onpremise
(defmethod -delete-application :server [org env appi]
  (let [org-id (org->id org)
        env-id (env->id org env)
        app-id (:id appi)]
    (-> @(http/delete (format (gen-url "/hybrid/api/v1/applications/%s") app-id)
                     {:headers (assoc (default-headers)
                                      "X-ANYPNT-ORG-ID" org-id
                                      "X-ANYPNT-ENV-ID" env-id)})
        (parse-response)
        ;; body is nil on HTTP 204
        (dissoc :body)
        (yc/add-extra-fields :org (org->name org) :env (env->name org env) :app (:name appi)))))

(defmethod -delete-application :default [org env appi]
  (throw (e/app-not-found "No application" {:org org :env env :app (:name appi)})))


(defn delete-application [{:keys [args all]
                           :as opts}]
  (let [[app env org] (reverse args) ;; app has to be specified
        org (or org *org*)           ;; If specified, use it
        env (or env *env*)         ;; If specified, use it
        [app target] (reverse (str/split app #"/"))
        ;; Ignore exception for no target here to check later
        target-id (yc/try-wrap (yc/target->id org env target))]

    (log/debug "Delete application:" (dissoc opts :summary))
    (if-not (and org env app)
      (throw (e/invalid-arguments "Org, Env and App need to be specified" {:args args}))
      (let [org-id (org->id org)
            env-id (env->id org env)
            apps (->> (yc/-get-deployed-applications org env)
                      (filter #(and
                                (or (nil? target-id) (= (get-in % [:target :target-id]) target-id))
                                (or (re-find (re-pattern (str "^" app "$")) (:name %))
                                    (re-find (re-pattern (str "^" app "$")) (str (:id %)))))))]
        (cond
          (or all (= 1 (count apps))) (cond->> apps
                                        *no-multi-thread* (map #(-delete-application org env %))
                                        (not *no-multi-thread*) (pmap #(-delete-application org env %))
                                        :always (apply concat))
          (< 1 (count apps)) (throw (e/multiple-app-name-found "several apps found. Use -A if all apps are to be deleted" {:org (org->name org) :env (env->name org env) :apps (mapv :name apps)}))
          (= 0 (count apps)) (throw (e/app-not-found "No app found" {:org (org->name org) :env (env->name org env) :app app})))))))


(defn delete-asset [{:keys [args group asset version all hard-delete]
                     :as opts}]

  (let [group-id (and group (org->id group))
        artifact-id asset]
    (if-not (and group-id artifact-id (or version all))
      (throw (e/invalid-arguments "Group, Asset and version need to to be specified" {:group group
                                                                                      :asset asset
                                                                                      :version version
                                                                                      :all all}))
      (let [vs (if all
                 (map :version (yc/get-assets {:group group :asset asset}))
                 [version])]
        (reduce (fn [r v]
                  (-> @(http/delete
                       (format (gen-url "/exchange/api/v2/assets/%s/%s/%s") group-id artifact-id v)
                        ;;Hard delete via API is forbidden
                        {:headers (conj (default-headers) {"x-delete-type" (if hard-delete "hard-delete" "soft-delete")})})
                      (parse-response)
                      (assoc :extra {:version v :group group :asset asset})
                      (cons r)
                      (vec)))
                []
                vs)))))


(defn delete-organization [{:keys [args]
                           [org] :args
                           :as opts}]
  (if-not org
    (throw (e/invalid-arguments "Org not specified" :args args))
    (let [org-id (org->id org)]
      (->> @(http/delete (format (gen-url "/accounts/api/organizations/%s") org-id)
                         {:headers (default-headers)})
           (parse-response)))))


(defn delete-api-instance [{:keys [args] :as opts}]
  (let [[api env org] (reverse args) ;; app has to be specified
        org (or org *org*)           ;; If specified, use it
        env (or env *env*)
        api-id (yc/api->id org env api)]

    (if-not (and org env api-id)
      (throw (e/invalid-arguments "Org, Env and Api need to be specified" {:args args}))
      (let [org-id (org->id org)
            env-id (env->id org env)]
        (-> @(http/delete (format (gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s") org-id env-id api-id)
                         {:headers (default-headers)})
            (parse-response)
            ;; body is nil on HTTP 204
            (dissoc :body))))))


(defn delete-api-contracts [{:keys [args] :as opts}]
  (let [[contract api env org] (reverse args) ;; app has to be specified
        org (or org *org*)           ;; If specified, use it
        env (or env *env*)
        api-id (yc/api->id org env api)]

    (if-not (and org env api-id)
      (throw (e/invalid-arguments "Org, Env and Api need to be specified" {:args args}))
      (let [org-id (org->id org)
            env-id (env->id org env)
            contract-id (yc/contract->id org env api contract)]
        ;; This API is found in browser Runtime Console
        ;; Contracts cannot be deleted unless the state is not revoked. 
        (-> @(http/post (format (gen-url "/apimanager/xapi/v1/organizations/%s/environments/%s/apis/%s/contracts/%s/revoke") org-id env-id api-id contract-id)
                       {:headers (default-headers)})
            (parse-response))
        (-> @(http/delete (format (gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s/contracts/%s") org-id env-id api-id contract-id)
                         {:headers (default-headers)})
            (parse-response)
            ;; body is nil on HTTP 204
            (dissoc :body))))))


(defn delete-idp-user-profile [{:keys [args provider]}]
  (let [[user] (reverse args)
        {root-org-id :id} (-get-root-organization)
        {:keys [id email]} (-get-user user)
        provider-id (provider->id provider)]
    @(http/delete (format (gen-url "/accounts/api/organizations/%s/users/%s/identityProviderProfiles")
                         root-org-id
                         id)
                 {:headers (default-headers)
                  :body (edn->json {:idp-user-id email :provider-id provider-id})})))


(defn delete-connected-app [{:keys [args] :as opts}]
  (let [[app-name-or-id] args]
    (when-not app-name-or-id
      (throw (e/invalid-arguments "Connected app name or client-id is required" :args args)))
    (let [client-id (yc/connected-app->id app-name-or-id)]
      (-> @(http/delete (format (gen-url "/accounts/api/connectedApplications/%s") client-id)
                       {:headers (default-headers)})
          (parse-response)
          (assoc :deleted-app app-name-or-id)))))

(defn delete-client-provider [{:keys [args] :as opts}]
  (let [[cp-name-or-id] args]
    (when-not cp-name-or-id
      (throw (e/invalid-arguments "Client provider name or ID is required" :args args)))
    (let [{root-org-id :id} (-get-root-organization)
          provider-id (client-provider->id cp-name-or-id)]
      (when-not provider-id
        (throw (e/no-item "Client provider not found" {:name cp-name-or-id})))
      (-> @(http/delete (format (gen-url "/accounts/api/organizations/%s/clientProviders/%s")
                               root-org-id provider-id)
                       {:headers (default-headers)})
          (parse-response)
          (assoc :deleted-provider cp-name-or-id)))))

(def route
  (for [op ["del" "delete"]]
    [op {:options options
         :usage usage}
     ["" {:help true}]
     ["|-h" {:help true}]                          
     ["|org" {:help true}]
     ["|org|{*args}" {:fields [:status]
                      :handler delete-organization}]
     
     ["|app"]
     ["|app|{*args}" {:fields [:status [:extra :org] [:extra :env] [:extra :app] ]
                      :handler delete-application}]
     ["|api"]
     ["|api|{*args}" {:fields [:status]
                      :handler delete-api-instance}]
     ["|contract"]
     ["|cont"]
     ["|contract|{*args}" {:fields [:status]
                           :handler delete-api-contracts}]
     ["|cont|{*args}" {:fields [:status]
                       :handler delete-api-contracts}]
     ["|asset"]
     ["|asset|{*args}" {:fields [:status [:extra :group] [:extra  :asset] [:extra :version]]
                        :handler delete-asset}]
     ["|idp-user"]
     ["|idp-user|{*args}" {:fields [:status :body]
                                   :handler delete-idp-user-profile}]
     ["|connected-app"]
     ["|connected-app|{*args}" {:fields [:status :deleted-app]
                                :handler delete-connected-app}]
     ["|capp"]
     ["|capp|{*args}" {:fields [:status :deleted-app]
                       :handler delete-connected-app}]
     ["|cp"]
     ["|cp|{*args}" {:fields [:status :deleted-provider]
                     :handler delete-client-provider}]
     ["|client-provider"]
     ["|client-provider|{*args}" {:fields [:status :deleted-provider]
                                   :handler delete-client-provider}]]))
