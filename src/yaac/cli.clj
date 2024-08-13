(ns yaac.cli
  (:gen-class)
  (:import [java.net URLEncoder])
  (:require [yaac.core :as yc :refer [*org* *env* *deploy-target* *no-cache* *no-multi-thread* *console*]]
            [yaac.util :as util]
            [yaac.nrepl]
            [reitit.core :as r]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [silvur.log :as log]
            [taoensso.nippy :as nippy]
            [silvur.util :refer [json->edn edn->json]]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.tools.cli :refer (parse-opts)]
            [yaac.error :as e]
            [taoensso.timbre :as timbre]
            [silvur.http :as http]
            [clojure.data.json :as json]
            [yaac.deploy :as dep]
            [yaac.delete :as del]
            [yaac.upload :as up]
            [yaac.download :as down]
            [yaac.login :as l]
            [yaac.create :as cr]
            [yaac.describe :as desc]
            [yaac.update :as upd]
            [yaac.config :as cnf]
            [yaac.logs :as logs]
            [yaac.analyze :as ana]
            [clojure.core.async :as async]
            [jansi-clj.core :as jansi]
            [yaac.auth :as auth]
            [yaac.http :as yh]
            [malli.instrument :as mi]
            [malli.core :as m]
            [yaac.specs.nrepl]
            [malli.error :as me]
))

(def version "0.6.0")

;;; main

(defn main-usage [options-summary]
  (->> ["Usage: yaac [options] <action>"
        ""
        (str "version: " version)
        ""
        "Options:"
        ""
        options-summary
        ""
        "Actions:"
        ""
        "  login     ...                                            Login and save token on local storage"
        "  get       org|env|asset|app|api|rtf|ps|rtt|server ...    List resources"
        "  upload    asset ...                                      Upload assets and apps"
        "  deploy    app|proxy ...                                  Deploy applications"
        "  delete    org|app|asset ...                              Delete assets"
        "  create    org|env|api ...                                Create resources"
        "  describe  app|asset ...                                  Describe resources"
        "  update    asset|api ...                                  Update resources configs"
        "  download  proxy ...                                      Download proxies as Jar file"
        "  config    context|credential|clear-cache ...             Configurate contexts"
        "  auth      azure                                          OAuth2 authorization code flow"
        "  http      -                                              Request HTTP to an application"
        ""
        "Please refer to the manual page for more information."
        ""]
    (str/join \newline)))

;;;; New implement

