(ns yaac.core.metrics
  "Metrics API handlers (observability/AMQL) extracted from yaac.core."
  (:require [clojure.string :as str]
            [zeph.client :as http]
            [yaac.core :as yc]
            [yaac.error :as e]
            [yaac.util :refer [edn->json]]))

;; Predefined metric types for common use cases
(def predefined-metrics
  {"app-inbound" {:metric "mulesoft.app.inbound"
                  :default-aggregation "count"
                  :measurement "requests"
                  :default-group-by ["deployment.id"]}
   "app-inbound-response-time" {:metric "mulesoft.app.inbound"
                                 :default-aggregation "avg"
                                 :measurement "response_time"}
   "app-outbound" {:metric "mulesoft.app.outbound"
                   :default-aggregation "count"
                   :measurement "requests"
                   :default-group-by ["deployment.id"]}
   "api-path" {:metric "mulesoft.api.path"
               :default-aggregation "count"
               :measurement "requests"
               :default-group-by ["api.instance.id"]}
   "api-summary" {:metric "mulesoft.api.summary"
                  :default-aggregation "count"
                  :measurement "requests"
                  :default-group-by ["api.instance.id"]}})

;; Parse duration string (1h, 30m, 1d) to milliseconds
(defn parse-duration [s]
  (when s
    (let [pattern #"(\d+)([smhd])"
          matches (re-find pattern (str s))]
      (when matches
        (let [[_ num unit] matches
              n (parse-long num)]
          (case unit
            "s" (* n 1000)
            "m" (* n 60 1000)
            "h" (* n 60 60 1000)
            "d" (* n 24 60 60 1000)
            nil))))))

;; Parse timestamp (Unix timestamp or ISO8601)
(defn parse-timestamp [s]
  (cond
    (number? s) s
    (string? s) (try
                  (parse-long s)
                  (catch Exception _
                    ;; If not a number, try ISO8601 parsing
                    (-> (java.time.Instant/parse s)
                        (.toEpochMilli))))
    :else nil))

;; Convert various time specifications to [start-ms end-ms]
(defn resolve-time-range [{:keys [start end from duration]}]
  (cond
    ;; Absolute time range
    (and start end)
    [(parse-timestamp start) (parse-timestamp end)]

    ;; Relative with duration
    (and from duration)
    (let [end-ms (System/currentTimeMillis)
          start-ms (- end-ms (parse-duration from))]
      [start-ms (+ start-ms (parse-duration duration))])

    ;; Relative from now
    from
    (let [end-ms (System/currentTimeMillis)
          start-ms (- end-ms (parse-duration from))]
      [start-ms end-ms])

    ;; Default: last 1 hour
    :else
    (let [end-ms (System/currentTimeMillis)
          start-ms (- end-ms (* 60 60 1000))]
      [start-ms end-ms])))

;; Build AMQL query from parameters
(defn build-amql-query [metric-name {:keys [aggregation group-by app-id api-id start end field measurement]}]
  (let [agg (str/upper-case (or aggregation "COUNT"))
        ;; Default measurement name is "requests"
        measure (or measurement field "requests")
        select-parts (cond-> [(format "%s(%s)" agg measure)]
                       group-by (concat (map #(format "\"%s\"" %) group-by)))
        select-clause (str/join ", " select-parts)
        group-clause (when group-by
                       (format " GROUP BY %s" (str/join ", " (map #(format "\"%s\"" %) group-by))))
        where-clauses (cond-> [(format "timestamp BETWEEN %d AND %d" start end)]
                        app-id (conj (format "\"app.id\" = '%s'" app-id))
                        api-id (conj (format "\"api.id\" = '%s'" api-id)))
        where-clause (str/join " AND " where-clauses)]
    (format "SELECT %s FROM \"%s\" WHERE %s%s"
            select-clause
            metric-name
            where-clause
            (or group-clause ""))))

;; GET /metric_types - List available metric types
(defn -list-metric-types [org env]
  (let [org-id (yc/org->id org)
        env-id (yc/env->id org env)]
    (->> @(http/get (yc/gen-url "/observability/api/v1/metric_types")
                   {:headers (yc/default-headers)
                    :query-params {:organizationId org-id
                                   :environmentId env-id}})
         (yc/parse-response)
         :body)))

;; GET /metric_types/{name}:describe - Get metric structure
(defn -describe-metric [org env metric-name]
  (let [org-id (yc/org->id org)
        env-id (yc/env->id org env)]
    (->> @(http/get (yc/gen-url (format "/observability/api/v1/metric_types/%s:describe"
                                    metric-name))
                   {:headers (yc/default-headers)
                    :query-params {:organizationId org-id
                                   :environmentId env-id}})
         (yc/parse-response)
         :body)))

;; POST /metrics:search - Query metrics with AMQL
(defn -search-metrics [org env amql-query {:keys [limit offset]}]
  (let [org-id (yc/org->id org)
        env-id (yc/env->id org env)
        payload {:query amql-query
                 :organizationId org-id
                 :environmentId env-id}]
    (->> @(http/post (yc/gen-url "/observability/api/v1/metrics:search")
                    {:headers (yc/default-headers)
                     :query-params {:offset (or offset 0)
                                    :limit (or limit 100)}
                     :body (edn->json payload)})
         (yc/parse-response :raw)
         :body)))

;; Public handler for metrics command
(defn get-metrics [{:keys [args describe type query start end from duration
                           aggregation app-id api-id group-by limit offset]
                    [org env] :args
                    :as opts}]
  (let [org (or org yc/*org*)
        env (or env yc/*env*)]
    (when-not (and org env)
      (throw (e/invalid-arguments "Organization and Environment required" {:args args})))

    (cond
      ;; List metric types
      (and (not describe) (not type) (not query))
      (-list-metric-types org env)

      ;; Describe metric
      describe
      (-describe-metric org env describe)

      ;; Query with predefined type
      type
      (let [[start-ms end-ms] (resolve-time-range opts)
            metric-def (get predefined-metrics type)
            _ (when-not metric-def
                (throw (e/invalid-arguments (str "Unknown metric type: " type)
                                           {:available-types (keys predefined-metrics)})))
            amql (build-amql-query (:metric metric-def)
                                   (merge {:start start-ms
                                           :end end-ms
                                           :aggregation (or aggregation
                                                           (:default-aggregation metric-def))
                                           :group-by (or group-by
                                                        (:default-group-by metric-def))
                                           :app-id app-id
                                           :api-id api-id
                                           :measurement (:measurement metric-def)
                                           :field (:field metric-def)}))]
        (-> (-search-metrics org env amql opts)
            :data
            (yc/add-extra-fields :org org
                                 :env env
                                 :metric-type type)))

      ;; Query with raw AMQL
      query
      (let [[start-ms end-ms] (resolve-time-range opts)]
        (-> (-search-metrics org env query opts)
            :data
            (yc/add-extra-fields :org org
                                 :env env))))))
