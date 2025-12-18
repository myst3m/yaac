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
  (:require [silvur
             [util :refer [json->edn edn->json]]
             [http :as http]
             [log :as log]]
            [reitit.core :as r]
            [yaac.core :refer [*org* *env* *deploy-target*
                               parse-response default-headers
                               org->id ps->id env->id api->id app->id org->name env->name gw->id load-session!
                               gen-url] :as yc]
            [yaac.deploy :as deploy]
            [yaac.error :as e]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [yaac.incubate :as ic]
            [camel-snake-kebab.extras :as cske]
            [camel-snake-kebab.core :as csk]
            [clojure.spec.alpha :as s]))


(defn usage [summary-options]
  (->> ["Usage: yaac create <resource> [options] key=value..."
        ""
        "Create resources like an organization"
        "Resources:"
        ""
        " - organization"
        " - environment"
        " - api"
        " - policy"
        " - invitation"
        " - connected-app"
        ""
        "Options:"
        ""
        summary-options
        ""
        "Keys:"
        ""
        "  organization:"
        "   - v-cores:             prod:sandbox:design            (default: 0.1:0.1:0)"
        "   - parent:  "
        "  environment:"
        "   - type:               production|sandbox|design       (default: sandbox)"
        "  api:"
        "   - technology:         mule4|flexGateway               (default: mule4)"
        "   - endpoint-type:      rest|http                       (default: rest, flexGateway: http)"
        "   - proxy-uri:          listen URL                      (default: http://0.0.0.0:8081)"
        "   - uri:                upstream URL"
        "   - deployment-type:    deployment type ch20|rtf        (default: ch20)"
        "   - target:             deploy target fg:<gw-name>      (Flex Gateway only)"
        ""
        "  policy:"
        "   - ip-allowlist:"
        "      * ip-expression    remote ip expression            (default: #[attributes.headers['x-forwarded-for']])"
        "      * ips              allow list                      (default: 0.0.0.0/0)"
        ""
        "  invitation:"
        "   - email:               user's email address            (required)"
        "   - teams:               team assignments                 (optional, format: team_name_or_id:membership_type,...)"
        "   - team-id:             single team ID or name           (optional)"
        "   - membership-type:     member or maintainer             (optional, default: member)"
        ""
        "  connected-app:"
        "   - name:                app name                         (required)"
        "   - redirect-uris:       comma-separated redirect URIs    (required)"
        "   - grant-types:         client_credentials|authorization_code (default: client_credentials)"
        "   - scopes:              comma-separated scopes           (optional)"
        "   - audience:            internal|everyone                (default: internal)"
        "   - public:              true|false                       (default: false, for authorization_code)"
        ""
        "Examples:"
        ""
        "# Create API instance "
        "  yaac create api -g T1 -a account-api -v 0.0.1  uri=https://httpbin.org"        
        ""
        "# Create API instance in the given org/env"
        "  yaac create api T1 Production -g T1 -a account-api -v 0.0.1 technology=mule4 uri=https://httpbin.org proxy-uri=http://0.0.0.0:8081"
        ""
        "# Create API instance on Flex Gateway and deploy to gateway 'f1'"
        "  yaac create api MuleSoft Production -g MuleSoft -a httpbin-api -v 1.0.0 technology=flexGateway uri=https://httpbin.org target=fg:f1"
        ""
        "# Create org named T1.1 in T1 with v-cores 0.1(Production), 0.3(Sandbox) and 0.0(Design)"
        "  yaac create org T1.1 --parent T1  v-cores=0.1:0.3:0.0"
        ""
        "# Create env named live in Production type"
        "  yaac create env T1.1 live type=production"
        ""
        "# Invite user to root organization"
        "  yaac create invite --email user@example.com"
        ""
        "# Invite user with team assignment"
        "  yaac create invite --email user@example.com --team-id abc123"
        ""
        "# Invite user with team as maintainer"
        "  yaac create invite --email user@example.com --team-id abc123 --membership-type maintainer"
        ""
        "# Create connected app with client_credentials grant"
        "  yaac create connected-app --name MyApp --redirect-uris http://localhost:8080/callback"
        ""
        "# Create connected app with authorization_code grant"
        "  yaac create connected-app --name MyApp --grant-types authorization_code --redirect-uris http://localhost:8080/callback"
        ""
        ""]
       (str/join \newline)))


