(ns yaac.core.policy
  "API Policy / Upstream management extracted from yaac.core.

  Covers /apimanager/api/v1/.../policies and /upstreams endpoints, plus
  the helpers used by yaac.update / yaac.describe / yaac.delete and the
  CLI routing handler `get-api-policies`."
  (:require [zeph.client :as http]
            [yaac.core :as yc]
            [yaac.util :refer [edn->json]]))

(defn -get-api-policies
  ([org env api] (-get-api-policies org env api false))
  ([org env api include-disabled?]
   (let [org-id (yc/org->id (or org yc/*org*))
         env-id (yc/env->id org-id (or env yc/*env*))
         api-id (yc/api->id org-id env-id api)
         url (cond-> (format (yc/gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s/policies")
                             org-id env-id api-id)
               include-disabled? (str "?includeDisabled=true"))]
     (-> @(http/get url {:headers (yc/default-headers)})
         (yc/parse-response)
         :body
         :policies
         (yc/add-extra-fields :id :policy-id
                              :asset-id (comp :asset-id :implementation-asset)
                              :version (comp :asset-version :template)
                              :type "regular"
                              :order :order
                              :disabled :disabled
                              :config :configuration-data)))))

(defn -get-automated-api-policies [org env]
  (let [org-id (yc/org->id (or org yc/*org*))
        env-id (yc/env->id org-id (or env yc/*env*))]
    (-> @(http/get (format (yc/gen-url "/apimanager/api/v1/organizations/%s/automated-policies")
                          org-id env-id)
                  {:query-params {:environmentId env-id}
                   :headers (yc/default-headers)})
        (yc/parse-response)
        :body
        :automated-policies
        (yc/add-extra-fields :id :id
                             :asset-id :asset-id
                             :version :asset-version
                             :type "automated"
                             :order :order))))

(defn get-api-policies [{:keys [args types]
                         [org env api] :args}]
  (let [[api env org] (reverse args)]
    (if api
      ;; Existing: get policies applied to an API instance (include disabled)
      (->> (yc/on-threads yc/*no-multi-thread*
             (-get-api-policies org env api true)
             (-get-automated-api-policies org env))
           (filter #((set (or (seq types) ["automated" "regular"])) (-> % :extra :type))))
      ;; Exchange search: args 0-1 (search term optional)
      (let [term (first args)
            assets (yc/get-assets (cond-> {:types ["policy"] :args [yc/mule-business-group-id] :all true}
                                    term (assoc :search-term [term])))]
        (map #(assoc % :extra {:asset-id (:asset-id %)
                                :version (:version %)
                                :group-id (yc/short-uuid (:group-id %))}) assets)))))

;; Upstream API functions
(defn -get-api-upstreams
  "Get upstreams for an API instance"
  [org env api]
  (let [org-id (yc/org->id (or org yc/*org*))
        env-id (yc/env->id org-id (or env yc/*env*))
        api-id (yc/api->id org-id env-id api)]
    (-> @(http/get (format (yc/gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s/upstreams")
                          org-id env-id api-id)
                  {:headers (yc/default-headers)})
        (yc/parse-response)
        :body)))

(defn -patch-api-upstream
  "Update an API upstream URI"
  [org env api upstream-id uri]
  (let [org-id (yc/org->id (or org yc/*org*))
        env-id (yc/env->id org-id (or env yc/*env*))
        api-id (yc/api->id org-id env-id api)]
    (-> @(http/patch (format (yc/gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s/upstreams/%s")
                            org-id env-id api-id upstream-id)
                    {:headers (yc/default-headers)
                     :body (edn->json {:uri uri})})
        (yc/parse-response)
        :body)))

;; Policy API functions
(defn -get-api-policy
  "Get a specific policy for an API instance by policy-id"
  [org env api policy-id]
  (let [org-id (yc/org->id (or org yc/*org*))
        env-id (yc/env->id org-id (or env yc/*env*))
        api-id (yc/api->id org-id env-id api)]
    (-> @(http/get (format (yc/gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s/policies/%s")
                          org-id env-id api-id policy-id)
                  {:headers (yc/default-headers)})
        (yc/parse-response)
        :body)))

(defn -patch-api-policy
  "Update an API policy configuration"
  [org env api policy-id config-data]
  (let [org-id (yc/org->id (or org yc/*org*))
        env-id (yc/env->id org-id (or env yc/*env*))
        api-id (yc/api->id org-id env-id api)]
    (-> @(http/patch (format (yc/gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s/policies/%s")
                            org-id env-id api-id policy-id)
                    {:headers (yc/default-headers)
                     :body (edn->json {:configurationData config-data})})
        (yc/parse-response)
        :body)))

(defn -patch-api-policy-state
  "Enable or disable an API policy"
  [org env api policy-id disabled?]
  (let [org-id (yc/org->id (or org yc/*org*))
        env-id (yc/env->id org-id (or env yc/*env*))
        api-id (yc/api->id org-id env-id api)]
    (-> @(http/patch (format (yc/gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s/policies/%s")
                            org-id env-id api-id policy-id)
                    {:headers (yc/default-headers)
                     :body (edn->json {:disabled disabled?})})
        (yc/parse-response)
        :body)))

(defn policy-name->id
  "Convert policy asset-id (name) to policy-id. Searches both enabled and disabled policies."
  [org env api policy-name]
  (let [policies (-get-api-policies org env api true)]
    (->> policies
         (filter #(= policy-name (-> % :extra :asset-id)))
         first
         :extra
         :id)))