(defn ext-parse-opts [{:keys [args] :as opts} option-schema]
  (let [{:keys [summary options arguments] :as matched-item} (parse-opts args option-schema)]
    (->> arguments
         (map #(str/split % #"="))
         (map (fn [[k v]]
                [(if v (keyword (str k)) (str k))
                 (some-> v (str/split #"[,]"))]))
         (reduce (fn [r [k v]]
                   (if (seq (filter seq v))
                     (assoc r k v)
                     (update r :args (comp vec conj) (name k)))) {})
         (into {})
         (concat options)
         (conj {:summary summary}))))

(def router (r/router [;; Login
                       yaac.login/route
                       ;; Get
                       yaac.core/route
                       ;; Upload
                       yaac.upload/route
                       ;; Download
                       yaac.download/route
                       ;; logs
                       yaac.logs/route
                       ;; Deploy
                       yaac.deploy/route
                       ;; Delete
                       yaac.delete/route
                       ;; Create
                       yaac.create/route
                       ;; Describe
                       yaac.describe/route
                       ;; Auth for Authorization
                       yaac.auth/route
                       ;; HTTP request to app
                       yaac.http/route
                       ;; Update
                       yaac.update/route
                       ;; Config
                       yaac.config/route]))

(def cli-global-options [["-o" "--output-format FORMAT" "Output format (short,json,edn)"
                          :default-desc "short"
                          :parse-fn keyword]
                         ["-H" "--no-header" "No header to pipe to other UNIX tools"
                          :default false]
                         ["-d" "--debug" "See debug log"
                          :default false]
                         ["-P" "--progress" "Display progress"
                          :default false]
                         ["-V" "--http-trace" "Show HTTP request and response flow"
                          :default false]
                         ["-X" "--http-trace-detail" "Show HTTP request and response details"
                          :default false]
                         ["-Z" "--no-cache" "Not using cache"
                          :default false]
                         ["-h" "--help" "This help"]])

(defn print-error [e & {:keys [output-format]}]
  (log/debug e)
  (let [{:keys [extra errors] :as exd} (ex-data e)]
    (let [es (keep identity (or (seq (map :extra errors)) [extra]))] 
      (if (seq es)
        (print (yc/default-format-by [:status :message] (or output-format :short) es {}))
        (print (yc/default-format-by [:status :message] (or output-format :short) [(assoc (or (ex-data e) {}) :message (or (ex-message e) "Unexpected error. Use -d option to investigte."))] {})))
      (flush))))

(defn print-explain [e & {:keys [output-format]}]
  (log/debug e)
  (print (yc/default-format-by
          [:status :message]
          (or output-format :short)
          (-> (m/explain (-> e ex-data :data :input) (-> e ex-data :data :args))
              (me/humanize)
              (->> (zipmap (-> e ex-data :data :args)))
              (->> (filter #(some? (second %))))
              (->> (map #(str (first %) " " (first (second %)))))
              (->> (str/join ","))
              (->> (hash-map :message)))
          {}))
  (flush))


(defn -cli [& a-args]
  (if-let [matched-route (or (r/match-by-path router (str/join "|" (map #(URLEncoder/encode %) a-args))) ;; URL endode for '%'
                             (r/match-by-path router (str/join "|" (map #(URLEncoder/encode %) (take 1 a-args)))))]
    (let [{:keys [data path-params path]} matched-route
          {:keys [handler options usage help no-token]} data
          {:keys [args] :as params} path-params
          cooked-params (cond-> path-params
                          :always  (update :args #(some-> % (str/split #"\|")))
                          :always (ext-parse-opts (concat options cli-global-options))
                          (:help data) (assoc :help (:help data)) ;; merge
                          (:output-format data) (assoc :output-format (:output-format data))) ;;merge
          ]

      (log/debug "route:" (r/match->path matched-route))
      (log/debug "cooked params:" (dissoc cooked-params :summary))
      (log/trace data)

      (if (or (:help cooked-params) (nil? handler))
        (async/go
          (async/>! *console* (usage (:summary cooked-params)))
          (async/>! *console* :done))
        (async/go
          (try
            (when-not no-token (yc/load-session!))
            (loop []
              (let [results (handler cooked-params)]
                (if (= :raw (:output-format data))
                  
                  (do (async/put! *console* \newline)
                      (async/put! *console* (with-out-str (json/pprint results)))
                      (async/put! *console* \newline)
                      (async/>! *console* :done))
                  (let [
                        no-header (:no-header cooked-params)
                        continue? (:continue (meta results))
                        ;; If given fields starts with "+", it is added to default fields
                        {cmd-given-fileds :fields} cooked-params
                        ;; Fields declared in the router

                        ;; Success: [{:extra {:org ....}}]
                        ;; Error:  #error{:data {:extra [{:org T1, :env Sandbox, :name hello, :target t1ps, :status 400, :message Configuring the instance type is not compatible with your pricing model}], :state 9000}}
                        sample (first results)

                        default-fields (or (:fields data) (map #(vector :extra %) (keys (or (:extra sample)
                                                                                            (:extra (ex-data sample))
                                                                                            (:extra (first (:errors (ex-data sample)))) ;; For error object returned by multi-threaded
                                                                                            {}))) [])
                        ;; 
                        specific-formatter (or (:formatter data) [])]


                    ;; (log/debug "default fields:" default-fields)
                    ;; (log/debug "Fields: " fs)
                    (log/debug "Output format: " (or (:output-format cooked-params) "Not specified"))

                    ;; If no Fields is specified, JSON format is used to output
                    (cond->> (if (fn? specific-formatter)
                               (do (specific-formatter (or (some-> (:output-format cooked-params) csk/->kebab-case-keyword)
                                                           (some-> (:output-format data) csk/->kebab-case-keyword))
                                                       results
                                                       cooked-params))
                               (let [preferred-fields (filter #(re-find #"^[^\+]" (name %)) cmd-given-fileds)]
                                 (yc/default-format-by (cond->> cmd-given-fileds
                                                         :always (map #(str/replace (name %) #"^\+" ""))
                                                         :always  (map #(mapv keyword (str/split % #"\.")))
                                                         (not (seq preferred-fields)) (into default-fields)
                                                         :always (distinct))
                                                       (or (some-> (:output-format cooked-params) csk/->kebab-case-keyword)
                                                           (some-> (:output-format data) csk/->kebab-case-keyword)
                                                           (if (seq default-fields) :short :json))
                                                       results
                                                       (assoc cooked-params :no-header (or no-header continue?)))))
                      (or (not continue?) (and (seq results) continue?)) (async/>!! *console*))
                    (if continue?
                      (do
                        (Thread/sleep 3000)
                        (recur))
                      (async/>!! *console* :done))))))
            (catch Exception e (do
                                 (print-error e cooked-params)
                                 (async/>!! *console* :done)))))))
    (async/go
      (async/>! *console* (main-usage (:summary (parse-opts a-args cli-global-options))))
      (async/>! *console* :done))))

(def default-context {})

(defn load-default-context! []
  (try
    (let [ctx (read-string (slurp (io/file (System/getenv "HOME") ".yaac" "config")))]
      (alter-var-root #'default-context (constantly ctx)))
    (catch Exception e (do "Nothing"))))

(defn set-http-tracing-mode! [& [level]]
  (log/set-min-level! :trace)
  (taoensso.timbre/merge-config!
   {:appenders {:println {:enabled? false}
                :http-tracer (http/http-trace-appender {:level (or level 0)})}}))

(defn reset-log-mode! []
  (log/set-min-level! :info)
  (taoensso.timbre/merge-config!
   {:appenders {:println {:enabled? true}
                :http-tracer {:enabled? false}}}))

(defn progress-loop []
  (let [op-ch (async/chan)]
    (async/go-loop [i 0]
      (let [op (async/poll! op-ch)]
        (if (= op :completed)
          (do (print (jansi/erase-line))
              (flush))
          (do (print (jansi/save-cursor)
                     (jansi/erase-line)
                     (jansi/a :bold (str (* i 10) " ms"))
                     (jansi/restore-cursor))
              (flush)
              (Thread/sleep 10)
              (recur (inc i))))))
    op-ch))



(defn cli [& args]
  (let [{:keys [options arguments summary errors] :as command-context} (parse-opts
                                                                         (map name args) ;; To string
                                                                         cli-global-options)]

    (reset-log-mode!)

    (when (:http-trace options)
      (set-http-tracing-mode! 0))

    (when (:http-trace-detail options)
      (set-http-tracing-mode! 1))

    
    (when (:debug options)
      (log/set-min-level! :trace)
      (taoensso.timbre/merge-config!
       {:appenders {:println {:enabled? true  :ns-filter {:allow #{"*"} :deny #{"silvur.http"}}}}}))

    (cond

      ;; nREPL
      (= (first args) "nrepl")
      (try
        (load-default-context!)
        (yc/load-session!)
        (binding [*org* (:organization default-context)
                  *env* (:environment default-context)
                  *no-cache* (:no-cache options)
                  *deploy-target* (:deploy-target default-context)]
          (log/debug "default:" *org* *env*)
          (apply yaac.nrepl/cli (rest args)))
        (catch clojure.lang.ExceptionInfo e (print-explain e))
        (catch Exception e (print-error e)))

      ;; Platform API
      :else
      (try
        (load-default-context!)
        (binding [*org* (:organization default-context)
                  *env* (:environment default-context)
                  *deploy-target* (:deploy-target default-context)
                  *no-cache* (:no-cache options)
                  *no-multi-thread* (:http-trace-detail options)
                  *console* (async/chan)]
          (log/debug "default:" *org* *env*)
          (log/debug "Args: " args)
          ;; result is pushed to *console* channel
          (let [pch (if (:progress options)
                      (progress-loop)
                      ;; dummy
                      (async/chan))]
            (apply -cli args)
            (when (or (:debug options) (:http-trace options)) (println)) ;; Just for eye candy
            (loop [ch *console*]
              (let [result (async/<!! ch)]
                (async/put! pch :completed)
                (log/debug "result:" result)
                (when-not (= result :done)
                  (print result)
                  (flush)
                  (recur ch))))
            )
          )
        (flush)
        (catch Exception e (print-error e))))))

(defn -main [& args]
  (mi/instrument!)
  (apply cli args)
  (shutdown-agents))