(def options [["-g" "--group NAME" "Group name. Normally BG name"]
              ["-a" "--asset NAME" "Asset name"]
              ["-v" "--version VERSION" "Asset version"]
              ["-p" "--parent NAME" "Parent organization"]
              ["-e" "--email EMAIL" "Email address for user invitation"]
              ["-t" "--teams TEAMS" "Team assignments (format: team_name_or_id:membership_type,...)"]
              [nil "--team-id ID_OR_NAME" "Single team ID or name for invitation"]
              [nil "--membership-type TYPE" "Membership type (member or maintainer, default: member)"]
              ["-n" "--name NAME" "Connected app name"]
              [nil "--grant-types TYPES" "Grant types (client_credentials or authorization_code)"]
              [nil "--scopes SCOPES" "Comma-separated scopes"]
              [nil "--redirect-uris URIS" "Comma-separated redirect URIs"]
              [nil "--audience AUDIENCE" "Audience (internal or everyone)"]
              [nil "--public" "Public client (for authorization_code)"]])


(defn create-organization [{:keys [parent
                                   v-cores
                                   args]
                            :or {v-cores ["0.0" "0.0" "0.0"]}
                            [org] :args
                            :as opts}]
  (if-not (and org parent)
    (throw (e/invalid-arguments "Org and its parent org needs to be specified" :args args))
    (let [parent-org-id (org->id parent)]
      (->> (http/post (gen-url "/accounts/api/organizations")
                       {:headers (default-headers)
                        :body (edn->json {:name org
                                          :ownerId (:id (:user (:body (yc/get-me ))))
                                          :parentOrganizationId parent-org-id
                                          :entitlements {:createEnvironments true
                                                         :createSubOrgs true
                                                         :globalDeployment true
                                                         :vCoresProduction {:assigned (or (parse-double (first v-cores)) 0.1)}
                                                         :vCoresSandbox {:assigned (or (parse-double (second v-cores)) 0.1)}
                                                         :vCoresDesign {:assigned (if (< (count v-cores) 3) 0.0 (parse-double (last v-cores)))}}})})
           (parse-response)))))

