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
                               api->id
                               gw->id
                               org->name
                               env->name
                               user->id
                               provider->id
                               client-provider->id
                               ps->id
                               rtf->id
                               load-session!
                               -get-root-organization
                               -get-user
                               -get-environments
                               -get-deployed-applications
                               -get-api-instances
                               -get-runtime-fabrics
                               -get-cloudhub20-privatespaces
                               -get-gateways
                               -get-managed-gateways
                               -get-api-policies
                               -get-secret-groups
                               alert->id
                               get-assets
                               gen-url] :as yc]
            [yaac.error :as e]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [yaac.incubate :as ic]
            [clojure.spec.alpha :as s]
            [camel-snake-kebab.core :as csk]
            [yaac.util :as util]))


(defn delete-usage [opts]
  (let [{:keys [summary help-all]} (if (map? opts) opts {:summary opts :help-all true})]
    (->> (concat
          ["Usage: delete <resources> [options]"
           ""
           "Delete assets, apps and resources."
           ""
           "Options:"
           ""
           summary
           ""]
          (when help-all
            ["Resources:"
             ""
             "  - org <org|id> [--force]                           Delete business group."
             "  - asset -g GROUP -a ASSET -v VERSION [options]     Delete asset."
             "  - app [org] [env] <app|id>                         Delete deployed application."
             "  - api [org] [env] <api|id>                         Delete deployed api instances."
             "  - idp-user <email> provider=<name>                 Delete IdP user profile."
             "  - connected-app <name|client-id>                   Delete connected app."
             "  - client-provider <name|id>                        Delete client provider (cp)."
             "  - rtf [org] <name|id>                              Delete Runtime Fabric cluster."
             "  - ps [org] <name|id>                               Delete Private Space."
             ""])
          ["Example:"
           ""
           "# Delete organization (fails if resources exist)"
           "  > yaac delete org T1"
           ""
           "# Delete organization with all resources (--force)"
           "# Deletes: apps, APIs, gateways, secret-groups, assets, RTF, Private Spaces"
           "  > yaac delete org T1 --force --dry-run"
           "  > yaac delete org T1 --force"
           ""
           "# Delete asset"
           "  > yaac delete asset -a hello-api -g T1 -v 0.0.1"
           ""
           "# Delete application"
           "  > yaac delete app hello-api"
           ""
           "# Delete all apps in org (all envs)"
           "  > yaac delete app T1 -A --dry-run"
           "  > yaac delete app T1 -A --force"
           ""
           "# Delete all apps in specific env"
           "  > yaac delete app T1 Production -A"
           ""
           "# Delete all APIs in org (all envs)"
           "  > yaac delete api T1 -A --dry-run"
           ""])
         (str/join \newline))))

(defn clear-usage [opts]
  (let [{:keys [summary]} (if (map? opts) opts {:summary opts})]
    (->> ["Usage: clear <resources> [options]"
          ""
          "Clear all resources from an organization without deleting the org itself."
          ""
          "Options:"
          ""
          summary
          ""
          "Resources:"
          ""
          "  - org <org|id>                Clear apps, APIs, gateways, secret-groups, assets from org."
          "                                (RTF and Private Spaces are NOT deleted)"
          ""
          "Example:"
          ""
          "# Preview what would be deleted"
          "  > yaac clear org T1 --dry-run"
          ""
          "# Clear all resources from organization"
          "  > yaac clear org T1"
          ""]
         (str/join \newline))))


(def options [["-g" "--group NAME" "Group name. Normally BG name"]
              ["-a" "--asset NAME" "Asset name"]
              ["-v" "--version VERSION" "Asset version"]
              ["-A" "--all" "Delete all (all versions / all apps in org+envs)"]
              [nil  "--all-orgs" "Delete from all organizations"]
              [nil  "--dry-run"  "Show items without deleting"]
              [nil  "--force"    "Force delete org with all resources / skip confirmation"]
              [nil  "--hard-delete"  "Hard delete for assets"]
              ["-M" "--managed" "For Managed Flex Gateway"]
              ["-t" "--type TYPE" "Alert type: api, app, or server"]])

(def clear-options [[nil  "--dry-run"  "Show items without deleting"]])

;; Forward declarations
(declare -delete-api-instance-by-id -delete-asset-by-id)

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


