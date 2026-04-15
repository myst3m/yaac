(ns yaac.logs
  (:require [yaac.core :refer [org->id env->id *org* *env* gen-url] :as yc]
            [yaac.describe :as desc]
            [yaac.error :as e]
            [zeph.client :as http]
            [silvur.datetime :refer [datetime datetime* *precision* chrono-unit]]
            [yaac.util :refer [json->edn]]
            [taoensso.timbre :as log]
            [clojure.string :as str]))

(def options [["-f" "--follow" "Follow log"
               :default false]
              ["-j" "--jmx" "Use JMX module endpoint (for CH2 apps with mule-jmx-module)"
               :default false]
              ["-i" "--internal" "Use internal URL (default: public URL) for JMX"
               :default false]
              ["-l" "--level LEVEL" "Log level filter for JMX (ERROR, WARN, INFO, DEBUG)"
               :default nil]
              ["-n" "--lines LINES" "Number of log lines for JMX"
               :default "100"]
              ["-p" "--pattern PATTERN" "Search pattern for JMX"
               :default nil]
              ["-s" "--search" "Search log file (uses /logs/search endpoint)"
               :default false]
              ["-t" "--tail" "Tail log file (uses /logs/tail endpoint)"
               :default false]])

(defn usage [opts]
  (let [{:keys [summary help-all]} (if (map? opts) opts {:summary opts :help-all true})]
    (->> (concat
          ["Usage: logs <app> <arguments> [options]"
           ""
           "Get logs for the specified app on CloudHub2.0"
           ""
           "Options:"
           ""
           summary
           ""]
          (when help-all
            ["Arguments"
             "  - [org] [env] <your-app> key1=val1 key2=val2"
             ""
             "Keys (standard mode):"
             "  - length"
             "  - descending"
             ""
             "JMX mode (-j):"
             "  Uses mule-jmx-module HTTP endpoints to retrieve logs"
             "  from CH2 apps. The app must include mule-jmx-module."
             ""])
          ["Example:"
           ""
           "# Standard CH2 logs"
           "  > yaac logs app T1 Production hello-app"
           ""
           "# JMX recent logs (default: 100 lines)"
           "  > yaac logs app T1 Production hello-app -j"
           ""
           "# JMX ERROR logs only"
           "  > yaac logs app T1 Production hello-app -j -l ERROR"
           ""
           "# JMX search for pattern"
           "  > yaac logs app T1 Production hello-app -j -s -p 'NullPointer'"
           ""
           "# JMX tail log file"
           "  > yaac logs app T1 Production hello-app -j -t -n 200"
           ""
           "# JMX using internal URL"
           "  > yaac logs app T1 Production hello-app -j -i"
           ""])
         (str/join \newline))))

;; --- Standard CH2 log retrieval ---

(defn -get-container-application-log [org env app & {:keys [descendants length] :as opts}]
  (let [org-id (org->id org)
        env-id (env->id org-id env)
        [{spec-id :version extra :extra}] (yc/-get-container-application-specs org-id env-id app)
        {:keys [deployment-id]} extra]
    (cond-> @(http/get (format (gen-url "/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments/%s/specs/%s/logs")  org-id env-id deployment-id spec-id)
                  {:headers (yc/default-headers)
                   :query-params opts})
      true (yc/parse-response)
      true :body
      true (yc/add-extra-fields :timestamp (fn [{:keys [timestamp]}]
                                             (binding [*precision* (chrono-unit :millis)]
                                               (str (datetime timestamp))))
                                :message (fn [{:keys [message]}]
                                           (pr-str message)))
      (:descending opts) reverse)))

;; --- JMX module log retrieval ---