(defn create-environment [{:keys [production type args]
                            [org env-name] :args
                            :as opts}]
  (if-not org
    (throw (e/invalid-arguments "Org needs to be specified" :args args))
    (let [env-type (or (keyword (first type)) :sandbox)
          org-id (org->id org)]
      (->> (http/post (format (gen-url "/accounts/api/organizations/%s/environments") org-id )
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
       :ch20 "CH2"
       :cloudhub2 "CH2"
       :hybrid "HY"
       :hy "HY"}
      (get (some-> x csk/->kebab-case-keyword) "CH2")))

;; (defn create-transit-gateway [{:keys [args]
;;                                [org ps] :args}]
  
;;   (let [org-id (org->id (or org *org* ))
;;         ps-id (ps->id org-id ps)]
;;     (-> (http/post (format "https://anypoint.mulesoft.com/runtimefabric/api/organizations/%s/privatespaces/%s/transitgateways " org-id ps-id)
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

        (let [api-result (->> (http/post (format (gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis") org-id env-id)
                                         {:headers (assoc (default-headers)
                                                          "X-ANYPNT-ORG-ID" org-id
                                                          "X-ANYPNT-ENV-ID" env-id)
                                          :body (cond-> {:technology (or technology "mule4"),
                                                         :instance-label (or (first label) asset)
                                                         :spec {:group-id group-id,
                                                                :asset-id asset,
                                                                :version version}
                                                         :endpoint {:tls-contexts nil,
                                                                    :response-timeout nil,
                                                                    :type (or (first endpoint-type) "rest"),
                                                                    :deployment-type dtype
                                                                    :proxy-uri proxy-uri,
                                                                    :uri upstream-uri,
                                                                    :is-cloud-hub nil}
                                                         :endpoint-uri nil}
                                                  (= technology "mule4") (-> (assoc-in [:endpoint :mule-version-4-or-above] true)
                                                                             (assoc-in [:endpoint :references-user-domain] false)
                                                                             (assoc-in [:endpoint :is-cloud-hub] false)
                                                                             (assoc-in [:endpoint :proxy-template] nil)
                                                                             (assoc-in [:endpoint :validation] "DISABLED"))
                                                  (= technology "flexGateway") (-> (assoc-in [:endpoint :deployment-type] "HY")
                                                                                   (assoc-in [:endpoint :type] (or (first endpoint-type) "http")))
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
            api-result))))))


(defn- gen-policy-config [policy {:keys [ip-expression ips
                                         delay-attempts maximum-requests queuing-limit expose-headers time-period-in-milliseconds delay-time-in-millis
                                         maximum-requests time-period-in-milliseconds
                                         
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
    (throw (e/not-implemented "Given policy is not implemented" {:name policy}))))

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
                                  {:group-id group-id :asset-id asset-id :version version}))))]

    (-> (http/post (format (gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s/policies") org-id env-id api-id)
                   {:headers (default-headers)
                    :body (edn->json :camel
                                     {:configuration-data (gen-policy-config (:asset-id policy-info) opts)
                                      :pointcut-data nil
                                      :asset-id (:asset-id policy-info)
                                      :asset-version (:version policy-info)
                                      :group-id (:group-id policy-info)})})
        (parse-response)
        :body)))

(defn create-api-policy [{:keys [args]
                          [org env api policy] :args
                          :as opts}]
  (let []
    (-create-api-policy org env api policy (dissoc opts :args))))


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
    (-> (http/post (format (gen-url "/accounts/api/organizations/%s/invites") org-id)
                   {:headers (default-headers)
                    :body (edn->json :snake body)})
        (parse-response)
        :body)))


(defn create-connected-app [{:keys [name grant-types scopes redirect-uris audience public]
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

  Example:
    yaac create connected-app --name MyApp --redirect-uris http://localhost:8080/callback
    yaac create connected-app --name MyApp --grant-types authorization_code --redirect-uris http://localhost:8080/callback"
  (when-not name
    (throw (e/invalid-arguments "Name is required for connected-app" :name name)))
  (when-not redirect-uris
    (throw (e/invalid-arguments "redirect-uris is required for connected-app" :redirect-uris redirect-uris)))
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
               public (assoc :public_client true))]
    (-> (http/post (gen-url "/accounts/api/connectedApplications")
                   {:headers (default-headers)
                    :body (edn->json :snake body)})
        (parse-response)
        :body)))


(def route
  (for [op ["c" "create"]]
    [op {:options options
         :usage usage}
     ["" {:help true}]   
     ["|-h" {:help true}]
     ["|" {:help true}
      ["org"]
      ["organization"]]
     ["|" {:handler create-organization}
      ["org|{*args}" ]
      ["organization|{*args}" ]]
     ["|" {:help true}
      ["env"]
      ["environement"]]
     ["|" {:handler create-environment}
      ["env|{*args}" ]
      ["environement|{*args}" ]]
     ["|"
      ["api|{*args}" {:fields [:id :asset-id :asset-version]
                      :handler create-api-instance}]]
     ["|"
      ["policy|{*args}" {:handler create-api-policy}]]
     ["|" {:handler invite-user
           :fields [:invited-email :status]}
      ["invitation|{*args}"]
      ["invite|{*args}"]]
     ["|" {:handler create-connected-app
           :fields [:client-id :client-secret :client-name]}
      ["connected-app|{*args}"]]]))
