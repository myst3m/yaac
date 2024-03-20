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


(ns yaac.update
  (:require [silvur
             [util :refer [json->edn edn->json]]
             [http :as http]
             [log :as log]]
            [reitit.core :as r]
            [yaac.core :refer [*org* *env* parse-response default-headers org->id env->id api->id org->name load-session!] :as yc]
            [yaac.error :as e]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [yaac.incubate :as ic]
            [clojure.spec.alpha :as s]))


(defn usage [summary-options]
  (->> ["Usage: update <resources> [options]"
        ""
        "Update configurations of assets, apps and resources."
        ""
        "Options:"
        ""
        summary-options
        ""
        "Resources:"
        ""
        "  - app [org] [env] <app> key=val       ...   Required to specify group, artifact name and version."
        "  - asset -g <group> -a <asset> key=val ...   Required to specify group, artifact name and version."
        "  - api [org] [env] <api> key=val                   ...   Required to specify group, artifact name and version."
        "  - org [org] key=val                   ...   Required to specify group, artifact name and version."
        ""
        "Keys:"
        "  app"
        "    - v-cores"
        "    - replicas"
        "    - runtime-version"
        "    - state=<start|stop>"
        "  asset"
        "    - labels"
        "  api"
        "    - asset-version"
        "  org"
        "    - v-cores-production"
        "    - v-cores-sandbox"
        "    - network-connections"
        "    - static-ips"
        "    - vpns"
        ""
        "Example:"
        ""
        "# Add tags db, demo to specified asset"
        "  > yaac update app hello-world v-cores=0.2 replicas=2"
        ""
        "# Add tags db, demo to specified asset"
        "  > yaac update asset labels=db,demo -g T1 -a hello-api -v 0.1.0"
        ""
        "# Update API version with given asset version"
        "  > yaac update api hello-api asset-version=0.2.0"
        ""]
       (str/join \newline)))


(def options [["-g" "--group NAME" "Group name. Normally BG name"]
              ["-a" "--asset NAME" "Asset name"]
              ["-v" "--version VERSION" "Asset version"]])


(defn update-asset-config [{:keys [group asset version labels]
                            :as opts}]
  (if-not (and group asset version)
    (throw (e/invalid-arguments "Group, asset and version are required" {:group group :asset asset}))
    (let [group-id (org->id group)
          url (format "https://anypoint.mulesoft.com/exchange/api/v2/organizations/%s/assets/%s/%s/%s/mutabledata" group-id group-id asset version)
          multipart [{:name "tags" :content (str/join "," labels)}]]
      (-> (http/patch url {:headers (yc/multipart-headers)
                           :multipart multipart})
          (parse-response)))))

(defn update-api-config [{:keys [args asset-version] :as opts}]
  (let [asset-version (first asset-version)
        [api env org] (reverse args) ;; app has to be specified
        org (or org *org*)           ;; If specified, use it
        env (or env *env*)]
    (if-not (and org env api)
      (throw (e/invalid-arguments "Org, Env and Api need to be specified" {:args args}))
      (let [org-id (org->id org)
            env-id (env->id org env)
            api-id (api->id org env api)]
        (-> (http/patch (format "https://anypoint.mulesoft.com/apimanager/api/v1/organizations/%s/environments/%s/apis/%s" org-id env-id api-id)
                        {:headers (yc/default-headers)
                         :body (edn->json :camel {:asset-version asset-version})})
            (parse-response)
            :body)))))



(defn update-app-config [{:keys [args v-cores replicas runtime-version state all]}]
  (let [[app-name env org] (reverse args) ;; app has to be specified
        target-org-id (yc/org->id (or org *org*))           ;; If specified, use it
        target-env-id (yc/env->id target-org-id (or env *env*))
        apps (yc/name->apps target-org-id target-env-id app-name)]

    (if (and (not all) (< 1 (count apps)))
      (throw (e/multiple-app-name-found "Several apps found. Use -A if you want to update all" {:apps (mapv :name apps)}))
      (->> apps
           (mapcat (fn [app]
                     (cond
                       (= "SERVER" (-> app :target :type))
                       (do
                         (-> "https://anypoint.mulesoft.com/hybrid/api/v1/applications/%s"
                             (format (:id app))
                             (http/patch {:headers (assoc (default-headers)
                                                          "X-ANYPNT-ORG-ID" target-org-id
                                                          "X-ANYPNT-ENV-ID" target-env-id)
                                          :body (edn->json (cond-> {}
                                                             state (assoc-in [:desired-status] (condp = (keyword (str/lower-case (first state)))
                                                                                                 :start "STARTED"
                                                                                                 :stop "STOPPED"))))})
                             (yc/parse-response)
                             :body
                             :data
                             (yc/add-extra-fields :status #(get-in % [:target :status])
                                                  :name #(get-in % [:target :name])
                                                  :id #(get-in % [:target :id]))))
                       
                       (= "MC" (-> app :target :provider))
                       (do
                         (-> "https://anypoint.mulesoft.com/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments/%s"
                             (format target-org-id target-env-id (:id app))
                             (http/patch {:headers (yc/default-headers)
                                          :body (edn->json (cond-> {}
                                                             v-cores (assoc-in [:application :v-cores] (str (first v-cores)))
                                                             replicas (assoc-in [:target :replicas] (parse-long (first replicas)))
                                                             runtime-version (assoc-in [:target :deployment-settings :runtime-version] (first runtime-version))
                                                             state (assoc-in [:application :desired-state] (condp = (keyword (str/lower-case (first state)))
                                                                                                             :start "STARTED"
                                                                                                             :stop "STOPPED"))))})
                             (parse-response)
                             :body
                             (yc/add-extra-fields :status #(get-in % [:status])
                                                  :name #(get-in % [:name])
                                                  :id #(get-in % [:id]))
))
                       :else (throw (e/runtime-target-not-found "Not implemented")))))))))

(defn update-organization-config [{:keys [args v-cores-production v-cores-sandbox static-ips network-connections vpns]
                                   [org] :args}]
  (let [org-id (org->id (or org *org*))
        body (cond-> {:id org-id}
               v-cores-production (assoc-in [:entitlements :v-cores-production :assigned] (parse-double (first v-cores-production)))
               v-cores-sandbox (assoc-in [:entitlements :v-cores-sandbox :assigned] (parse-double (first v-cores-sandbox)))
               static-ips (assoc-in [:entitlements :static-ips :assigned] (parse-long (first static-ips)))
               network-connections (assoc-in [:entitlements :network-connections :assigned] (parse-long (first network-connections)))
               vpns (assoc-in [:entitlements :vpns :assigned] (parse-long (first vpns))))]
    (->> (http/put (format "https://anypoint.mulesoft.com/accounts/api/organizations/%s" org-id)
                   {:headers (default-headers)
                    :body (edn->json body)})
         (parse-response)
         :body)))
