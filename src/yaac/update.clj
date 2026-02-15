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
  (:require [yaac.util :as util :refer [json->edn edn->json]]
            [taoensso.timbre :as log]
            [zeph.client :as http]
            [reitit.core :as r]
            [yaac.core :refer [*org* *env* parse-response default-headers short-uuid org->id env->id api->id org->name ps->id conn->id load-session! gen-url assign-connected-app-scopes connected-app->id -get-root-organization -get-client-provider client-provider->id -get-api-upstreams -patch-api-upstream -get-api-policy -patch-api-policy policy-name->id] :as yc]
            [yaac.error :as e]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [yaac.incubate :as ic]
            [clojure.spec.alpha :as s]
            [jsonista.core :as json]))


(defn usage [opts]
  (let [{:keys [summary help-all]} (if (map? opts) opts {:summary opts :help-all true})]
    (->> (concat
          ["Usage: update <resources> [options]"
           ""
           "Update configurations of assets, apps and resources."
           ""
           "Options:"
           ""
           summary
           ""]
          (when help-all
            ["Resources:"
             ""
             "  - app [org] [env] <app> key=val       ...                    "
             "  - asset <asset> key=val               ...                    "
             "  - api [org] [env] <api> key=val       ...                    "
             "  - org [org] key=val                   ...                    "
             "  - conn [org] <private-space> <connection> ... key=val  "
             "  - connected-app <name-or-client-id> --scopes ... --org-scopes ..."
             "  - client-provider <name-or-id> --name ... --authorize-url ..."
             "  - upstream [org] [env] <api> --upstream-uri <uri>"
             "  - policy [org] [env] <api> <policy-name> --jwks-url <url>"
             ""
             "Keys:"
             "  app"
             "    - v-cores"
             "    - replicas"
             "    - runtime-version"
             "    - state=<start|stop>"
             "  asset"
             "    - labels"
             "    Note: -g defaults to current org, -v defaults to latest version"
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
             "  client-provider (cp)"
             "    - --description                       : Description"
             "    - --issuer                            : Token issuer identifier"
             "    - --authorize-url                     : Authorization endpoint URL"
             "    - --token-url                         : Token endpoint URL"
             "    - --introspect-url                    : Introspection endpoint URL"
             "    - --client-id                         : Primary client ID for introspection"
             "    - --client-secret                     : Primary client secret"
             "    - --allow-client-import               : Allow client import (true/false)"
             "    - --allow-external-client-modification: Allow external modification (true/false)"
             "    - --allow-local-client-deletion       : Allow local deletion (true/false)"
             "  upstream"
             "    - --upstream-uri                      : Upstream URI for API instance"
             "  policy"
             "    - --jwks-url                          : JWKS URL for JWT validation policy"
             ""])
          ["Example:"
           ""
           "# Update app state, v-cores, replicas"
           "  > yaac update app hello-world v-cores=0.2 replicas=2 state=stop"
           ""
           "# Update asset labels"
           "  > yaac update asset hello-api labels=db,demo -g T1 -v 0.1.0"
           ""])
         (str/join \newline))))


(def options [["-g" "--group NAME" "Group name. Normally BG name"]
              ["-a" "--asset NAME" "Asset name"]
              ["-v" "--version VERSION" "Asset version"]
              [nil "--scopes SCOPES" "Comma-separated scopes for connected-app"]
              [nil "--org-scopes SCOPES" "Comma-separated org-level scopes for connected-app"]
              [nil "--env-scopes SCOPES" "Comma-separated env-level scopes for connected-app"]
              [nil "--org ORG" "Organization for scopes"]
              [nil "--env ENVS" "Comma-separated environments for env-level scopes"]
              ;; Client provider options
              [nil "--description DESC" "Client provider description"
               :id :description]
              [nil "--issuer ISSUER" "Token issuer identifier"
               :id :issuer]
              [nil "--authorize-url URL" "Authorization endpoint URL"
               :id :authorize-url]
              [nil "--token-url URL" "Token endpoint URL"
               :id :token-url]
              [nil "--introspect-url URL" "Introspection endpoint URL"
               :id :introspect-url]
              [nil "--client-id ID" "Primary client ID for token introspection"
               :id :client-id]
              [nil "--client-secret SECRET" "Primary client secret"
               :id :client-secret]
              [nil "--allow-client-import BOOL" "Allow client import (true/false)"
               :id :allow-client-import]
              [nil "--allow-external-client-modification BOOL" "Allow external client modification (true/false)"
               :id :allow-external-client-modification]
              [nil "--allow-local-client-deletion BOOL" "Allow local client deletion (true/false)"
               :id :allow-local-client-deletion]
              ;; API upstream options
              [nil "--upstream-uri URI" "Upstream URI for API instance"
               :id :upstream-uri]
              ;; API policy options
              [nil "--jwks-url URL" "JWKS URL for JWT validation policy"
               :id :jwks-url]])


