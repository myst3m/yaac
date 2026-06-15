(ns yaac.core.routing
  "CLI routing table for `yaac list / ls / get` subcommands.

  Extracted from yaac.core to keep core.clj focused on HTTP/auth/resource
  primitives. The handlers themselves still live in yaac.core; this namespace
  is purely the dispatch table."
  (:require [yaac.core :as yc]
            [yaac.core.alerts :as alerts]
            [yaac.core.cloudhub2 :as ch2]
            [yaac.core.gateway :as gw]
            [yaac.core.identity :as identity]
            [yaac.core.metrics :as metrics]
            [yaac.core.policy :as policy]))

(def ^:private route-body
  [["" {:help true}]
   ["|-h" {:help true}]
   ["|" {:fields [:name [:id :fmt yc/short-uuid] :parent-name]
         :formatter yc/format-org-all
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
   ["|" {:fields [[:organization-id :fmt yc/short-uuid] [:group-id :fmt yc/short-uuid] [:extra :group-name] :asset-id  :type :version]
         :handler yc/get-assets}
    ["asset"]
    ["asset|{*args}"]]


   ;; Get proxy
   ["|" {:fields [[:organization-id :fmt yc/short-uuid] [:environment-id :fmt yc/short-uuid] [:id :fmt yc/short-uuid] :application-name :type :target-type :target-name ]
         :handler yc/get-api-proxies}
    ["proxy"]
    ["proxy|{*args}"]]

   ;; Get apps
   ["|" {:fields [[:extra :org]
                  [:extra :env]
                  :name
                  [:id :fmt yc/short-uuid]
                  [:extra :status]
                  [:application :status :as "applied"]
                  [:extra :target]]

         :wide-fields [[:extra :org]
                       [:extra :env]
                       :name
                       [:id :fmt yc/short-uuid]
                       [:extra :status]
                       [:application :status :as "applied"]
                       [:application :ref :version :as "version"]
                       [:application :v-cores]
                       [:target :replicas]
                       [:extra :target]]

         :handler yc/get-deployed-applications}
    ["app"]
    ["app|{*args}"]
    ["application"]
    ["application|{*args}"]]


   ;; Get runtime fabrics
   ["|" {:fields [:name [:id :fmt yc/short-uuid] :status :desired-version :vendor :region]
         :handler gw/get-runtime-fabrics}
    ["rtf"]
    ["rtf|{*args}"]
    ["runtime-fabric"]
    ["runtime-fabric|{*args}"]]

   ;; Get runtime targets
   ["|" {:fields [:name :type [:id :fmt yc/short-uuid] :region :status]
         :handler yc/get-runtime-targets}
    ["rtt"]
    ["rtt|{*args}"]
    ["runtime-target"]
    ["runtime-target|{*args}"]]


   ;; Get servers
   ["|" {:fields [:name [:id :fmt yc/short-uuid] :mule-version :agent-version :status
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
   ["|" {:fields [[:id :fmt yc/short-uuid] :name :status :region]
         :handler ch2/get-cloudhub20-privatespaces}
    ["ps" ]
    ["ps|{*args}"]
    ["private-space"]
    ["private-space|{*args}"]]

   ;; Get secret groups
   ["|" {:fields [[:meta :id :fmt yc/short-uuid] :name [:meta :locked] :_org :_env]
         :handler yc/get-secret-groups}
    ["sg"]
    ["sg|{*args}"]
    ["secret-group"]
    ["secret-group|{*args}"]]

   ;; Get apis
   ["|" {:fields [[:id :fmt yc/short-uuid] :asset-id :exchange-asset-name :status :technology
                  :product-version :asset-version]
         :handler yc/get-api-instances}
    ["api"]
    ["api|{*args}"]
    ["api-instance"]
    ["api-instance|{*args}"]]

   ;; All Flex Gateways (standalone + managed)
   ["|" {:fields [[:id :fmt yc/short-uuid] [:extra :org] [:extra :env] :name :status :source]
         :handler gw/get-gateways}
    ["gw"]
    ["gw|{*args}"]
    ["gateway"]
    ["gateway|{*args}"]]

   ;; Get enttitlements
   ["|" {:handler ch2/get-entitlements
         :fields [:name [:extra :id :fmt yc/short-uuid]
                  [:extra :v-cores-production :as "production"]
                  [:extra :v-cores-sandbox :as "sandbox"]
                  [:extra :static-ips :as "static-ips"]
                  [:extra :network-connections :as "connections"]
                  [:extra :vpns :as "vpns"]
                  [:extra :managed-gateway-large :as "fgw-large"]
                  [:extra :managed-gateway-small :as "fgw-small"]]}
    ["entitlement"]
    ["entitlement|{*args}"]
    ["ent"]
    ["ent|{*args}"]]


   ;; Get available node ports
   ["|" {:handler ch2/get-available-node-ports}
    ["node-port"]
    ["node-port|{*args}"]
    ["np"]
    ["np|{*args}"]]

   ;; Contracts
   ["|" {:fields [[:application :name] [:id :fmt yc/short-uuid] :status [:api-id :fmt yc/short-uuid] [:extra :api-name]]
         :handler yc/get-api-contracts}
    ["contract"]
    ["contract|{*args}"]
    ["cont"]
    ["cont|{*args}"]]

   ["|" {:fields [:client-name :grant-types]
         :handler yc/get-connected-applications}
    ["connected-app"]
    ["connected-app|{*args}"]
    ["capp"]
    ["capp|{*args}"]
    ["ca"]
    ["ca|{*args}"]]

   ;; Get available scopes
   ["|" {:fields [:scope :type]
         :handler yc/get-available-scopes
         :no-token true}
    ["scope"]
    ["scope|{*args}"]
    ["scopes"]
    ["scopes|{*args}"]]

   ["|" {:fields [:username [:id :fmt yc/short-uuid] :email [:extra :idp]]
         :handler yc/get-users}
    ["user"]
    ["user|{*args}"]]

   ["|" {:fields [:team-name [:team-id :fmt yc/short-uuid] :team-type [:org-id :fmt yc/short-uuid]]
         :handler yc/get-teams}
    ["team"]
    ["team|{*args}"]]

   ;; Team members
   ["|" {:fields [:name [:id :fmt yc/short-uuid] :identity-type :membership-type [:extra :team]]
         :handler yc/get-team-members}
    ["team-member"]
    ["team-member|{*args}"]
    ["tm"]
    ["tm|{*args}"]]

   ;; Team roles
   ["|" {:fields [:name [:role-id :fmt yc/short-uuid] :context-params [:extra :team]]
         :handler yc/get-team-roles}
    ["team-role"]
    ["team-role|{*args}"]]

   ;; Roles (permission sets; deprecated in favor of teams)
   ["|" {:fields [:name [:role-id :fmt yc/short-uuid] :description]
         :handler yc/get-roles}
    ["role"]
    ["role|{*args}"]]

   ["|" {:handler ch2/get-cloudhub20-connections}
    ["conn"]
    ["conn|{*args}"]
    ["connection"]
    ["connection|{*args}"]
    ["vpn"]
    ["vpn|{*args}"]]

   ["|" {:handler policy/get-api-policies
          :fields [[:extra :asset-id] [:extra :version] [:extra :group-id]
                   [:extra :id :fmt yc/short-uuid] [:extra :type] [:extra :order]
                   [:extra :disabled] [:extra :config]]}
    ["policy"]
    ["policy|{*args}"]
    ["pol"]
    ["pol|{*args}"]]

   ;; IDP
   ["|" {:handler identity/get-identity-providers
         :fields [ :name [:extra :id :fmt yc/short-uuid] [:extra :org] [:extra :type]]}
    ["idp"]
    ["idp|{*args}"]]

   ;; Client Providers (OpenID Connect client management)
   ["|" {:handler identity/get-client-providers
         :fields [:name [:extra :id :fmt yc/short-uuid] [:extra :org] [:extra :type]]}
    ["cp"]
    ["cp|{*args}"]
    ["client-provider"]
    ["client-provider|{*args}"]]

   ;; Metrics
   ["|" {:handler metrics/get-metrics
         :fields [:name :label :description]}
    ["metrics"]
    ["metrics|{*args}"]]

   ;; Alerts (--type api|app|server)
   ["|" {:handler alerts/get-alerts
         :fields [:alert-type :alert-id :alert-name :name :severity :alert-state :state :metric-type
                  [:extra :api-id] [:extra :app-id] [:extra :resource-type]]}
    ["alert"]
    ["alert|{*args}"]]])

(def route
  (for [op ["list" "ls" "get"]]
    (into [op {:options yc/options :usage yc/usage}] route-body)))
