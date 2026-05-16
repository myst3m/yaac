(ns yaac.core.identity
  "Identity Providers and Client Providers (OIDC/OAuth) extracted from yaac.core."
  (:require [taoensso.timbre :as log]
            [zeph.client :as http]
            [yaac.core :as yc]))

(defn -get-identity-provider-users [org]
  (let [org-id (yc/org->id org)]
    (log/debug "org:" org-id)
    (-> @(http/get (format (yc/gen-url "/accounts/api/organizations/%s/provider/users") org-id)
                  {:headers (yc/default-headers)})
        (yc/parse-response)
        :body
        :data)))

(defn -get-identity-providers* []
  (let [{:keys [id name]} (first (filter :is-root (yc/-get-organizations)))]
    (-> @(http/get (format (yc/gen-url "/accounts/api/organizations/%s/identityProviders") id)
                  {:headers (yc/default-headers)})
        (yc/parse-response)
        :body
        :data
        (yc/add-extra-fields :org (yc/org->name id)
                             :id :provider-id
                             :type (comp :name :type)))))

(def -get-identity-providers (memoize -get-identity-providers*))

;;; Client Providers (for OAuth/OIDC client management)

(declare -get-client-providers)

(defn -get-client-providers*
  "Get client providers (OpenID Connect client management providers)"
  ([]
   (let [{:keys [id]} (first (filter :is-root (yc/-get-organizations)))]
     (-get-client-providers id)))
  ([org]
   (let [org-id (yc/org->id org)]
     (-> @(http/get (format (yc/gen-url "/accounts/api/organizations/%s/clientProviders") org-id)
                   {:headers (yc/default-headers)})
         (yc/parse-response)
         :body
         :data
         (yc/add-extra-fields :org (yc/org->name org-id)
                              :id :provider-id
                              :type (comp :name :type))))))

(def -get-client-providers (memoize -get-client-providers*))

(defn get-client-providers [{[org] :args}]
  (-get-client-providers (or org (:id (yc/-get-root-organization)))))

(defn -get-client-provider*
  "Get a specific client provider by ID or name with full details"
  [id-or-name]
  (let [{:keys [id]} (yc/-get-root-organization)
        providers (-get-client-providers id)
        provider (first (filter #(or (= id-or-name (:provider-id %))
                                     (= id-or-name (:name %)))
                                providers))]
    (when provider
      (-> @(http/get (format (yc/gen-url "/accounts/api/organizations/%s/clientProviders/%s")
                            id (:provider-id provider))
                    {:headers (yc/default-headers)})
          (yc/parse-response)
          :body))))

(def -get-client-provider (memoize -get-client-provider*))

(defn client-provider->id [id-or-name]
  (->> (-get-client-provider id-or-name)
       :provider-id))

(defn client-provider->name [id-or-name]
  (->> (-get-client-provider id-or-name)
       :name))

(defn get-identity-providers [{:keys [args]}]
  (-get-identity-providers))

(defn -get-identity-provider* [id-or-name]
  (->> (-get-identity-providers)
       (filter #(or (= id-or-name (:provider-id %))
                    (= id-or-name (:name %))))
       (first)))

(def -get-identity-provider (memoize -get-identity-provider*))

(defn provider->id [id-or-name]
  (->> (-get-identity-provider id-or-name)
       :provider-id))

(defn provider->name [id-or-name]
  (->> (-get-identity-provider id-or-name)
       :name))
