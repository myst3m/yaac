;; *   Yaac
;; *
;; *   Copyright (c) Tsutomu Miyashita. All rights reserved.
;; *
;; *   The use and distribution terms for this software are covered by the
;; *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; *   which can be found in the file epl-v10.html at the root of this distribution.
;; *   By using this software in any fashion, you are agreeing to be bound by
;; * 	 the terms of this license.
;; *   You must not remove this notice, or any other, from this software.


(ns yaac.deploy
  (:require [silvur
             [util :refer [json->edn edn->json]]
             [http :as http]
             [log :as log]]
            [reitit.core :as r]
            [yaac.core :refer [*org* *env* *deploy-target* *no-multi-thread* parse-response default-headers org->id env->id org->name target->id target->name load-session! env->name] :as yc]
            [camel-snake-kebab.core :as csk]
            [yaac.error :as e]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [yaac.incubate :as ic]
            [clojure.spec.alpha :as s]

            ))

(def deploy-available-options
  {:rtf {:assets [:cpu "request,limit ex. 450m,550m"
                  :mem "request,limit ex. 700Mi,700Mi"
                  :replicas "number ex. 1,2,..."]}
   :ch20 {:assets [:v-cores "number ex. 0.1, 0.2, 0.5, 1, 2, 4 (For classic pricing model)"
                   :runtime-version "string ex. 4.6.0:42-java8"
                   :instance-type "string ex. nano, small, small.mem"
                   :replicas "number ex. 1,2,..."
                   :node-port "number ex. 30500"
                   :target-port "number ex. 8081"]}})

