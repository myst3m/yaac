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
  (:require [yaac.util :as util :refer [json->edn edn->json]]
            [taoensso.timbre :as log]
            [zeph.client :as http]
            [reitit.core :as r]
            [yaac.core :refer [*org* *env* *deploy-target* *no-multi-thread*
                               parse-response default-headers
                               org->id env->id org->name target->id target->name load-session! env->name
                               gen-url] :as yc]
            [camel-snake-kebab.core :as csk]
            [yaac.error :as e]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [yaac.incubate :as ic]
            [clojure.spec.alpha :as s]))

(defn deploy-to-flex-gateway
  "Deploy API instance to Flex Gateway"
  [org-id env-id api-id target-id]
  (-> @(http/post (format (gen-url "/proxies/xapi/v1/organizations/%s/environments/%s/apis/%s/deployments")
                         org-id env-id api-id)
                 {:headers (default-headers)
                  :body (edn->json :camel
                                   {:gateway-version "1.9.5"
                                    :target-id target-id
                                    :target-name "server"
                                    :target-type "server"
                                    :type "HY"
                                    :environment-id env-id})})
      (parse-response)
      :body))

(def deploy-available-options
  {:rtf {:assets [:cpu "request,limit ex. 450m,550m"
                  :mem "request,limit ex. 700Mi,700Mi"
                  :replicas "number ex. 1,2,..."
                  :runtime-version "string ex. 4.10.1:12e (default: 4.10.1:12e)"
                  :java-version "8 or 17 (default: 17). Combined as runtime-version-java17"]}
   :ch2 {:assets [:v-cores "number ex. 0.1, 0.2, 0.5, 1, 2, 4 (For classic pricing model)"
                   :runtime-version "string ex. 4.10.1:12e (default: 4.10.1:12e)"
                   :java-version "8 or 17 (default: 17). Combined as runtime-version-java17"
                   :instance-type "string ex. nano, small, small.mem"
                   :replicas "number ex. 1,2,..."
                   :node-port "number ex. 30500"
                   :target-port "number ex. 8081"]}})