(defn- collect-apps-for-deletion
  "削除対象のアプリを収集。org-envsは[[org env]...]形式"
  [org-envs]
  (->> org-envs
       (pmap (fn [[g e]]
               (try (->> (-get-deployed-applications g e)
                         (map #(assoc % :_org g :_env e)))
                    (catch Exception _ []))))
       (apply concat)
       (vec)))

(defn delete-application [{:keys [args all dry-run force]
                           :as opts}]
  (let [[app env org] (reverse args) ;; app has to be specified
        org (or org *org*)           ;; If specified, use it
        env (or env *env*)]          ;; If specified, use it

    (log/debug "Delete application:" (dissoc opts :summary))

    (if all
      ;; -A フラグ: スコープ内の全アプリ削除
      (let [org-envs (cond
                       ;; orgのみ指定 → BG配下の全環境
                       (and org (nil? env) (nil? app))
                       (->> (-get-environments org)
                            (mapv (fn [{e :name}] [org e])))
                       ;; org/env両方指定（appはnil）→ その環境のみ
                       (and org env (nil? app))
                       [[org env]]
                       ;; org/env/app指定 → その環境内で名前マッチするもの
                       (and org env app)
                       [[org env]]
                       ;; orgなし → エラー
                       :else
                       (throw (e/invalid-arguments "Org required for -A" {:args args})))
            all-apps (collect-apps-for-deletion org-envs)
            ;; app指定がある場合はフィルタ
            apps (if app
                   (filterv #(or (= (:name %) app)
                                 (= (str (:id %)) app)
                                 (re-find (re-pattern app) (:name %)))
                            all-apps)
                   all-apps)]
        (cond
          (empty? apps)
          (throw (e/app-not-found "No applications found" {:org org :env env}))

          dry-run
          (do (println (format "Would delete %d application(s):" (count apps)))
              (doseq [a apps]
                (println (format "  - %s (%s/%s)" (:name a) (:_org a) (:_env a))))
              [])

          (or force (util/confirm-prompt
                     (format "Delete %d application(s)?" (count apps)) apps))
          (do (println (format "Deleting %d application(s)..." (count apps)))
              (->> apps
                   (pmap #(-delete-application (:_org %) (:_env %) %))
                   (vec)))

          :else
          (do (println "Aborted.") [])))

      ;; 既存ロジック（単一アプリ削除）
      (let [[app target] (when app (reverse (str/split app #"/")))
            target-id (when (and org env target)
                        (yc/try-wrap (yc/target->id org env target)))]
        (if-not (and org env app)
          (throw (e/invalid-arguments "Org, Env and App need to be specified" {:args args}))
          (let [apps (->> (-get-deployed-applications org env)
                          (filter #(and
                                    (or (nil? target-id) (= (get-in % [:target :target-id]) target-id))
                                    (or (re-find (re-pattern (str "^" app "$")) (:name %))
                                        (re-find (re-pattern (str "^" app "$")) (str (:id %)))))))]
            (cond
              (= 1 (count apps))
              (cond->> apps
                *no-multi-thread* (map #(-delete-application org env %))
                (not *no-multi-thread*) (pmap #(-delete-application org env %))
                :always (apply concat))

              (< 1 (count apps))
              (throw (e/multiple-app-name-found "several apps found. Use -A if all apps are to be deleted"
                                                {:org (org->name org) :env (env->name org env) :apps (mapv :name apps)}))

              (= 0 (count apps))
              (throw (e/app-not-found "No app found" {:org (org->name org) :env (env->name org env) :app app})))))))))


(defn delete-asset [{:keys [args group asset version all hard-delete]
                     :as opts}]

  (let [group (or group *org*)
        group-id (and group (org->id group))
        artifact-id asset]
    (if-not (and group-id artifact-id (or version all))
      (throw (e/invalid-arguments "Group, Asset and version need to to be specified" {:group group
                                                                                      :asset asset
                                                                                      :version version
                                                                                      :all all}))
      (let [vs (if all
                 (map :version (yc/get-assets {:group group :asset asset}))
                 [version])]
        (try
          (->> vs
               (mapv (fn [v]
                       (util/spin (str "Deleting " artifact-id ":" v "..."))
                       (-delete-asset-by-id group-id artifact-id v hard-delete))))
          (finally (util/spin)))))))


(defn- dry-run->table-data
  "Convert dry-run result to table format data"
  [{:keys [org apps apis gws sgs assets rtfs pss org-delete]}]
  (concat
   (map (fn [name] {:extra {:type "asset" :name name :status "pending"}}) assets)
   (map (fn [name] {:extra {:type "app" :name name :status "pending"}}) apps)
   (map (fn [name] {:extra {:type "api" :name name :status "pending"}}) apis)
   (map (fn [name] {:extra {:type "gw" :name name :status "pending"}}) gws)
   (map (fn [name] {:extra {:type "sg" :name name :status "pending"}}) sgs)
   (map (fn [name] {:extra {:type "rtf" :name name :status "pending"}}) rtfs)
   (map (fn [name] {:extra {:type "ps" :name name :status "pending"}}) pss)
   (when org-delete
     [{:extra {:type "org" :name org :status "pending"}}])))

(defn format-org-cleanup
  "Custom formatter for org cleanup operations (clear/delete)"
  [output-format data opts]
  (let [first-item (first data)]
    (cond
      ;; Dry-run mode - convert to table format
      (= "dry-run" (get-in first-item [:extra :action]))
      (let [table-data (dry-run->table-data (:extra first-item))]
        (case output-format
          :json (util/json-pprint first-item)
          :edn (with-out-str (clojure.pprint/pprint first-item))
          (if (empty? table-data)
            "No resources to delete.\n"
            (yc/default-format-by [[[:extra :type]] [[:extra :name]] [[:extra :status]]]
                                  :short table-data opts))))

      ;; Actual deletion results
      :else
      (case output-format
        :json (util/json-pprint (mapv :extra data))
        :edn (with-out-str (clojure.pprint/pprint (mapv :extra data)))
        (yc/default-format-by [[[:extra :type]] [[:extra :name]] [[:extra :env]] [[:extra :status]] [[:extra :message]]]
                              :short data opts)))))

(defn- -delete-asset-by-id
  "Delete a single asset version"
  ([group-id asset-id version]
   (-delete-asset-by-id group-id asset-id version false))
  ([group-id asset-id version hard-delete?]
   (try
     (let [resp (-> @(http/delete (format (gen-url "/exchange/api/v2/assets/%s/%s/%s") group-id asset-id version)
                                  {:headers (conj (default-headers)
                                                  {"x-delete-type" (if hard-delete? "hard-delete" "soft-delete")})})
                    (parse-response))]
       (assoc resp :extra {:asset asset-id :version version}))
     (catch Exception e
       (let [data (ex-data e)
             raw (:raw data)
             http-status (or (:status data) 500)
             dependents (get-in raw [:details :dependents])
             ;; Extract dependency info from conflict error (status 409)
             msg (cond
                   ;; Conflict with dependents
                   (and (= 409 http-status) (seq dependents))
                   (let [;; Group by org-id and asset-id
                         grouped (->> dependents
                                      (map (juxt :organization-id :asset-id))
                                      distinct
                                      (map (fn [[org-id asset-id]]
                                             (let [org-name (or (yc/org->name org-id) org-id)]
                                               (str org-name "/" asset-id)))))]
                     (str "Conflict: used by " (str/join ", " grouped)))
                   ;; Other structured error
                   (:message raw)
                   (str (:message raw))
                   ;; Fallback
                   :else
                   (ex-message e))]
         {:status http-status :extra {:asset asset-id :version version :message msg}})))))

(defn- -collect-org-resources
  "Collect all resources (apps, apis, assets, gateways, secret-groups, rtf, ps) from an organization"
  [org]
  (let [envs (-get-environments org)
        org-envs (mapv (fn [{e :name}] [org e]) envs)
        ;; Collect apps
        apps (->> org-envs
                  (mapcat (fn [[g e]]
                            (try (map #(assoc % :_org g :_env e)
                                      (-get-deployed-applications g e))
                                 (catch Exception ex
                                   (log/debug "Error collecting apps from" g e ":" (ex-message ex))
                                   []))))
                  vec)
        ;; Collect APIs
        apis (->> org-envs
                  (mapcat (fn [[g e]]
                            (try (map #(assoc % :_org g :_env e)
                                      (-get-api-instances g e))
                                 (catch Exception ex
                                   (log/debug "Error collecting apis from" g e ":" (ex-message ex))
                                   []))))
                  vec)
        ;; Collect Flex Gateways
        gws (->> org-envs
                 (mapcat (fn [[g e]]
                           (try (map #(assoc % :_org g :_env e)
                                     (-get-gateways g e))
                                (catch Exception ex
                                  (log/debug "Error collecting gateways from" g e ":" (ex-message ex))
                                  []))))
                 vec)
        ;; Collect Secret Groups
        sgs (->> org-envs
                 (mapcat (fn [[g e]]
                           (try (-get-secret-groups g e)
                                (catch Exception ex
                                  (log/debug "Error collecting secret groups from" g e ":" (ex-message ex))
                                  []))))
                 vec)
        ;; Collect assets
        assets (try (get-assets {:group org})
                    (catch Exception ex
                      (log/debug "Error collecting assets from" org ":" (ex-message ex))
                      []))
        ;; Collect Runtime Fabrics
        rtfs (try (-get-runtime-fabrics org)
                  (catch Exception ex
                    (log/debug "Error collecting RTFs from" org ":" (ex-message ex))
                    []))
        ;; Collect Private Spaces
        pss (try (-get-cloudhub20-privatespaces org)
                 (catch Exception ex
                   (log/debug "Error collecting Private Spaces from" org ":" (ex-message ex))
                   []))]
    (log/debug "Collected resources - apps:" (count apps) "apis:" (count apis)
               "gws:" (count gws) "sgs:" (count sgs) "assets:" (count assets) "rtfs:" (count rtfs) "pss:" (count pss))
    {:apps apps :apis apis :gws gws :sgs sgs :assets assets :rtfs rtfs :pss pss}))

(defn- -delete-org-resources
  "Delete all resources from an organization. Returns deletion results.
   Order: Apps -> APIs -> Gateways -> Assets -> Secret Groups -> RTF -> PS (dependency order)"
  [org {:keys [apps apis gws sgs assets rtfs pss]}]
  (let [results (atom [])
        asset-ids (distinct (map :asset-id assets))
        org-id (org->id org)]
    ;; 1. Delete apps first (they depend on assets)
    (doseq [app apps]
      (try
        ;; -delete-application returns a vector via add-extra-fields, take first
        (let [r (first (-delete-application (:_org app) (:_env app) app))]
          (swap! results conj (update r :extra assoc :type "app" :status "deleted")))
        (catch Exception e
          (let [msg (or (get-in (ex-data e) [:extra :message]) (ex-message e))]
            (swap! results conj {:extra {:type "app" :name (:name app) :env (:_env app)
                                         :status "failed" :message msg}})))))
    ;; 2. Delete APIs
    (doseq [api apis]
      (try
        ;; -delete-api-instance-by-id returns a vector via add-extra-fields, take first
        (let [r (first (-delete-api-instance-by-id (:_org api) (:_env api) (:id api)))]
          (swap! results conj (update r :extra assoc :type "api" :name (:asset-id api) :status "deleted")))
        (catch Exception e
          (let [msg (or (get-in (ex-data e) [:extra :message]) (ex-message e))]
            (swap! results conj {:extra {:type "api" :name (:asset-id api) :env (:_env api)
                                         :status "failed" :message msg}})))))
    ;; 3. Delete Flex Gateways (if any exist, skip remaining steps - GW takes 7 days to fully delete)
    (doseq [{:keys [id name source _org _env]} gws]
      (let [org-id (org->id _org)
            env-id (env->id _org _env)
            url (if (= source "standalone")
                  (format (gen-url "/standalone/api/v1/organizations/%s/environments/%s/gateways/%s") org-id env-id id)
                  (format (gen-url "/gatewaymanager/api/v1/organizations/%s/environments/%s/gateways/%s") org-id env-id id))]
        (try
          (-> @(http/delete url {:headers (default-headers)})
              (parse-response))
          (swap! results conj {:extra {:type "gw" :name name :env _env :status "deleted"}})
          (catch Exception e
            (swap! results conj {:extra {:type "gw" :name name :env _env
                                         :status "failed" :message (ex-message e)}})))))
    (if (seq gws)
      ;; GW exists: skip remaining steps (GW deletion takes 7 days)
      (conj @results {:extra {:type "info" :name "-" :status "pending"
                              :message "Flex Gateway takes 7 days to delete. Retry after deletion completes."}})
      ;; No GW: continue with remaining deletions
      (do
        ;; 4. Delete assets
        (doseq [asset-id asset-ids]
          (try
            (let [rs (delete-asset {:group org :asset asset-id :all true})]
              (doseq [r rs]
                (swap! results conj (update r :extra assoc :type "asset" :name asset-id :status "deleted"))))
            (catch Exception e
              (swap! results conj {:extra {:type "asset" :name asset-id
                                           :status "failed" :message (ex-message e)}}))))
        ;; 5. Delete Secret Groups
        (doseq [{:keys [name _org _env] {:keys [id]} :meta} sgs]
          (let [org-id (org->id _org)
                env-id (env->id _org _env)]
            (try
              (-> @(http/delete (format (gen-url "/secrets-manager/api/v1/organizations/%s/environments/%s/secretGroups/%s") org-id env-id id)
                               {:headers (default-headers)})
                  (parse-response))
              (swap! results conj {:extra {:type "sg" :name name :env _env :status "deleted"}})
              (catch Exception e
                (swap! results conj {:extra {:type "sg" :name name :env _env
                                             :status "failed" :message (ex-message e)}})))))
        ;; 7. Delete Runtime Fabrics
        (doseq [{:keys [id name]} rtfs]
          (try
            (-> @(http/delete (format (gen-url "/runtimefabric/api/organizations/%s/fabrics/%s") org-id id)
                             {:headers (default-headers)})
                (parse-response))
            (swap! results conj {:extra {:type "rtf" :name name :status "deleted"}})
            (catch Exception e
              (swap! results conj {:extra {:type "rtf" :name name
                                           :status "failed" :message (ex-message e)}}))))
        ;; 8. Delete Private Spaces
        (doseq [{:keys [id name]} pss]
          (try
            (-> @(http/delete (format (gen-url "/runtimefabric/api/organizations/%s/privatespaces/%s") org-id id)
                             {:headers (default-headers)})
                (parse-response))
            (swap! results conj {:extra {:type "ps" :name name :status "deleted"}})
            (catch Exception e
              (swap! results conj {:extra {:type "ps" :name name
                                           :status "failed" :message (ex-message e)}}))))
        @results))))

(defn clear-organization
  "Clear resources (apps, apis, gateways, secret-groups, assets) from org without deleting the org itself.
   RTF and Private Spaces are NOT deleted."
  [{:keys [args dry-run]
    [org] :args
    :as opts}]
  (if-not org
    (throw (e/invalid-arguments "Org not specified" :args args))
    (let [{:keys [apps apis gws sgs assets] :as resources} (-collect-org-resources org)]
      (if dry-run
        ;; Dry-run: show what would be deleted
        [{:extra {:org org :action "dry-run"
                  :apps (mapv :name apps)
                  :apis (mapv :asset-id apis)
                  :gws (mapv :name gws)
                  :sgs (mapv :name sgs)
                  :assets (mapv :asset-id assets)}}]
        ;; Actually delete resources (but not org, not RTF, not PS)
        (let [results (-delete-org-resources org (dissoc resources :pss :rtfs))]
          (if (empty? results)
            [{:extra {:org org :action "clear" :message "No resources to clear"}}]
            results))))))

(defn delete-organization [{:keys [args dry-run force]
                           [org] :args
                           :as opts}]
  (if-not org
    (throw (e/invalid-arguments "Org not specified" :args args))
    (let [org-id (org->id org)]
      (if force
        ;; --force: Delete all related resources first, then org
        (let [{:keys [apps apis gws sgs assets rtfs pss] :as resources} (-collect-org-resources org)]
          (if dry-run
            ;; Dry-run: show what would be deleted
            [{:extra {:org org :action "dry-run"
                      :apps (mapv :name apps)
                      :apis (mapv :asset-id apis)
                      :gws (mapv :name gws)
                      :sgs (mapv :name sgs)
                      :assets (mapv :asset-id assets)
                      :rtfs (mapv :name rtfs)
                      :pss (mapv :name pss)
                      :org-delete true}}]
            ;; Actually delete resources then org
            (let [results (-delete-org-resources org resources)]
              (if (or (seq gws) (seq pss))
                ;; GW/PS exists: skip org deletion (deletion takes time)
                (conj results {:extra {:type "org" :name org :status "pending"
                                       :message "Gateway/Private Space deletion in progress. Delete org manually after completion."}})
                ;; No GW/PS: delete org immediately
                (try
                  (let [r (parse-response @(http/delete (format (gen-url "/accounts/api/organizations/%s") org-id)
                                                        {:headers (default-headers)}))]
                    (conj results (assoc r :extra {:type "org" :name org :status "deleted"})))
                  (catch Exception e
                    (conj results {:extra {:type "org" :name org :status "failed" :message (ex-message e)}})))))))

        ;; Normal: just try to delete org
        (if dry-run
          ;; Dry-run without --force: show org only
          [{:extra {:org org :action "dry-run" :org-delete true}}]
          (try
            (let [r (parse-response @(http/delete (format (gen-url "/accounts/api/organizations/%s") org-id)
                                                  {:headers (default-headers)}))]
              [(assoc r :extra {:type "org" :name org :status "deleted"})])
            (catch clojure.lang.ExceptionInfo ex
              (let [{:keys [extra] :as data} (ex-data ex)
                    status (:status extra)]
                (if (= status 409)
                  ;; 409 Conflict: Get assets and include in error
                  (let [assets (try (get-assets {:group org}) (catch Exception _ []))
                        asset-names (mapv :asset-id assets)]
                    (throw (ex-info (ex-message ex)
                                    (update data :extra assoc
                                            :assets asset-names
                                            :message (str (:message extra)
                                                          " Assets: " (str/join ", " asset-names))))))
                  (throw ex))))))))))


(defn- -delete-api-instance-by-id
  "指定したAPI instanceを削除"
  [org env api-id]
  (let [org-id (org->id org)
        env-id (env->id org env)]
    (-> @(http/delete (format (gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s") org-id env-id api-id)
                     {:headers (default-headers)})
        (parse-response)
        (dissoc :body)
        (yc/add-extra-fields :org (org->name org) :env (env->name org env) :api-id api-id))))

(defn- collect-apis-for-deletion
  "削除対象のAPIを収集。org-envsは[[org env]...]形式"
  [org-envs]
  (->> org-envs
       (pmap (fn [[g e]]
               (try (->> (-get-api-instances g e)
                         (map #(assoc % :_org g :_env e)))
                    (catch Exception _ []))))
       (apply concat)
       (vec)))

(defn delete-api-instance [{:keys [args all dry-run force] :as opts}]
  (let [[api env org] (reverse args) ;; api has to be specified
        org (or org *org*)           ;; If specified, use it
        env (or env *env*)]

    (if all
      ;; -A フラグ: スコープ内の全API削除
      (let [org-envs (cond
                       ;; orgのみ指定 → BG配下の全環境
                       (and org (nil? env) (nil? api))
                       (->> (-get-environments org)
                            (mapv (fn [{e :name}] [org e])))
                       ;; org/env両方指定（apiはnil）→ その環境のみ
                       (and org env (nil? api))
                       [[org env]]
                       ;; org/env/api指定 → その環境内で名前マッチするもの
                       (and org env api)
                       [[org env]]
                       ;; orgなし → エラー
                       :else
                       (throw (e/invalid-arguments "Org required for -A" {:args args})))
            all-apis (collect-apis-for-deletion org-envs)
            ;; api指定がある場合はフィルタ
            apis (if api
                   (filterv #(or (= (:asset-id %) api)
                                 (= (str (:id %)) api))
                            all-apis)
                   all-apis)]
        (cond
          (empty? apis)
          (throw (e/api-not-found "No API instances found" {:org org :env env}))

          dry-run
          (do (println (format "Would delete %d API instance(s):" (count apis)))
              (doseq [a apis]
                (println (format "  - %s (id=%s, %s/%s)"
                                 (or (:asset-id a) (:exchange-asset-name a) "?")
                                 (:id a) (:_org a) (:_env a))))
              [])

          (or force (util/confirm-prompt
                     (format "Delete %d API instance(s)?" (count apis)) apis))
          (do (println (format "Deleting %d API instance(s)..." (count apis)))
              (->> apis
                   (pmap #(-delete-api-instance-by-id (:_org %) (:_env %) (:id %)))
                   (vec)))

          :else
          (do (println "Aborted.") [])))

      ;; 既存ロジック（単一API削除）
      (let [api-id (yc/api->id org env api)]
        (if-not (and org env api-id)
          (throw (e/invalid-arguments "Org, Env and Api need to be specified" {:args args}))
          (-delete-api-instance-by-id org env api-id))))))


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


;; Delete API Policy

(defn- policy->id
  "Get policy ID from name or ID"
  [org env api policy-name-or-id]
  (let [policies (-get-api-policies org env api)]
    (or (->> policies (filter #(= (str (:id %)) policy-name-or-id)) first :id)
        (->> policies (filter #(= (:asset-id %) policy-name-or-id)) first :id)
        policy-name-or-id)))

(defn delete-api-policy
  "Delete a policy from an API instance

   Usage: yaac delete policy <org> <env> <api> <policy-id-or-name>"
  [{:keys [args] :as opts}]
  (let [[policy api env org] (reverse args)
        org (or org *org*)
        env (or env *env*)]
    (if-not (and org env api policy)
      (throw (e/invalid-arguments "Org, Env, Api and Policy need to be specified" {:args args}))
      (let [org-id (org->id org)
            env-id (env->id org env)
            api-id (api->id org-id env-id api)
            policy-id (policy->id org env api policy)]
        (util/spin (str "Deleting policy " policy "..."))
        (-> @(http/delete (format (gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s/policies/%s")
                                  org-id env-id api-id policy-id)
                         {:headers (default-headers)})
            (parse-response)
            (dissoc :body)
            (assoc :deleted-policy policy))))))


;; Delete Monitoring Alert

(defn- -delete-api-alert
  "Delete an API alert"
  [org env alert]
  (let [org-id (org->id org)
        env-id (env->id org env)
        alert-id (alert->id "api" org env alert)]
    (when-not alert-id
      (throw (e/no-item "API alert not found" {:alert alert})))
    (util/spin (str "Deleting API alert " alert "..."))
    (-> @(http/delete (format (gen-url "/monitoring/api/alerts/api/v2/organizations/%s/environments/%s/alerts/%s")
                              org-id env-id alert-id)
                     {:headers (default-headers)})
        (parse-response)
        (dissoc :body)
        (assoc :deleted-alert alert :alert-type "api"))))

(defn- -delete-app-alert
  "Delete an application alert"
  [org alert]
  (let [org-id (org->id org)
        alert-id (alert->id "app" org nil alert)]
    (when-not alert-id
      (throw (e/no-item "Application alert not found" {:alert alert})))
    (util/spin (str "Deleting application alert " alert "..."))
    (-> @(http/delete (format (gen-url "/monitoring/api/v2/organizations/%s/alerts/%s") org-id alert-id)
                     {:headers (default-headers)})
        (parse-response)
        (dissoc :body)
        (assoc :deleted-alert alert :alert-type "app"))))

(defn delete-alert
  "Delete alert - unified handler for api/app/server types

   Usage:
     yaac delete alert <org> <env> <alert-id-or-name> --type api
     yaac delete alert <org> <alert-id-or-name> --type app"
  [{:keys [args type] :as opts}]
  (let [alert-type (or type "api")]
    (case alert-type
      "api"
      (let [[alert env org] (reverse args)
            org (or org *org*)
            env (or env *env*)]
        (if-not (and org env alert)
          (throw (e/invalid-arguments "Org, Env and Alert need to be specified for API alerts" {:args args}))
          (-delete-api-alert org env alert)))

      "app"
      (let [[alert org] (reverse args)
            org (or org *org*)]
        (if-not (and org alert)
          (throw (e/invalid-arguments "Org and Alert need to be specified for application alerts" {:args args}))
          (-delete-app-alert org alert)))

      "server"
      (throw (e/invalid-arguments "Server alerts not yet implemented" {}))

      (throw (e/invalid-arguments "Unknown alert type. Use --type api|app|server" {:type type})))))


;; Delete Managed Flex Gateway

(defn delete-managed-gateway
  "Delete a Managed Flex Gateway

   Usage: yaac delete gateway <org> <env> <gateway-name-or-id> -M"
  [{:keys [args managed] :as opts}]
  (let [[gw env org] (reverse args)
        org (or org *org*)
        env (or env *env*)]
    (cond
      (not managed)
      (throw (e/invalid-arguments "Use -M/--managed flag for Managed Flex Gateway" {}))

      (not (and org env gw))
      (throw (e/invalid-arguments "Org, Env and Gateway need to be specified" {:args args}))

      :else
      (let [org-id (org->id org)
            env-id (env->id org env)
            ;; Get gateway ID from managed gateways only
            gw-id (or (->> (-get-managed-gateways org env)
                           (filter #(or (= (:name %) gw) (= (:id %) gw)))
                           first
                           :id)
                      (throw (e/no-item "Managed gateway not found" {:name gw})))]
        (util/spin (str "Deleting Managed Flex Gateway " gw "..."))
        (-> @(http/delete (format (gen-url "/gatewaymanager/api/v1/organizations/%s/environments/%s/gateways/%s")
                                  org-id env-id gw-id)
                         {:headers (default-headers)})
            (parse-response)
            (dissoc :body)
            (assoc :deleted-gateway gw))))))


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

(defn delete-runtime-fabric [{:keys [args all dry-run force] :as opts}]
  (let [[rtf-name org] (reverse args)
        org (or org *org*)]
    (when-not org
      (throw (e/invalid-arguments "Org is required" {:args args})))
    (if all
      ;; -A: 全RTF削除
      (let [rtfs (-get-runtime-fabrics org)]
        (cond
          (empty? rtfs)
          (throw (e/no-item "No Runtime Fabric clusters found" {:org org}))

          dry-run
          (do (println (format "Would delete %d Runtime Fabric cluster(s):" (count rtfs)))
              (doseq [r rtfs]
                (println (format "  - %s (id=%s)" (:name r) (:id r))))
              [])

          (or force (util/confirm-prompt
                     (format "Delete %d Runtime Fabric cluster(s)?" (count rtfs)) rtfs))
          (->> rtfs
               (mapv (fn [{:keys [id name]}]
                       (let [org-id (org->id org)]
                         (try
                           (-> @(http/delete (format (gen-url "/runtimefabric/api/organizations/%s/fabrics/%s") org-id id)
                                            {:headers (default-headers)})
                               (parse-response)
                               (dissoc :body)
                               (assoc :deleted-rtf name))
                           (catch Exception e
                             {:status 500 :deleted-rtf name :error (ex-message e)}))))))

          :else
          (do (println "Aborted.") [])))

      ;; 単一削除
      (if-not rtf-name
        (throw (e/invalid-arguments "RTF name or ID is required" {:args args}))
        (let [org-id (org->id org)
              rtf-id (rtf->id org rtf-name)]
          (when-not rtf-id
            (throw (e/no-item "Runtime Fabric not found" {:org org :rtf rtf-name})))
          [(-> @(http/delete (format (gen-url "/runtimefabric/api/organizations/%s/fabrics/%s") org-id rtf-id)
                            {:headers (default-headers)})
               (parse-response)
               (dissoc :body)
               (assoc :deleted-rtf rtf-name))])))))

(defn delete-private-space [{:keys [args all dry-run force] :as opts}]
  (let [[ps-name org] (reverse args)
        org (or org *org*)]
    (when-not org
      (throw (e/invalid-arguments "Org is required" {:args args})))
    (if all
      ;; -A: 全Private Space削除
      (let [pss (-get-cloudhub20-privatespaces org)]
        (cond
          (empty? pss)
          (throw (e/no-item "No Private Spaces found" {:org org}))

          dry-run
          (do (println (format "Would delete %d Private Space(s):" (count pss)))
              (doseq [p pss]
                (println (format "  - %s (id=%s)" (:name p) (:id p))))
              [])

          (or force (util/confirm-prompt
                     (format "Delete %d Private Space(s)?" (count pss)) pss))
          (->> pss
               (mapv (fn [{:keys [id name]}]
                       (let [org-id (org->id org)]
                         (try
                           (-> @(http/delete (format (gen-url "/runtimefabric/api/organizations/%s/privatespaces/%s") org-id id)
                                            {:headers (default-headers)})
                               (parse-response)
                               (dissoc :body)
                               (assoc :deleted-ps name))
                           (catch Exception e
                             {:status 500 :deleted-ps name :error (ex-message e)}))))))

          :else
          (do (println "Aborted.") [])))

      ;; 単一削除
      (if-not ps-name
        (throw (e/invalid-arguments "Private Space name or ID is required" {:args args}))
        (let [org-id (org->id org)
              ps-id (ps->id org ps-name)]
          (when-not ps-id
            (throw (e/no-item "Private Space not found" {:org org :ps ps-name})))
          [(-> @(http/delete (format (gen-url "/runtimefabric/api/organizations/%s/privatespaces/%s") org-id ps-id)
                            {:headers (default-headers)})
               (parse-response)
               (dissoc :body)
               (assoc :deleted-ps ps-name))])))))

(def route
  (concat
   ;; delete command
   (for [op ["remove" "rm" "del" "delete"]]
     [op {:options options
          :usage delete-usage}
      ["" {:help true}]
      ["|-h" {:help true}]
      ["|org" {:help true}]
      ["|org|{*args}" {:fields [:status]
                       :formatter format-org-cleanup
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
      ["|policy"]
      ["|policy|{*args}" {:fields [:status :deleted-policy]
                          :handler delete-api-policy}]
      ["|alert"]
      ["|alert|{*args}" {:fields [:status :deleted-alert :alert-type]
                          :handler delete-alert}]
      ["|gateway"]
      ["|gw"]
      ["|gateway|{*args}" {:fields [:status :deleted-gateway]
                           :handler delete-managed-gateway}]
      ["|gw|{*args}" {:fields [:status :deleted-gateway]
                      :handler delete-managed-gateway}]
      ["|asset"]
      ["|asset|{*args}" {:fields [:status [:extra :asset] [:extra :version] [:extra :message]]
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
                                    :handler delete-client-provider}]
      ["|rtf"]
      ["|rtf|{*args}" {:fields [:status :deleted-rtf :error]
                       :handler delete-runtime-fabric}]
      ["|runtime-fabric"]
      ["|runtime-fabric|{*args}" {:fields [:status :deleted-rtf :error]
                                   :handler delete-runtime-fabric}]
      ["|ps"]
      ["|ps|{*args}" {:fields [:status :deleted-ps :error]
                      :handler delete-private-space}]
      ["|private-space"]
      ["|private-space|{*args}" {:fields [:status :deleted-ps :error]
                                  :handler delete-private-space}]])
   ;; clear command
   [["clear" {:options clear-options
              :usage clear-usage}
     ["" {:help true}]
     ["|-h" {:help true}]
     ["|org" {:help true}]
     ["|org|{*args}" {:fields [:status]
                      :formatter format-org-cleanup
                      :handler clear-organization}]]]))
