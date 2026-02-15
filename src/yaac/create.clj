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


(ns yaac.create
  (:require [yaac.util :as util :refer [json->edn edn->json]]
            [taoensso.timbre :as log]
            [zeph.client :as http]
            [reitit.core :as r]
            [yaac.core :refer [*org* *env* *deploy-target*
                               parse-response default-headers short-uuid
                               org->id ps->id rtf->id env->id api->id app->id org->name env->name gw->id load-session!
                               gen-url assign-connected-app-scopes -get-root-organization -get-api-upstreams] :as yc]
            [yaac.deploy :as deploy]
            [yaac.error :as e]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [yaac.incubate :as ic]
            [camel-snake-kebab.extras :as cske]
            [camel-snake-kebab.core :as csk]
            [clojure.spec.alpha :as s]
            [jsonista.core :as json]))


(defn usage [opts]
  (let [{:keys [summary help-all]} (if (map? opts) opts {:summary opts :help-all true})]
    (->> (concat
          ["Usage: yaac create <resource> [options] key=value..."
           ""
           "Create resources like an organization"
           ""
           "Options:"
           ""
           summary
           ""]
          (when help-all
            ["Resources:"
             ""
             " - organization"
             " - environment"
             " - private-space (ps)"
             " - api"
             " - policy"
             " - invitation"
             " - connected-app"
             " - client-provider (cp)"
             ""
             "Keys:"
             ""
             "  organization:"
             "   - v-cores:             prod:sandbox:design            (default: 0.1:0.1:0)"
             "  environment:"
             "   - type:               production|sandbox|design       (default: sandbox)"
             "  api:"
             "   - technology:         mule4|flexGateway               (default: mule4)"
             "   - uri:                upstream URL"
             "   - target:             deploy target fg:<gw-name>      (Flex Gateway only)"
             "  invitation:"
             "   - email:               user's email address"
             "   - team-id:             team ID or name"
             "  connected-app:"
             "   - name:                app name"
             "   - redirect-uris:       comma-separated redirect URIs"
             "  private-space:"
             "   - --region:            AWS region (e.g., ap-northeast-1)"
             "   - --cidr-block:        CIDR block (e.g., 10.0.0.0/24)"
             "   - --reserved-cidrs:    Reserved CIDRs (comma-separated)"
             ""])
          ["Example:"
           ""
           "# Create API instance"
           "  > yaac create api -g T1 -a account-api -v 0.0.1 uri=https://httpbin.org"
           ""
           "# Create organization"
           "  > yaac create org T1.1 --parent T1 v-cores=0.1:0.3:0.0"
           ""
           "# Create environment"
           "  > yaac create env T1.1 live type=production"
           ""
           "# Create Private Space with network"
           "  > yaac create ps T1 my-ps --region ap-northeast-1 --cidr-block 10.0.0.0/24"
           ""])
         (str/join \newline))))


