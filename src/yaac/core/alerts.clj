(ns yaac.core.alerts
  "Alerts API handlers (api / app / server) extracted from yaac.core.

  --type api: API alerts via /monitoring/api/alerts/api/v2
  --type app: Application alerts via /monitoring/api/v2"
  (:require [clojure.string :as str]
            [zeph.client :as http]
            [yaac.core :as yc]))

;; API Alerts (Monitoring API)
(defn -get-api-alerts
  "Get API alerts for an environment"
  [org env]
  (let [org-id (yc/org->id (or org yc/*org*))
        env-id (yc/env->id org-id (or env yc/*env*))]
    (-> @(http/get (format (yc/gen-url "/monitoring/api/alerts/api/v2/organizations/%s/environments/%s/alerts")
                          org-id env-id)
                  {:headers (yc/default-headers)})
        (yc/parse-response)
        :body)))

;; Application Alerts (Monitoring API v2)
(defn -get-app-alerts
  "Get application alerts via Monitoring API v2"
  [org]
  (let [org-id (yc/org->id (or org yc/*org*))]
    (-> @(http/get (format (yc/gen-url "/monitoring/api/v2/organizations/%s/alerts") org-id)
                   {:headers (yc/default-headers)})
        (yc/parse-response)
        :body)))

(defn- extract-response-codes [alert]
  (let [sub-filters (-> alert :resources first :sub-filters)]
    (when-let [rc-filter (some #(when (= "response_code" (:name %)) %) sub-filters)]
      (str/join "," (:values rc-filter)))))

(defn get-alerts
  "Get alerts - unified handler for api/app/server types

   Usage:
     yaac get alerts <org> <env> --type api    (API alerts require env)
     yaac get alerts <org> --type app          (Application alerts, org only)"
  [{:keys [args type] :as opts}]
  (let [alert-type (or type "api")]
    (case alert-type
      ;; API Alerts (requires org + env)
      "api"
      (let [[env org] (reverse args)
            org (or org yc/*org*)
            env (or env yc/*env*)
            org-id (yc/org->id org)
            env-id (yc/env->id org-id env)
            alerts (-get-api-alerts org env)
            org-name (yc/org->name org)
            env-name (yc/env->name org env)]
        (->> alerts
             (mapv (fn [alert]
                     (assoc alert
                            :alert-type "api"
                            :extra {:org org-name
                                    :env env-name
                                    :api-id (-> alert :resources first :api-id)
                                    :response-codes (extract-response-codes alert)})))))

      ;; Application Alerts (org only, optional env filter)
      "app"
      (let [[env-or-org org] (reverse args)
            ;; If only one arg provided, it's the org
            [org env] (if org
                        [org env-or-org]
                        [(or env-or-org yc/*org*) nil])
            org-id (yc/org->id org)
            env-id (when env (yc/env->id org-id env))
            alerts (-get-app-alerts org)
            org-name (yc/org->name org)
            filtered (if env-id
                       (filter #(= env-id (-> % :resource :environment-id)) alerts)
                       alerts)]
        (->> filtered
             (mapv (fn [alert]
                     (assoc alert
                            :alert-type "app"
                            :extra {:org org-name
                                    :env (-> alert :resource :environment-id)
                                    :app-id (-> alert :resource :app-id)
                                    :resource-type (-> alert :resource :type)})))))

      ;; Server Alerts (placeholder)
      "server"
      (throw (ex-info "Server alerts not yet implemented" {:type type}))

      ;; Unknown type
      (throw (ex-info "Unknown alert type. Use --type api|app|server" {:type type})))))

(defn alert->id
  "Resolve alert name to ID based on alert type"
  [alert-type org env alert-name-or-id]
  (case alert-type
    "api"
    (let [alerts (-get-api-alerts org env)]
      (or
       (some #(when (= alert-name-or-id (:alert-id %)) (:alert-id %)) alerts)
       (some #(when (= alert-name-or-id (:name %)) (:alert-id %)) alerts)))

    "app"
    (let [alerts (-get-app-alerts org)]
      (or
       (some #(when (= alert-name-or-id (:alert-id %)) (:alert-id %)) alerts)
       (some #(when (= alert-name-or-id (:alert-name %)) (:alert-id %)) alerts)))

    nil))
