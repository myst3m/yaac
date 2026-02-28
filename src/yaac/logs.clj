(ns yaac.logs
  (:require [yaac.core :refer [org->id env->id *org* *env* gen-url] :as yc]
            [zeph.client :as http]
            [silvur.datetime :refer [datetime datetime* *precision* chrono-unit]]
            [clojure.string :as str]))

(def options [["-f" "--follow" "Follow log"
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
             "Keys:"
             "  - length"
             "  - descending"
             ""])
          ["Example:"
           ""
           "# Get application logs"
           "  > yaac logs app T1 Production hello-app"
           ""])
         (str/join \newline))))

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


(def latest-timestamp (atom 0))

(defn get-container-application-log [{:keys [args length descending follow]
                                      [org env app] :args}]
  (let [[app env org] (reverse args)
        org (or org *org*)
        env (or env *env*)
        log-data (-get-container-application-log org env app {:length (first length)
                                                              :descending (or descending  true)
                                                              :startTime @latest-timestamp})]
    (when (seq log-data)
      (reset! latest-timestamp (inc (:timestamp (last log-data)))))
    (with-meta log-data {:continue follow})))


(def route 
  ["logs" {:options options
           :usage usage}
   ["" {:help true}]
   ["|-h" {:help true}]
   ["|app|{*args}" {:handler get-container-application-log
                    :fields [[:extra :timestamp] :log-level [:extra :message]]}]])