(def options [["-g" "--group NAME" "Group name. Normally BG name"]
              ["-a" "--asset NAME" "Asset name"]
              ["-v" "--version VERSION" "Asset version"]
              ["-p" "--parent NAME" "Parent organization"]
              ["-e" "--email EMAIL" "Email address for user invitation"]
              ["-t" "--teams TEAMS" "Team assignments (format: team_name_or_id:membership_type,...)"]
              [nil "--team-id ID_OR_NAME" "Single team ID or name for invitation"]
              [nil "--membership-type TYPE" "Membership type (member or maintainer, default: member)"]
              ["-n" "--name NAME" "Connected app / client provider name"]
              [nil "--grant-types TYPES" "Grant types (client_credentials or authorization_code)"]
              [nil "--scopes SCOPES" "Comma-separated scopes"]
              [nil "--redirect-uris URIS" "Comma-separated redirect URIs"]
              [nil "--audience AUDIENCE" "Audience (internal or everyone)"]
              [nil "--public" "Public client (for authorization_code)"]
              ;; Client provider options
              [nil "--description DESC" "Client provider description"]
              [nil "--issuer ISSUER" "Token issuer identifier"]
              [nil "--authorize-url URL" "Authorization endpoint URL"]
              [nil "--token-url URL" "Token endpoint URL"]
              [nil "--introspect-url URL" "Introspection endpoint URL"]
              [nil "--register-url URL" "Client registration endpoint URL"]
              [nil "--registration-auth AUTH" "Client registration authorization"]
              [nil "--client-id ID" "Primary client ID"]
              [nil "--client-secret SECRET" "Primary client secret"]
              [nil "--timeout MS" "Client request timeout in ms (default: 5000)"]
              [nil "--allow-untrusted-certs" "Allow untrusted certificates"]
              ;; Private Space options
              [nil "--region REGION" "AWS region (e.g., ap-northeast-1, us-east-1)"]
              [nil "--cidr-block CIDR" "CIDR block (e.g., 10.0.0.0/24)"]
              [nil "--reserved-cidrs CIDRS" "Reserved CIDRs (comma-separated)"]
              ;; Managed Flex Gateway options
              ["-M" "--managed" "Create Managed Flex Gateway (CloudHub 2.0)"]
              [nil "--target TARGET" "Deploy target (ps:<name> for Private Space, rtf:<name> for RTF)"]
              [nil "--channel CHANNEL" "Release channel (edge or lts, default: lts)"]
              [nil "--runtime-version VERSION" "Flex Gateway runtime version"]
              [nil "--size SIZE" "Gateway size (small or large, default: small)"]
              [nil "--public-url URLS" "Public URLs (comma-separated)"]
              [nil "--forward-ssl" "Forward SSL session"]
              [nil "--last-mile-security" "Enable last mile security"]
              [nil "--log-level LEVEL" "Log level (debug, info, warn, error, default: info)"]
              [nil "--forward-logs" "Forward logs to Anypoint Monitoring"]
              [nil "--upstream-timeout MS" "Upstream response timeout in ms"]
              [nil "--connection-timeout MS" "Connection idle timeout in ms"]
              ;; Alert options (for both API and Application alerts)
              [nil "--api API" "API name or ID (for API alerts)"]
              [nil "--app APP" "Application ID (for application alerts)"]
              [nil "--cluster-id ID" "Cluster/Target ID (for application alerts)"]
              [nil "--severity LEVEL" "Alert severity (info, warning, critical, default: critical)"]
              [nil "--threshold N" "Alert threshold value"]
              [nil "--interval MINS" "Check interval in minutes (default: 5)"]
              [nil "--metric-type TYPE" "Metric type (message-count, cpu, memory, response-time, error-count)"]
              [nil "--deployment-type TYPE" "Deployment type (cloudhub, hybrid, rtf, default: hybrid for Flex)"]
              [nil "--response-codes CODES" "Response codes to filter (comma-separated, e.g., 503,502)"]
              [nil "--subject SUBJECT" "Email subject template"]
              [nil "--message MESSAGE" "Email message template"]
              [nil "--operator OP" "Comparison operator (above, below, default: above)"]
              [nil "--aggregation FUNC" "Aggregation function (sum, avg, max, min, default: sum)"]
              [nil "--type TYPE" "Alert type: api, app, or server"]])


