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
                               parse-response default-headers org->id env->id app->id org->name load-session!] :as yc]
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
        ""
        "Options:"
        ""
        summary-options
        ""
        "Keys:"
        ""
        "  organization:"
        "   - v-cores:             prod:sandbox:design             (default: 0.1:0.1:0)"
        "   - parent:  "
        "  environment:"
        "   - type:               production|sandbox|design       (default: sandbox)"
        "  api:"
        "   - technology:         mule4|flexGateway               (default: mule4)"
        "   - proxy-uri:          listen URL                      (defaul: http://0.0.0.0:8081)"
        "   - uri:                upstream URL"
        "   - deployment-type:    deployment type ch20|rtf        (defaul: ch20)"   
        ""
        "Examples:"
        ""
        "# Create API instance "
        "  yaac create api -g T1 -a account-api -v 0.0.1  uri=https://httpbin.org"        
        ""
        "# Create API instance in the given org/env"
        "  yaac create api T1 Production -g T1 -a account-api -v 0.0.1 technology=mule4 uri=https://httpbin.org proxy-uri=http://0.0.0.0:8081"
        ""
        "# Create org named T1.1 in T1 with v-cores 0.1(Production), 0.3(Sandbox) and 0.0(Design)"
        "  yaac create org T1.1 --parent T1  v-cores=0.1:0.3:0.0"
        ""
        "# Create env named live in Production type"
        "  yaac create env T1.1 live type=production"        
        ""

        ""
        ""]
       (str/join \newline)))


(def options [["-g" "--group NAME" "Group name. Normally BG name"]
              ["-a" "--asset NAME" "Asset name"]
              ["-v" "--version VERSION" "Asset version"]
              ["-p" "--parent NAME" "Parent organization"]])


(defn create-organization [{:keys [parent
                                   v-cores
                                   args]
                            :or {v-cores ["0.0" "0.0" "0.0"]}
                            [org] :args
                            :as opts}]
  (if-not (and org parent)
    (throw (e/invalid-arguments "Org and its parent org needs to be specified" :args args))
    (let [parent-org-id (org->id parent)]
      (->> (http/post "https://anypoint.mulesoft.com/accounts/api/organizations"
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
      (->> (http/post (format "https://anypoint.mulesoft.com/accounts/api/organizations/%s/environments" org-id )
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
       :cloudhub2 "CH2"}
      (get (some-> x csk/->kebab-case-keyword) "CH2")))

(defn create-api-instance [{:keys [group asset version uri deployment-type proxy-uri label technology args]
                            [org env] :args
                            :as opts}]

  (log/debug "Upstream URI" uri)
  (log/debug "Proxy URI" (or proxy-uri "-"))
  (log/debug "Deployment type:" (or deployment-type "-"))
        
  (let [org (or org *org*)
        env (or env *env*)]
    (if-not (and org env uri)
      (throw (e/invalid-arguments "Org, Env, upstream URI and deployment-type needs to be specified, or set default." :args args))
      (let [proxy-uri (or (first proxy-uri) "http://0.0.0.0:8081") ;; it comes as array since '=' parameters
            upstream-uri (first uri) ;; it comes as array since '=' parameters
            dtype (deployment-targets (or (first deployment-type)
                                          (when *deploy-target* (keyword (first (str/split *deploy-target* #":"))))))
            org-id (org->id org)
            env-id (env->id org-id env)
            group-id (org->id (or group org *org*))]
        
      (log/debug "org:" org-id "env:" env-id "group:" group-id "deployment-type:" deployment-type)
      
        (->> (http/post (format "https://anypoint.mulesoft.com/apimanager/api/v1/organizations/%s/environments/%s/apis" org-id env-id)
                        {:headers (assoc (default-headers)
                                         "X-ANYPNT-ORG-ID" org-id
                                         "X-ANYPNT-ENV-ID" env-id)
                         :body (edn->json :camel {:technology (or technology "mule4"),
                                                  :instance-label (or label asset)
                                                  :spec {:group-id group-id,
                                                         :asset-id asset,
                                                         :version version}
                                                  :endpoint {:tls-contexts nil,
                                                             :response-timeout nil,
                                                             :type "rest",
                                                             :deployment-type dtype
                                                             :references-user-domain false,
                                                             :mule-version-4-or-above true,
                                                             :proxy-uri proxy-uri,
                                                             :uri upstream-uri,
                                                             :validation "DISABLED",
                                                             :proxy-template nil,
                                                             :is-cloud-hub false}
                                                  :endpoint-uri nil})
                         })
             (parse-response)
             :body)))))
