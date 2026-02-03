(ns yaac.cli
  (:gen-class)
  (:import [java.net URLEncoder])
  (:import [com.dylibso.chicory.wasm.types Value]
           [com.dylibso.chicory.wasm Parser]
           [com.dylibso.chicory.runtime  Module ExportFunction Instance])
  (:require [yaac.core :as yc :refer [*org* *env* *deploy-target* *no-cache* *no-multi-thread* *console* global-base-url]]
            [yaac.util :as util]
            [reitit.core :as r]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]
            [taoensso.nippy :as nippy]
            [yaac.util :refer [json->edn edn->json]]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.tools.cli :refer (parse-opts)]
            [yaac.error :as e]
            [taoensso.timbre :as timbre]
            [zeph.client :as zeph-client]
            [jsonista.core :as json]
            [yaac.util :as yutil]
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
            [yaac.nrepl]
            [yaac.analyze :as ana]
            ;;[yaac.dw :as dw]
            [clojure.core.async :as async]
            [jansi-clj.core :as jansi]
            [yaac.auth :as auth]
            [yaac.http :as yh]
            [yaac.manifest :as manifest]))

(def version "0.8.1")

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
        "  deploy    app|proxy|manifest ...                         Deploy applications"
        "  delete    org|app|asset ...                              Delete assets"
        "  create    org|env|api ...                                Create resources"
        "  describe  app|asset ...                                  Describe resources"
        "  update    asset|api ...                                  Update resources configs"
        "  download  proxy ...                                      Download proxies as Jar file"
        "  config    context|credential|clear-cache ...             Configurate contexts"
        "  auth      azure                                          OAuth2 authorization code flow"
        "  http      -                                              Request HTTP to an application"
;;        "  dw        [script-path] [input-payload]                  Execute DataWeave scripts"
        ""
        "Please refer to the manual page for more information."
        ""]
    (str/join \newline)))

;;;; New implement

(def cli-global-options [["-o" "--output-format FORMAT" "Output format (short,json,edn)"
                          :default-desc "short"
                          :parse-fn keyword]
                         ["-H" "--no-header" "No header to pipe to other UNIX tools"
                          :default false]
                         ["-d" "--debug" "See debug log"
                          :default false]
                         ["-U" "--base-url <url>" "Base URL."
                          :default "https://anypoint.mulesoft.com"]
                         ["-P" "--progress" "Display progress"
                          :default false]
                         ["-V" "--http-trace" "Show HTTP request and response flow"
                          :default false]
                         ["-X" "--http-trace-detail" "Show HTTP request and response details"
                          :default false]
                         ["-Z" "--no-cache" "Not using cache"
                          :default false]
                         ["-1" "--http1" "Force HTTP/1.1 (disable HTTP/2)"
                          :default false]
                         ["-h" "--help" "Brief help"]
                         [nil "--help-all" "Show all options including common options"]])

;; Brief summary of global options (one-liner)
(def cli-global-options-brief
  "  Common: -o FORMAT, -H, -d, -V, -X, -Z, -1  (use --help-all for details)")

(defn ext-parse-opts [{:keys [args] :as opts} option-schema & {:keys [global-opts]}]
  (let [;; Parse with all options for actual option values
        {:keys [summary options arguments] :as matched-item} (parse-opts args option-schema)
        ;; Generate command-specific summary (exclude global options)
        cmd-options (remove (set cli-global-options) option-schema)
        {:keys [summary] :as cmd-result} (when (seq cmd-options)
                                           (parse-opts [] cmd-options))
        cmd-summary (or summary "")
        ;; Create brief or full summary - check both parsed options and global-opts
        help-all? (or (:help-all options) (:help-all global-opts))
        brief-summary (str (when (seq cmd-summary) (str cmd-summary "\n"))
                           cli-global-options-brief)]
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
         (conj {:summary (if help-all?
                           (:summary matched-item)
                           brief-summary)}))))

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
                       ;; Manifest deploy
                       yaac.manifest/route
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
                       yaac.config/route
                       ;; DataWeave
                       ;;yaac.dw/route
                       ]))

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
          {:message (str (ex-message e))}
          {}))
  (flush))