(defn -get-app-url
  "Get public or internal URL for a CH2 app via deployment API directly"
  [org env app-name use-internal]
  (let [org-id (org->id org)
        env-id (env->id org-id env)
        apps (try (yc/-get-container-applications org-id env-id)
                  (catch Throwable t
                    (throw (ex-info (str "Failed to list apps: " (.getMessage t))
                                   {:org org :env env}))))
        appi (first (filter #(= app-name (:name %)) apps))]
    (when-not appi
      (throw (ex-info (str "Application '" app-name "' not found in " env)
                      {:app app-name :env env})))
    (let [app-id (:id appi)
          detail (try (-> @(http/get (format (yc/gen-url "/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments/%s")
                                             org-id env-id app-id)
                                     {:headers (yc/default-headers)})
                          (yc/parse-response)
                          :body)
                      (catch Throwable t
                        (throw (ex-info (str "Failed to get app details: " (.getMessage t))
                                        {:app app-name}))))
          {:keys [public-url internal-url]}
          (-> detail :target :deployment-settings :http :inbound)
          non-blank? (fn [s] (and s (not (str/blank? s))))]
      (if use-internal
        (if (non-blank? internal-url)
          internal-url
          (throw (ex-info (str "No internal URL for '" app-name "'")
                          {:app app-name})))
        (if (non-blank? public-url)
          public-url
          (throw (ex-info (str "No public URL for '" app-name "'. Add a public endpoint or use -i for internal URL.")
                          {:app app-name})))))))

(defn -jmx-check-connectivity
  "Quick connectivity check to verify JMX module is reachable. Fail fast if not."
  [base-url]
  (let [resp (try @(http/get (str base-url "/logs")
                             {:query-params {:lines "1"}
                              :timeout 5000})
                  (catch Exception ex
                    (throw (ex-info (str "JMX endpoint unreachable: " base-url "/logs — "
                                        (.getMessage ex))
                                   {:url base-url :cause ex}))))]
    (when (>= (:status resp) 400)
      (throw (ex-info (str "JMX endpoint returned HTTP " (:status resp)
                           ". Ensure the app includes mule-jmx-module and /logs is exposed.")
                      {:url base-url :status (:status resp) :body (:body resp)})))))

(defn -jmx-get-logs
  "Fetch logs from JMX module /logs endpoint"
  [base-url & {:keys [lines level pattern]}]
  (let [query-params (cond-> {}
                       lines   (assoc :lines lines)
                       level   (assoc :level level)
                       pattern (assoc :pattern pattern))
        resp @(http/get (str base-url "/logs")
                        {:query-params query-params})]
    (if (< (:status resp) 400)
      (-> (json->edn :kebab (:body resp))
          :entries)
      (throw (ex-info "JMX logs endpoint returned error"
                      {:status (:status resp) :body (:body resp)})))))

(defn -jmx-tail-log
  "Fetch logs from JMX module /logs/tail endpoint"
  [base-url & {:keys [lines file]}]
  (let [query-params (cond-> {}
                       lines (assoc :lines lines)
                       file  (assoc :file file))
        resp @(http/get (str base-url "/logs/tail")
                        {:query-params query-params})]
    (if (< (:status resp) 400)
      (:body resp)
      (throw (ex-info "JMX tail endpoint returned error"
                      {:status (:status resp) :body (:body resp)})))))

(defn -jmx-search-log
  "Search logs from JMX module /logs/search endpoint"
  [base-url & {:keys [lines pattern file]}]
  (let [query-params (cond-> {}
                       lines   (assoc :lines lines)
                       pattern (assoc :pattern pattern)
                       file    (assoc :file file))
        resp @(http/get (str base-url "/logs/search")
                        {:query-params query-params})]
    (if (< (:status resp) 400)
      (:body resp)
      (throw (ex-info "JMX search endpoint returned error"
                      {:status (:status resp) :body (:body resp)})))))

(defn -format-jmx-entries
  "Format JMX log entries into yaac output format"
  [entries]
  (mapv (fn [entry]
          (-> entry
              (assoc :extra {:timestamp (:timestamp entry)
                             :message (:message entry)})
              (assoc :log-level (:level entry))))
        entries))

;; --- Main handlers ---

(def latest-timestamp (atom 0))

(defn get-container-application-log [{:keys [args length descending follow jmx internal
                                             level lines pattern search tail]
                                      [org env app] :args}]
  (let [[app env org] (reverse args)
        org (or org *org*)
        env (or env *env*)]
    (if jmx
      ;; JMX module mode
      (let [base-url (-get-app-url org env app internal)]
        (log/debug "JMX base URL:" base-url)
        (-jmx-check-connectivity base-url)
        (cond
          ;; Tail mode: raw text output
          tail
          (let [result (-jmx-tail-log base-url :lines lines)]
            (with-meta [{:extra {:message result}}] {:output-format :raw}))

          ;; Search mode: raw text output
          search
          (let [result (-jmx-search-log base-url :lines lines :pattern (or pattern "ERROR"))]
            (with-meta [{:extra {:message result}}] {:output-format :raw}))

          ;; Default: structured log entries
          :else
          (let [entries (-jmx-get-logs base-url :lines lines :level level :pattern pattern)]
            (-format-jmx-entries entries))))

      ;; Standard CH2 log mode
      (let [log-data (-get-container-application-log org env app {:length (first length)
                                                                   :descending (or descending true)
                                                                   :startTime @latest-timestamp})]
        (when (seq log-data)
          (reset! latest-timestamp (inc (:timestamp (last log-data)))))
        (with-meta log-data {:continue follow})))))


(def route
  ["logs" {:options options
           :usage usage}
   ["" {:help true}]
   ["|-h" {:help true}]
   ["|app|{*args}" {:handler get-container-application-log
                    :fields [[:extra :timestamp] :log-level [:extra :message]]}]])
