;   Copyright (c) Tsutomu Miyashita. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns yaac.core
  (:gen-class)
  (:import [java.util.zip ZipInputStream ZipEntry]
           [java.io FileInputStream]
           [java.nio.charset Charset])
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.cli :refer (parse-opts)]
            [clojure.zip :as z]
            [clojure.tools.cli :refer [parse-opts]]
            [muuntaja.core :as m]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [taoensso.nippy :as nippy]
            [zeph.client :as http]
            [silvur.nio :as nio]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [yaac.util :refer [json->edn edn->json]]
            [taoensso.timbre :as log]
            [jsonista.core :as json]
            [yaac.util :as yutil]
            [yaac.util :as util]
            [yaac.error :as e]
            [clojure.data.xml :as dx]
            [clojure.set :as set]
            [yaac.yaml :as yaml]
            [clojure.core.async :as async :refer [go <!!]]))


(def ^:dynamic *org*)
(def ^:dynamic *env*)
(def ^:dynamic *deploy-target*)
(def ^:dynamic *no-cache* false)
(def ^:dynamic *no-multi-thread*)
(def ^:dynamic *console*)
(def mule-business-group-id "68ef9520-24e9-4cf2-b2f5-620025690913")
(def global-base-url "https://anypoint.mulesoft.com")