(defn usage [opts]
  (let [{:keys [summary help-all]} (if (map? opts) opts {:summary opts :help-all true})]
    (->> (concat
          ["Usage: deploy <arguments> [options]"
           ""
           "Deploy application to target runtime. "
           ""
           "Options:"
           ""
           summary
           ""]
          (when help-all
            ["Arguments"
             ""
             "  - <app> [org] [env] [your-app|app-prefix] [options] target=<deploy-target-name> key1=val1 key2=val2 ..."
             "  - <proxy> [org] [env] <api-instance> [options] target=<deploy-target-name>"
             "  - <manifest> <manifest.yaml> [--dry-run] [--only app1,app2]"
             ""
             "Keys:"
             ""
             "# Runtime Fabric"
             (str/join \newline (->> (partition 2 (-> deploy-available-options :rtf :assets))
                                     (map #(format "  - %-25s %-10s" (name (first %)) (second %)))
                                     (seq)))
             ""
             "# CloudHub 2.0"
             (str/join \newline (->> (partition 2 (-> deploy-available-options :ch2 :assets))
                                     (map #(format "  - %-25s %-10s" (name (first %)) (second %)))
                                     (seq)))
             ""
             "  Shared space IDs: cloudhub-ap-northeast-1, cloudhub-us-east-1, etc."
             "  Use 'yaac get rtt -g <org>' to find available targets."
             ""])
          ["Example:"
           ""
           "# Deploy app to CloudHub 2.0"
           "  > yaac deploy app T1 Production my-app -g T1 -a hello-app -v 0.0.1 target=cloudhub-ap-northeast-1"
           ""
           "# Deploy to Hybrid (onpremise)"
           "  > yaac deploy app target=hy:leibniz"
           ""])
         (str/join \newline))))

(def options [["-g" "--group NAME" "Group name. Normally BG name"]
              ["-a" "--asset NAME" "Asset name"]
              ["-v" "--version VERSION" "Asset version"]
              ["-q" "--search-term STRING" "Query string. Same as search-term=STRING"
               :parse-fn #(str/split % #",")]])

(defn- build-runtime-version
  "Build runtime version string with java version suffix.
   e.g., '4.10.1:12e' + '17' -> '4.10.1:12e-java17'
   If runtime-version already contains '-java', use as-is."
  [runtime-version java-version]
  (let [rv (first runtime-version)
        jv (first java-version)]
    (cond
      (nil? rv) nil
      (re-find #"-java\d+" rv) rv  ; already has java version
      :else (str rv "-java" jv))))

(defn -make-rtf-payload [org env {:keys [group asset version app target runtime-version
                                         cpu mem replicas java-version]
                                  :or {runtime-version ["4.10.1:12e"] java-version ["17"]}
                                  :as opts}]

  ;; opts: {:prop0 v0, :prop1 v2, :group "T1", :asset "hello-world, :version "1.0.0"}
  (let [target-id (yc/target->id org env target)
        full-runtime-version (build-runtime-version runtime-version java-version)]
    (log/debug "deploy options: " opts)
    (log/debug "target id:" target-id)
    (log/debug "runtime version:" full-runtime-version)
    {:application
     {:ref {:groupId (org->id group)
            :artifactId asset
            :version version
            :packaging "jar"}
      :integrations {:services {:objectStoreV2 {:enabled (boolean (:object-store-v2 opts))}}}
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
              :deploymentSettings (cond-> {:updateStrategy "rolling"
                                           :enforceDeployingReplicasAcrossNodes false
                                           :forwardSslSession false
                                           :generateDefaultPublicUrl false
                                           :persistentObjectStore false
                                           :jvm {}
                                           :lastMileSecurity false
                                           :http {:inbound {:publicUrl nil, :pathRewrite nil}}
                                           :resources {:cpu {:limit (last cpu)  :reserved (first cpu)}
                                                       :memory {:limit (last mem)  :reserved (first mem)}}
                                           :disableAmLogForwarding false
                                           :clustered false}
                                    full-runtime-version (assoc :runtimeVersion full-runtime-version))}}))

(defn -deploy-rtf-application [{:keys [args labels cpu mem replicas group asset version runtime-version java-version search-term]
                                [cluster org env app-or-prefix] :args
                                :or {cpu ["400m" "2000m"] mem ["1000Mi" "1500Mi"] replicas ["1"] runtime-version ["4.10.1:12e"] java-version ["17"]}
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
                                      [http/patch (format (gen-url "/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments/%s")
                                                          target-org-id
                                                          target-env-id
                                                          (:id deployed-app))]
                                      [http/post (format (gen-url "/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments") target-org-id target-env-id)])

                      rtf-payload (-make-rtf-payload org env
                                                     (-> (into {} (filter #(re-find #"^\+" (name (first %)) ) opts))
                                                         (conj {:group g :asset a :version v :cpu cpu :mem mem :replicas replicas
                                                                :app target-app-name :target cluster
                                                                :runtime-version runtime-version
                                                                :java-version java-version})))]

                  (log/debug "Deploy URL:" url)
                  (log/debug "payload:" rtf-payload)
                  
                  (-> @(http-fn url {:body (edn->json rtf-payload)
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
                                                 :deployment-status (get payload :status)
                                                 :target (yc/target->name org env target-id))
                            (throw (e/invalid-arguments))))))
                (throw (e/invalid-arguments "Invalid arguments" {:args args}))))]
      
      (cond->> (yc/-select-app-assets opts)
        (not *no-multi-thread*) (pmap deploy*)
        *no-multi-thread* (map deploy* )
        :always (apply concat)))))

(defn -deploy-cloudhub20-application [{:keys [args replicas asset group version labels
                                              runtime-version
                                              java-version
                                              search-term
                                              v-cores
                                              instance-type
                                              clustered
                                              node-port
                                              target-port
                                              object-store-v2]
                                       ;; v-cores should be removed for New PP
                                       [cluster org env app-or-prefix] :args
                                       :or {replicas ["1"]
                                            clustered false
                                            instance-type "small"
                                            java-version ["17"]}
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
                                      [http/patch (format (gen-url "/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments/%s")
                                                          target-org-id
                                                          target-env-id
                                                          (:id deployed-app))]
                                      [http/post (format (gen-url "/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments") target-org-id target-env-id)])
                      [node-port] node-port
                      [target-port] target-port
                      full-runtime-version (build-runtime-version runtime-version java-version)]

                  (-> @(http-fn url {:headers (default-headers)
                                      :body (edn->json (cond-> {:application
                                                                {:ref {:groupId asset-group-id
                                                                       :artifactId asset-name
                                                                       :version version
                                                                       :packaging "jar"}
                                                                 :integrations {:services {:objectStoreV2 {:enabled (boolean (:object-store-v2 opts))}}}
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
                                                                                                      :http {:inbound {:pathRewrite nil}}
                                                                                                      :disableAmLogForwarding false
                                                                                                      :clustered clustered}
                                                                                               full-runtime-version (assoc :runtimeVersion full-runtime-version)
                                                                                               node-port (assoc :tcp {:inbound
                                                                                                                      {:ports [{:portNumber (parse-long node-port)
                                                                                                                                :applicationPortNumber (or (parse-long target-port) 8081)}]}}))}}
                                                         (and (not v-cores) instance-type) (assoc-in [:target :deploymentSettings :instanceType] (str "mule." (first instance-type)))
                                                         v-cores (assoc-in [:application :vCores] (as-> (parse-double (first v-cores)) x
                                                                                                    (if (= 0.0 (mod x 1)) (long x) x)))))})
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
                                                 :deployment-status (get payload :status)
                                                 :target (yc/target->name org env target-id))
                            (throw (e/invalid-arguments))))))
                (catch Exception e
                  (let [data (ex-data e)
                        status (or (-> data :extra :status) (-> data :status))
                        msg (or (-> data :extra :message) (-> data :body :message) (ex-message e))
                        ;; Add v-cores hint if error is related to pricing model/instance type
                        hint (cond
                               (re-find #"(?i)instance.?type.*pricing|pricing.*instance" (str msg))
                               " (Hint: Your pricing model requires v-cores=<value>, e.g., v-cores=0.1)"

                               (and (= status 400) (re-find #"(?i)vcore|resource|replica" (str msg)))
                               " (Hint: Try specifying v-cores=<value>, e.g., v-cores=0.1)"

                               :else nil)
                        full-msg (str msg hint)]
                    [(e/error full-msg {:org (org->name org)
                                        :env (env->name org env)
                                        :name (or (and (seq app-or-prefix) app-or-prefix) a)
                                        :target cluster
                                        :status status
                                        :message full-msg})]))))]
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
  (when-not target-name
    (throw (e/invalid-arguments "No target specified. Use target=hy:<server-name> to deploy to a Hybrid server."
                                {:hint "Run 'yaac get server <org> <env>' to see available servers."})))
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
                   (-> @(http/post (gen-url "/hybrid/api/v1/applications")
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
                 (catch Exception e
                  (let [msg (or (-> (ex-data e) :extra :message)
                                (-> (ex-data e) :body :message)
                                (ex-message e)
                                "Deployment failed")]
                    [(e/error msg {:org (org->name org)
                                   :env (env->name org env)
                                   :name (or (and (seq app-or-prefix) app-or-prefix) a)
                                   :target target-name
                                   :status (or (-> (ex-data e) :extra :status)
                                               (-> (ex-data e) :status))
                                   :message msg})])))
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
;; deploy app T1 Production my-app target=ch2:ap-northeast-1 -g T1
(defn deploy-application [{:keys [args target]
                           :as opts}]

  (log/debug "opts:" (dissoc opts :summary))
  (log/debug "target:" (or target *deploy-target*))

  ;; target is array ["ch2:..." or "rtf:..." etc.]
  
  (if-not (or target *deploy-target*)
    (throw (e/invalid-arguments "No target specified. Use target=<target-name> (e.g., target=cloudhub-ap-northeast-1 or target=hy:<server-name>)"
                                {:hint "Run 'yaac get target <org> <env>' for CH2/RTF targets, or 'yaac get server <org> <env>' for Hybrid servers."}))
    (let [given-target-name (name (or (first target) *deploy-target*))
          ;; Handle prefix for target types: hy:, rtf:, ch2:
          [actual-target-name target-filter] (cond
                                               (str/starts-with? given-target-name "hy:")
                                               [(subs given-target-name 3) #(= "SERVER" (:type %))]

                                               (str/starts-with? given-target-name "rtf:")
                                               [(subs given-target-name 4) #(= "runtime-fabric" (:type %))]

                                               (str/starts-with? given-target-name "ch2:")
                                               [(subs given-target-name 4) #(contains? #{"cloudhub2" "shared-space" "private-space"} (:type %))]

                                               (str/starts-with? given-target-name "ch20:")
                                               [(subs given-target-name 5) #(contains? #{"cloudhub2" "shared-space" "private-space"} (:type %))]

                                               :else
                                               [given-target-name (constantly true)])
          [org env app] (case (count args)
                          ;; deploy app target=rtf:k1 labels=demo,db (no prefix)
                          0 [*org* *env* ""]
                          ;; deploy app my-app target=rtf:k1
                          1 [*org* *env* (first args)]
                          ;; deploy app T1 my-app target=hy:leibniz
                          2 [(first args) *env* (second args)]
                          ;;  deploy app T1 Production my-app target=ch2:ap-northeast-1
                          3 [(first args) (second args) (last args)]
                          (throw (e/invalid-arguments "Org and Env should be specified or use default context with yaac config command" {:args args :target target})))

          [[target-name target-type] :as targets] (->> (yc/-get-runtime-targets org env)
                                                       (filter target-filter)
                                                       (filter #(= (str/lower-case (:name %))
                                                                   (str/lower-case actual-target-name)))
                                                       (map (juxt :name (comp keyword str/lower-case :type))))

          n-args [target-name org env app]
          resolved-opts (cond-> opts
                          (not (:group opts))                 (assoc :group org)
                          (and (seq app) (not (:asset opts))) (assoc :asset app))]

      (log/debug "targets:" targets)
      (log/debug "n-args:" n-args)
      (log/debug "target-type:" target-type)
      (log/debug "resolved-opts:" resolved-opts)

      (when-not target-name
        (throw (e/runtime-target-not-found "No specified target name." {:target-name given-target-name})))

      (cond
        (= 0 (count targets)) (throw (e/runtime-target-not-found "No runtime found"))
        (< 1 (count targets)) (throw (e/multiple-runtime-targets "Multiple runtimes found" (into {} targets)))
        :else (do
                (util/spin (str "Deploying " (or app "app") " to " target-name "..."))
                (cond
                  (#{:runtime-fabric} target-type) (-deploy-rtf-application (assoc resolved-opts :args n-args))
                  (#{:cloudhub2 :ps :private-space :shared-space} target-type) (-deploy-cloudhub20-application (assoc resolved-opts :args n-args))
                  (#{:hybrid :server} target-type) (-deploy-hybrid-application (assoc resolved-opts :args n-args))
                  :else (throw (e/runtime-target-not-found "No specified target type." {:target-type target-type :target-name target-name}))))))))

;; This is for RTF/CH2
;; https://anypoint.mulesoft.com/exchange/portals/anypoint-platform/f1e97bc6-315a-4490-82a7-23abe036327a.anypoint-platform/proxies-xapi/minor/1.0/pages/Getting%20Started/

(defn deploy-api-proxy [{:keys [args target name]}]
  (if-not (or (seq target) *deploy-target*)
    (throw (e/invalid-arguments "No target as target=rtf:k1 specified. Specify target option or configure default target by yaac config text." {:args args :target target}))
    (let [target (or (first target) *deploy-target*)
          
          [runtime-target cluster] (str/split target #":")
          [org env api api-id] (case (count args)
                                 ;; deploy proxy my-api target=rtf:k1
                                 1 [*org* *env* (first args) (yc/api->id *org* *env* (first args))]
                                 ;;  deploy proxy T1 Production my-api target=ch2:ap-northeast-1
                                 3 [(first args) (second args) (last args) (yc/api->id (first args) (second args) (last args))]
                                 (throw (e/invalid-arguments "Org and Env should be specified or use default context with yaac config command" {:args args :target target})))

          [type target-type target-name target-id] (cond
                                                     (#{:rtf :runtime-fabric} (keyword runtime-target)) ["RF" "runtime-fabric" cluster (yc/rtf->id org cluster)]
                                                     (#{:ch2 :cloudhub2} (keyword runtime-target)) ["CH2" "shared-space" (csk/->HTTP-Header-Case cluster) cluster]
                                                     (#{:hy :hybrid} (keyword runtime-target)) ["HY" "server" (str cluster) (str (yc/hybrid-server->id org env cluster))]
                                                     (#{:fg :flex :flexgateway} (keyword runtime-target)) ["HY" "server" cluster (yc/gw->id org env cluster)]
                                                     :else (throw (e/not-implemented "Not implemented" {:args args :target target})))]
      
      (if-not (and org env target-id)
        (throw (e/invalid-arguments "Org, Env and Api need to be specified" {:args args}))
        (let [org-id (org->id org)
              env-id (env->id org env)
              app (or (first name) (str "proxy-" api))
              proxy (->> (yc/-get-api-proxies org env api)
                         (filter #(= (:target-id %) target-id))
                         (first))]

          (let [opts {:headers (default-headers)
                      :body (edn->json :camel
                                       {:gateway-version "4.4.0"
                                        :target-id target-id
                                        :target-name target-type
                                        :target-type "server"
                                        :type type
                                        :environment-id env-id
                                        :environment-name (env->name org env)})}]
            (-> (if proxy
                  @(http/patch (format (gen-url "/proxies/xapi/v1/organizations/%s/environments/%s/apis/%s/deployments/%s")
                                       org-id env-id api-id (:id proxy))
                               opts)
                  @(http/post (format (gen-url "/proxies/xapi/v1/organizations/%s/environments/%s/apis/%s/deployments")
                                      org-id env-id api-id)
                              opts))
                (parse-response)
                :body)))))))


(def route
  (for [op ["deploy" "dep"]]
    [op {:options options
         :usage usage}
     ["" {:help true}]
     ["|-h" {:help true}]
     ["|app" {:help true}]
     ["|app|{*args}" {:handler deploy-application}]
     ["|proxy|{*args}" {:fields [:organization-id
                                 :environment-id
                                 :id
                                 :api-id
                                 :application-name
                                 :type
                                 :target-id]
                        :handler deploy-api-proxy}]]))



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
