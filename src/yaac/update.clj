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


(ns yaac.update
  (:require [silvur
             [util :refer [json->edn edn->json]]
             [http :as http]
             [log :as log]]
            [reitit.core :as r]
            [yaac.core :refer [*org* *env* parse-response default-headers org->id env->id api->id org->name ps->id conn->id load-session! gen-url assign-connected-app-scopes connected-app->id] :as yc]
            [yaac.error :as e]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [yaac.incubate :as ic]
            [clojure.spec.alpha :as s]))


(defn usage [summary-options]
  (->> ["Usage: update <resources> [options]"
        ""
        "Update configurations of assets, apps and resources."
        ""
        "Options:"
        ""
        summary-options
        ""
        "Resources:"
        ""
        "  - app [org] [env] <app> key=val       ...                    "
        "  - asset -g <group> -a <asset> key=val ...                    "
        "  - api [org] [env] <api> key=val       ...                    "
        "  - org [org] key=val                   ...                    "
        "  - conn [org] <private-space> <connection> ... key=val  "
        "  - connected-app <name-or-client-id> --scopes ... --org-scopes ..."
        ""
        "Keys:"
        "  app"
        "    - v-cores"
        "    - replicas"
        "    - runtime-version"
        "    - state=<start|stop>"
        "  asset"
        "    - labels"
        "  api"
        "    - asset-version"
        "  org|organization"
        "    - v-cores-production"
        "    - v-cores-sandbox"
        "    - network-connections"
        "    - static-ips"
        "    - vpns"
        "  conn|connection"
        "    - static-routes         : ex. +172.17.0.0/16,+192.168.11.0/24"
        "  connected-app"
        "    - --scopes              : basic scopes (profile,openid)"
        "    - --org-scopes          : org-level scopes (read:organization,edit:organization)"
        "    - --env-scopes          : env-level scopes (read:applications,admin:cloudhub)"
        "    - --env                 : environments for env-scopes (Production,Sandbox)"
        ""
        "Example:"
        ""
        "# Add tags db, demo to specified asset"
        "  > yaac update app hello-world v-cores=0.2 replicas=2"
        ""
        "# Add tags db, demo to specified asset"
        "  > yaac update asset labels=db,demo -g T1 -a hello-api -v 0.1.0"
        ""
        "# Update API version with given asset version"
        "  > yaac update api hello-api asset-version=0.2.0"
        ""
        "# Update static routes on connections in a private space"
        "  > yaac update conn T1 t1ps onpremise static-routes=172.17.0.0/16"
        ""
        "# Update connected app scopes"
        "  > yaac update connected-app myapp --scopes profile --org-scopes read:organization --env-scopes read:applications --env Production,Sandbox"
        ""]
       (str/join \newline)))


(def options [["-g" "--group NAME" "Group name. Normally BG name"]
              ["-a" "--asset NAME" "Asset name"]
              ["-v" "--version VERSION" "Asset version"]
              [nil "--scopes SCOPES" "Comma-separated scopes for connected-app"]
              [nil "--org-scopes SCOPES" "Comma-separated org-level scopes for connected-app"]
              [nil "--env-scopes SCOPES" "Comma-separated env-level scopes for connected-app"]
              [nil "--org ORG" "Organization for scopes"]
              [nil "--env ENVS" "Comma-separated environments for env-level scopes"]])


(defn update-asset-config [{:keys [group asset version labels]
                            :as opts}]
  (if-not (and group asset version)
    (throw (e/invalid-arguments "Group, asset and version are required" {:group group :asset asset}))
    (let [group-id (org->id group)
          url (format (gen-url "/exchange/api/v2/organizations/%s/assets/%s/%s/%s/mutabledata") group-id group-id asset version)
          multipart [{:name "tags" :content (str/join "," labels)}]]
      (-> (http/patch url {:headers (yc/multipart-headers)
                           :multipart multipart})
          (parse-response)))))