(defmacro try-wrap [& body]
  `(try
     ~@body
     (catch Exception e# )))

(defn set-global-base-url [url]
  (->> (condp = url
         "hyperforce" "https://jp1.platform.mulesoft.com"
         "https://anypoint.mulesoft.com")
       (constantly)
       (alter-var-root #'global-base-url)))


(defn memoize-file [f]
  (let [memo-file (io/file (System/getenv "HOME") ".yaac" "cache")
        memo-key (str/replace (str f) #"@.*" "")]
    (letfn [(store-cache [f & args]
              (let [ret (apply f args)]
                (io/make-parents memo-file)
                ;; Read current cache and merge to avoid race condition overwrites
                (let [current-cache (if (.exists memo-file)
                                      (try (read-string (slurp memo-file))
                                           (catch Exception _ {}))
                                      {})]
                  (spit memo-file (assoc-in current-cache [memo-key args] ret)))
                ret))]
      (memoize
       (fn [& args]
         (if (true? *no-cache*)
           (apply f args)
           (if (and (.exists memo-file) (not *no-cache*))
             (let [cache (read-string (slurp memo-file))]
               (log/debug "cache file:" (str memo-file))
               (if-let [cached-ret (get-in cache [memo-key args])]
                 (do (log/debug "cache hit:" memo-key)
                     cached-ret)
                 (do (log/debug "cache miss:" memo-key)
                     (apply store-cache f args))))
             (apply store-cache f args))))))))

(defmacro on-threads [no-multi-thread? & body]
  (let [xs (->> body (mapv #(list 'go (list 'try % (list 'catch 'Exception 'e [(list 'assoc (list 'ex-data 'e) :error true)])))))]
    `(if-not ~no-multi-thread?
       (let [results# (reduce (fn [r# x#] (vec (concat r# (<!! x#)))) [] ~xs)
             errs# (->> (filter :error results#)
                        (map #(dissoc % :error)))]

         (if (seq errs#)
           (do
             (throw (ex-info "Errors in threads" (e/multi-errors errs#))))
           results#))
       (<!! (go (concat ~@body))))))



(defn gen-url [& url-paths]
  (str/join (map (fn [x] (if (re-find #"/$" x) (subs x 0 (dec (count x))) x)) (cons global-base-url url-paths))))


(defn slurp-pom-file [jar-path]
  (with-open [zis (ZipInputStream. (FileInputStream. (io/file jar-path)))]
    (let [pom-entry (first (filter #(re-find #".*/pom.xml" (.getName ^ZipEntry %)) (repeatedly #(.getNextEntry zis))))
          pom-content (loop [buf (byte-array 4096)
                             s ""]
                        (let [size (.read zis buf)]
                          (if (not= -1 size)
                            (recur buf (str s (String. buf 0 size)))
                            s)))]
      pom-content)))

(def default-credential {})
(def default-credentials-path (str/join  java.io.File/separator
                                        [(System/getenv "HOME") ".yaac" "credentials"]))
(def default-session-path (str/join  java.io.File/separator
                                        [(System/getenv "HOME") ".yaac" "session"]))

(defn set-session! [{:keys [access-token] :as m}]
  (alter-var-root #'default-credential (constantly m)))

(defn parse-response
    ([message]
     (parse-response :kebab message))
    ([csk-type {:keys [body status] :as message}]
     (cond (or (> 200 status) (<= 400 status))
           (let [raw (json->edn csk-type body)]
             (throw (ex-info (or (:message raw) "Request failed")
                             {:status status
                              :message (or (:message raw) "Request failed")
                              :raw raw})))
           :else {:status status :body (json->edn csk-type body)})))

(defn default-headers []
  {"Content-Type" "application/json"
   "Authorization" (str "Bearer " (:access-token default-credential))})

(defn multipart-headers []
  {"Authorization" (str "Bearer " (:access-token default-credential))
   "Content-Type" "multipart/form-data"})

(defn load-credentials
  ([]
   (cske/transform-keys (fn [x] (keyword (str/replace (name x) #"_" "-")))(json->edn :raw (slurp yaac.core/default-credentials-path))))
  ([cname]
   (if-let [k (keyword cname)]
     (if-let [ctx (k (load-credentials))]
       ctx
       (throw (e/invalid-credentials "No credentials" {:context  cname})))
     (throw (e/invalid-credentials "Specified argument is NULL" {:arg cname})))))

(defn -store-credentials! [name id secret grant-type scope]
  (let [cred-file (io/file default-credentials-path)]
    (spit cred-file (yutil/json-pprint (cond-> (assoc-in (if (.exists cred-file)
                                                       (json->edn :raw (slurp cred-file))
                                                       {})
                                                     [name] {"client_id" id
                                                             "client_secret" secret
                                                             "grant_type" grant-type
                                                             })
                                     (seq scope) (assoc-in  [name "scope"] (or scope "full")))))))


(defn load-session! []
  (let [cred-file (io/file default-session-path)]
    (if (.exists cred-file)
      (-> (nippy/thaw-from-file cred-file)
          (set-session!)
            (dorun))
      (throw (e/no-session-store "No session store" {:file (str cred-file)})))))

(defn store-session! [token]
  (doto  (io/file default-session-path)
    (io/make-parents)
    (nippy/freeze-to-file token))
  token)


(defn raw-get [url]
  (-> @(http/get url {:headers (default-headers)})
      (parse-response)))


(defn -get-me []
  (-> @(http/get (gen-url "/accounts/api/me")
                 {:headers (default-headers)})
      (parse-response)))

(defn get-me []
  (-get-me))

(def -get-me (memoize-file -get-me))

(def graphql-query-options-group
  {:assets [:organization-ids "comma separated IDs or names"
            :public "boolean"
            ;; :categories 'comma-separated-strings
            ;; :customFields 'comma-separated-strings
            ;; :master-organization-id 'comma-separated-strings
            :search-term "string"
            :types '(or "app" "example" "template" "rest-api" "soap-api")
            :offset "number"
            :limit "number (default: 30, max: 250)"
            :min-mule-version "string"
            ;; :is-generated 'boolean
            ;; :extension-model []
            :labels "comma separated strings"
            :latest-versions-only "boolean"
            ;; :shared-with-me []

            ]
   :asset [:asset-id "string"
           :group-id "string"
           :version "string"]})

(def graphql-result-fields [:organization-id
                            :group-id
                            :asset-id
                            :name
                            :labels
                            :type
                            :version
                            :is-public
                            :status])

;; GET
(defn usage [opts]
  (let [{:keys [summary help-all]} (if (map? opts) opts {:summary opts :help-all true})]
    (->> (concat
          ["Usage: get <resource> ..."
           ""
           "Options:"
           ""
           summary
           ""]
          (when help-all
            ["Resources:"
             ""
             "  - organization                          Get organizations"
             "  - environment [org]                     Get environements"
             "  - application [org] [env]               Get deployed application"
             "  - api [org] [env]                       Get API instances"
             "  - contract [org] [env]                  Get contracts"
             "  - runtime-fabric [org]                  Get Runtime Fabric clusters"
             "  - server [org] [env]                    Get servers (onpremise)"
             "  - private-space  [org] [env]            Get CloudHub 2.0 Private Spaces "
             "  - secret-group [org] [env]              Get Secret Groups"
             "  - asset [org] key1=val1 key2=val2 ...   Get assets in Anypoint Exchange"
             "  - user [org]                            Get users that belong to the organization"
             "  - team                                  Get teams in the root organization"
             "  - connected-app                         Get connected applications"
             "  - runtime-target [org] [env]            Get runtime targets of RTF and CloudHub 2.0"
             "  - gateway [org] [env]                   Get Flex Gateways (standalone + managed)"
             "  - policy [org] [env] api                Get Policies"
             "  - entitlement                           Get entitilements for each runtime"
             "  - idp                                   Get External IdP"
             "  - client-provider                       Get Client Providers (OpenID Connect)"
             "  - node-port [org]                       Get available node ports for apps using TCP"
             "  - metrics [org] [env]                   Get metrics from Anypoint Monitoring"
             ""
             "Exchange Graph API Keys:"
             ""
             (str/join \newline (->> (partition 2 (:assets graphql-query-options-group))
                                  (map #(format "  - %-25s %-10s" (name (first %)) (second %)))
                                  (seq)))
             ""
             "Fields:"
             ""
             (str "  " (str/join " | " (map name graphql-result-fields)))
             ""])
          ["Example:"
           ""
           "# Get organizations"
           "  > yaac get org"
           ""
           "# Get applications in org/env"
           "  > yaac get app T1 Production"
           ""
           "# Get assets with custom fields"
           "  > yaac get asset T1 -F group-id,asset-id,name"
           ""
           "# Get metrics"
           "  > yaac get metrics T1 Production --type app-inbound --from 1h"
           ""])
         (str/join \newline))))

(def options [["-g" "--group NAME" "For asset query. Same as organization_ids=ID"]
              ["-a" "--asset NAME" "For asset query. Asset name"]
              ["-v" "--version VERSION" "For asset query. Asset version"]
              ["-q" "--search-term STRING" "Query string. Same as search-term=STRING"
               :parse-fn #(str/split % #",")]
              ["-A" "--all" "Query assets in all organizations or all applications"]
              ["-F" "--fields FIELDS" "Fields for assets list"
               :parse-fn #(mapv csk/->kebab-case-keyword (str/split % #","))]
              ;; Metrics options
              [nil "--type TYPE" "Predefined metric type (app-inbound, app-inbound-response-time, app-outbound, api-path, api-summary)"
               :id :type]
              [nil "--describe METRIC" "Describe metric structure"
               :id :describe]
              [nil "--query AMQL" "Raw AMQL query"
               :id :query]
              [nil "--start TIMESTAMP" "Start timestamp (Unix ms or ISO8601)"
               :id :start]
              [nil "--end TIMESTAMP" "End timestamp (Unix ms or ISO8601)"
               :id :end]
              [nil "--from DURATION" "Relative start time (e.g., 1h, 30m, 1d)"
               :id :from]
              [nil "--duration DURATION" "Duration from start (e.g., 30m, 1h)"
               :id :duration]
              [nil "--aggregation FUNC" "Aggregation function (count, sum, avg, max, min)"
               :id :aggregation]
              [nil "--app-id ID" "Filter by application ID"
               :id :app-id]
              [nil "--api-id ID" "Filter by API ID"
               :id :api-id]
              [nil "--group-by DIMS" "Group by dimensions (comma-separated)"
               :id :group-by
               :parse-fn #(str/split % #",")]
              [nil "--limit N" "Result limit"
               :id :limit
               :parse-fn parse-long]
              [nil "--offset N" "Result offset"
               :id :offset
               :parse-fn parse-long]])

;; (defn get* [{:keys [args] :as opts}]
;;   (let []
;;     (println (get-usage summary))))

(defn- -get-organizations []
  (-> (-get-me) :body :user :member-of-organizations))

(defn- -get-environments [org]
  (let [org-id (and org (:id (first (filter #(= (name org) (:name %)) (-get-organizations)) )))]
    (if org-id
      (-> @(http/get (format (gen-url "/accounts/api/organizations/%s/environments")
                             org-id)
                     {:headers (default-headers)})
          (parse-response)
          :body
          :data)
      (throw (e/org-not-found "Not found organization" :org (or org-id org))))))

(defn -get-root-organization []
  (first (filter :is-root (-get-organizations))))

(def -get-environments (memoize-file -get-environments))

;; Forward declarations for -A flag support
(declare add-extra-fields get-assets -get-deployed-applications -get-api-instances
         -get-gateways -get-connected-applications -get-users org->id)

;; Exposed  get-* functions

;;; allow to receive option map for abstraction
(defn get-organizations [{:keys [args all] :as opts}]
  (let [[org] args
        org (or org *org*)]
    (if all
      ;; -A フラグ: BG配下の全環境から全リソース取得
      ;; Return as list for consistent CLI processing
      (do
        (when-not org
          (throw (e/invalid-arguments "Org required for -A" {:args args})))
        (let [envs (-get-environments org)
              org-envs (mapv (fn [{e :name}] [org e]) envs)]
          [{:environments envs
            :apps (->> org-envs
                       (pmap (fn [[g e]]
                               (try (add-extra-fields (-get-deployed-applications g e) :org g :env e)
                                    (catch Exception _ []))))
                       (apply concat) vec)
            :assets (try (get-assets {:group org}) (catch Exception _ []))
            :apis (->> org-envs
                       (pmap (fn [[g e]]
                               (try (add-extra-fields (-get-api-instances g e) :org g :env e)
                                    (catch Exception _ []))))
                       (apply concat) vec)
            :gateways (->> org-envs
                           (pmap (fn [[g e]]
                                   (try (add-extra-fields (-get-gateways g e) :org g :env e)
                                        (catch Exception _ []))))
                           (apply concat) vec)
            :connected-apps (-get-connected-applications)
            :users (-get-users (org->id org))}]))
      ;; 既存ロジック（組織一覧のみ）
      (-get-organizations))))

(defn get-environments [{:keys [args all] :as opts}]
  (let [[org env] (case (count args)
                    0 [*org* *env*]
                    1 [(first args) nil]
                    [(first args) (second args)])
        org (or org *org*)
        env (or env *env*)]
    (if all
      ;; -A フラグ: 環境内の全リソース取得
      (do
        (when-not (and org env)
          (throw (e/invalid-arguments "Org and Env required for -A" {:args args})))
        {:apps (add-extra-fields (-get-deployed-applications org env) :org org :env env)
         :apis (add-extra-fields (-get-api-instances org env) :org org :env env)
         :gateways (add-extra-fields (-get-gateways org env) :org org :env env)})
      ;; 既存ロジック（環境一覧のみ）
      (-get-environments org))))


;; Org/Env name does not require to throw exception since it is not used for query.
;;

(defn org->name [id-or-name]
  (let [xs (-get-organizations)]
    (last (first (filter #((set %) id-or-name) (map (juxt :id :name) xs))))))

(defn env->name [org-id-or-name id-or-name]
  (let [xs (-get-environments (org->name org-id-or-name))]
    (last (first (filter #((set %) id-or-name) (map (juxt :id :name) xs))))))

;; No throw exception for get functions so as to query OOB assets
(defn org->id* [id-or-name]
  (or (->> (-get-organizations)
        (map (juxt :id :name))
        (filter #((set %) id-or-name) )
        (ffirst ))
      id-or-name))

(defn org->id [id-or-name]
  (or (org->id* id-or-name)
      (throw (e/org-not-found "Not found organization" :org id-or-name))))



(defn env->id [org-id-or-name id-or-name]
  (let [xs (-get-environments (org->name org-id-or-name))
        env-id (ffirst (filter #((set %) id-or-name) (map (juxt :id :name) xs)))]
    (or env-id (throw (e/env-not-found "Not found environement" :org org-id-or-name :env id-or-name)))))

(defmulti map->graphql (fn [{:keys [asset-id group-id]}]
                         (if (and asset-id group-id)
                           :asset
                           :assets)))

;; group-id can be handled, but if asset-id also is given, use :asset for the specific request for the asset
(defmethod map->graphql :assets [{:keys [organization-ids labels all group-id]
                                  [org] :args
                                  :as opts}]
  (let [default-opts {:limit "30" :latest-versions-only "true"}
        cooked-opts (if all
                      (assoc opts :organization-ids [])
                      (->> (concat (:organization-ids opts) 
                                   (if group-id
                                     [group-id]  ; group-id is already converted to ID  
                                     [(or org *org*)]))
                          (keep identity )
                          (mapv org->id)  ; org->id handles IDs safely
                          (assoc opts :organization-ids )))]

    (log/debug "Search options: " (dissoc cooked-opts :summary))
    ;; When Only asset-name is given, it is informed to users 
    (when (:organization-ids cooked-opts)
      (doto (str "{assets("
                 (->> (reduce (fn [s [k v]]
                                ;; In order to normalize [] and string, it converts to array
                                (let [vs (flatten [v])]
                                  (log/debug k vs)
                                  (conj s
                                        (->> (cond
                                               (#{:offset :limit} k) [(csk/->camelCaseString k) (parse-long (first vs))]
                                               (#{:public :is-generated :latest-versions-only :shared-with-me} k) [(csk/->camelCaseString k) (parse-boolean v)]
                                               (#{:organization-ids} k) [(csk/->camelCaseString k) (str "[" (str/join "," (map pr-str (map org->id vs))) "]")]
                                               (#{:labels :types} k) [(csk/->camelCaseString k) (str "[" (str/join "," (map pr-str vs)) "]")]
                                               (#{:search-term} k) [(csk/->camelCaseString k) (pr-str (first vs))]
                                               :else (str/join "," [(csk/->camelCaseString k) (map pr-str vs)]))
                                             (str/join ": ")))))
                              
                              []
                              (conj default-opts (select-keys cooked-opts (:assets graphql-query-options-group))))
                      (str/join ","))
                 
                 ") "
                 (str "{" (reduce (fn [s x]
                                    (str s (csk/->camelCaseString x) " "))
                                  ""
                                  graphql-result-fields)
                      "}")
                 "}")
        (log/debug)))))

;; To be refactored.
(defmethod map->graphql :asset [{:keys [group-id asset-id version] :as opts}]
  (let [query-body (str "{asset("
                        (reduce (fn [s [k v]]
                                  (str s
                                       (str (csk/->camelCaseString k) ": " (str "\"" v "\"" ))
                                       " "))
                                ""
                                (select-keys opts graphql-result-fields))
                        ") "
                        
                        "{"
                        ;; For current version
                        (reduce (fn [s x]
                                  (str s (csk/->camelCaseString x) " "))
                                ""
                                graphql-result-fields)
                        " "
                        ;; For older versions
                        "otherVersions "
                        (str "{" (reduce (fn [s x]
                                           (str s (csk/->camelCaseString x) " "))
                                         ""
                                         graphql-result-fields)
                             "}")
                        "}"
                        "}")]
    query-body))

(defmethod map->graphql :default [m & ks]
  (println "Not matched parameters.")
  (println "Given params:" m)
  (println "Checks with:" ks)
  (println "Capable param")
  (println "  assets: "  (take-nth 2 (:assets graphql-query-options-group)))
  (println "  asset:  "  (take-nth 2 (:asset graphql-query-options-group))))

(defn graphql-search-assets [{:keys [group-id ] :as query-map}]
  ;; Mule API has 2 types of graphql query options.
  ;; 1. assets : query assets by Org IDs mainly -> response is in [:assets]
  ;; 2. asset  : query 1 asset for each version mainly -> response is in [:asset :other-version]
  
  (if-let [gql (map->graphql query-map)]
    (-> @(http/post (gen-url "/graph/api/v2/graphql")
                    {:headers (default-headers)
                     :body (edn->json {:query gql
                                       :variables {:accessToken (:access-token default-credential)}})})
      (parse-response)
      :body
      :data
      (as-> result
          (keep identity (or (:assets result)
                             (cons (dissoc (-> result :asset) :other-versions)
                                   (-> result :asset :other-versions))))))
    (throw (e/invalid-arguments "Invalid query options. Hint: it is required to specify the group/organization-ids" (dissoc query-map :summary)))))

(defn count-visual-width [s]
  (let [asciis (filter  #(<= 32 % 126) (.getBytes s))
        others-count (- (count s) (count asciis))
        others-byte-length (- (count (.getBytes s)) (count asciis))]
    ;; To be fixed.
    ;; Ex
    ;; データベースアクセスAPI
    ;; => count:  13 (10+3)
    ;; => visual: 23 (2*10+3)
    ;; => bytes:  33 (3*10+3)
    ;;
    ;; Assume all 2 byte visually
    {:data s :count (count s) :ascii (count asciis) :others-byte-length others-byte-length :others others-count :visual-width (+ (* 2 others-count) (count asciis))}))


(defn count-visual-widths-list [key-fields data]
  (->> key-fields
       (map (fn [k]
              (let [ks (flatten [k])
                    ukey (take-while #(not= :as %) ks)
                    [title] (reverse (drop-while #(not= :as %) ks))]
                
                [ukey
                 (->> data
                      (map (apply comp (reverse ukey))) ;; see below to know why is reverse being used .
                      (map (comp count-visual-width str)))
                 (or title (name (last ukey)))])))))

(defn default-format-by [fields output-type data {:keys [no-header] :as opts}]
  (log/debug "fields:" fields)
  (log/debug "output-type:" output-type)
  (log/debug "first data:" (first data))
  (log/debug "options:" (dissoc opts :summary))

  (cond
    (sequential? data)
    (let [data (keep #(if (instance? clojure.lang.ExceptionInfo %) (ex-data %) %) data) ;; Anypoint reponses [null] for get proxy...
          count-alist (count-visual-widths-list fields data)
          ;; ukeys are uniformed key  [[:id] [:name] [:status :application] [:status] [:last-modified-date] [:target-id :target]]
          ukeys (map (comp vec first) count-alist)]

      (log/debug "Keys:" ukeys)
      (log/debug "Counts: " count-alist)

      (case output-type
        :json (yutil/json-pprint (mapv #(dissoc % :extra) data))
        :edn (with-out-str (clojure.pprint/pprint (mapv #(dissoc % :extra) data)))
        :yaml (yaml/generate-string (mapv #(dissoc % :extra) data))
        (let [count-map (into {} (map #(vec (take 2 %)) count-alist))
              title-map (into {} (map #(vector (first %) (last %)) count-alist))]
          ;; count-alist [[:id] [{...}] :title-id]
          ;; title-map: {(:id) id, (:name) name, (:entitlements :v-cores-production :assigned) production, (:entitlements :v-cores-sandbox :assigned) assigned, (:entitlements :static-ips :assigned) assigned, (:entitlements :network-connections :assigned) assigned, (:entitlements :vpns :assigned) assigned}
          
          (log/debug count-map)
          (letfn [(column-max-width [k]
                    (apply max (keep identity (cons (or (count (name (get title-map k "")))
                                                        (-> k last name count))
                                                    (map :visual-width (get count-map k))))))]
            
            (->> count-alist
                 (reduce (fn [out [k vs]]
                           ;;[k vs] => [(:extra :id) [{...}...]
                           (let [max-column-width (column-max-width k)]
                             ;; header + data
                             (->> (conj out
                                        (cond->> (count-map k)
                                          :always (mapv (fn [{:keys [others data ascii count visual-width ]}]
                                                          (let [data (if (seq data) data "-")]
                                                            (if (= others 0)
                                                              (format (str "%-" (+ max-column-width) "s  ") data)
                                                              (str data (str/join (repeat (- max-column-width visual-width) " ")) "  ")))))
                                          ;; Header
                                          (not no-header) (cons (format (str "%-" max-column-width "s  ") (str/upper-case (name (title-map k)))))
                                          :always (vec)))
                                  (vec))))
                         [])
                 (apply map (comp str/trim str))
                 (str/join \newline)
                 ((fn [s] (str s \newline))))))))
    
    (map? data)
    (default-format-by fields output-type [data] opts)

    :default
    (do (log/debug "default-format-by" data)
        data)))

(defn format-org-all
  "Custom formatter for `get org -A` - displays each category as separate table"
  [output-format data opts]
  (let [first-item (first data)]
    (if (and (map? first-item) (contains? first-item :environments))
      ;; -A mode: multiple categories (data is [{:environments [...] :apps [...] ...}])
      (let [all-data first-item]
        (cond
          (= output-format :json) (yutil/json-pprint all-data)
          (= output-format :edn) (with-out-str (clojure.pprint/pprint all-data))
          (= output-format :yaml) (yaml/generate-string all-data)
          ;; Default: separate tables for each category
          :else
          (let [sections [{:key :environments :fields [[:name] [:id] [:type]] :title "Environments"}
                          {:key :apps :fields [[[:extra :env]] [:name] [:status] [[:application :status] :as "app-status"]] :title "Apps"}
                          {:key :assets :fields [[:asset-id] [:type] [:version]] :title "Assets"}
                          {:key :apis :fields [[[:extra :env]] [:asset-id] [:asset-version]] :title "APIs"}
                          {:key :gateways :fields [[[:extra :env]] [:name] [:id]] :title "Gateways"}
                          {:key :connected-apps :fields [[:client-name] [:client-id]] :title "Connected Apps"}
                          {:key :users :fields [[:first-name] [:last-name] [:email]] :title "Users" :filter-fn #(seq (:email %))}]]
            (->> sections
                 (keep (fn [{:keys [key fields title filter-fn]}]
                         (when-let [items (seq (cond->> (get all-data key)
                                                 filter-fn (filter filter-fn)))]
                           (str "== " title " ==\n"
                                (default-format-by fields :short items opts)))))
                 (str/join "\n")))))
      ;; Normal mode: list of orgs (data is [{:name ... :id ...} ...])
      (default-format-by [[:name] [:id] [:parent-name]] output-format data opts))))

(defn add-extra-fields [coll & {:as kvs}]
  (log/debug "extra fields: " kvs)
  (log/debug "data:" coll)
  (->> (flatten [coll])
       (mapv #(assoc % :extra (->> kvs
                                   (mapv (fn [[k vf]]
                                           [k (if (instance? clojure.lang.IFn vf)
                                                (vf %)
                                                vf)]))
                                   (into {})
                                   (doall))))))

(defn get-assets [{:keys [group asset version args all]
                   [org] :args
                   :as opts}]
  ;; (when-not (or (every? identity [group asset]) (every? not [group asset]))
  ;;   (throw (e/invalid-arguments "A group and a name should be given both, or neighter." {:group group :asset asset})))
  (let [group-id (when group (org->id group))]
    ;; Added group-id and asset-id as keys so as to query by asset(...)
    (-> (graphql-search-assets (cond-> opts
                                 group (assoc :group-id group-id)
                                 asset (assoc :asset-id asset)))
        (->> (filter #(or all 
                         ;; If group is specified, use group-id for filtering
                         (if group
                           (= (:group-id %) group-id)
                           ;; Otherwise use the original logic
                           (and (= (:organization-id %) (org->id (or org *org*)))
                                (= (:group-id %) (or (try-wrap (org->id org))
                                                    (try-wrap (org->id *org*)))))))))
        (add-extra-fields :group-name #(org->name (:group-id %)))
        (->> (map #(assoc % :organization-name (or (org->name (:organization-id %)) "-")))))))



(declare target->name)


(defn -get-container-applications [org env]
  (when (and org env)
    (let [org-id (org->id org)
          env-id (env->id org env)]
      (-> @(http/get (format (gen-url "/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments")  org-id env-id)
                    {:headers (default-headers)})
          (parse-response)
          :body
          :items
          (add-extra-fields :org org :env env
                            :status #(get-in % [:application :status])
                            :target #(target->name org-id env-id (get-in % [:target :target-id])))))))


;; --- Flex Gateway APIs ---
;; Standalone: /standalone/api/v1/.../gateways (self-managed)
;; Managed:    /gatewaymanager/api/v1/.../gateways (CloudHub 2.0 managed)

(defn -get-standalone-gateways
  "Get self-managed Flex Gateways from Standalone API"
  [org env]
  (when (and org env)
    (let [org-id (org->id org)
          env-id (env->id org env)]
      (->> @(http/get (format (gen-url "/standalone/api/v1/organizations/%s/environments/%s/gateways") org-id env-id)
                     {:headers (default-headers)})
           (parse-response)
           :body
           :content
           (mapv #(assoc % :source "standalone" :extra {:org org :env env}))))))

(defn -get-managed-gateways
  "Get managed Flex Gateways from Gateway Manager API (CloudHub 2.0)"
  [org env]
  (when (and org env)
    (let [org-id (org->id org)
          env-id (env->id org env)]
      (->> @(http/get (format (gen-url "/gatewaymanager/api/v1/organizations/%s/environments/%s/gateways") org-id env-id)
                     {:headers (default-headers)})
           (parse-response)
           :body
           :content
           (mapv #(assoc % :source "managed" :extra {:org org :env env}))))))

(defn -get-gateways
  "Get all Flex Gateways (standalone + managed)"
  [org env]
  (let [standalone (or (-get-standalone-gateways org env) [])
        managed (or (-get-managed-gateways org env) [])]
    (into standalone managed)))

(defn get-gateways
  "Handler for getting all Flex Gateways"
  [{[org env] :args :keys [all] :as opts}]
  (if all
    (->> (-get-organizations)
         (mapcat (fn [{g :name}]
                   (try
                     (->> (-get-environments g)
                          (mapv (fn [{e :name}] [g e])))
                     (catch Exception e (log/debug (ex-cause e))))))
         (pmap (fn [[g e]]
                 (try
                   (add-extra-fields (-get-gateways g e) :org g :env e)
                   (catch Exception e (log/debug (ex-cause e))))))
         (apply concat))
    (let [org (or org *org*)
          env (or env *env*)]
      (if-not (and org env)
        (throw (e/invalid-arguments "Org and Env need to be specified" {:args opts}))
        (add-extra-fields (-get-gateways org env) :org org :env env)))))

(defn gw->id
  "Get Flex Gateway ID from name. Searches both standalone and managed gateways."
  [org env gw-name]
  (let [gws (-get-gateways org env)]
    (or (->> gws (filter #(= (:name %) gw-name)) first :id)
        (->> gws (filter #(= (:id %) gw-name)) first :id)
        (throw (e/no-item (str "Gateway not found: " gw-name)
                          {:gateways (map :name gws)})))))



(def -get-container-applications (memoize -get-container-applications))

(defn -get-runtime-fabrics [org]
  (let [org (or org *org*)
        org-id (org->id org)]
    (if org-id
      (->> @(http/get (format (gen-url "/runtimefabric/api/organizations/%s/fabrics/") org-id)
                     {:headers (default-headers)})
           (parse-response)
           :body)
      (throw (e/org-not-found "Not found organization" :org (or org-id org))))))

(defn get-runtime-fabrics [{[org & others] :args :as opts}]
  (when (seq others)
    (throw (e/invalid-arguments "Invalid extra arguments found" {:args opts})))
  (-get-runtime-fabrics org))

(defn rtf->id [org cluster]
  (let [[r] (->> (-get-runtime-fabrics org)
                 (filter #(or (= cluster (:name %))
                              (= cluster (:id %)))))]
    (:id r)))



(defn -get-cloudhub20-privatespaces [org]
  (let [org-id (org->id org)]
    (if org-id
      (->> @(http/get (format (gen-url "/runtimefabric/api/organizations/%s/privatespaces") org-id)
                     {:headers (default-headers)})
           (parse-response)
           :body
           :content)
      (throw (e/org-not-found "Not found organization" :org (or org-id org))))))

(defn get-cloudhub20-privatespaces [{[org] :args :as opts}]
  (-get-cloudhub20-privatespaces (or org *org*)))


;; --- Secret Manager APIs ---

(defn -get-secret-groups
  "Get all secret groups from an environment"
  [org env]
  (let [org-id (org->id org)
        env-id (env->id org env)]
    (if (and org-id env-id)
      (->> @(http/get (format (gen-url "/secrets-manager/api/v1/organizations/%s/environments/%s/secretGroups") org-id env-id)
                     {:headers (default-headers)})
           (parse-response)
           :body
           (mapv #(assoc % :_org org :_env env)))
      [])))

(defn get-secret-groups
  "Handler for getting secret groups"
  [{[org env] :args :keys [all] :as opts}]
  (let [org (or org *org*)
        env (or env *env*)]
    (if all
      ;; Get from all orgs/envs
      (->> (-get-organizations)
           (mapcat (fn [{g :name}]
                     (try
                       (->> (-get-environments g)
                            (mapcat (fn [{e :name}]
                                      (try (-get-secret-groups g e)
                                           (catch Exception _ [])))))
                       (catch Exception _ []))))
           (vec))
      ;; Get from specific org/env
      (if (and org env)
        (-get-secret-groups org env)
        (throw (e/invalid-arguments "Org and Env are required" {:org org :env env}))))))


(defn -get-api-instances 
  ([org env]
   (let [org-id (org->id org)
         env-id (env->id org env)]
     (->> @(http/get (format (gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis") org-id env-id)
                    {:headers (default-headers)})
          (parse-response)
          :body
          :assets
          ;; Added exchange-asset-name to each api instance
          (mapcat (fn [{:keys [apis exchange-asset-name]}]
                    (map #(assoc % :exchange-asset-name exchange-asset-name) apis)))
          (vec))))
  ([org env api]
   (->> (-get-api-instances org env)
        (filterv #(or (= (:asset-id %) (str api))
                      (= (str (:id %)) (str api)))))))

(def -get-api-instances (memoize -get-api-instances))

(defn get-api-instances [{:keys [args]
                          [org env] :args}]
  (when-not (and (or org *org*) (or env *env*))
    (throw (e/org-not-found "Not found organization and/or environement" {:args args})))
  (-get-api-instances (or org *org*) (or env *env*)))


(defn -get-servers [org env]
  (let [org-id (org->id org)
        env-id (env->id org-id env)]
    (-> @(http/get (gen-url "/hybrid/api/v1/servers")
                   {:headers (assoc (default-headers)
                                    "X-ANYPNT-ORG-ID" org-id
                                    "X-ANYPNT-ENV-ID" env-id)})
        (parse-response)
        :body
        :data)))

(defn hybrid-server->id [org env cluster]
  (let [[r] (->> (-get-servers org env)
                 (filter #(or (= cluster (:name %))
                              (= cluster (:id %)))))]
    (:id r)))

(defn get-servers [{[org env] :args}]
  (let [org (or org *org*)
        env (or env *env*)]
    (-get-servers org env)))

(defn -get-runtime-cloud-targets [org-sym]
;; This function is required be authenticated by 'Act as an user'
  (let [org-id (org->id org-sym)]
    (if org-id
      (->> @(http/get (format (gen-url "/runtimefabric/api/organizations/%s/targets") org-id)
                      {:headers (default-headers)})
           (parse-response)
           :body
           ;; workaround for issue that  Anypoint returns multiple record if Private Space is associated to multiple envs.
           set
           vec)
      (throw (e/org-not-found "Not found organization" :org (or org-id org-sym))))))



(def -get-servers (memoize-file -get-servers))
(def -get-runtime-cloud-targets (memoize-file -get-runtime-cloud-targets))

(defn api->id [org env api]
  (let [apis (-get-api-instances org env api)]
    (cond
      (= 0  (count apis))
      (throw (e/api-not-found "No api found" {:api api}))
      (< 1 (count apis))
      (throw (e/multiple-api-name-found "Found multiple API instances" {:apis (mapv :id apis)}))
      :else
      (:id (first apis)))))


(defn target->id [org env target]
  (->> (pmap (fn [f] (f)) [#(-get-runtime-cloud-targets org) #(-get-servers org env)])
       (apply concat)
       (filter #(or (= target (:name %)) (= target (str (:id %)))))
       (first)
       :id))

(defn target->name [org env target]
  (->> (pmap (fn [f] (f)) [#(-get-runtime-cloud-targets org) #(-get-servers org env)])
       (apply concat)
       (filter #(or (= target (:name %)) (= target (str (:id %)))))
       (first)
       :name))


;; Notes:
;;  Exchange API requires the same group-id to be configured
;;  - Endpoint URL
;;  - Given pom.xml
;;  - pom.xml in Jar
;;
;; Therefore, it is better to unpack Jar and copy pom.xml in Jar and get group-id from the Pom.
;; version and asset-id can be modified in outer pom.xml

(defn assoc-pom [root-loc m]
  (loop [l (z/down root-loc)
         m (cske/transform-keys csk/->camelCaseString m)]
    (cond
      (nil? l) (println (str "Not found keys: " (str/join " " (map name (keys m)))))
      (or (z/end? l) (empty? m)) (dx/emit-str (z/root l))

      :else
      (let [k (when-let [z (:tag (z/node l))];
                (last (str/split (name z) #"/")))
            v (m k)]
        (if v
          (recur (z/right (z/edit l (fn [n] (assoc n :content [v])))) (dissoc m k))
          (recur (z/right l) m))))))

(defn pom-get-gav [root-loc]
  ;; Assume to use clojure.data.xml to use native-image
  (loop [l (z/down root-loc)
         m {}]

    (cond
      (or (nil? l) (z/end? l)) m
      (not (dx/element? (z/node l))) (recur (z/right l) m)
      (#{:group-id :artifact-id :version} (csk/->kebab-case-keyword (last (str/split  (name (:tag (z/node l))) #"/"))))
      (recur (z/right l) (assoc m
                           (csk/->kebab-case-keyword (:tag (z/node l)))
                           (first (:content (z/node l)))))
      :else
      (recur (z/right l) m))))

(declare provider->name)
;;; Get
(defn -get-users [org]
  (let [org (or org *org*)
        org-id (org->id org)]
    (-> @(http/get (format (gen-url "/accounts/api/organizations/%s/users") org-id)
                   {:headers (default-headers)})
         (parse-response)
         :body
         :data
         (add-extra-fields :idp #(provider->name (get % :idprovider-id )))
         )))

(def -get-users (memoize -get-users))

(defn get-users [_]
  (-get-users (:id (-get-root-organization))))

(defn -get-user [user]
  (let [{root-org-id :id} (-get-root-organization)]
    (or (->> (-get-users root-org-id)
             (filter #(or (= user (:username %))
                          (= user (:id %))))
             (first))
        (throw (e/org-not-found "Not found user" :user user)))))

(defn get-user [id-or-name]
  (-get-user id-or-name))

(defn user->name [id-or-name]
  (:username (-get-user id-or-name)))

(defn user->id [id-or-name]
  (:id (-get-user id-or-name)))


;;; Teams

(defn -get-teams []
  ;; Teams API only works on root/master organization
  (let [{root-org-id :id} (-get-root-organization)]
    (-> @(http/get (format (gen-url "/accounts/api/organizations/%s/teams") root-org-id)
                  {:headers (default-headers)})
        (parse-response)
        :body
        :data)))

(def -get-teams (memoize-file -get-teams))

(defn get-teams [& _]
  (-get-teams))

(defn team->id [id-or-name]
  (let [teams (-get-teams)
        team (first (filter #(or (= id-or-name (:id %))
                                 (= id-or-name (:team-id %))
                                 (= id-or-name (:team-name %)))
                            teams))]
    (or (:team-id team)
        (:id team)
        (throw (e/team-not-found "Team not found" {:team id-or-name})))))

(defn team->name [id-or-name]
  (let [teams (-get-teams)]
    (:team-name (first (filter #(or (= id-or-name (:id %))
                                    (= id-or-name (:team-id %))
                                    (= id-or-name (:team-name %)))
                               teams)))))


(defn assets-fields [& {:keys [output-format fields]
                        :or {fields [:group-id :asset-id  :type :version :status]}}]
  (loop [sf fields
         r []]
    (if-not (seq sf)
      (if-not ((set r) :organization-name)
        (conj r :organization-name)
        r)
      (if (= (first sf) :organization-id)
        (recur (rest sf ) (conj (conj r (first sf)) :organization-name))
        (recur (rest sf) (conj r (first sf)))))))




(defn -get-runtime-targets [org env]
  (on-threads *no-multi-thread*
    (-get-servers org env)
    (-get-runtime-cloud-targets org)))


(defn get-runtime-targets [{[org env] :args}]
  (let [org (or org *org*)
        env (or env *env*)]
    (-get-runtime-targets org env)))

(def -get-runtime-targets (memoize-file -get-runtime-targets))




(defn -get-connected-applications []
  (->> @(http/get (gen-url "/accounts/api/connectedApplications")
                   {:headers (default-headers)})
       (parse-response)
       :body
       :data))

(defn get-connected-applications [_]
  (-get-connected-applications))

(defn connected-app->id
  "Convert connected app name or client-id to client-id"
  [name-or-id]
  (let [apps (get-connected-applications nil)
        app (first (filter #(or (= name-or-id (:client-id %))
                                (= name-or-id (:client-name %)))
                           apps))]
    (or (:client-id app)
        (throw (e/connected-app-not-found "Connected app not found" {:connected-app name-or-id})))))

(defn get-connected-app-scopes
  "Get scopes for a connected app by client-id"
  [client-id]
  (->> @(http/get (format (gen-url "/accounts/api/connectedApplications/%s/scopes") client-id)
                 {:headers (default-headers)})
       (parse-response)
       :body
       :data))

(defn- classify-scope
  "Classify a scope as basic, org, or env based on naming patterns"
  [scope]
  (cond
    ;; Basic scopes - no context needed
    (contains? #{"full" "read:full" "profile" "openid" "email" "offline_access"} scope)
    "basic"

    ;; Env-level scopes - require org + envId
    (or (re-find #"applications" scope)
        (re-find #"cloudhub" scope)
        (re-find #"runtime" scope)
        (re-find #"servers" scope)
        (re-find #"alerts" scope)
        (re-find #"flows" scope)
        (re-find #"queues" scope)
        (re-find #"schedules" scope)
        (re-find #"tenants" scope)
        (re-find #"data$" scope))
    "env"

    ;; Org-level scopes - require org context
    (or (re-find #"org" scope)
        (re-find #"organization" scope)
        (re-find #"suborg" scope)
        (re-find #"users" scope)
        (re-find #"roles" scope)
        (re-find #"teams" scope)
        (re-find #"invites" scope)
        (re-find #"clients" scope)
        (re-find #"providers" scope)
        (re-find #"exchange" scope)
        (re-find #"portals" scope)
        (re-find #"api" scope)
        (re-find #"assets" scope))
    "org"

    ;; Default to org for unknown scopes
    :else "org"))

(defn get-available-scopes
  "Get available scopes from OpenID Connect discovery endpoint"
  [_]
  (-> @(http/get (gen-url "/accounts/api/v2/oauth2/.well-known/openid-configuration")
                {:headers (default-headers)})
      (parse-response)
      :body
      :scopes-supported
      (->> (map #(hash-map :scope % :type (classify-scope %))))))

(defn assign-connected-app-scopes
  "Assign scopes to a connected app.
   client-id: the connected app's client ID
   opts map with:
     :scopes     - vector of basic scope names (no context needed)
     :org-scopes - vector of org-level scope names
     :org-id     - organization ID for org-level scopes
     :env-scopes - vector of env-level scope names
     :env-ids    - vector of environment IDs for env-level scopes"
  [client-id {:keys [scopes org-scopes org-id env-scopes env-ids]}]
  (let [;; Basic scopes with empty context
        ;; Use string keys to avoid snake_case conversion issues
        basic-scope-objects (mapv (fn [scope]
                                    {"scope" scope "context_params" {}})
                                  (or scopes []))
        ;; Org-level scopes with org context
        org-scope-objects (when (and org-id (seq org-scopes))
                            (mapv (fn [scope]
                                    {"scope" scope "context_params" {"org" org-id}})
                                  org-scopes))
        ;; Env-level scopes with org+env context (one entry per env)
        ;; The API requires "envId" (camelCase) not "env_id"
        env-scope-objects (when (and org-id (seq env-ids) (seq env-scopes))
                            (for [env-id env-ids
                                  scope env-scopes]
                              {"scope" scope "context_params" {"org" org-id "envId" env-id}}))
        all-scopes (concat basic-scope-objects
                           (or org-scope-objects [])
                           (or env-scope-objects []))
        body {"scopes" (vec all-scopes)}]
    (log/debug "Assigning scopes:" body)
    ;; Use PATCH to /organizations/{org}/connectedApplications/{client}/scopes
    ;; Use json/write-str directly to preserve exact key names:
    ;; - "context_params" must stay snake_case (not contextParams)
    ;; - "envId" must stay camelCase (not env_id)
    (-> @(http/patch (format (gen-url "/accounts/api/organizations/%s/connectedApplications/%s/scopes") org-id client-id)
                    {:headers (default-headers)
                     :body (json/write-value-as-string body)})
        (parse-response)
        :body)))


(defn -get-api-proxies [org env api]
  (let [org-id (org->id org)
        env-id (env->id org env)
        api-id (api->id org env api)]
    (-> @(http/get (format (gen-url "/proxies/xapi/v1/organizations/%s/environments/%s/apis/%s/deployments") org-id env-id api-id)
                  {:headers (default-headers)})
        (parse-response)
        :body)))

(defn get-api-proxies [{:keys [args] :as opts}]
  (let [[api env org] (reverse args) ;; app has to be specified
        org (or org *org*)           ;; If specified, use it
        env (or env *env*)]
    (if-not (and org env api)
      (throw (e/invalid-arguments "Org, Env and Api need to be specified" {:args args}))
      (-get-api-proxies org env api))))


(defn -get-hybrid-applications [org env]
  (let [org-id (org->id org)
        env-id (env->id org env)]
    (log/debug "org:" org-id)
    (log/debug "env:" env-id)
    (-> @(http/get (gen-url "/hybrid/api/v1/applications")
                  {:headers (assoc (default-headers)
                                   "X-ANYPNT-ORG-ID" org-id
                                   "X-ANYPNT-ENV-ID" env-id)})
        (parse-response)
        :body
        :data
        (add-extra-fields :org (org->name org)
                          :env (env->name org env)
                          :status #(get-in % [:last-reported-status])
                          :target #(get-in % [:target :name]))
        )))

(defn -get-identity-provider-users [org]
  (let [org-id (org->id org)]
    (log/debug "org:" org-id)
    (-> @(http/get (format (gen-url "/accounts/api/organizations/%s/provider/users") org-id)
                  {:headers (default-headers)})
        (parse-response)
        :body
        :data
        ;; (add-extra-fields :org (org->name org)
        ;;                   :id :provider-id
        ;;                   :type (comp :name :type))
        )))

(defn -get-identity-providers []
  (let [{:keys [id name]} (first (filter :is-root (-get-organizations)))]
    (-> @(http/get (format (gen-url "/accounts/api/organizations/%s/identityProviders") id)
                  {:headers (default-headers)})
        (parse-response)
        :body
        :data
        (add-extra-fields :org (org->name id)
                          :id :provider-id
                          :type (comp :name :type)))))

(def -get-identity-providers (memoize -get-identity-providers))

;;; Client Providers (for OAuth/OIDC client management)

(defn -get-client-providers
  "Get client providers (OpenID Connect client management providers)"
  ([]
   (let [{:keys [id]} (first (filter :is-root (-get-organizations)))]
     (-get-client-providers id)))
  ([org]
   (let [org-id (org->id org)]
     (-> @(http/get (format (gen-url "/accounts/api/organizations/%s/clientProviders") org-id)
                   {:headers (default-headers)})
         (parse-response)
         :body
         :data
         (add-extra-fields :org (org->name org-id)
                           :id :provider-id
                           :type (comp :name :type))))))

(def -get-client-providers (memoize -get-client-providers))

(defn get-client-providers [{[org] :args}]
  (-get-client-providers (or org (:id (-get-root-organization)))))

(defn -get-client-provider
  "Get a specific client provider by ID or name with full details"
  [id-or-name]
  (let [{:keys [id]} (-get-root-organization)
        providers (-get-client-providers id)
        provider (first (filter #(or (= id-or-name (:provider-id %))
                                     (= id-or-name (:name %)))
                                providers))]
    (when provider
      (-> @(http/get (format (gen-url "/accounts/api/organizations/%s/clientProviders/%s")
                            id (:provider-id provider))
                    {:headers (default-headers)})
          (parse-response)
          :body))))

(def -get-client-provider (memoize -get-client-provider))

(defn client-provider->id [id-or-name]
  (->> (-get-client-provider id-or-name)
       :provider-id))

(defn client-provider->name [id-or-name]
  (->> (-get-client-provider id-or-name)
       :name))

(defn get-identity-providers [{:keys [args]}]
  (-get-identity-providers))


(defn -get-identity-provider [id-or-name]
  (->> (-get-identity-providers)
       (filter #(or (= id-or-name (:provider-id %))
                    (= id-or-name (:name %))))
       (first)))

(def -get-identity-provider (memoize -get-identity-provider))

(defn provider->id [id-or-name]
  (->> (-get-identity-provider id-or-name)
       :provider-id))

(defn provider->name [id-or-name]
  (->> (-get-identity-provider id-or-name)
       :name))



(def -get-hybrid-applications (memoize -get-hybrid-applications))

(defn -get-deployed-applications [org env]
  (on-threads *no-multi-thread*
    (-get-container-applications org env)
    (-get-hybrid-applications org env)))

;; (def -get-deployed-applications (memoize -get-deployed-applications))

(defn name->apps [org env app]
  (->> (-get-deployed-applications org env)
       (filter #(or (= (:name %) app) (= (:id %) app)))))


(defn get-deployed-applications [{:keys [args no-multi-thread search-term]
                                  :as opts}]
  (log/debug "Opts:" opts)
  (let [{:keys [all]} opts
        ;; 引数が1つの場合は検索語として扱い、org/envはデフォルトを使う
        [org env search] (case (count args)
                           0 [*org* *env* nil]
                           1 [*org* *env* (first args)]
                           2 [(first args) (second args) nil]
                           [(first args) (second args) (nth args 2 nil)])
        search-term (or search-term search)]
    (if all
      (->> (-get-organizations)
           (mapcat (fn [{g :name}]
                     (try
                       (->> (-get-environments g)
                           (mapv (fn [{e :name}] [g e])))
                       (catch Exception e (log/debug (ex-cause e))))))
           (pmap (fn [[g e]] (try
                               (-get-deployed-applications g e)
                               (catch Exception e (log/debug (ex-cause e))))))
           (apply concat)
           (filter #(re-find (re-pattern (or search-term ".")) (:name %))))
      (if-not (and org env)
        (throw (e/invalid-arguments "Org and Env need to be specified" {:args args
                                                                        :availables {:org (map :name (-get-organizations))
                                                                                    :env (map :name (-get-environments org))}}))
        (->> (-get-deployed-applications org env)
             (filter #(re-find (re-pattern (or (str/join search-term) ".")) (:name %))))))))


(defn -get-api-contracts [org env api]
  (let [org-id (org->id org)
        env-id (env->id org-id env)
        api-id (api->id org env api)]
    (-> @(http/get (format (gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s/contracts") org-id env-id api-id)
                  {:headers (default-headers)})
        (parse-response)
        :body
        :contracts
        (->> (map #(assoc % :extra {:api-name api}))))))

(defn get-api-contracts [{:keys [args]
                          [org env api] :args}]
  (let [[api env org] (reverse args)
        org-id (org->id (or org *org*))
        env-id (env->id org-id (or env *env*))
        api-id (api->id org-id env-id api)]
    (if-not (and org-id env-id api-id)
      (throw (e/invalid-arguments "Org, Env and API need to be specified" {:args args
                                                                           :availables {:org (map :name (-get-organizations))
                                                                                        :env (map :name (-get-environments org))}}))
      (-get-api-contracts org-id env-id api-id))))

(defn contract->id [org env api contract]
  (->> (-get-api-contracts org env api)
       (filter #(= contract (-> % :application :name)))
       first
       :id))

(defn -select-app-assets [{[cluster org env app] :args
                           :keys [group asset version labels search-term] :as opts}]
  (cond
    (and app group asset version)
    [{:group-id (org->id group) :asset-id asset :version version :types ["app"]}]
    
    (and app group asset)
    (take-last 1 (sort-by :version (get-assets {:group group :asset asset :types ["app"]})))

    (or (seq labels) search-term) ;; Graph Query in get-assets uses 'labels' instead of 'tags'...
    (cond-> {:group group :types ["app"]}
      (seq labels) (assoc  :labels labels)
      (seq search-term) (assoc :search-term search-term)
      :always (get-assets ))
    
    :else
    (throw (e/invalid-arguments "An app and GAV are not specified. Otherwise you should specify tags to filter." (dissoc opts :summary)))))


(defn equal-kvs->map [resource-opts]
  (try
    (into {} (mapv #(vec (str/split % #"=")) resource-opts))
    (catch Exception e {})))


(defn target->id [org env id-or-name]
  (let [;; Handle hy: prefix for hybrid/on-premise servers
        [target-name target-sources] (if (str/starts-with? id-or-name "hy:")
                                       [(subs id-or-name 3) (-get-servers org env)]
                                       [id-or-name (-get-runtime-targets org env)])
        targets (filter #(or (= (:name %) target-name)
                             (= (str (:id %)) target-name))
                        target-sources)]
    (cond
      (= 0 (count targets))  (throw (e/target-not-found "No target found" {:target id-or-name :available (map :id targets)}))
      (< 1 (count targets)) (throw (e/multiple-target-name-found "Multiple target name found. Specific id instead of target name" {:name id-or-name} ))
      :else (-> targets first :id))))

(defn app->id
  ([org-id-or-name env-id-or-name id-or-name]
   (app->id :any org-id-or-name env-id-or-name id-or-name))
  ([runtime-target-id org-id-or-name env-id-or-name id-or-name]
   (log/debug "app->id: " runtime-target-id org-id-or-name env-id-or-name id-or-name)
   (if-let [xs (-get-deployed-applications (org->id org-id-or-name) (env->id org-id-or-name env-id-or-name))]
     (let [runtime-target-id (or runtime-target-id :any)
           apps (->> xs
                     (filter #(and 
                               (or (= :any runtime-target-id)
                                   ;; Hybrid
                                   (= (-> % :target :id) runtime-target-id)
                                   ;; CH2
                                   (= (-> % :target :target-id) runtime-target-id)
                                   (= (-> % :target :name) runtime-target-id))
                               (or (= (str (:id %)) id-or-name)
                                   (= (:name %) id-or-name)))))]
       (cond
         (= 0 (count apps))  (throw (e/app-not-found "No app found" {:app id-or-name :available (map :id apps)}))
         (< 1 (count apps)) (throw (e/multiple-app-name-found "Multiple app name found. Specific id instead of app name" {:name id-or-name} ))
         :else (-> apps first :id)))
     (throw (e/app-not-found "No app found" {:org org-id-or-name :env env-id-or-name :app id-or-name})))))

(defn -get-container-application-specs [org env app]
  (let [org-id (org->id (or org *org*))
        env-id (env->id org-id (or env *env*))
        [{deployment-id :id}] (filter #(#{(:id %) (:name %)} app) (-get-container-applications org env))]
    (-> @(http/get (format (gen-url "/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments/%s/specs")  org-id env-id deployment-id)
                  {:headers (default-headers)})
        (parse-response)
        :body
        (add-extra-fields :deployment-id deployment-id))))

(s/def ::id string?)
(s/def ::name string?)
(s/def :entitlement/v-cores-production number?)
(s/def :entitlement/v-cores-sandbox number?)
(s/def :entitlement/static-ips number?)
(s/def :entitlement/network-connections number?)
(s/def :entitlement/vpns number?)
(s/def :entitlement/view (s/coll-of (s/keys :req-un [::id
                                                     ::name
                                                     :entitlement/v-cores-production
                                                     :entitlement/v-cores-sandbox
                                                     :entitlement/static-ips
                                                     :entitlement/network-connections
                                                     :entitlement/vpns])))

(defn get-entitlements [{:keys [args]}]
  {:post [(s/valid? :entitlement/view (map :extra %))]}
  (let [orgs (-get-organizations)
        xf #(try
              (->> @(http/get (format (gen-url "/accounts/api/organizations/%s") (:id %))
                             {:headers (default-headers)})
                   (parse-response)
                   :body)
              (catch Exception e :error))]
    (cond-> orgs
      *no-multi-thread* (->> (map xf))
      (not *no-multi-thread*) (->> (pmap xf))
      true (->> (reduce (fn [r x]
                      (if (not= x :error)
                        (conj r x)
                        r))
                    []))
      true (add-extra-fields :id :id
                             :name :name
                             :v-cores-production (comp  :assigned :v-cores-production :entitlements)
                             :v-cores-sandbox (comp  :assigned :v-cores-sandbox :entitlements)
                             :static-ips (comp  :assigned :static-ips :entitlements)
                             :network-connections (comp  :assigned :network-connections :entitlements)
                             :vpns (comp  :assigned :vpns :entitlements)))))

(defn -get-available-node-ports [org]
  (let [org-id (org->id org)
        ps (-get-cloudhub20-privatespaces org-id)
        xf #(try
              (-> @(http/get (format (gen-url "/runtimefabric/api/organizations/%s/privatespaces/%s/ports") org-id (:id %))
                            {:headers (default-headers)
                             :query-params {:available true :count 10}})
                  (parse-response)
                  :body
                  (assoc :private-space %))
              (catch Exception e (log/debug (ex-message e)) {:private-space %}))]
    (cond-> ps
      (not *no-multi-thread*) (->> (pmap xf))
      *no-multi-thread* (->> (map xf))
      true (add-extra-fields
            :org (org->name org)
            :private-space (comp :name :private-space)
            :ports (comp (partial str/join ",") :ports))
      true (->> (sort-by (comp :private-space :extra))))))


(defn get-available-node-ports [{:keys [args]
                                 [org] :args}]
  (-get-available-node-ports (or org *org*)))


(defn ps->id [org ps]
  (let [xs (->> (-get-cloudhub20-privatespaces org)
                (filter #(or (= ps (:id %))
                             (= ps (:name %)))))]
    (cond
      (= 1 (count xs)) (:id (first xs))
      (< 1 (count xs)) (throw (e/multiple-private-sppace-found "Multiple private spaces found" {:name ps} )))))

(def -get-container-application-specs (memoize -get-container-application-specs))

(defn get-transit-gateways [{:keys [args]  [org ps] :args}]
  (let [org-id (org->id (or org *org*))
        ps-id (ps->id org-id ps)]
    (-> @(http/get (format (gen-url "/runtimefabric/api/organizations/%s/privatespaces/%s/transitgateways") org-id ps-id)
                  {:headers (default-headers)})
        (parse-response)
        :body)))

(defn -get-cloudhub20-vpns [org ps]
  (let [org-id (org->id (or org *org*))
        ps-id (ps->id org-id ps)]
    (-> @(http/get (format (gen-url "/runtimefabric/api/organizations/%s/privatespaces/%s/connections") org-id ps-id)
                   {:headers (default-headers)})
         (parse-response)
         :body
         (->> (mapcat (juxt :vpns)))
         (->> (apply concat))
         (add-extra-fields :id :connection-id
                           :name :connection-name
                           :type "vpn"
                           :status :vpn-connection-status
                           :routes (comp #(str/join "," %) :static-routes)))))



(defn -get-cloudhub20-transit-gateways [org ps]
  (let [org-id (org->id (or org *org*))
        ps-id (ps->id org-id ps)]
    (-> @(http/get (format (gen-url "/runtimefabric/api/organizations/%s/privatespaces/%s/transitgateways") org-id ps-id)
                   {:headers (default-headers)})
        (parse-response)
         :body
         (add-extra-fields :id :id ;;(comp :id :resource-share :spec)
                           :name :name
                           :type "tgw"
                           :status (comp :attachment :status)
                           :routes (comp #(str/join "," %) :routes :status))
         )))

(defn -get-cloudhub20-connections [org ps]
  (on-threads *no-multi-thread*
    (-get-cloudhub20-vpns org ps)
    (-get-cloudhub20-transit-gateways org ps)))

(defn get-cloudhub20-connections [{:keys [args] [org ps] :args}]
  (let [[ps org] (reverse args)]
    (-get-cloudhub20-connections org ps)))

(defn conn->id [org ps conn]
  (let [xs (->> (-get-cloudhub20-connections org ps)
                (filter #(or (= (:name %) conn) (= (:id %) conn))))]

    (cond
      (= 1 (count xs)) (or (:connection-id (first xs)) (:id (first xs)))
      (< 1 (count xs)) (throw (e/multiple-connections "Multiple connection found")))))


(defn -get-api-policies [org env api]
  (let [org-id (org->id (or org *org*))
        env-id (env->id org-id (or env *env*))
        api-id (api->id org-id env-id api)]
    (-> @(http/get (format (gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s/policies")
                          org-id env-id api-id)
                  {:headers (default-headers)})
        (parse-response)
        :body
        :policies
        (add-extra-fields :id :policy-id
                          :asset-id (comp :asset-id :implementation-asset)
                          :version (comp :asset-version :template)
                          :type "regular"
                          :order :order))))

(defn -get-automated-api-policies [org env]
  (let [org-id (org->id (or org *org*))
        env-id (env->id org-id (or env *env*))]
    (-> @(http/get (format (gen-url "/apimanager/api/v1/organizations/%s/automated-policies")
                          org-id env-id)
                  {:query-params {:environmentId env-id}
                   :headers (default-headers)})
        (parse-response)
        :body
        :automated-policies
        (add-extra-fields :id :id
                          :asset-id :asset-id
                          :version :asset-version
                          :type "automated"
                          :order :order))))

(defn get-api-policies [{:keys [args types]
                         [org env api] :args}]
  (let [[api env org] (reverse args)]
    (->> (on-threads *no-multi-thread*
           (-get-api-policies org env api)
           (-get-automated-api-policies org env))
         (filter #((set (or (seq types) ["automated" "regular"])) (-> % :extra :type))))))

;; Upstream API functions
(defn -get-api-upstreams
  "Get upstreams for an API instance"
  [org env api]
  (let [org-id (org->id (or org *org*))
        env-id (env->id org-id (or env *env*))
        api-id (api->id org-id env-id api)]
    (-> @(http/get (format (gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s/upstreams")
                          org-id env-id api-id)
                  {:headers (default-headers)})
        (parse-response)
        :body)))

(defn -patch-api-upstream
  "Update an API upstream URI"
  [org env api upstream-id uri]
  (let [org-id (org->id (or org *org*))
        env-id (env->id org-id (or env *env*))
        api-id (api->id org-id env-id api)]
    (-> @(http/patch (format (gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s/upstreams/%s")
                            org-id env-id api-id upstream-id)
                    {:headers (default-headers)
                     :body (edn->json {:uri uri})})
        (parse-response)
        :body)))

;; Policy API functions
(defn -get-api-policy
  "Get a specific policy for an API instance by policy-id"
  [org env api policy-id]
  (let [org-id (org->id (or org *org*))
        env-id (env->id org-id (or env *env*))
        api-id (api->id org-id env-id api)]
    (-> @(http/get (format (gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s/policies/%s")
                          org-id env-id api-id policy-id)
                  {:headers (default-headers)})
        (parse-response)
        :body)))

(defn -patch-api-policy
  "Update an API policy configuration"
  [org env api policy-id config-data]
  (let [org-id (org->id (or org *org*))
        env-id (env->id org-id (or env *env*))
        api-id (api->id org-id env-id api)]
    (-> @(http/patch (format (gen-url "/apimanager/api/v1/organizations/%s/environments/%s/apis/%s/policies/%s")
                            org-id env-id api-id policy-id)
                    {:headers (default-headers)
                     :body (edn->json {:configurationData config-data})})
        (parse-response)
        :body)))

(defn policy-name->id
  "Convert policy asset-id (name) to policy-id"
  [org env api policy-name]
  (let [policies (-get-api-policies org env api)]
    (->> policies
         (filter #(= policy-name (-> % :extra :asset-id)))
         first
         :extra
         :id)))

;; Need Titanium subscription
(defn -get-monitoring-entity-id [org env entity-type]
  (let [org-id (org->id org)
        env-id (env->id org env)
        etype (keyword entity-type)]
    (log/warn "Monitoring URL should be fixed for hyperforce.")
    (-> @(http/get (format "https://monitoring.anypoint.mulesoft.com/monitoring/archive/api/v1/organizations/%s/environments/%s/%s"
                          org env entity-type)
                  {:headers (default-headers)})
        (parse-response)
        :body)))

;;; Alerts API Implementation (Unified)
;;; --type api: API alerts via /monitoring/api/alerts/api/v2
;;; --type app: Application alerts via /monitoring/api/v2

;; API Alerts (Monitoring API)
(defn -get-api-alerts
  "Get API alerts for an environment"
  [org env]
  (let [org-id (org->id (or org *org*))
        env-id (env->id org-id (or env *env*))]
    (-> @(http/get (format (gen-url "/monitoring/api/alerts/api/v2/organizations/%s/environments/%s/alerts")
                          org-id env-id)
                  {:headers (default-headers)})
        (parse-response)
        :body)))

;; Application Alerts (Monitoring API v2)
(defn -get-app-alerts
  "Get application alerts via Monitoring API v2"
  [org]
  (let [org-id (org->id (or org *org*))]
    (-> @(http/get (format (gen-url "/monitoring/api/v2/organizations/%s/alerts") org-id)
                   {:headers (default-headers)})
        (parse-response)
        :body)))

(defn- extract-response-codes [alert]
  (let [sub-filters (-> alert :resources first :sub-filters)]
    (when-let [rc-filter (some #(when (= "response_code" (:name %)) %) sub-filters)]
      (str/join "," (:values rc-filter)))))

(defn get-alerts
  "Get alerts - unified handler for api/app/server types

   Usage:
     yaac get alerts <org> <env> --type api    (API alerts require env)
     yaac get alerts <org> --type app          (Application alerts, org only)"
  [{:keys [args type] :as opts}]
  (let [alert-type (or type "api")]
    (case alert-type
      ;; API Alerts (requires org + env)
      "api"
      (let [[env org] (reverse args)
            org (or org *org*)
            env (or env *env*)
            org-id (org->id org)
            env-id (env->id org-id env)
            alerts (-get-api-alerts org env)
            org-name (org->name org)
            env-name (env->name org env)]
        (->> alerts
             (mapv (fn [alert]
                     (assoc alert
                            :alert-type "api"
                            :extra {:org org-name
                                    :env env-name
                                    :api-id (-> alert :resources first :api-id)
                                    :response-codes (extract-response-codes alert)})))))

      ;; Application Alerts (org only, optional env filter)
      "app"
      (let [[env-or-org org] (reverse args)
            ;; If only one arg provided, it's the org
            [org env] (if org
                        [org env-or-org]
                        [(or env-or-org *org*) nil])
            org-id (org->id org)
            env-id (when env (env->id org-id env))
            alerts (-get-app-alerts org)
            org-name (org->name org)
            filtered (if env-id
                       (filter #(= env-id (-> % :resource :environment-id)) alerts)
                       alerts)]
        (->> filtered
             (mapv (fn [alert]
                     (assoc alert
                            :alert-type "app"
                            :extra {:org org-name
                                    :env (-> alert :resource :environment-id)
                                    :app-id (-> alert :resource :app-id)
                                    :resource-type (-> alert :resource :type)})))))

      ;; Server Alerts (placeholder)
      "server"
      (throw (ex-info "Server alerts not yet implemented" {:type type}))

      ;; Unknown type
      (throw (ex-info "Unknown alert type. Use --type api|app|server" {:type type})))))

(defn alert->id
  "Resolve alert name to ID based on alert type"
  [alert-type org env alert-name-or-id]
  (case alert-type
    "api"
    (let [alerts (-get-api-alerts org env)]
      (or
       (some #(when (= alert-name-or-id (:alert-id %)) (:alert-id %)) alerts)
       (some #(when (= alert-name-or-id (:name %)) (:alert-id %)) alerts)))

    "app"
    (let [alerts (-get-app-alerts org)]
      (or
       (some #(when (= alert-name-or-id (:alert-id %)) (:alert-id %)) alerts)
       (some #(when (= alert-name-or-id (:alert-name %)) (:alert-id %)) alerts)))

    nil))

;;; Metrics API Implementation

;; Predefined metric types for common use cases
(def predefined-metrics
  {"app-inbound" {:metric "mulesoft.app.inbound"
                  :default-aggregation "count"
                  :measurement "requests"
                  :default-group-by ["deployment.id"]}
   "app-inbound-response-time" {:metric "mulesoft.app.inbound"
                                 :default-aggregation "avg"
                                 :measurement "response_time"}
   "app-outbound" {:metric "mulesoft.app.outbound"
                   :default-aggregation "count"
                   :measurement "requests"
                   :default-group-by ["deployment.id"]}
   "api-path" {:metric "mulesoft.api.path"
               :default-aggregation "count"
               :measurement "requests"
               :default-group-by ["api.instance.id"]}
   "api-summary" {:metric "mulesoft.api.summary"
                  :default-aggregation "count"
                  :measurement "requests"
                  :default-group-by ["api.instance.id"]}})

;; Parse duration string (1h, 30m, 1d) to milliseconds
(defn parse-duration [s]
  (when s
    (let [pattern #"(\d+)([smhd])"
          matches (re-find pattern (str s))]
      (when matches
        (let [[_ num unit] matches
              n (parse-long num)]
          (case unit
            "s" (* n 1000)
            "m" (* n 60 1000)
            "h" (* n 60 60 1000)
            "d" (* n 24 60 60 1000)
            nil))))))

;; Parse timestamp (Unix timestamp or ISO8601)
(defn parse-timestamp [s]
  (cond
    (number? s) s
    (string? s) (try
                  (parse-long s)
                  (catch Exception _
                    ;; If not a number, try ISO8601 parsing
                    (-> (java.time.Instant/parse s)
                        (.toEpochMilli))))
    :else nil))

;; Convert various time specifications to [start-ms end-ms]
(defn resolve-time-range [{:keys [start end from duration]}]
  (cond
    ;; Absolute time range
    (and start end)
    [(parse-timestamp start) (parse-timestamp end)]

    ;; Relative with duration
    (and from duration)
    (let [end-ms (System/currentTimeMillis)
          start-ms (- end-ms (parse-duration from))]
      [start-ms (+ start-ms (parse-duration duration))])

    ;; Relative from now
    from
    (let [end-ms (System/currentTimeMillis)
          start-ms (- end-ms (parse-duration from))]
      [start-ms end-ms])

    ;; Default: last 1 hour
    :else
    (let [end-ms (System/currentTimeMillis)
          start-ms (- end-ms (* 60 60 1000))]
      [start-ms end-ms])))

;; Build AMQL query from parameters
(defn build-amql-query [metric-name {:keys [aggregation group-by app-id api-id start end field measurement]}]
  (let [agg (str/upper-case (or aggregation "COUNT"))
        ;; Default measurement name is "requests"
        measure (or measurement field "requests")
        select-parts (cond-> [(format "%s(%s)" agg measure)]
                       group-by (concat (map #(format "\"%s\"" %) group-by)))
        select-clause (str/join ", " select-parts)
        group-clause (when group-by
                       (format " GROUP BY %s" (str/join ", " (map #(format "\"%s\"" %) group-by))))
        where-clauses (cond-> [(format "timestamp BETWEEN %d AND %d" start end)]
                        app-id (conj (format "\"app.id\" = '%s'" app-id))
                        api-id (conj (format "\"api.id\" = '%s'" api-id)))
        where-clause (str/join " AND " where-clauses)]
    (format "SELECT %s FROM \"%s\" WHERE %s%s"
            select-clause
            metric-name
            where-clause
            (or group-clause ""))))

;; GET /metric_types - List available metric types
(defn -list-metric-types [org env]
  (let [org-id (org->id org)
        env-id (env->id org env)]
    (->> @(http/get (gen-url "/observability/api/v1/metric_types")
                   {:headers (default-headers)
                    :query-params {:organizationId org-id
                                   :environmentId env-id}})
         (parse-response)
         :body)))

;; GET /metric_types/{name}:describe - Get metric structure
(defn -describe-metric [org env metric-name]
  (let [org-id (org->id org)
        env-id (env->id org env)]
    (->> @(http/get (gen-url (format "/observability/api/v1/metric_types/%s:describe"
                                    metric-name))
                   {:headers (default-headers)
                    :query-params {:organizationId org-id
                                   :environmentId env-id}})
         (parse-response)
         :body)))

;; POST /metrics:search - Query metrics with AMQL
(defn -search-metrics [org env amql-query {:keys [limit offset]}]
  (let [org-id (org->id org)
        env-id (env->id org env)
        payload {:query amql-query
                 :organizationId org-id
                 :environmentId env-id}]
    (->> @(http/post (gen-url "/observability/api/v1/metrics:search")
                    {:headers (default-headers)
                     :query-params {:offset (or offset 0)
                                    :limit (or limit 100)}
                     :body (edn->json payload)})
         (parse-response :raw)
         :body)))

;; Public handler for metrics command
(defn get-metrics [{:keys [args describe type query start end from duration
                           aggregation app-id api-id group-by limit offset]
                    [org env] :args
                    :as opts}]
  (let [org (or org *org*)
        env (or env *env*)]
    (when-not (and org env)
      (throw (e/invalid-arguments "Organization and Environment required" {:args args})))

    (cond
      ;; List metric types
      (and (not describe) (not type) (not query))
      (-list-metric-types org env)

      ;; Describe metric
      describe
      (-describe-metric org env describe)

      ;; Query with predefined type
      type
      (let [[start-ms end-ms] (resolve-time-range opts)
            metric-def (get predefined-metrics type)
            _ (when-not metric-def
                (throw (e/invalid-arguments (str "Unknown metric type: " type)
                                           {:available-types (keys predefined-metrics)})))
            amql (build-amql-query (:metric metric-def)
                                   (merge {:start start-ms
                                           :end end-ms
                                           :aggregation (or aggregation
                                                           (:default-aggregation metric-def))
                                           :group-by (or group-by
                                                        (:default-group-by metric-def))
                                           :app-id app-id
                                           :api-id api-id
                                           :measurement (:measurement metric-def)
                                           :field (:field metric-def)}))]
        (-> (-search-metrics org env amql opts)
            :data
            (add-extra-fields :org org
                             :env env
                             :metric-type type)))

      ;; Query with raw AMQL
      query
      (let [[start-ms end-ms] (resolve-time-range opts)]
        (-> (-search-metrics org env query opts)
            :data
            (add-extra-fields :org org
                             :env env))))))



(def ^:private route-body
  [["" {:help true}]
   ["|-h" {:help true}]
   ["|" {:fields [:name :id :parent-name]
         :formatter format-org-all
         :handler get-organizations}
    ;; Get orgs
    ["org"]
    ["org|{*args}"]
    ["organization"]
    ["organization|{*args}"]]

   
   
   ;; Get envs
   ["|" {:fields [:name :id :type]
         :handler get-environments}
    ["env"]
    ["env|{*args}"]
    ["environment"]
    ["environment|{*args}"]]
   
   ;; Get assets
   ["|" {:fields [:organization-id :group-id [:extra :group-name] :asset-id  :type :version]
         :handler get-assets}
    ["asset"]
    ["asset|{*args}"]]
   
   
   ;; Get proxy
   ["|" {:fields [:organization-id :environment-id :id :application-name :type :target-type :target-name ]
         :handler get-api-proxies}
    ["proxy"]
    ["proxy|{*args}"]]

   ;; Get apps
   ["|" {:fields [[:extra :org]
                  [:extra :env]
                  :name
                  :id
                  [:extra :status]
                  [:application :status :as "applied"]
                  [:extra :target]]

         :handler get-deployed-applications}
    ["app"]
    ["app|{*args}"]
    ["application"]
    ["application|{*args}"]]


   ;; Get runtime fabrics
   ["|" {:fields [:name :id :status :desired-version :vendor :region]
         :handler get-runtime-fabrics}
    ["rtf"]
    ["rtf|{*args}"]
    ["runtime-fabric"]
    ["runtime-fabric|{*args}"]]

   ;; Get runtime targets
   ["|" {:fields [:name :type :id :region :status]
         :handler get-runtime-targets}
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
         :handler get-servers}
    ["serv"]
    ["serv|{*args}"]
    ["server"]
    ["server|{*args}"]]
   
   ;; Get private spaces
   ["|" {:fields [:id :name :status :region]
         :handler get-cloudhub20-privatespaces}
    ["ps" ]
    ["ps|{*args}"]
    ["private-space"]
    ["private-space|{*args}"]]

   ;; Get secret groups
   ["|" {:fields [[:meta :id] :name [:meta :locked] :_org :_env]
         :handler get-secret-groups}
    ["sg"]
    ["sg|{*args}"]
    ["secret-group"]
    ["secret-group|{*args}"]]

   ;; Get apis
   ["|" {:fields [:id :asset-id :exchange-asset-name :status :technology 
                  :product-version :asset-version]
         :handler get-api-instances}
    ["api"]
    ["api|{*args}"]
    ["api-instance"]
    ["api-instance|{*args}"]]

   ;; All Flex Gateways (standalone + managed)
   ["|" {:fields [:id [:extra :org] [:extra :env] :name :status :source]
         :handler get-gateways}
    ["gw"]
    ["gw|{*args}"]
    ["gateway"]
    ["gateway|{*args}"]]

   ;; Get enttitlements
   ["|" {:handler get-entitlements
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
   ["|" {:handler get-available-node-ports}
    ["node-port"]
    ["node-port|{*args}"]
    ["np"]
    ["np|{*args}"]]

   ;; Contracts
   ["|" {:fields [[:application :name] :id :status :api-id [:extra :api-name]]
         :handler get-api-contracts}
    ["contract"]
    ["contract|{*args}"]
    ["cont"]
    ["cont|{*args}"]]

   ["|" {:fields [:client-name :grant-types]
         :handler get-connected-applications}
    ["connected-app"]
    ["connected-app|{*args}"]
    ["capp"]
    ["capp|{*args}"]
    ["ca"]
    ["ca|{*args}"]]

   ;; Get available scopes
   ["|" {:fields [:scope :type]
         :handler get-available-scopes
         :no-token true}
    ["scope"]
    ["scope|{*args}"]
    ["scopes"]
    ["scopes|{*args}"]]

   ["|" {:fields [:username :id :email [:extra :idp]]
         :handler get-users}
    ["user"]
    ["user|{*args}"]]

   ["|" {:fields [:team-name :team-id :org-id]
         :handler get-teams}
    ["team"]
    ["team|{*args}"]]

   ["|" {:handler get-cloudhub20-connections}
    ["conn"]
    ["conn|{*args}"]
    ["connection"]
    ["connection|{*args}"]]

   ["|" {:handler get-api-policies}
    ["policy"]
    ["policy|{*args}"]
    ["pol"]
    ["pol|{*args}"]]

   ;; IDP
   ["|" {:handler get-identity-providers
         :fields [ :name [:extra :id] [:extra :org] [:extra :type]]}
    ["idp"]
    ["idp|{*args}"]]

   ;; Client Providers (OpenID Connect client management)
   ["|" {:handler get-client-providers
         :fields [:name [:extra :id] [:extra :org] [:extra :type]]}
    ["cp"]
    ["cp|{*args}"]
    ["client-provider"]
    ["client-provider|{*args}"]]

   ;; Metrics
   ["|" {:handler get-metrics
         :fields [:name :label :description]}
    ["metrics"]
    ["metrics|{*args}"]]

   ;; Alerts (--type api|app|server)
   ["|" {:handler get-alerts
         :fields [:alert-type :alert-id :alert-name :name :severity :alert-state :state :metric-type
                  [:extra :api-id] [:extra :app-id] [:extra :resource-type]]}
    ["alert"]
    ["alert|{*args}"]]])

(def route
  (for [op ["list" "ls" "get"]]
    (into [op {:options options :usage usage}] route-body)))




;; https://anypoint.mulesoft.com/standalone/api/v1/organizations/fe1db8fb-8261-4b5c-a591-06fea582f980/environments/0d0debc2-8327-4e41-b5fb-7911421cc2c5/gateways?pageNumber=0&pageSize=20&name=&status=
