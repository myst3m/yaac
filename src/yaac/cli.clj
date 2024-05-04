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
            [yaac.http :as yh]))

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
        "  http                                                     Request HTTP to an application"
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
                       ["login" {:options l/options
                                 :usage l/usage
                                 :no-token true}
                        ["" {:help true}]
                        ["|{*args}" {:fields [:token-type
                                          :access-token
                                          :expires-in]
                                     :handler l/login}]]
                       
                       ;; Get
                       ["get" {:options yc/options
                                :usage yc/usage}
                        ["" {:help true}]   
                        ["|-h" {:help true}]
                        ["|" {:fields [:name :id :parent-name]
                              :handler yc/get-organizations}
                         ;; Get orgs
                         ["org"]
                         ["org|{*args}"]
                         ["organization"]
                         ["organization|{*args}"]]

                        
                        
                        ;; Get envs
                        ["|" {:fields [:name :id :type]
                              :handler yc/get-environments}
                         ["env"]
                         ["env|{*args}"]
                         ["environment"]
                         ["environment|{*args}"]]
                        
                        ;; Get assets
                        ["|" {:fields [:organization-id :group-id [:extra :group-name] :asset-id  :type :version]
                              :handler yc/get-assets}
                         ["asset"]
                         ["asset|{*args}"]]
                        
                        
                        ;; Get proxy
                        ["|" {:fields [:organization-id :environment-id :id :application-name :type :target-type :target-name ]
                              :handler yc/get-api-proxies}
                         ["proxy"]
                         ["proxy|{*args}"]]

                        ;; Get apps
                        ["|" {:fields [[:extra :org]
                                       [:extra :env]
                                       :name
                                       :id
                                       [:extra :status]
                                       [:extra :target]]
                              
                              :handler yc/get-deployed-applications}
                         ["app"]
                         ["app|{*args}"]
                         ["application"]
                         ["application|{*args}"]]


                        ;; Get runtime fabrics
                        ["|" {:fields [:name :id :status :desired-version :vendor :region]
                              :handler yc/get-runtime-fabrics}
                         ["rtf"]
                         ["rtf|{*args}"]
                         ["runtime-fabric"]
                         ["runtime-fabric|{*args}"]]

                        ;; Get runtime targets
                        ["|" {:fields [:name :type :id :region :status]
                              :handler yc/get-runtime-targets}
                         ["rtt"]
                         ["rtt|{*args}"]
                         ["runtime-target"]
                         ["runtime-target|{*args}"]]


                        ;; Get servers
                        ["|" {:fields [:name :id :mule-version :agent-version :status
                                       [:runtime-information :jvm-information :runtime :name]
                                       [:runtime-information :jvm-information :runtime :version]
                                       [:runtime-information :os-information :name]
                                       
]
                              :handler yc/get-servers}
                         ["serv"]
                         ["serv|{*args}"]
                         ["server"]
                         ["server|{*args}"]]
                        
                        ;; Get private spaces
                        ["|" {:fields [:id :name :status :region]
                              :handler yc/get-cloudhub20-privatespaces}
                         ["ps" ]
                         ["ps|{*args}"]
                         ["private-space"]
                         ["private-space|{*args}"]]
                        
                        ;; Get apis
                        ["|" {:fields [:id :asset-id :exchange-asset-name :status :technology 
                                       :product-version :asset-version]
                              :handler yc/get-api-instances}
                         ["api"]
                         ["api|{*args}"]
                         ["api-instance"]
                         ["api-instance|{*args}"]]

                        ;; Get enttitlements
                        ["|" {:handler yc/get-entitlements
                              ;;:fields
                              ;; [:id :name
                              ;;  [:entitlements :v-cores-production :assigned :as "production"]
                              ;;  [:entitlements :v-cores-sandbox :assigned :as "sandbox"]
                              ;;  [:entitlements :static-ips :assigned :as "static-ip"]
                              ;;  [:entitlements :network-connections :assigned :as "connections"]
                              ;;  [:entitlements :vpns :assigned :as "vpn"]]
                              }
                         ["entitlement"]
                         ["entitlement|{*args}"]
                         ["ent"]
                         ["ent|{*args}"]]


                        ;; Get available node ports
                        ["|" {:handler yc/get-available-node-ports}
                         ["node-port"]
                         ["node-port|{*args}"]
                         ["np"]
                         ["np|{*args}"]]
                        
                        ;; Contracts
                        ["|" {:fields [[:application :name] :id :status :api-id [:extra :api-name]]
                              :handler yc/get-api-contracts}
                         ["contract"]
                         ["contract|{*args}"]
                         ["cont"]
                         ["cont|{*args}"]]

                        ["|" {:fields [:client-name :grant-types]
                              :handler yc/get-connected-applications}
                         ["connected-app"]
                         ["connected-app|{*args}"]
                         ["ca"]
                         ["ca|{*args}"]]
                        
                        ["|" {:fields [:username :id :last-name :email :org-type]
                              :handler yc/get-user}
                         ["user"]
                         ["user|{*args}"]]

                        ["|" {:handler yc/get-cloudhub20-connections}
                         ["conn"]
                         ["conn|{*args}"]
                         ["connection"]
                         ["connection|{*args}"]]
                        
                        ["|" {:handler yc/get-api-policies}
                         ["policy"]
                         ["policy|{*args}"]
                         ["pol"]
                         ["pol|{*args}"]]]


                       ;; Upload
                       (for [op ["up" "upload"]]
                         [op {:options up/options
                              :usage up/usage}
                          ["" {:help true}]   
                          ["|-h" {:help true}]
                          ["|asset" {:help true}]
                          ["|asset|{*args}" {:fields [:organization-id :group-id [:extra :group-name] :name :asset-id  :type :version]
                                             :handler up/upload-asset}]])

                       ;; Download

                       ["download" {:options upd/options
                                    :usage upd/usage}
                        ["" {:help true}]   
                        ["|-h" {:help true}]
                        ["|proxy" {:help true}] 
                        ["|proxy|{*args}" {:fields [:status]
                                           :handler down/download-api-proxies}]
                        ["|api" {:help true}]
                        ["|api|{*args}" {:fields [:status :path]
                                         :handler down/download-api-proxies}]]
                       ;; logs
                       ["logs" {:options logs/options
                                :usage logs/usage}
                        ["" {:help true}]
                        ["|-h" {:help true}]
                        ["|app|{*args}" {:handler logs/get-container-application-log
                                         :fields [[:extra :timestamp] :log-level [:extra :message]]}]]

                       
                       ;; Deploy
                       (for [op ["dep" "deploy"]]
                         [op {:options dep/options
                              :usage dep/usage}
                          ["" {:help true}]   
                          ["|-h" {:help true}]
                          ["|app" {:help true}]
                          ["|app|{*args}" {:handler dep/deploy-application}]
                          ["|proxy|{*args}" {:fields [:organization-id
                                                      :environment-id
                                                      :id
                                                      :api-id
                                                      :application-name
                                                      :type
                                                      :target-id]
                                             :handler dep/deploy-api-proxy}]])

                       ;; Delete
                       (for [op ["del" "delete"]]
                         [op {:options del/options
                              :usage del/usage}
                          ["" {:help true}]
                          ["|-h" {:help true}]                          
                          ["|org" {:help true}]
                          ["|org|{*args}" {:fields [:status]
                                           :handler del/delete-organization}]
                          
                          ["|app"]
                          ["|app|{*args}" {:fields [:status [:extra :org] [:extra :env] [:extra :app] ]
                                           :handler del/delete-application}]
                          ["|api"]
                          ["|api|{*args}" {:fields [:status]
                                           :handler del/delete-api-instance}]
                          ["|contract"]
                          ["|cont"]
                          ["|contract|{*args}" {:fields [:status]
                                                :handler del/delete-api-contracts}]
                          ["|cont|{*args}" {:fields [:status]
                                            :handler del/delete-api-contracts}]
                          ["|asset"]
                          ["|asset|{*args}" {:fields [:status [:extra :group] [:extra  :asset] [:extra :version]]
                                             :handler del/delete-asset}]])

                       ;; Create
                       (for [op ["c" "create"]]
                         [op {:options cr/options
                              :usage cr/usage}
                          ["" {:help true}]   
                          ["|-h" {:help true}]
                          ["|" {:help true}
                           ["org"]
                           ["organization"]]
                          ["|" {:handler cr/create-organization}
                           ["org|{*args}" ]
                           ["organization|{*args}" ]]
                          ["|" {:help true}
                           ["env"]
                           ["environement"]]
                          ["|" {:handler cr/create-environment}
                           ["env|{*args}" ]
                           ["environement|{*args}" ]]
                          ["|"
                           ["api|{*args}" {:fields [:id :asset-id :asset-version]
                                           :handler cr/create-api-instance}]]
                          ["|"
                           ["policy|{*args}" {:handler cr/create-api-policy}]]])

                       ;; Describe
                       (for [op ["desc" "describe"]]
                         [op {:options desc/options
                              :usage desc/usage}
                          ["" {:help true}]   
                          ["|-h" {:help true}]

                          ["|org" {:handler desc/describe-organization}]
                          ["|organization" {:handler desc/describe-organization}]
                          ["|org|{*args}" {:handler desc/describe-organization}]
                          ["|organization|{*args}" {:handler desc/describe-organization}]

                          ["|env" {:handler desc/describe-environments}]
                          ["|env|{*args}" {:handler desc/describe-environments}]
                          ["|environment" {:handler desc/describe-environments}]
                          ["|environment|{*args}" {:handler desc/describe-environments}]
                          
                          ["|app" {:help true}]
                          ["|application" {:help true}]
                          ["|app|{*args}" {:fields [:id :name
                                                    [:extra :status]
                                                    [:application :status :as "pod"]
                                                    [:application :v-cores]
                                                    [:target :replicas]
                                                    [:target :deployment-settings :http :inbound :public-url]
                                                    [:target :deployment-settings :http :inbound :internal-url]]
                                           :handler desc/describe-application}]
                          ["|application|{*args}" {:handler desc/describe-application}]
                          ["|asset" {:help true}]
                          ["|asset|{*args}" {:handler desc/describe-asset}]
                          ["|api" {:help true}]
                          ["|api|{*args}" {:handler desc/describe-api-instance}]

                          ])
                       ;; Auth for Authorization
                       ["auth" {:options auth/options
                                :usage auth/usage}
                        ["" {:help true}]
                        ["|azure" {:help true}]
                        ["|azure|{*args}" {:fields [[:extra :tenant]
                                                    :token-type
                                                    :expires-in
                                                    :scope]
                                           :handler auth/auth-azure}]]

                       ;; HTTP request to app
                       ["http" {:options yh/options
                                :usage yh/usage}
                        ["" {:help true}]
                        ["|{*args}" {:handler yh/request
                                     :output-format :raw}]]
                       ;; Update
                       ["update" {:options upd/options
                                  :usage upd/usage}
                        ["" {:help true}]   
                        ["|-h" {:help true}]
                        ["|app" {:help true}]
                        ["|app|{*args}" {:fields [[:extra :id]
                                                  [:extra :name]
                                                  ;;[:application :v-cores]
                                                  ;;[:target :replicas]
                                                  [:extra :status]]
                                         :handler upd/update-app-config}]

                        ["|asset" {:help true}]
                        ["|asset|{*args}" {:fields [:status]
                                           :handler upd/update-asset-config}]
                        ["|api" {:help true}]
                        ["|api|{*args}" {:fields [:id :asset-version :technology [:endpoint :deployment-type] [:endpoint :uri] [:endpoint :proxy-uri]]
                                         :handler upd/update-api-config}]
                        ["|org" {:help true}]
                        ["|org|{*args}" {:handler upd/update-organization-config
                                         :fields [:id :name
                                                 [:entitlements :v-cores-production :assigned]
                                                 [:entitlements :v-cores-sandbox :assigned]
                                                 [:entitlements :static-ips :assigned]
                                                 [:entitlements :network-connections :assigned]
                                                 [:entitlements :vpns :assigned]]}]
                        ["|connection" {:help true}]
                        ["|conn" {:help true}]
                        ["|connection|{*args}" {:handler upd/update-cloudhub20-connection}]
                        ["|conn|{*args}" {:handler upd/update-cloudhub20-connection}]]

                       

                       (for [op ["conf" "config"]]
                         [op {:options cnf/options
                              :usage cnf/usage
                              :no-token true
                              :fields [:status]}
                          ["" {:help true}]
                          ["|-h" {:help true}]
                          ["|ctx" {:fields [:organization :environment :deploy-target]
                                   :handler cnf/current-context}]
                          ["|ctx|{*args}" {:fields [:organization :environment :deploy-target]
                                           :handler cnf/config-context}]
                          ["|context" {:fields [:organization :environment :deploy-target]
                                       :handler cnf/current-context}]

                          ["|context|{*args}" {:fields [:organization :environment :deploy-target]
                                               :handler cnf/config-context}]
                          ["|cred" {:handler cnf/config-credentials}]
                          ["|cred|{*args}" {:handler cnf/config-credentials}]
                          ["|credential" {:handler cnf/config-credentials}]
                          ["|credential|{*args}" {:handler cnf/config-credentials}]
                          ["|clear-cache" {:handler cnf/clear-cache}]
                          ["|cc" {:handler cnf/clear-cache}]
                          ["|clear-cache|{*args}" {:handler cnf/clear-cache}]
                          ["|cc|{*args}" {:handler cnf/clear-cache}]])

                       
                       ["cc" {:options cnf/options
                              :usage cnf/usage}]
                       ["cc|{*args}" {:handler ana/compile}]]))

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

(defn print-error [e {:keys [output-format]}]
  (log/debug e)
  (let [{:keys [extra errors] :as exd} (ex-data e)]
    (if-let [es (or (seq (map :extra errors)) [extra])] 
      (do
        (print (yc/default-format-by [:status :message] (or output-format :short) es {})))
      (clojure.pprint/pprint (assoc (ex-data e) :message (or (ex-message e) "Unexpected error. Use -d option to investigte."))))))

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
                  
                  (do (async/put! *console* (with-out-str (json/pprint results)))
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
      (empty? arguments)
      (println (main-usage summary))

      ;; nREPL
      (= (first args) "nrepl")
      (apply yaac.nrepl/cli (rest args))

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
  (apply cli args)
  (shutdown-agents))