(defn -cli [global-opts & a-args]
  (if-let [matched-route (or (r/match-by-path router (str/join "|" (map #(URLEncoder/encode %) a-args))) ;; URL endode for '%'
                             (r/match-by-path router (str/join "|" (map #(URLEncoder/encode %) (take 1 a-args)))))]
    (let [{:keys [data path-params path]} matched-route
          {:keys [handler options usage help no-token]} data
          {:keys [args] :as params} path-params
          cooked-params (cond-> path-params
                          :always  (-> (update :args #(some-> % (str/split #"\|"))))
                          :always (ext-parse-opts (concat options cli-global-options) :global-opts global-opts)
                          :always (merge global-opts)  ;; merge global options
                          (:help data) (assoc :help (:help data)) ;; merge
                          (:output-format data) (assoc :output-format (:output-format data))) ;;merge
          ]

      (log/debug "route:" (r/match->path matched-route))
      (log/debug "cooked params:" (dissoc cooked-params :summary))
      (log/trace data)

      (if (or (:help cooked-params) (:help-all cooked-params) (nil? handler))
        (async/go
          (async/>! *console* (usage {:summary (:summary cooked-params)
                                      :help-all (:help-all cooked-params)}))
          (async/>! *console* :done))
        (async/go
          (try
            (binding [zeph-client/*trace* (:http-trace global-opts)
                      zeph-client/*trace-detail* (:http-trace-detail global-opts)
                      zeph-client/*force-http1* (:http1 global-opts)
                      zeph-client/*trace-limit* (if (:http-trace-detail global-opts) 0 2000)]
              (when-not no-token (yc/load-session!))
              (loop []
                (let [results (util/with-spin "Processing..." (handler cooked-params))]
                (if (= :raw (:output-format data))
                  
                  (do (async/put! *console* \newline)
                      (async/put! *console* (yutil/json-pprint results))
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
                                 (log/debug "command:" cmd-given-fileds)
                                 (yc/default-format-by (cond->> cmd-given-fileds
                                                         :always (map #(str/replace (name %) #"^\+" ""))
                                                         :always (map #(mapv keyword (str/split % #"\.")))
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
                      (async/>!! *console* :done)))))))
            (catch Exception e (do
                                 (print-error e cooked-params)
                                 (async/>!! *console* :done)))))))
    (async/go
      (let [{:keys [summary]} (parse-opts a-args cli-global-options)
            brief-summary (str cli-global-options-brief)]
        ;; Check help-all from global-opts (passed from cli function)
        (async/>! *console* (main-usage (if (:help-all global-opts) summary brief-summary))))
      (async/>! *console* :done))))

(def default-context {})

(defn load-default-context! []
  (try
    (let [ctx (read-string (slurp (io/file (System/getenv "HOME") ".yaac" "config")))]
      (alter-var-root #'default-context (constantly ctx)))
    (catch Exception e (do "Nothing"))))

(defn reset-log-mode! []
  (log/set-min-level! :info)
  (taoensso.timbre/merge-config!
   {:appenders {:println {:enabled? true}}}))

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
  (let [{options1 :options arguments1 :arguments summary :summary errors :errors} (parse-opts
                                                                                     (map name args) ;; To string
                                                                                     cli-global-options
                                                                                     :in-order true)
        ;; Extract global options from remaining arguments without full parsing
        ;; This preserves command-specific options like -g, -a, -v
        ;; Build lookup tables from cli-global-options dynamically (both short and long flags)
        global-opt-info (into {} (for [[short long-with-meta & rest-opts] cli-global-options
                                       :let [opts-map (apply hash-map (drop 1 rest-opts))
                                             [_ long-flag metavar] (re-matches #"--(\S+)\s*(\S+)?" long-with-meta)
                                             kw (keyword long-flag)
                                             has-value? (some? metavar)
                                             info {:key kw :has-value has-value? :parse-fn (:parse-fn opts-map)}]
                                       entry (if short
                                               [[short info] [(str "--" long-flag) info]]
                                               [[(str "--" long-flag) info]])]
                                   entry))
        [options2 arguments] (loop [args arguments1, opts {}, result []]
                               (if (empty? args)
                                 [opts result]
                                 (let [[arg & rest-args] args
                                       opt-info (global-opt-info arg)]
                                   (cond
                                     (and opt-info (not (:has-value opt-info)))
                                     (recur rest-args (assoc opts (:key opt-info) true) result)

                                     (and opt-info (:has-value opt-info))
                                     (let [v (first rest-args)
                                           parse-fn (or (:parse-fn opt-info) identity)]
                                       (recur (rest rest-args) (assoc opts (:key opt-info) (parse-fn v)) result))

                                     :else
                                     (recur rest-args opts (conj result arg))))))
        ;; Merge options from both passes (use OR for boolean flags)
        options (merge-with (fn [v1 v2] (if (boolean? v1) (or v1 v2) v2)) options1 options2)]

    (reset-log-mode!)
    (yc/set-global-base-url (:base-url options))

    (when (:debug options)
      (log/set-min-level! :trace)
      (taoensso.timbre/merge-config!
       {:appenders {:println {:enabled? true  :ns-filter {:allow #{"*"} :deny #{"zeph.client"}}}}}))


    (cond
      ;; nREPL
      (= (first arguments) "nrepl")
      (try
        (load-default-context!)
        (yc/load-session!)
        (binding [*org* (:organization default-context)
                  *env* (:environment default-context)
                  *no-cache* (:no-cache options)
                  *deploy-target* (:deploy-target default-context)]
          (log/debug "default:" *org* *env*)
          (apply yaac.nrepl/cli (rest arguments)))
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
                  zeph-client/*force-http1* (:http1 options)
                  zeph-client/*trace* (:http-trace options)
                  zeph-client/*trace-detail* (:http-trace-detail options)
                  zeph-client/*trace-limit* (if (:http-trace-detail options) 0 2000)
                  *console* (async/chan)]
          (log/debug "default:" *org* *env*)
          (log/debug "Args: " arguments)
          ;; result is pushed to *console* channel
          (let [pch (if (:progress options)
                      (progress-loop)
                      ;; dummy
                      (async/chan))]
            (apply -cli options arguments)
            (loop [ch *console*]
              (let [result (async/<!! ch)]
                (async/put! pch :completed)
                (log/debug "result:" result)
                (when-not (= result :done)
                  (print result)
                  (flush)
                  (recur ch))))))
        (flush)
        (catch Exception e (print-error e))))))

(defn -main [& args]
  (apply cli args)
  (shutdown-agents))
