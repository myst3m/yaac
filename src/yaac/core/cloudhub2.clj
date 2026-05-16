(ns yaac.core.cloudhub2
  "CloudHub 2.0 resources extracted from yaac.core: private spaces,
  entitlements, available node ports, VPN/transit gateway connections."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [zeph.client :as http]
            [yaac.core :as yc]
            [yaac.error :as e]))

(defn -get-cloudhub20-privatespaces [org]
  (let [org-id (yc/org->id org)]
    (if org-id
      (->> @(http/get (format (yc/gen-url "/runtimefabric/api/organizations/%s/privatespaces") org-id)
                     {:headers (yc/default-headers)})
           (yc/parse-response)
           :body
           :content)
      (throw (e/org-not-found "Not found organization" :org (or org-id org))))))

(defn get-cloudhub20-privatespaces [{[org] :args :as opts}]
  (-get-cloudhub20-privatespaces (or org yc/*org*)))

(s/def ::id string?)
(s/def ::name string?)
(s/def :entitlement/v-cores-production number?)
(s/def :entitlement/v-cores-sandbox number?)
(s/def :entitlement/static-ips number?)
(s/def :entitlement/network-connections number?)
(s/def :entitlement/vpns number?)
(s/def :entitlement/managed-gateway-large (s/nilable number?))
(s/def :entitlement/managed-gateway-small (s/nilable number?))
(s/def :entitlement/view (s/coll-of (s/keys :req-un [::id
                                                     ::name
                                                     :entitlement/v-cores-production
                                                     :entitlement/v-cores-sandbox
                                                     :entitlement/static-ips
                                                     :entitlement/network-connections
                                                     :entitlement/vpns]
                                    :opt-un [:entitlement/managed-gateway-large
                                             :entitlement/managed-gateway-small])))

(defn get-entitlements [{:keys [args]}]
  {:post [(s/valid? :entitlement/view (map :extra %))]}
  (let [orgs (yc/-get-organizations)
        xf #(try
              (->> @(http/get (format (yc/gen-url "/accounts/api/organizations/%s") (:id %))
                             {:headers (yc/default-headers)})
                   (yc/parse-response)
                   :body)
              (catch Exception e :error))]
    (cond-> orgs
      yc/*no-multi-thread* (->> (map xf))
      (not yc/*no-multi-thread*) (->> (pmap xf))
      true (->> (reduce (fn [r x]
                      (if (not= x :error)
                        (conj r x)
                        r))
                    []))
      true (yc/add-extra-fields :id :id
                                :name :name
                                :v-cores-production (comp :assigned :v-cores-production :entitlements)
                                :v-cores-sandbox (comp :assigned :v-cores-sandbox :entitlements)
                                :static-ips (comp :assigned :static-ips :entitlements)
                                :network-connections (comp :assigned :network-connections :entitlements)
                                :vpns (comp :assigned :vpns :entitlements)
                                :managed-gateway-large (comp :assigned :managed-gateway-large :entitlements)
                                :managed-gateway-small (comp :assigned :managed-gateway-small :entitlements)))))

(defn -get-available-node-ports [org]
  (let [org-id (yc/org->id org)
        ps (-get-cloudhub20-privatespaces org-id)
        xf #(try
              (-> @(http/get (format (yc/gen-url "/runtimefabric/api/organizations/%s/privatespaces/%s/ports") org-id (:id %))
                            {:headers (yc/default-headers)
                             :query-params {:available true :count 10}})
                  (yc/parse-response)
                  :body
                  (assoc :private-space %))
              (catch Exception e (log/debug (ex-message e)) {:private-space %}))]
    (cond-> ps
      (not yc/*no-multi-thread*) (->> (pmap xf))
      yc/*no-multi-thread* (->> (map xf))
      true (yc/add-extra-fields
            :org (yc/org->name org)
            :private-space (comp :name :private-space)
            :ports (comp (partial str/join ",") :ports))
      true (->> (sort-by (comp :private-space :extra))))))

(defn get-available-node-ports [{:keys [args]
                                 [org] :args}]
  (-get-available-node-ports (or org yc/*org*)))

(defn ps->id [org ps]
  (let [all (-get-cloudhub20-privatespaces org)
        xs (->> all (filter #(or (= ps (:id %))
                                  (= ps (:name %)))))
        xs (if (seq xs) xs
               (->> all (filter #(yc/prefix-match? ps (str (:id %))))))]
    (cond
      (= 1 (count xs)) (:id (first xs))
      (< 1 (count xs)) (throw (e/multiple-private-sppace-found "Multiple private spaces found" {:name ps})))))

(defn get-transit-gateways [{:keys [args]  [org ps] :args}]
  (let [org-id (yc/org->id (or org yc/*org*))
        ps-id (ps->id org-id ps)]
    (-> @(http/get (format (yc/gen-url "/runtimefabric/api/organizations/%s/privatespaces/%s/transitgateways") org-id ps-id)
                  {:headers (yc/default-headers)})
        (yc/parse-response)
        :body)))

(defn -get-cloudhub20-vpns [org ps]
  (let [org-id (yc/org->id (or org yc/*org*))
        ps-id (ps->id org-id ps)]
    (-> @(http/get (format (yc/gen-url "/runtimefabric/api/organizations/%s/privatespaces/%s/connections") org-id ps-id)
                   {:headers (yc/default-headers)})
         (yc/parse-response)
         :body
         (->> (mapcat (juxt :vpns)))
         (->> (apply concat))
         (yc/add-extra-fields :id :connection-id
                              :name :connection-name
                              :type "vpn"
                              :status :vpn-connection-status
                              :routes (comp #(str/join "," %) :static-routes)))))

(defn -get-cloudhub20-transit-gateways [org ps]
  (let [org-id (yc/org->id (or org yc/*org*))
        ps-id (ps->id org-id ps)]
    (-> @(http/get (format (yc/gen-url "/runtimefabric/api/organizations/%s/privatespaces/%s/transitgateways") org-id ps-id)
                   {:headers (yc/default-headers)})
        (yc/parse-response)
         :body
         (yc/add-extra-fields :id :id
                              :name :name
                              :type "tgw"
                              :status (comp :attachment :status)
                              :routes (comp #(str/join "," %) :routes :status)))))

(defn -get-cloudhub20-connections [org ps]
  (yc/on-threads yc/*no-multi-thread*
    (-get-cloudhub20-vpns org ps)
    (-get-cloudhub20-transit-gateways org ps)))

(defn get-cloudhub20-connections [{:keys [args] [org ps] :args}]
  (let [[ps org] (reverse args)]
    (-get-cloudhub20-connections org ps)))

(defn conn->id [org ps conn]
  (let [all (-get-cloudhub20-connections org ps)
        xs (->> all (filter #(or (= (:name %) conn) (= (:id %) conn))))
        xs (if (seq xs) xs
               (->> all (filter #(yc/prefix-match? conn (str (:id %))))))]
    (cond
      (= 1 (count xs)) (or (:connection-id (first xs)) (:id (first xs)))
      (< 1 (count xs)) (throw (e/multiple-connections "Multiple connection found")))))