(defn update-asset-config [{:keys [args group asset version labels]
                            :as opts}]
  (let [;; Asset can come from args or -a option
        asset (or (first args) asset)
        _ (when-not asset
            (throw (e/invalid-arguments "Asset name is required" {:asset asset})))
        ;; Group defaults to current org
        group (or group yc/*org*)
        group-id (yc/org->id group)
        ;; Get latest version if not specified
        version (or version
                    (let [assets (yc/get-assets {:group group :asset asset})]
                      (when (empty? assets)
                        (throw (e/no-item "Asset not found" {:group group :asset asset})))
                      (:version (first assets))))
        url (format (gen-url "/exchange/api/v2/organizations/%s/assets/%s/%s/%s/mutabledata")
                    group-id group-id asset version)
        multipart [{:name "tags" :content (str/join "," labels)}]]
    (-> @(http/patch url {:headers (yc/multipart-headers)
                         :multipart multipart})
        (parse-response))))

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
        (-> @(http/patch (format (gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s") org-id env-id api-id)
                        {:headers (yc/default-headers)
                         :body (edn->json :camel {:asset-version asset-version})})
            (parse-response)
            :body)))))



(defn update-app-config [{:keys [args v-cores replicas runtime-version state all group asset version]}]
  (let [[app-name env org] (reverse args) ;; app has to be specified
        target-org-id (yc/org->id (or org *org*))           ;; If specified, use it
        target-env-id (yc/env->id target-org-id (or env *env*))
        apps (yc/name->apps target-org-id target-env-id app-name)]
    (util/spin (str "Updating app " app-name "..."))
    (if (and (not all) (< 1 (count apps)))
      (throw (e/multiple-app-name-found "Several apps found. Use -A if you want to update all" {:apps (mapv :name apps)}))
      (->> apps
           (mapcat (fn [app]
                     (cond
                       (= "SERVER" (-> app :target :type))
                       (do
                         (-> (gen-url "/hybrid/api/v1/applications/%s")
                             (format (:id app))
                             (#(deref (http/patch % {:headers (assoc (default-headers)
                                                                     "X-ANYPNT-ORG-ID" target-org-id
                                                                     "X-ANYPNT-ENV-ID" target-env-id)
                                                     :body (edn->json (cond-> {}
                                                                        state (assoc-in [:desired-status] (condp = (keyword (str/lower-case (first state)))
                                                                                                            :start "STARTED"
                                                                                                            :stop "STOPPED"))))})))
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
                             (#(deref (http/patch % {:headers (yc/default-headers)
                                                     :body (edn->json (cond-> {}
                                                                        ;; Asset reference for version update
                                                                        (and group asset version)
                                                                        (assoc-in [:application :ref] {:group-id group
                                                                                                       :artifact-id asset
                                                                                                       :version version
                                                                                                       :packaging "jar"})
                                                                        v-cores (assoc-in [:application :v-cores] (str (first v-cores)))
                                                                        replicas (assoc-in [:target :replicas] (parse-long (first replicas)))
                                                                        runtime-version (assoc-in [:target :deployment-settings :runtime-version] (first runtime-version))
                                                                        state (assoc-in [:application :desired-state] (condp = (keyword (str/lower-case (first state)))
                                                                                                                        :start "STARTED"
                                                                                                                        :stop "STOPPED"))))})))
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
    (->> @(http/put (format (gen-url "/accounts/api/organizations/%s") org-id)
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
      :vpn (-> @(http/patch
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
      :tgw (-> @(http/patch
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

(defn update-client-provider
  "Update a client provider (OpenID Connect) configuration

   NOTE: This API requires 'admin:orgclientproviders' scope and Organization Administrator permission.

   Supports updating the following fields:
   - description: Provider description
   - issuer: Token issuer identifier
   - authorize-url: Authorization endpoint URL
   - token-url: Token endpoint URL
   - introspect-url: Introspection endpoint URL
   - client-id: Primary client ID for token introspection
   - client-secret: Primary client secret
   - allow-client-import: Allow client import (true/false)
   - allow-external-client-modification: Allow external client modification (true/false)
   - allow-local-client-deletion: Allow local client deletion (true/false)"
  [{:keys [args description issuer authorize-url token-url introspect-url
           client-id client-secret allow-client-import
           allow-external-client-modification allow-local-client-deletion] :as opts}]
  (let [cp-name-or-id (first args)
        _ (when-not cp-name-or-id
            (throw (e/invalid-arguments "Client provider name or ID is required" {:args args})))
        current-cp (-get-client-provider cp-name-or-id)
        _ (when-not current-cp
            (throw (e/no-item "Client provider not found" {:name cp-name-or-id})))
        provider-id (:provider-id current-cp)
        {root-org-id :id} (-get-root-organization)
        ;; Get current OIDC config for default values
        oidc-config (:oidc-dynamic-client-provider current-cp)
        ;; Build minimal update body - construct JSON directly to preserve snake_case
        oidc-body (cond-> {"allow_local_client_deletion"
                           (if allow-local-client-deletion
                             (parse-boolean allow-local-client-deletion)
                             (:allow-local-client-deletion oidc-config))
                           "allow_external_client_modification"
                           (if allow-external-client-modification
                             (parse-boolean allow-external-client-modification)
                             (:allow-external-client-modification oidc-config))
                           "allow_client_import"
                           (if allow-client-import
                             (parse-boolean allow-client-import)
                             (:allow-client-import oidc-config))
                           "issuer" (or issuer (:issuer oidc-config))}
                    ;; Add urls if any URL is specified
                    (or authorize-url token-url introspect-url)
                    (assoc "urls" (cond-> {}
                                    (or authorize-url (get-in oidc-config [:urls :authorize]))
                                    (assoc "authorize" (or authorize-url (get-in oidc-config [:urls :authorize])))
                                    (or token-url (get-in oidc-config [:urls :token]))
                                    (assoc "token" (or token-url (get-in oidc-config [:urls :token])))
                                    (or introspect-url (get-in oidc-config [:urls :introspect]))
                                    (assoc "introspect" (or introspect-url (get-in oidc-config [:urls :introspect])))))
                    ;; Add primary_client if client-id or client-secret is specified
                    (or client-id client-secret (get-in oidc-config [:primary-client :id]))
                    (assoc "primary_client" (cond-> {}
                                              (or client-id (get-in oidc-config [:primary-client :id]))
                                              (assoc "id" (or client-id (get-in oidc-config [:primary-client :id])))
                                              client-secret
                                              (assoc "secret" client-secret))))
        updated-body (cond-> {}
                       description (assoc "type" {"description" description})
                       :always (assoc "oidc_dynamic_client_provider" oidc-body))
        ;; Use jsonista directly to avoid key transformation
        json-body (json/write-value-as-string updated-body)]
    (log/debug "Updating client provider:" provider-id)
    (log/debug "Update body:" json-body)
    (-> @(http/patch (format (gen-url "/accounts/api/organizations/%s/clientProviders/%s") root-org-id provider-id)
                    {:headers (default-headers)
                     :body json-body})
        (parse-response)
        :body
        (yc/add-extra-fields :id :provider-id
                             :name :name
                             :type (comp :name :type)))))

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

;; API Upstream update
(defn update-api-upstream
  "Update API instance upstream URI"
  [{:keys [args upstream-uri] :as opts}]
  (let [[api env org] (reverse args)
        org (or org *org*)
        env (or env *env*)]
    (when-not (and org env api)
      (throw (e/invalid-arguments "Org, Env and API need to be specified" {:args args})))
    (when-not upstream-uri
      (throw (e/invalid-arguments "upstream-uri is required" {:args args})))
    (let [upstreams (-get-api-upstreams org env api)
          upstream-id (-> upstreams :upstreams first :id)]
      (if upstream-id
        (do
          (-patch-api-upstream org env api upstream-id upstream-uri)
          [{:extra {:api-id api
                    :upstream-id upstream-id
                    :uri upstream-uri
                    :status "updated"}}])
        (throw (e/no-item "No upstream found for this API instance" {:api api}))))))

;; API Policy update
(defn update-api-policy
  "Update API policy configuration by policy name (asset-id)"
  [{:keys [args jwks-url] :as opts}]
  (let [[policy-name api env org] (reverse args)
        org (or org *org*)
        env (or env *env*)]
    (when-not (and org env api policy-name)
      (throw (e/invalid-arguments "Org, Env, API and policy-name need to be specified" {:args args})))
    (when-not jwks-url
      (throw (e/invalid-arguments "At least one policy option (e.g. --jwks-url) is required" opts)))
    (if-let [policy-id (policy-name->id org env api policy-name)]
      (let [current-policy (-get-api-policy org env api policy-id)
            current-config (:configuration-data current-policy)
            updated-config (cond-> current-config
                             jwks-url (assoc :jwks-url jwks-url))]
        (-patch-api-policy org env api policy-id updated-config)
        [{:extra {:api-id api
                  :policy-name policy-name
                  :policy-id policy-id
                  :status "updated"}}])
      (throw (e/no-item (str "Policy '" policy-name "' not found for API " api) {:api api :policy-name policy-name})))))

(def route
  (for [op ["update" "upd"]]
    [op {:options options
         :usage usage}
     ["" {:help true}]
     ["|-h" {:help true}]
   ["|app" {:help true}]
   ["|app|{*args}" {:fields [[:extra :id :fmt short-uuid]
                             [:extra :name]
                             ;;[:application :v-cores]
                             ;;[:target :replicas]
                             [:extra :status]]
                    :handler update-app-config}]

   ["|asset" {:help true}]
   ["|asset|{*args}" {:fields [:status]
                      :handler update-asset-config}]
   ["|api" {:help true}]
   ["|api|{*args}" {:fields [[:id :fmt short-uuid] :asset-version :technology [:endpoint :deployment-type] [:endpoint :uri] [:endpoint :proxy-uri]]
                    :handler update-api-config}]
   ["|org" {:help true}]
   ["|org|{*args}" {:handler update-organization-config
                    :fields [:name [:id :fmt short-uuid]
                             [:entitlements :v-cores-production :assigned :as "production"]
                             [:entitlements :v-cores-sandbox :assigned :as "sandbox"]
                             [:entitlements :static-ips :assigned :as "static-ips"]
                             [:entitlements :network-connections :assigned :as "connections"]
                             [:entitlements :vpns :assigned :as "vpns"]]}]
   ["|connection" {:help true}]
   ["|conn" {:help true}]
   ["|connection|{*args}" {:handler update-cloudhub20-connection}]
   ["|conn|{*args}" {:handler update-cloudhub20-connection}]
   ["|connected-app" {:help true}]
   ["|connected-app|{*args}" {:fields [[:extra :client-id] [:extra :scopes]]
                              :handler update-connected-app}]
   ;; Client Providers
   ["|cp" {:help true}]
   ["|cp|{*args}" {:fields [[:extra :name] [:extra :id :fmt short-uuid] [:extra :type]]
                   :handler update-client-provider}]
   ["|client-provider" {:help true}]
   ["|client-provider|{*args}" {:fields [[:extra :name] [:extra :id :fmt short-uuid] [:extra :type]]
                                 :handler update-client-provider}]
   ;; API Upstream
   ["|upstream" {:help true}]
   ["|upstream|{*args}" {:fields [[:extra :api-id] [:extra :upstream-id] [:extra :uri] [:extra :status]]
                         :handler update-api-upstream}]
   ;; API Policy
   ["|policy" {:help true}]
     ["|policy|{*args}" {:fields [[:extra :api-id] [:extra :policy-name] [:extra :policy-id] [:extra :status]]
                         :handler update-api-policy}]]))