(defn update-api-config [{:keys [args asset-version] :as opts}]
  (let [asset-version (first asset-version)
        [api env org] (reverse args) ;; app has to be specified
        org (or org *org*)           ;; If specified, use it
        env (or env *env*)]
    (if-not (and org env api)
      (throw (e/invalid-arguments "Org, Env and Api need to be specified" {:args args}))
      (let [org-id (org->id org)
            env-id (env->id org env)
            api-id (api->id org env api)]
        (-> (http/patch (format (gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s") org-id env-id api-id)
                        {:headers (yc/default-headers)
                         :body (edn->json :camel {:asset-version asset-version})})
            (parse-response)
            :body)))))



(defn update-app-config [{:keys [args v-cores replicas runtime-version state all]}]
  (let [[app-name env org] (reverse args) ;; app has to be specified
        target-org-id (yc/org->id (or org *org*))           ;; If specified, use it
        target-env-id (yc/env->id target-org-id (or env *env*))
        apps (yc/name->apps target-org-id target-env-id app-name)]

    (if (and (not all) (< 1 (count apps)))
      (throw (e/multiple-app-name-found "Several apps found. Use -A if you want to update all" {:apps (mapv :name apps)}))
      (->> apps
           (mapcat (fn [app]
                     (cond
                       (= "SERVER" (-> app :target :type))
                       (do
                         (-> (gen-url "/hybrid/api/v1/applications/%s")
                             (format (:id app))
                             (http/patch {:headers (assoc (default-headers)
                                                          "X-ANYPNT-ORG-ID" target-org-id
                                                          "X-ANYPNT-ENV-ID" target-env-id)
                                          :body (edn->json (cond-> {}
                                                             state (assoc-in [:desired-status] (condp = (keyword (str/lower-case (first state)))
                                                                                                 :start "STARTED"
                                                                                                 :stop "STOPPED"))))})
                             (yc/parse-response)
                             :body
                             :data
                             (yc/add-extra-fields :status #(get-in % [:target :status])
                                                  :name #(get-in % [:target :name])
                                                  :id #(get-in % [:target :id]))))
                       
                       (= "MC" (-> app :target :provider))
                       (do
                         (-> (gen-url "/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments/%s")
                             (format target-org-id target-env-id (:id app))
                             (http/patch {:headers (yc/default-headers)
                                          :body (edn->json (cond-> {}
                                                             v-cores (assoc-in [:application :v-cores] (str (first v-cores)))
                                                             replicas (assoc-in [:target :replicas] (parse-long (first replicas)))
                                                             runtime-version (assoc-in [:target :deployment-settings :runtime-version] (first runtime-version))
                                                             state (assoc-in [:application :desired-state] (condp = (keyword (str/lower-case (first state)))
                                                                                                             :start "STARTED"
                                                                                                             :stop "STOPPED"))))})
                             (parse-response)
                             :body
                             (yc/add-extra-fields :status #(get-in % [:status])
                                                  :name #(get-in % [:name])
                                                  :id #(get-in % [:id]))
))
                       :else (throw (e/runtime-target-not-found "Not implemented")))))))))

(defn update-organization-config [{:keys [args v-cores-production v-cores-sandbox static-ips network-connections vpns]
                                   [org] :args}]
  (let [org-id (org->id (or org *org*))
        body (cond-> {:id org-id}
               v-cores-production (assoc-in [:entitlements :v-cores-production :assigned] (parse-double (first v-cores-production)))
               v-cores-sandbox (assoc-in [:entitlements :v-cores-sandbox :assigned] (parse-double (first v-cores-sandbox)))
               static-ips (assoc-in [:entitlements :static-ips :assigned] (parse-long (first static-ips)))
               network-connections (assoc-in [:entitlements :network-connections :assigned] (parse-long (first network-connections)))
               vpns (assoc-in [:entitlements :vpns :assigned] (parse-long (first vpns))))]
    (->> (http/put (format (gen-url "/accounts/api/organizations/%s") org-id)
                   {:headers (default-headers)
                    :body (edn->json body)})
         (parse-response)
         :body)))

(defn update-cloudhub20-connection [{:keys [args static-routes]
                                     [org ps conn] :args}]
  (log/debug "static routes: " static-routes)
  (let [[conn ps org] (reverse args)
        org-id (org->id (or org *org*))
        ps-id (ps->id org-id ps)

        {{:keys [id name  type]} :extra {:keys [routes]} :status}
        (->> (yc/-get-cloudhub20-connections org-id ps-id)
             (filter #(or (= conn (-> % :extra :id))
                          (= conn (-> % :extra :name))))
             (first))
        
        additional-routes (set (filter #(= "+" (subs % 0 1)) static-routes))
        replace-routes (set (filter #(not= "+" (subs % 0 1)) static-routes))

        merged-routes (if (seq replace-routes)
                        replace-routes
                        (reduce #(set (conj %1 (subs %2 1))) routes additional-routes))]

    (condp = (keyword type)
      :vpn (-> (http/patch
                (format (gen-url "/runtimefabric/api/organizations/%s/privatespaces/%s/connections/%s") org-id ps-id id)
                {:headers (default-headers)
                 :body (edn->json :camel {:id id
                                          :name name
                                          :static-routes merged-routes})})
               (parse-response)
               :body
               ((juxt :vpns))
               (->> (apply concat))
               (yc/add-extra-fields :id :connection-id
                                    :name :connection-name
                                    :type type
                                    :routes (fn [x] (->> x :static-routes (str/join ",")))))
      :tgw (-> (http/patch
                (format (gen-url "/runtimefabric/api/organizations/%s/privatespaces/%s/transitgateways/%s") org-id ps-id id)
                {:headers (default-headers)
                 :body (edn->json :camel {:routes merged-routes})})
               (parse-response)
               :body
               (yc/add-extra-fields :id :id
                                    :name :name
                                    :type type
                                    :routes (comp #(str/join "," %) :routes)))
      (throw (e/not-implemented "This type is not supported" {:type type :id id })))))

(defn update-connected-app [{:keys [args scopes org-scopes env-scopes org env] :as opts}]
  "Update a connected app's scopes

  Usage:
    yaac update connected-app <app-name-or-client-id> --scopes profile --org-scopes read:organization --env-scopes read:applications --env Production,Sandbox

  Options:
    --scopes      - Comma-separated basic scopes (e.g., profile,openid)
    --org-scopes  - Comma-separated org-level scopes (e.g., read:organization,edit:organization)
    --env-scopes  - Comma-separated env-level scopes (e.g., read:applications,admin:cloudhub)
    --org         - Organization for scopes (default: current org)
    --env         - Comma-separated environments for env-level scopes (e.g., Production,Sandbox)"
  (let [app-name (first args)
        _ (when-not app-name
            (throw (e/invalid-arguments "Connected app name or client-id is required" {:args args})))
        client-id (connected-app->id app-name)
        org-id (when (or org-scopes env-scopes) (org->id (or org *org*)))
        scope-list (when scopes (str/split scopes #","))
        org-scope-list (when org-scopes (str/split org-scopes #","))
        env-scope-list (when env-scopes (str/split env-scopes #","))
        env-list (when env (str/split env #","))
        env-ids (when (and env-scopes env-list)
                  (mapv #(yc/env->id org-id %) env-list))
        all-scopes (concat (or scope-list []) (or org-scope-list []) (or env-scope-list []))]
    (when (empty? all-scopes)
      (throw (e/invalid-arguments "At least one of --scopes, --org-scopes, or --env-scopes is required" opts)))
    (when (and env-scopes (not env))
      (throw (e/invalid-arguments "--env is required when using --env-scopes" {:env-scopes env-scopes})))
    (log/debug "Updating scopes for" client-id ":" all-scopes)
    (assign-connected-app-scopes client-id {:scopes scope-list
                                            :org-scopes org-scope-list
                                            :org-id org-id
                                            :env-scopes env-scope-list
                                            :env-ids env-ids})
    [{:extra {:client-id client-id
              :scopes (str/join "," all-scopes)}}]))

(def route
  ["update" {:options options
             :usage usage}
   ["" {:help true}]   
   ["|-h" {:help true}]
   ["|app" {:help true}]
   ["|app|{*args}" {:fields [[:extra :id]
                             [:extra :name]
                             ;;[:application :v-cores]
                             ;;[:target :replicas]
                             [:extra :status]]
                    :handler update-app-config}]

   ["|asset" {:help true}]
   ["|asset|{*args}" {:fields [:status]
                      :handler update-asset-config}]
   ["|api" {:help true}]
   ["|api|{*args}" {:fields [:id :asset-version :technology [:endpoint :deployment-type] [:endpoint :uri] [:endpoint :proxy-uri]]
                    :handler update-api-config}]
   ["|org" {:help true}]
   ["|org|{*args}" {:handler update-organization-config
                    :fields [:id :name
                             [:entitlements :v-cores-production :assigned]
                             [:entitlements :v-cores-sandbox :assigned]
                             [:entitlements :static-ips :assigned]
                             [:entitlements :network-connections :assigned]
                             [:entitlements :vpns :assigned]]}]
   ["|connection" {:help true}]
   ["|conn" {:help true}]
   ["|connection|{*args}" {:handler update-cloudhub20-connection}]
   ["|conn|{*args}" {:handler update-cloudhub20-connection}]
   ["|connected-app" {:help true}]
   ["|connected-app|{*args}" {:fields [[:extra :client-id] [:extra :scopes]]
                              :handler update-connected-app}]])