(defn create-organization [{:keys [parent
                                   v-cores
                                   args]
                            :or {v-cores ["0.0" "0.0" "0.0"]}
                            [org] :args
                            :as opts}]
  (if-not (and org parent)
    (throw (e/invalid-arguments "Org and its parent org needs to be specified" :args args))
    (let [parent-org-id (org->id parent)
          ;; v-cores=1.0:1.0:0.0 → CLIパーサーは","でsplitするが":"区切りもサポート
          vc (if (and (= (count v-cores) 1) (str/includes? (first v-cores) ":"))
               (str/split (first v-cores) #":")
               v-cores)]
      (util/spin (str "Creating organization " org "..."))
      (->> @(http/post (gen-url "/accounts/api/organizations")
                       {:headers (default-headers)
                        :body (edn->json {:name org
                                          :ownerId (:id (:user (:body (yc/get-me ))))
                                          :parentOrganizationId parent-org-id
                                          :entitlements {:createEnvironments true
                                                         :createSubOrgs true
                                                         :globalDeployment true
                                                         :vCoresProduction {:assigned (or (some-> (first vc) parse-double) 0.1)}
                                                         :vCoresSandbox {:assigned (or (some-> (second vc) parse-double) 0.1)}
                                                         :vCoresDesign {:assigned (if (< (count vc) 3) 0.0 (parse-double (last vc)))}}})})
           (parse-response)))))

(defn create-environment [{:keys [production type args]
                            [org env-name] :args
                            :as opts}]
  (if-not org
    (throw (e/invalid-arguments "Org needs to be specified" :args args))
    (let [env-type (or (keyword (first type)) :sandbox)
          org-id (org->id org)]
      (util/spin (str "Creating environment " env-name "..."))
      (->> @(http/post (format (gen-url "/accounts/api/organizations/%s/environments") org-id )
                       {:headers (default-headers)
                        :body (edn->json {:name env-name
                                          :isProduction (= env-type :production)
                                          :type env-type})})
           (parse-response)
           :body))))

;; yaac create api T1 Production label=account-api -g T1 -a account-api -v 1.0.0

;; Note: Name is not in attributes, instead, instance-label is used.

(defn deployment-targets [x]
  (-> {:rtf "RF"
       :runtime-fabric "RF"
       :ch2 "CH2"
       :cloudhub2 "CH2"
       :hybrid "HY"
       :hy "HY"}
      (get (some-> x csk/->kebab-case-keyword) "CH2")))

;; (defn create-transit-gateway [{:keys [args]
;;                                [org ps] :args}]
  
;;   (let [org-id (org->id (or org *org* ))
;;         ps-id (ps->id org-id ps)]
;;     (-> @(http/post (format "https://anypoint.mulesoft.com/runtimefabric/api/organizations/%s/privatespaces/%s/transitgateways " org-id ps-id)
;;                    {:headers (default-headers)
;;                     :body })))
;;   )


(defn create-api-instance [{:keys [group asset version uri deployment-type proxy-uri label technology endpoint-type target args]
                            [org env] :args
                            :as opts}]

  (log/debug "Upstream URI" uri)
  (log/debug "Proxy URI" (or proxy-uri "-"))
  (log/debug "Deployment type:" (or deployment-type "-"))
  (log/debug "Target:" (or target "-"))

  (let [org (or org *org*)
        env (or env *env*)]
    (if-not (and org env uri)
      (throw (e/invalid-arguments "Org, Env, upstream URI and deployment-type needs to be specified, or set default." :args args))
      (do
        (util/spin (str "Creating API instance in " org "/" env "..."))
        (let [proxy-uri (or (first proxy-uri) "http://0.0.0.0:8081") ;; it comes as array since '=' parameters
            upstream-uri (first uri) ;; it comes as array since '=' parameters
            dtype (deployment-targets (or (first deployment-type)
                                          (when *deploy-target* (keyword (first (str/split *deploy-target* #":"))))))
            technology (first technology)
            target-spec (first target) ;; e.g., "fg:f1" or "flex:my-gateway"
            version version
            org-id (org->id org)
            env-id (env->id org-id env)
            group-id (org->id (or group org *org*))]

      (log/debug "org:" org-id "env:" env-id "group:" group-id "deployment-type:" dtype)

        ;; For HTTP API without Exchange asset (Flex Gateway)
        (let [endpoint-type-val (or (first endpoint-type) (if (= technology "flexGateway") "http" "rest"))
              ;; Only include spec if asset is provided
              base-body (cond-> {:technology (or technology "mule4"),
                                 :instance-label (or (first label) asset "http-api")
                                 :endpoint {:tls-contexts nil,
                                            :response-timeout nil,
                                            :type endpoint-type-val,
                                            :deployment-type dtype
                                            :proxy-uri proxy-uri,
                                            :uri upstream-uri,
                                            :is-cloud-hub nil}
                                 :endpoint-uri nil}
                          ;; Include spec only when asset is specified
                          asset (assoc :spec {:group-id group-id,
                                              :asset-id asset,
                                              :version version}))
              api-result (->> @(http/post (format (gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis") org-id env-id)
                                         {:headers (assoc (default-headers)
                                                          "X-ANYPNT-ORG-ID" org-id
                                                          "X-ANYPNT-ENV-ID" env-id)
                                          :body (cond-> base-body
                                                  (= technology "mule4") (-> (assoc-in [:endpoint :mule-version-4-or-above] true)
                                                                             (assoc-in [:endpoint :references-user-domain] false)
                                                                             (assoc-in [:endpoint :is-cloud-hub] false)
                                                                             (assoc-in [:endpoint :proxy-template] nil)
                                                                             (assoc-in [:endpoint :validation] "DISABLED"))
                                                  (= technology "flexGateway") (-> (assoc-in [:endpoint :deployment-type] "HY")
                                                                                   (assoc-in [:endpoint :type] endpoint-type-val))
                                                  true (->> (edn->json :camel)))})
                             (parse-response)
                             :body)]
          ;; If target is specified and technology is flexGateway, deploy to the gateway
          (if (and target-spec (= technology "flexGateway"))
            (let [[target-type gw-name] (str/split target-spec #":")
                  target-id (when (and gw-name (#{:fg :flex :flexgateway} (keyword target-type)))
                              (gw->id org env gw-name))
                  api-id (:id api-result)]
              (if target-id
                (do
                  (log/debug "Deploying API" api-id "to Flex Gateway" gw-name "(" target-id ")")
                  (deploy/deploy-to-flex-gateway org-id env-id api-id target-id)
                  api-result)
                api-result))
            api-result)))))))


(defn- gen-policy-config [policy {:keys [ip-expression ips
                                         delay-attempts maximum-requests queuing-limit expose-headers time-period-in-milliseconds delay-time-in-millis
                                         maximum-requests time-period-in-milliseconds
                                         ;; Circuit breaker options
                                         max-connections max-pending-requests max-requests max-retries max-connection-pools
                                         ]}]
  (condp = (keyword policy)
    :ip-allowlist {:ip-expression (or (first ip-expression) "#[attributes.headers['x-forwarded-for']]"),
                   :ips (or ips ["0.0.0.0/0"])}
    :spike-control {:delay-attempts (or (some-> delay-attempts first parse-long) 1)
                    :maximum-requests (or (some-> maximum-requests first parse-long) 1)
                    :queuing-limit (or (some-> queuing-limit first parse-long) 5)
                    :expose-headers (or (= (first expose-headers) "true") false)
                    :time-period-in-milliseconds (or (some-> time-period-in-milliseconds first parse-long) 1000)
                    :delay-time-in-millis (or (some-> delay-time-in-millis first parse-long) 1000)}
    :rate-limiting {:rateLimits [{:maximumRequests (or (some-> maximum-requests first parse-long) 10)
                                   :timePeriodInMilliseconds (or (some-> time-period-in-milliseconds first parse-long) 60000)}]
                    :clusterizable true
                    :exposeHeaders false}
    :circuit-breaker {:thresholds (cond-> {}
                                    max-connections (assoc :maxConnections (some-> max-connections first parse-long))
                                    max-pending-requests (assoc :maxPendingRequests (some-> max-pending-requests first parse-long))
                                    max-requests (assoc :maxRequests (some-> max-requests first parse-long))
                                    max-retries (assoc :maxRetries (some-> max-retries first parse-long))
                                    max-connection-pools (assoc :maxConnectionPools (some-> max-connection-pools first parse-long)))}
    (throw (e/not-implemented "Given policy is not implemented" {:name policy}))))

;; Outbound policies require different endpoint and upstream IDs
(def outbound-policies #{"circuit-breaker"})

(defn- outbound-policy? [asset-id]
  (contains? outbound-policies asset-id))

(defn -create-api-policy [org env api policy & [opts]]
  (let [org-id (org->id (or org *org*))
        env-id (env->id org-id (or env *env*))
        api-id (api->id org-id env-id api)
        ;; Support full policy spec (groupId/assetId/version) or just policy name
        [group-id asset-id version] (if (str/includes? policy "/")
                                      (str/split policy #"/")
                                      [nil policy nil])
        ;; If full spec provided, use it directly; otherwise search
        policy-info (if (and group-id asset-id version)
                      {:group-id group-id :asset-id asset-id :version version}
                      (let [policies (->> (yc/get-assets {:types ["policy"] :args [yc/mule-business-group-id]})
                                          ;; Exact match for asset-id
                                          (filter #(= policy (:asset-id %))))]
                        (cond
                          (= 0 (count policies)) (throw (e/no-item (str "No policy found: " policy)))
                          (< 1 (count policies)) (throw (e/multiple-policies "Multiple policy found" {:extra policies}))
                          :else (let [{:keys [asset-id version group-id]} (first policies)]
                                  {:group-id group-id :asset-id asset-id :version version}))))
        is-outbound (outbound-policy? (:asset-id policy-info))]

    (if is-outbound
      ;; Outbound policy - use xapi endpoint with upstream IDs
      (let [upstreams-resp (-get-api-upstreams org env api)
            upstream-ids (mapv :id (:upstreams upstreams-resp))]
        (when (empty? upstream-ids)
          (throw (e/invalid-arguments "No upstreams found for API. Outbound policies require at least one upstream." {:api api})))
        (-> @(http/post (format (gen-url "/apimanager/xapi/v1/organizations/%s/environments/%s/apis/%s/policies/outbound-policies") org-id env-id api-id)
                       {:headers (default-headers)
                        :body (edn->json :camel
                                         {:configuration-data (gen-policy-config (:asset-id policy-info) opts)
                                          :asset-id (:asset-id policy-info)
                                          :asset-version (:version policy-info)
                                          :group-id (:group-id policy-info)
                                          :upstream-ids upstream-ids})})
            (parse-response)
            :body))
      ;; Inbound policy - use standard endpoint
      (-> @(http/post (format (gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s/policies") org-id env-id api-id)
                     {:headers (default-headers)
                      :body (edn->json :camel
                                       {:configuration-data (gen-policy-config (:asset-id policy-info) opts)
                                        :pointcut-data nil
                                        :asset-id (:asset-id policy-info)
                                        :asset-version (:version policy-info)
                                        :group-id (:group-id policy-info)})})
          (parse-response)
          :body))))

(defn create-api-policy [{:keys [args]
                          [org env api policy] :args
                          :as opts}]
  (let []
    (-create-api-policy org env api policy (dissoc opts :args))))


;; Alerts (Unified: --type api|app|server)

(def deployment-types
  {"cloudhub" "CLOUDHUB"
   "ch" "CLOUDHUB"
   "hybrid" "HYBRID"
   "hy" "HYBRID"
   "flex" "HYBRID"
   "rtf" "RTF"})

(def app-alert-metric-types
  {"message-count" "message_count"
   "message_count" "message_count"
   "cpu" "cpu"
   "memory" "memory"
   "response-time" "response_time"
   "response_time" "response_time"
   "error-count" "error_count"
   "error_count" "error_count"})

(defn -create-api-alert
  "Create an API alert"
  [org env {:keys [name api severity threshold interval
                   deployment-type response-codes
                   email subject message]}]
  (let [org-id (yc/org->id (or org *org*))
        env-id (yc/env->id org-id (or env *env*))
        api-id (when api (yc/api->id org-id env-id api))
        deploy-type (get deployment-types (str/lower-case (or deployment-type "hybrid")) "HYBRID")
        sub-filters (when (seq response-codes)
                      [{:name "response_code"
                        :values (if (string? response-codes)
                                  (str/split response-codes #",")
                                  response-codes)}])
        body {:type "basic"
              :name name
              :severity (or severity "critical")
              :masterOrganizationId org-id
              :organizationId org-id
              :environmentId env-id
              :resourceType "api"
              :deploymentType deploy-type
              :metricType "api_request_count"
              :resources (if api-id
                           [{:apiVersionId (str api-id)
                             :apiId (str api-id)
                             :type "api"
                             :subFilters (or sub-filters [])}]
                           [])
              :condition {:operator "above"
                          :threshold (or (some-> threshold parse-long) 0)
                          :interval (or (some-> interval parse-long) 5)}
              :wildcardAlert (nil? api-id)
              :notifications [{:type "email"
                               :recipients (if (string? email)
                                             (str/split email #",")
                                             email)
                               :subject (or subject "${severity}: ${api} Alert")
                               :message (or message "Alert triggered for API ${api} at ${timestamp}.\nEnvironment: ${environment}")}]}]
    (util/spin (str "Creating API alert " name "..."))
    (-> @(http/post (format (yc/gen-url "/monitoring/api/alerts/api/v2/organizations/%s/environments/%s/alerts")
                           org-id env-id)
                   {:headers (yc/default-headers)
                    :body (edn->json :camel body)})
        (yc/parse-response)
        :body)))

(defn -create-app-alert
  "Create an application alert via Monitoring API v2"
  [org env {:keys [name app cluster-id severity metric-type threshold operator
                   interval aggregation email subject message]}]
  (let [org-id (yc/org->id org)
        env-id (yc/env->id org-id env)
        app-id (when app
                 (if (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" app)
                   app
                   (yc/app->id org env app)))
        metric (get app-alert-metric-types (or metric-type "message_count") "message_count")
        body {:alertType "basic"
              :alertName name
              :masterOrganizationId org-id
              :organizationId org-id
              :resource {:type "application"
                         :organizationId org-id
                         :environmentId env-id
                         :deploymentType "RTF"
                         :clusterId (or cluster-id "")
                         :appId (or app-id "")}
              :alertViolationInterval (or (some-> interval parse-long) 5)
              :aggregationInterval 1
              :aggregationFunction (or aggregation "sum")
              :metricType metric
              :properties [{:name "operator" :value (or operator "above")}
                           {:name "threshold" :value (str (or threshold "0"))}]
              :alertNotifications [{:recipients (if email
                                                  (if (string? email)
                                                    (str/split email #",")
                                                    email)
                                                  [])
                                    :subject (or subject "${severity}: ${resource} ${condition}")
                                    :message (or message "Alert triggered for ${resource}")
                                    :type "email"}]
              :severity (str/lower-case (or severity "warning"))}]
    (util/spin (str "Creating app alert " name "..."))
    (-> @(http/post (format (gen-url "/monitoring/api/v2/organizations/%s/alerts") org-id)
                    {:headers (yc/default-headers)
                     :body (edn->json body)})
        (yc/parse-response)
        :body)))

(defn create-alert
  "Create alert - unified handler for api/app/server types

   Usage:
     yaac create alert <org> <env> --type api --name 'Alert Name' --api 'api-name' --email 'email'
     yaac create alert <org> <env> --type app --name 'Alert Name' --app 'app-id' --cluster-id 'id' --email 'email'"
  [{:keys [args type name api app cluster-id severity threshold interval
           deployment-type response-codes metric-type operator aggregation
           email subject message]
    :as opts}]
  (when-not name
    (throw (e/invalid-arguments "Alert name is required (--name)" {})))
  (when-not email
    (throw (e/invalid-arguments "Email recipient is required (--email)" {})))

  (let [[env org] (reverse args)
        org (or org *org*)
        env (or env *env*)
        alert-type (or type "api")]

    (when-not (and org env)
      (throw (e/invalid-arguments "Both org and env are required for alerts" {:args args})))

    (case alert-type
      "api"
      [(-create-api-alert org env (dissoc opts :args :type))]

      "app"
      (do
        (when-not app
          (throw (e/invalid-arguments "--app (application ID) is required for app alerts" {})))
        [(-create-app-alert org env (dissoc opts :args :type))])

      "server"
      (throw (e/invalid-arguments "Server alerts not yet implemented" {}))

      (throw (e/invalid-arguments "Unknown alert type. Use --type api|app|server" {:type type})))))


;; Managed Flex Gateway

(defn- parse-target
  "Parse target string like 'ps:my-private-space' or 'rtf:my-cluster'"
  [org target]
  (when target
    (let [[type name] (str/split target #":" 2)]
      (case (str/lower-case type)
        "ps" (ps->id org name)
        "rtf" (rtf->id org name)
        (throw (e/invalid-arguments "Target must be ps:<name> or rtf:<name>" {:target target}))))))

(defn -create-managed-gateway
  "Create a Managed Flex Gateway on CloudHub 2.0"
  [org env name target-id {:keys [channel runtime-version size
                                   public-url forward-ssl last-mile-security
                                   log-level forward-logs
                                   upstream-timeout connection-timeout]}]
  (let [org-id (org->id org)
        env-id (env->id org-id env)
        body {:name name
              :targetId target-id
              :releaseChannel (str/lower-case (or channel "lts"))
              :runtimeVersion runtime-version
              :size (str/lower-case (or size "small"))
              :configuration
              {:ingress (cond-> {:publicUrl (or public-url "")}
                          forward-ssl (assoc :forwardSslSession true)
                          last-mile-security (assoc :lastMileSecurity true))
               :logging (cond-> {:level (str/lower-case (or log-level "info"))}
                          forward-logs (assoc :forwardLogs true))
               :properties {:upstreamResponseTimeout (if upstream-timeout (parse-long upstream-timeout) 60000)
                            :connectionIdleTimeout (if connection-timeout (parse-long connection-timeout) 60000)}}}]
    (util/spin (str "Creating Managed Flex Gateway " name "..."))
    (-> @(http/post (format (gen-url "/gatewaymanager/api/v1/organizations/%s/environments/%s/gateways") org-id env-id)
                    {:headers (default-headers)
                     :body (edn->json :camel body)})
        (parse-response)
        :body)))

(defn create-managed-gateway
  "Handler for creating Managed Flex Gateway

   Usage: yaac create gateway <org> <env> <name> -M --target ps:<private-space>

   Options:
     -M, --managed              Create Managed Flex Gateway (required)
     --target <ps:name|rtf:name> Deploy target (Private Space or RTF)
     --channel <edge|lts>       Release channel (default: lts)
     --runtime-version <ver>    Flex Gateway version
     --size <small|large>       Gateway size (default: small)
     --public-url <urls>        Public URLs (comma-separated)
     --forward-ssl              Forward SSL session
     --last-mile-security       Enable last mile security
     --log-level <level>        Log level (default: info)
     --forward-logs             Forward logs
     --upstream-timeout <ms>    Upstream response timeout
     --connection-timeout <ms>  Connection idle timeout"
  [{:keys [managed target runtime-version args]
    [org env gw-name] :args
    :as opts}]
  (let [org (or org *org*)
        env (or env *env*)]
    (cond
      (not managed)
      (throw (e/invalid-arguments "Use -M/--managed flag for Managed Flex Gateway" {}))

      (not (and org env gw-name))
      (throw (e/invalid-arguments "Org, Env and Gateway name are required" {:args args}))

      (not target)
      (throw (e/invalid-arguments "Target is required (--target ps:<name> or rtf:<name>)" {}))

      (not runtime-version)
      (throw (e/invalid-arguments "Runtime version is required (--runtime-version <version>, e.g., 1.9.3)" {}))

      (not (:public-url opts))
      (throw (e/invalid-arguments "Public URL is required (--public-url <url>)" {}))

      :else
      (let [target-id (parse-target org target)]
        (-create-managed-gateway org env gw-name target-id (dissoc opts :args :managed :target))))))


(defn invite-user [{:keys [email teams team-id membership-type]
                    :as opts}]
  "Invite a user to the root organization

  Required:
    --email    - Email address of user to invite

  Optional:
    --teams         - Comma-separated list of team IDs or names (format: team:membership_type)
    --team-id       - Single team ID or name (shortcut)
    --membership-type - Membership type (member or maintainer, default: member)

  Example:
    yaac create invite --email user@example.com
    yaac create invite --email user@example.com --team-id MyTeam
    yaac create invite --email user@example.com --team-id abc123 --membership-type maintainer"
  (when-not email
    (throw (e/invalid-arguments "Email is required" :email email)))
  (let [{root-org-id :id} (yc/-get-root-organization)
        org-id root-org-id
        ;; Parse teams if provided
        teams-list (cond
                     ;; If teams string provided (format: "team1:member,team2:maintainer")
                     teams
                     (map (fn [team-str]
                            (let [[tid mtype] (str/split team-str #":")]
                              {:team-id (yc/team->id (str/trim tid))
                               :membership-type (or (some-> mtype str/trim) "member")}))
                          (str/split teams #","))

                     ;; If single team-id provided (can be ID or name)
                     team-id
                     [{:team-id (yc/team->id team-id)
                       :membership-type (or membership-type "member")}]

                     ;; No teams
                     :else nil)
        ;; Request body is an array of invitation objects
        body [(cond-> {:email email}
                teams-list (assoc :teams teams-list))]]
    (-> @(http/post (format (gen-url "/accounts/api/organizations/%s/invites") org-id)
                   {:headers (default-headers)
                    :body (edn->json :snake body)})
        (parse-response)
        :body)))


(defn create-connected-app [{:keys [name grant-types scopes redirect-uris audience public org-scopes env-scopes org env]
                              :as opts}]
  "Create a connected app in the root organization

  Required:
    --name           - Name of the connected app
    --redirect-uris  - Comma-separated redirect URIs

  Optional:
    --grant-types    - Grant types: client_credentials or authorization_code (default: client_credentials)
    --scopes         - Comma-separated scopes (e.g., profile,openid)
    --audience       - Audience: internal or everyone (default: internal)
    --public         - Public client flag for authorization_code (default: false)
    --org-scopes     - Comma-separated org-level scopes (e.g., read:organization,edit:organization)
    --env-scopes     - Comma-separated env-level scopes (e.g., read:applications,admin:cloudhub)
    --org            - Organization for scopes (default: current org)
    --env            - Comma-separated environments for env-level scopes (e.g., Production,Sandbox)

  Example:
    yaac create connected-app --name MyApp --redirect-uris http://localhost:8080/callback
    yaac create connected-app --name MyApp --grant-types client_credentials --redirect-uris http://localhost --org-scopes read:organization --env-scopes read:applications --env Production"
  (when-not name
    (throw (e/invalid-arguments "Name is required for connected-app" :name name)))
  (when-not redirect-uris
    (throw (e/invalid-arguments "redirect-uris is required for connected-app" :redirect-uris redirect-uris)))
  (when (and env-scopes (not env))
    (throw (e/invalid-arguments "--env is required when using --env-scopes" {:env-scopes env-scopes})))
  (util/spin (str "Creating connected app " name "..."))
  (let [grant-type-list (if grant-types
                          (str/split grant-types #",")
                          ["client_credentials"])
        scope-list (if scopes
                     (str/split scopes #",")
                     ["profile"])
        redirect-uri-list (str/split redirect-uris #",")
        audience-val (or audience "internal")
        body (cond-> {:client_name name
                      :grant_types grant-type-list
                      :scopes scope-list
                      :audience audience-val
                      :redirect_uris redirect-uri-list}
               public (assoc :public_client true))
        result (-> @(http/post (gen-url "/accounts/api/connectedApplications")
                              {:headers (default-headers)
                               :body (edn->json :snake body)})
                   (parse-response)
                   :body)]
    ;; Assign org/env-level scopes if specified
    (when (or org-scopes env-scopes)
      (let [org-id (org->id (or org *org*))
            org-scope-list (when org-scopes (str/split org-scopes #","))
            env-scope-list (when env-scopes (str/split env-scopes #","))
            env-list (when env (str/split env #","))
            env-ids (when (and env-scopes env-list)
                      (mapv #(yc/env->id org-id %) env-list))]
        (log/debug "Assigning scopes - org:" org-scope-list "env:" env-scope-list)
        (assign-connected-app-scopes (:client_id result)
                                     {:scopes scope-list
                                      :org-scopes org-scope-list
                                      :org-id org-id
                                      :env-scopes env-scope-list
                                      :env-ids env-ids})))
    result))

(defn create-client-provider
  "Create a new OpenID Connect client provider

   Required options:
   - --name: Provider name
   - --authorize-url: Authorization endpoint URL
   - --token-url: Token endpoint URL
   - --introspect-url: Introspection endpoint URL
   - --register-url: Client registration endpoint URL
   - --issuer: Token issuer identifier
   - --client-id: Primary client ID
   - --client-secret: Primary client secret"
  [{:keys [name description issuer authorize-url token-url introspect-url
           register-url registration-auth client-id client-secret timeout
           allow-untrusted-certs] :as opts}]
  (when-not name
    (throw (e/invalid-arguments "Client provider name is required (--name)" opts)))
  (when-not (and authorize-url token-url introspect-url)
    (throw (e/invalid-arguments "URLs are required (--authorize-url, --token-url, --introspect-url)" opts)))
  (when-not register-url
    (throw (e/invalid-arguments "Registration URL is required (--register-url)" opts)))
  (when-not issuer
    (throw (e/invalid-arguments "Issuer is required (--issuer)" opts)))
  (when-not (and client-id client-secret)
    (throw (e/invalid-arguments "Client credentials are required (--client-id, --client-secret)" opts)))

  (let [{root-org-id :id} (-get-root-organization)
        request-body {"name" name
                      "type" {"name" "openid-dynamic-client"
                              "description" (or description "")}
                      "allow_untrusted_certificates" (boolean allow-untrusted-certs)
                      "oidc_dynamic_client_provider"
                      {"allow_local_client_deletion" true
                       "allow_external_client_modification" true
                       "allow_client_import" true
                       "issuer" issuer
                       "client_request_timeout" (if timeout (parse-long timeout) 5000)
                       "urls" {"authorize" authorize-url
                               "token" token-url
                               "introspect" introspect-url}
                       "client" {"urls" {"register" register-url}
                                 "registration" {"authorization" (or registration-auth "")}}
                       "primary_client" {"id" client-id
                                         "secret" client-secret}}}
        json-body (json/write-value-as-string request-body)]
    (log/debug "Creating client provider:" name)
    (log/debug "Request body:" json-body)
    (-> @(http/post (format (gen-url "/accounts/api/organizations/%s/clientProviders") root-org-id)
                   {:headers (default-headers)
                    :body json-body})
        (parse-response)
        :body
        (yc/add-extra-fields :id :provider-id
                             :name :name
                             :type (comp :name :type)))))

(defn create-private-space
  "Create a Private Space in CloudHub 2.0.
   If region and cidr-block are specified, configures the network immediately."
  [{:keys [args name region cidr-block reserved-cidrs]
    [org ps-name] :args
    :as opts}]
  (let [org (or org *org*)
        ps-name (or ps-name name)]
    (when-not org
      (throw (e/invalid-arguments "Org is required" {:args args})))
    (when-not ps-name
      (throw (e/invalid-arguments "Private Space name is required" {:args args})))
    (let [org-id (org->id org)
          ;; Step 1: Create Private Space
          ps-resp (-> @(http/post (format (gen-url "/runtimefabric/api/organizations/%s/privatespaces") org-id)
                                 {:headers (default-headers)
                                  :body (edn->json :camel {:name ps-name})})
                      (parse-response)
                      :body)
          ps-id (:id ps-resp)]
      ;; Step 2: Configure network if region/cidr specified
      (if (and region cidr-block)
        (let [network-config (cond-> {:region region :cidr-block cidr-block}
                               reserved-cidrs (assoc :reserved-cidrs (str/split reserved-cidrs #",")))
              result (-> @(http/patch (format (gen-url "/runtimefabric/api/organizations/%s/privatespaces/%s") org-id ps-id)
                                     {:headers (default-headers)
                                      :body (edn->json :camel {:network network-config})})
                         (parse-response)
                         :body)]
          (yc/add-extra-fields result :name :name :status :status :region :region))
        ;; No network config
        (yc/add-extra-fields ps-resp :name :name :status :status :region :region)))))

(def route
  (for [op ["create" "new"]]
    [op {:options options
         :usage usage}
     ["" {:help true}]   
     ["|-h" {:help true}]
     ["|" {:help true}
      ["org"]
      ["organization"]]
     ["|" {:handler create-organization
            :fields [[:body :name] [:body :id :fmt short-uuid]]}
      ["org|{*args}" ]
      ["organization|{*args}" ]]
     ["|" {:help true}
      ["env"]
      ["environement"]]
     ["|" {:handler create-environment
            :fields [[:body :name] [:body :id :fmt short-uuid] [:body :type]]}
      ["env|{*args}" ]
      ["environement|{*args}" ]]
     ["|" {:handler create-private-space
           :fields [[:id :fmt short-uuid] [:extra :name] [:extra :status]]}
      ["ps|{*args}"]
      ["private-space|{*args}"]]
     ["|"
      ["api|{*args}" {:fields [[:id :fmt short-uuid] :asset-id :asset-version]
                      :handler create-api-instance}]]
     ["|"
      ["policy|{*args}" {:handler create-api-policy}]]
     ["|" {:handler create-managed-gateway
           :fields [[:id :fmt short-uuid] :name :status]}
      ["gateway|{*args}"]
      ["gw|{*args}"]]
     ["|" {:handler invite-user
           :fields [:invited-email :status]}
      ["invitation|{*args}"]
      ["invite|{*args}"]]
     ["|" {:handler create-connected-app
           :fields [:client-id :client-secret :client-name]}
      ["connected-app|{*args}"]]
     ["|" {:handler create-client-provider
           :fields [[:extra :name] [:extra :id :fmt short-uuid] [:extra :type]]}
      ["cp|{*args}"]
      ["client-provider|{*args}"]]
     ["|" {:handler create-alert
           :fields [:alert-id :alert-name :name :severity :state :enabled]}
      ["alert|{*args}"]]]))