(defn usage [summary-options]
  (->> ["Usage: deploy <arguments> [options]"
        ""
        ""
        "Deploy application to target runtime. "
        ""
        "Before this command, the application needs to be uploaded to Exchange"
        "since the applications on Exchange are transferred to RTF/CloudHub 2.0."
        ""
        
        "Options:"
        ""
        summary-options
        ""
        "Arguments"
        ""
        "  - <app> [org] [env] [your-app|app-prefix] [options] target=<deploy-target-name> key1=val1 key2=val2 ...   Required to target runtime"
        "  - <proxy> [org] [env] <api-instance> [options] target=<deploy-target-name>"
        ""
        "Keys:"
        ""
        "# Runtime Fabric"
        (str/join \newline (->> (partition 2 (-> deploy-available-options :rtf :assets))
                                (map #(format "  - %-25s %-10s" (name (first %)) (second %)))
                                (seq)))
        ""
        "# CloudHub 2.0"
        (str/join \newline (->> (partition 2 (-> deploy-available-options :ch20 :assets))
                                (map #(format "  - %-25s %-10s" (name (first %)) (second %)))
                                (seq)))
        ""
        "  Available shared space IDs:"
        "  - cloudhub-ap-northeast-1"
        "  - cloudhub-ap-southeast-1"
        "  - cloudhub-ap-southeast-2"
        "  - cloudhub-us-east-1"
        "    etc...  others can be found by using yaac get rtt -g <org>"
        ""
        "Properties to applications"
        "  - runtine-version"
        "Properties can be added by using prefix '+' to the property name like +host=cloudhub.com +port=8081 etc..."
        ""
        "Example:"
        ""
        "# Deploy the application as the name 'hello' on T1-Production env. The app is required to be uploaded as the same name."
        "  yaac deploy app T1 Production my-hello-app -g T1 -a hello-app -v 0.0.1 target=cloudhub-ap-northeast-1"
        ""
        "# Deploy the application as the name 'db-app'. the app can be specified with GAV arguments"
        "  yaac deploy app my-hello-app target=my-cluster -g T1 -a hello-app -v 0.0.1 cpu=500m,1000m +dbhost=mysql.svc.cluster.local +dbport=3306"
        ""
        "# Deploy an application using TCP listening on 7888/tcp  bound to Node Port 30500/tcp. "
        "  Use yaac get node-port T1 to check available ports."
        "  yaac deploy app -g T1 -a nrepl node-port=30501 target-port=7888"
        ""
        "  Use TTY listening on 7999/tcp to connect from internal network using .tcp.cloudhub.io."
        "  yaac deploy app nrepl-tty  -g T1 -a nrepl target=t1ps  v-cores=0.1 +transport.type=TTY +transport.port=7999 node-port=30501 target-port=7999 replicas=2 v-cores=0.1"
        ""
        "# Deploy with the default organization and environment"
        "  yaac deploy app target=my-cluster labels=db,demo"
        ""
        "# Deploy apps with labels 'db' and 'demo' to Hybrid (onpremise) runtime named 'leibniz'"
        "  yaac deploy app target=hy:leibniz labels=db,demo"
        ""
        "# Deploy proxy of API instance account-api to RTF cluster0, if no target option, it uses cloudhub-ap-northeast-1"
        "  yaac deploy proxy account-api target=cluster0"
        ""
        ""]
       (str/join \newline)))

(def options [["-g" "--group NAME" "Group name. Normally BG name"]
              ["-a" "--asset NAME" "Asset name"]
              ["-v" "--version VERSION" "Asset version"]
              ["-q" "--search-term STRING" "Query string. Same as search-term=STRING"
               :parse-fn #(str/split % #",")]])

(defn -make-rtf-payload [org env {:keys [group asset version app target runtime-version
                                         cpu mem replicas]
                                  :or {runtime-version ["4.5.0"]}
                                  :as opts}]
  ;; opts: {:prop0 v0, :prop1 v2, :group "T1", :asset "hello-world, :version "1.0.0"}
  (let [target-id (yc/target->id org env target)]
    (log/debug "deploy options: " opts)
    (log/debug "target id:" target-id)
    {:application
     {:ref {:groupId (org->id group)
            :artifactId asset
            :version version
            :packaging "jar"}
      :integrations {:services {:objectStoreV2 {:enabled false}}}
      :assets []
      :desiredState "STARTED"
      :configuration {:mule.agent.logging.service {:scopeLoggingConfigurations []}
                      :mule.agent.application.properties.service
                      {:properties (->> opts
                                        (filter (fn [[k v] ](re-find #"^\+" (name k))))
                                        (map (fn [[k v]] [(keyword (subs (name k) 1)) (str/join "," v)]))
                                        (into {}))
                       :secureProperties {}
                       :applicationName app}}}
     :name app
     :target {:targetId target-id
              :replicas (parse-long (str (first replicas)))
              :provider "MC"
              :deploymentSettings (cond-> {
                                           :updateStrategy "rolling"
                                           
                                           :enforceDeployingReplicasAcrossNodes false
                                           :forwardSslSession false
                                           :generateDefaultPublicUrl false
                                           :persistentObjectStore false
                                           :jvm {}
                                           :lastMileSecurity false
                                           :http {:inbound {:publicUrl nil, :pathRewrite nil}},
                                           :resources {:cpu {:limit (last cpu)  :reserved (first cpu)},
                                                       :memory {:limit (last mem)  :reserved (first mem)}}
                                           :disableAmLogForwarding false,
                                           :clustered false}
                                    (seq runtime-version) (assoc :runtimeVersion (first runtime-version)))}}))

(defn -deploy-rtf-application [{:keys [args labels cpu mem replicas group asset version runtime-version search-term]
                                [cluster org env app-or-prefix] :args
                               :or {cpu ["450m" "550m"] mem ["1200Mi" "1200Mi"] replicas ["1"] runtime-version ["4.5.0"]}
                               :as opts}]
  (let [many-deploys? (or (some? labels) (some? search-term))
        ;; Resource
        [cpu-reserved cpu-limit] cpu
        [mem-reserved mem-limit] mem]

    (letfn [(deploy* [{g :group-id a :asset-id v :version}]
              (if (and cluster org env g a v)
                (let [ ;; Deploy target
                      target-org-id (org->id org)
                      target-env-id (env->id target-org-id env)
                      target-app-name (cond
                                        many-deploys? (str/join "-" (filter (comp not empty?) [app-or-prefix a]))
                                        (seq app-or-prefix) app-or-prefix
                                        :else a)
                      target-cluster cluster
                      ;; Assets
                      [{target-id :id}] (filter #(= target-cluster (:name %)) (yc/get-runtime-fabrics {:args [(org->name target-org-id)]}))
                      deployed-apps (->> (yc/get-deployed-applications {:args [org env]})
                                         (filter #(= (-> % :target :target-id) target-id)))

                      [deployed-app] (filter #(= target-app-name (:name %)) deployed-apps)

                      [http-fn url] (if (seq deployed-app)
                                      [http/patch (format "https://anypoint.mulesoft.com/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments/%s"
                                                          target-org-id
                                                          target-env-id
                                                          (:id deployed-app))]
                                      [http/post (format "https://anypoint.mulesoft.com/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments" target-org-id target-env-id)])]

                  (log/debug "Deploy URL:" url)

                  (-> (http-fn url {:body (edn->json (-make-rtf-payload org env
                                                                        (-> (into {} (filter #(re-find #"^\+" (name (first %)) ) opts)) 
                                                                            (conj {:group g :asset a :version v :cpu cpu :mem mem :replicas replicas
                                                                                   :app target-app-name :target cluster
                                                                                   :runtimeVersion (first runtime-version)}))))
                                    :headers (default-headers)})
                      (parse-response)
                      :body
                      (as-> payload
                          (if-not (:errors payload)
                            (yc/add-extra-fields payload
                                                 :org (org->name org)
                                                 :env (env->name org env)
                                                 :name target-app-name
                                                 :id (get payload :id)
                                                 :status (get-in payload [:application :status])
                                                 :target (yc/target->name org env target-id))
                            (throw (e/invalid-arguments))))))
                (throw (e/invalid-arguments "Invalid arguments" {:args args}))))]
      
      (cond->> (yc/-select-app-assets opts)
        (not *no-multi-thread*) (pmap deploy*)
        *no-multi-thread* (map deploy* )
        :always (apply concat)))))

(defn -deploy-cloudhub20-application [{:keys [args replicas asset group version labels
                                              runtime-version
                                              search-term
                                              v-cores
                                              instance-type
                                              clustered
                                              node-port
                                              target-port ]
                                       ;; v-cores should be removed for New PP
                                       [cluster org env app-or-prefix] :args
                                       :or {;;v-cores ["0.1"]
                                            replicas ["1"]
                                            clustered false
                                            instance-type "small"
                                            }
                                       :as opts}]

  (log/debug "deploy-cloudhub20-application:" args)
  
  (let [many-deploys? (or (some? labels) (some? search-term))]
    (letfn [(deploy* [{g :group-id a :asset-id v :version}]
              (log/debug "g a v:" g a v)
              (try
                (when-not (and cluster org env g a v)
                  (throw (e/invalid-arguments {:org org :env env :group g :artifact a :version v})))
                
                (let [ ;; Deploy target
                      target-org-id (org->id org)
                      target-env-id (env->id target-org-id env)
                      target-app-name (cond
                                        many-deploys? (str/join "-" (filter (comp not empty?) [app-or-prefix a]))
                                        (seq app-or-prefix) app-or-prefix
                                        :else a) ;; If deploying by labels, prefix is added
                      target-id (->> (yc/-get-runtime-cloud-targets target-org-id)
                                     (filter #(or (= cluster (:name %))
                                                  (= cluster (:id %))))
                                     (first)
                                     :id
                                     ((fn [id] (or id (throw (e/runtime-target-not-found "No target found" {:org org :env env :target cluster}))))))
                      ;; Assets
                      [asset-name asset-group-id version] [a (org->id g) v]
                      deployed-app (->> (yc/get-deployed-applications {:args [org env]})
                                        (filter #(= (-> % :target :target-id) target-id))
                                        (filter #(and (= target-app-name (:name %))
                                                      (= target-id (:target-id (:target %)))))
                                        (first))
                      
                      [http-fn url] (if (seq deployed-app)
                                      [http/patch (format "https://anypoint.mulesoft.com/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments/%s"
                                                          target-org-id
                                                          target-env-id
                                                          (:id deployed-app))]
                                      [http/post (format "https://anypoint.mulesoft.com/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments" target-org-id target-env-id)])
                      [node-port] node-port
                      [target-port] target-port
                      [runtime-version] runtime-version]
                  

                  (-> {:headers (default-headers)
                       :body (edn->json (cond-> {:application
                                                 {:ref {:groupId asset-group-id
                                                        :artifactId asset-name
                                                        :version version
                                                        :packaging "jar"}
                                                  :integrations {:services {:objectStoreV2 {:enabled false}}}
                                                  :assets []
                                                  :desiredState "STARTED"
                                                  :configuration {:mule.agent.logging.service {:scopeLoggingConfigurations []}
                                                                  :mule.agent.application.properties.service
                                                                  {:properties (->> opts
                                                                                    (filter #(re-find #"^\+" (name (first %))))
                                                                                    (map (fn [[k v]] [(keyword (subs (name k) 1)) (str/join "," v)]))
                                                                                    (into {}))
                                                                   :secureProperties {}
                                                                   :applicationName target-app-name}}}
                                                 :name target-app-name
                                                 :target {:targetId target-id
                                                          :replicas (parse-long (first replicas))
                                                          :provider "MC"
                                                          :deploymentSettings (cond-> {:updateStrategy "rolling"
                                                                                       :enforceDeployingReplicasAcrossNodes false
                                                                                       :forwardSslSession false
                                                                                       :generateDefaultPublicUrl true
                                                                                       :persistentObjectStore false
                                                                                       :jvm {}
                                                                                       :lastMileSecurity false
                                                                                       ;; :http {:inbound {:publicUrl nil, :pathRewrite nil}},
                                                                                       :http {:inbound {:pathRewrite nil}},
                                                                                       :disableAmLogForwarding false,
                                                                                       :clustered clustered}
                                                                                runtime-version (assoc :runtimeVersion runtime-version)
                                                                                node-port (assoc :tcp {:inbound
                                                                                                       {:ports [{:portNumber (parse-long node-port)
                                                                                                                 :applicationPortNumber (or (parse-long target-port) 8081)}]}}))}}
                                          (and (not v-cores) instance-type) (assoc-in [:target :deploymentSettings :instanceType] (str "mule." (first instance-type)))
                                          v-cores (assoc-in [:application :vCores] (parse-double (first v-cores)) )))}
                      (->> (http-fn url))
                      (parse-response)
                      :body
                      (as-> payload
                          (if-not (:errors payload)
                            (yc/add-extra-fields payload
                                                 :org (org->name org)
                                                 :env (env->name org env)
                                                 :name target-app-name
                                                 :id (get payload :id)
                                                 :status (get-in payload [:application :status])
                                                 :target (yc/target->name org env target-id))
                            (throw (e/invalid-arguments))))))
                (catch Exception e
                  (or (:errors (ex-data e)) [(e/error (ex-data e))])
                  ;; [(e/error {:org (org->name org)
                  ;;            :env (env->name org env)
                  ;;            :name (or (and (seq app-or-prefix) app-or-prefix) a)
                  ;;            :target cluster
                  ;;            :status (-> (ex-data e) :status)
                  ;;            :message (-> (ex-data e) :body :message)})]
                  )))]
      (let [apps (yc/-select-app-assets opts)]
        (log/debug "apps:" apps)
        (try
          (when-not (seq apps)
            (throw (e/no-asset-found)))
          (cond->> apps
            (false? *no-multi-thread*) (pmap deploy*)
            *no-multi-thread* (map deploy*)
            :always (apply concat))
          (catch Exception e [(e/no-asset-found {:org (org->name org)
                                                 :env (env->name org env)
                                                 :name app-or-prefix
                                                 :target cluster})]))))))

(defn -deploy-hybrid-application [{:keys [args group asset version labels search-term]
                                   [target-name org env app-or-prefix] :args
                                   :as opts}]
  (log/debug "deploy-hybrid-application" args)
  (let [many-deploys? (or (some? labels) (some? search-term))
        org-id (org->id org)
        env-id (env->id org env)
        target-id (yc/target->id org env target-name)]

    (letfn [(deploy* [{g :group-id a :asset-id v :version}]
              (if (and target-name org env g a v)
                (try
                 (let [target-app-name (cond
                                         many-deploys? (str/join "-" (filter (comp not empty?) [app-or-prefix a]))
                                         (seq app-or-prefix) app-or-prefix
                                         :else a)]
                   (-> (http/post "https://anypoint.mulesoft.com/hybrid/api/v1/applications"
                                  {:headers (assoc (default-headers)
                                                   "X-ANYPNT-ORG-ID" org-id
                                                   "X-ANYPNT-ENV-ID" env-id)
                                   :body (edn->json :camel {:applicationSource {:artifactId a
                                                                                :groupId g
                                                                                :organizationId org-id,
                                                                                :source "EXCHANGE",
                                                                                :version v},
                                                            :configuration {:mule.agent.application.properties.service
                                                                            {:applicationName target-app-name
                                                                             :properties (->> opts
                                                                                              (filter #(re-find #"^\+" (name (first %))))
                                                                                              (map (fn [[k v]] [(keyword (subs (name k) 1)) (str/join "," v)]))
                                                                                              (into {}))}}
                                                            :artifactName target-app-name,
                                                            :targetId target-id})})
                       (parse-response)
                       :body
                       :data
                       (yc/add-extra-fields :org (org->name org)
                                            :env (env->name org env)
                                            :id :id
                                            :name app-or-prefix
                                            :message :message
                                            :status #(get-in % [:last-reported-status])
                                            :target target-name)))
                 (catch Exception e [(e/error {:org (org->name org)
                                               :env (env->name org env)
                                               :name (or (and (seq app-or-prefix) app-or-prefix) a)
                                               :target target-name
                                               :status (-> (ex-data e) :status)
                                               :message (-> (ex-data e) :body :message)})]))
                [(e/invalid-arguments {:org (org->name org)
                                       :env (env->name org env)
                                       :name app-or-prefix
                                       :target target-name})]))]
      (let [apps (yc/-select-app-assets opts)]
        (if (seq apps)
          (cond->> apps
            (false? *no-multi-thread*) (pmap deploy*)
            *no-multi-thread* (map deploy*)
            :always (apply concat))
          [(e/no-asset-found {:org (org->name org)
                                    :env (env->name org env)
                                    :name app-or-prefix
                                    :target target-name})])))))


;; deploy app my-app target=rtf:k1
;; deploy app T1 Production my-app target=ch20:ap-northeasst -g T1 
(defn deploy-application [{:keys [args target]
                           :as opts}]

  (log/debug "opts:" (dissoc opts :summary))
  (log/debug "target:" (or target *deploy-target*))

  ;; target is array ["ch20:..."]
  
  (if-not (or target *deploy-target*)
    (throw (e/invalid-arguments "No target as target=cloudhub-ap-northeast-1 specified. Specify target option or configure default target by yaac config text."))
    (let [given-target-name (name (or (first target) *deploy-target*))
          [org env app] (case (count args)
                          ;; deploy app target=rtf:k1 labels=demo,db (no prefix)
                          0 [*org* *env* ""]
                          ;; deploy app my-app target=rtf:k1
                          1 [*org* *env* (first args)]
                          ;;  deploy app T1 Production my-app target=ch20:ap-northeasst
                          3 [(first args) (second args) (last args)]
                          (throw (e/invalid-arguments "Org and Env should be specified or use default context with yaac config command" {:args args :target target})))
          
          [[target-name target-type] :as targets] (->> (yc/-get-runtime-targets org env)
                                                       (filter #(= (str/lower-case (:name %))
                                                                   (str/lower-case given-target-name)))
                                                       (map (juxt :name (comp keyword str/lower-case :type))))

          n-args [target-name org env app]]

      (log/debug "targets:" targets)
      (log/debug "n-args:" n-args)
      (log/debug "target-type:" target-type)

      (when-not target-name
        (throw (e/runtime-target-not-found "No specified target name." {:target-name given-target-name})))

      (cond
        (= 0 (count targets)) (throw (e/runtime-target-not-found "No runtime found"))
        (< 1 (count targets)) (throw (e/multiple-runtime-targets "Multiple runtimes found" (into {} targets)))
        :else (cond 
                (#{:runtime-fabric} target-type) (-deploy-rtf-application (assoc opts :args n-args)) ;; remove runtime-target
                (#{:cloudhub2 :ps :private-space :shared-space} target-type) (-deploy-cloudhub20-application (assoc opts :args n-args))
                (#{:hybrid :server} target-type) (-deploy-hybrid-application (assoc opts :args n-args))
                :else (throw (e/runtime-target-not-found "No specified target type." {:target-type target-type :target-name target-name})))))))

;; This is for RTF/CH20
;; https://anypoint.mulesoft.com/exchange/portals/anypoint-platform/f1e97bc6-315a-4490-82a7-23abe036327a.anypoint-platform/proxies-xapi/minor/1.0/pages/Getting%20Started/

(defn deploy-api-proxy [{:keys [args target name]}]
  (if-not (or (seq target) *deploy-target*)
    (throw (e/invalid-arguments "No target as target=rtf:k1 specified. Specify target option or configure default target by yaac config text." {:args args :target target}))
    (let [target (or (first target) *deploy-target*)
          [runtime-target cluster] (str/split target #":")
          [org env api api-id] (case (count args)
                                 ;; deploy proxy my-api target=rtf:k1
                                 1 [*org* *env* (first args) (yc/api->id *org* *env* (first args))]
                                 ;;  deploy proxy T1 Production my-api target=ch20:ap-northeasst
                                 3 [(first args) (second args) (last args) (yc/api->id (first args) (second args) (last args))]
                                 (throw (e/invalid-arguments "Org and Env should be specified or use default context with yaac config command" {:args args :target target})))
          [type target-type target-name target-id] (cond
                                                     (#{:rtf :runtime-fabric} (keyword runtime-target)) ["RF" "runtime-fabric" cluster (yc/rtf->id org cluster)]
                                                     (#{:ch20 :cloudhub2} (keyword runtime-target)) ["CH2" "shared-space" (csk/->HTTP-Header-Case cluster) cluster]
                                                     :else (throw (e/not-implemented "Not implemented" {:args args :target target})))]
      
      (if-not (and org env target-id)
        (throw (e/invalid-arguments "Org, Env and Api need to be specified" {:args args}))
        (let [org-id (org->id org)
              env-id (env->id org env)
              app (or (first name) (str "proxy-" api))
              proxy (->> (yc/-get-api-proxies org env api)
                         (filter #(= (:target-id %) target-id))
                         (first))]

          (cond->> {:headers (default-headers)
                    :body (edn->json :camel {:name app,
                                             :type type,
                                             :target
                                             {:target-id target-id,
                                              :deployment-settings {:runtime-version "4.4.0"}}})}
               
            (not proxy) (http/post (format "https://anypoint.mulesoft.com/proxies/xapi/v1/organizations/%s/environments/%s/apis/%s/deployments"
                                           org-id
                                           env-id
                                           api-id))
            (some? proxy) (http/patch (format "https://anypoint.mulesoft.com/proxies/xapi/v1/organizations/%s/environments/%s/apis/%s/deployments/%s"
                                              org-id
                                              env-id
                                              api-id
                                              (:id proxy)))
            
            :always (parse-response)
            :always :body))))))





;;; Hybrid deploy
;; POST https://anypoint.mulesoft.com/hybrid/api/v1/applications?_=1694769381513
;; {
;;   "artifactName": "my-app",
;;   "targetId": 31577654,
;;   "application": {
;;     "configuration": {
;;       "mule.agent.logging.service": {
;;         "scopeLoggingConfigurations": []
;;       }
;;     }
;;   },
;;   "applicationSource": {
;;     "source": "EXCHANGE",
;;     "groupId": "fe1db8fb-8261-4b5c-a591-06fea582f980",
;;     "artifactId": "hello-large-app",
;;     "version": "1.0.0",
;;     "organizationId": "fe1db8fb-8261-4b5c-a591-06fea582f980"
;;   }
;; }
