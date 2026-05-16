(ns yaac.core.gateway
  "Flex Gateway (standalone + managed) and Runtime Fabric resources
  extracted from yaac.core."
  (:require [taoensso.timbre :as log]
            [zeph.client :as http]
            [yaac.core :as yc]
            [yaac.error :as e]))

;; --- Flex Gateway APIs ---
;; Standalone: /standalone/api/v1/.../gateways (self-managed)
;; Managed:    /gatewaymanager/api/v1/.../gateways (CloudHub 2.0 managed)

(defn -get-standalone-gateways
  "Get self-managed Flex Gateways from Standalone API"
  [org env]
  (when (and org env)
    (let [org-id (yc/org->id org)
          env-id (yc/env->id org env)]
      (->> @(http/get (format (yc/gen-url "/standalone/api/v1/organizations/%s/environments/%s/gateways") org-id env-id)
                     {:headers (yc/default-headers)})
           (yc/parse-response)
           :body
           :content
           (mapv #(assoc % :source "standalone" :extra {:org org :env env}))))))

(defn -get-managed-gateway-detail
  "Get detailed info for a single managed Flex Gateway"
  [org-id env-id gw-id]
  (try
    (-> @(http/get (format (yc/gen-url "/gatewaymanager/api/v1/organizations/%s/environments/%s/gateways/%s") org-id env-id gw-id)
                  {:headers (yc/default-headers)})
        (yc/parse-response)
        :body)
    (catch Exception e
      (log/debug "Failed to get gateway detail" gw-id (ex-message e))
      nil)))

(defn -get-managed-gateways
  "Get managed Flex Gateways from Gateway Manager API (CloudHub 2.0)"
  [org env]
  (when (and org env)
    (let [org-id (yc/org->id org)
          env-id (yc/env->id org env)]
      (->> @(http/get (format (yc/gen-url "/gatewaymanager/api/v1/organizations/%s/environments/%s/gateways") org-id env-id)
                     {:headers (yc/default-headers)})
           (yc/parse-response)
           :body
           :content
           (mapv #(assoc % :source "managed" :extra {:org org :env env}))))))

(defn -get-gateways
  "Get all Flex Gateways (standalone + managed)"
  [org env]
  (let [standalone (or (-get-standalone-gateways org env) [])
        managed (or (-get-managed-gateways org env) [])]
    (into standalone managed)))

(defn get-gateways
  "Handler for getting all Flex Gateways"
  [{[org env] :args :keys [all] :as opts}]
  (if all
    (->> (yc/-get-organizations)
         (mapcat (fn [{g :name}]
                   (try
                     (->> (yc/-get-environments g)
                          (mapv (fn [{e :name}] [g e])))
                     (catch Exception e (log/debug (ex-cause e))))))
         (pmap (fn [[g e]]
                 (try
                   (yc/add-extra-fields (-get-gateways g e) :org g :env e)
                   (catch Exception e (log/debug (ex-cause e))))))
         (apply concat))
    (let [org (or org yc/*org*)
          env (or env yc/*env*)]
      (if-not (and org env)
        (throw (e/invalid-arguments "Org and Env need to be specified" {:args opts}))
        (yc/add-extra-fields (-get-gateways org env) :org org :env env)))))

(defn gw->id
  "Get Flex Gateway ID from name. Searches both standalone and managed gateways."
  [org env gw-name]
  (let [gws (-get-gateways org env)]
    (or (->> gws (filter #(= (:name %) gw-name)) first :id)
        (->> gws (filter #(= (:id %) gw-name)) first :id)
        (let [matches (->> gws (filter #(yc/prefix-match? gw-name (str (:id %)))))]
          (when (= 1 (count matches))
            (:id (first matches))))
        (throw (e/no-item (str "Gateway not found: " gw-name)
                          {:gateways (map :name gws)})))))

;; --- Runtime Fabric ---

(defn -get-runtime-fabrics [org]
  (let [org (or org yc/*org*)
        org-id (yc/org->id org)]
    (if org-id
      (->> @(http/get (format (yc/gen-url "/runtimefabric/api/organizations/%s/fabrics/") org-id)
                     {:headers (yc/default-headers)})
           (yc/parse-response)
           :body)
      (throw (e/org-not-found "Not found organization" :org (or org-id org))))))

(defn get-runtime-fabrics [{[org & others] :args :as opts}]
  (when (seq others)
    (throw (e/invalid-arguments "Invalid extra arguments found" {:args opts})))
  (-get-runtime-fabrics org))

(defn rtf->id [org cluster]
  (let [rtfs (-get-runtime-fabrics org)
        [r] (->> rtfs (filter #(or (= cluster (:name %))
                                   (= cluster (:id %)))))]
    (or (:id r)
        (let [matches (->> rtfs (filter #(yc/prefix-match? cluster (str (:id %)))))]
          (when (= 1 (count matches))
            (:id (first matches)))))))
