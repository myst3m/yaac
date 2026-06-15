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
(def ^:dynamic *quiet* false)
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
  (let [xs (->> body (mapv #(list `async/go (list 'try % (list 'catch 'Exception 'e [(list 'assoc (list 'ex-data 'e) :error true)])))))]
    `(if-not ~no-multi-thread?
       (let [results# (reduce (fn [r# x#] (vec (concat r# (async/<!! x#)))) [] ~xs)
             errs# (->> (filter :error results#)
                        (map #(dissoc % :error)))]

         (if (seq errs#)
           (do
             (throw (ex-info "Errors in threads" (e/multi-errors errs#))))
           results#))
       (async/<!! (async/go (concat ~@body))))))



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
             "  - team [<name> --member | --role]       Get teams; or one team's members / roles"
             "  - role [filter]                         Get permission roles (deprecated; prefer teams)"
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
           ""
           "# Teams: list, then a team's members or roles"
           "  > yaac get team"
           "  > yaac get team admin --member"
           "  > yaac get team admin --role"
           ""])
         (str/join \newline))))

(def options [["-g" "--group NAME" "For asset query. Same as organization_ids=ID"]
              ["-a" "--asset NAME" "For asset query. Asset name"]
              ["-v" "--version VERSION" "For asset query. Asset version"]
              ["-Q" "--search-term STRING" "Query string. Same as search-term=STRING"
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
               :parse-fn parse-long]
              ;; Team sub-views
              [nil "--member" "For `get team <name>`: list the team's members"
               :id :member]
              [nil "--role" "For `get team <name>`: list the roles assigned to the team"
               :id :role]])

;; (defn get* [{:keys [args] :as opts}]
;;   (let []
;;     (println (get-usage summary))))

(defn -get-organizations []
  (-> (-get-me) :body :user :member-of-organizations))

(defn -get-environments [org]
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

(defn prefix-match? [input actual]
  (and actual input
       (str/starts-with? (str actual) (str input))))

(defn org->name [id-or-name]
  (let [xs (-get-organizations)]
    (last (first (filter #((set %) id-or-name) (map (juxt :id :name) xs))))))

(defn env->name [org-id-or-name id-or-name]
  (let [xs (-get-environments (org->name org-id-or-name))]
    (last (first (filter #((set %) id-or-name) (map (juxt :id :name) xs))))))

;; No throw exception for get functions so as to query OOB assets
(defn org->id* [id-or-name]
  (if (= id-or-name "global")
    mule-business-group-id
    (let [orgs (-get-organizations)
          pairs (map (juxt :id :name) orgs)]
    (or (ffirst (filter #((set %) id-or-name) pairs))
        ;; prefix match fallback
        (let [matches (filter #(prefix-match? id-or-name (first %)) pairs)]
          (when (= 1 (count matches))
            (ffirst matches)))
        ;; UUID形式ならIDとしてそのまま返す、それ以外はnil（org->idでthrowさせる）
        (when (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" (str id-or-name))
          id-or-name)))))

(defn org->id [id-or-name]
  (or (org->id* id-or-name)
      (throw (e/org-not-found "Not found organization" :org id-or-name))))



(defn env->id [org-id-or-name id-or-name]
  (let [xs (-get-environments (org->name org-id-or-name))
        pairs (map (juxt :id :name) xs)
        env-id (or (ffirst (filter #((set %) id-or-name) pairs))
                   (let [matches (filter #(prefix-match? id-or-name (first %)) pairs)]
                     (when (= 1 (count matches))
                       (ffirst matches))))]
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

(defn short-uuid [s]
  (if-let [[_ prefix] (re-matches #"([0-9a-f]{8})-[0-9a-f]{4}-.*" (str s))]
    prefix
    (str s)))

(defn count-visual-widths-list [key-fields data]
  (->> key-fields
       (map (fn [k]
              (let [ks (flatten [k])
                    ukey (take-while #(and (not= :as %) (not= :fmt %)) ks)
                    fmt-fn (second (drop-while #(not= :fmt %) ks))
                    [title] (reverse (drop-while #(not= :as %) ks))]

                [ukey
                 (->> data
                      (map (apply comp (reverse ukey))) ;; see below to know why is reverse being used .
                      (map (comp count-visual-width str (or fmt-fn identity))))
                 (or title (name (last ukey)))])))))

(defn default-format-by [fields output-type data {:keys [no-header] :as opts}]
  (log/trace "fields:" fields)
  (log/trace "output-type:" output-type)
  (log/trace "first data:" (first data))
  (log/trace "options:" (dissoc opts :summary))

  (cond
    (sequential? data)
    (let [data (keep #(if (instance? clojure.lang.ExceptionInfo %) (ex-data %) %) data) ;; Anypoint reponses [null] for get proxy...
          count-alist (count-visual-widths-list fields data)
          ;; ukeys are uniformed key  [[:id] [:name] [:status :application] [:status] [:last-modified-date] [:target-id :target]]
          ukeys (map (comp vec first) count-alist)]

      (log/trace "Keys:" ukeys)
      (log/trace "Counts: " count-alist)

      (case output-type
        :json (yutil/json-pprint (mapv #(dissoc % :extra) data))
        :edn (with-out-str (clojure.pprint/pprint (mapv #(dissoc % :extra) data)))
        :yaml (yaml/generate-string (mapv #(dissoc % :extra) data))
        (let [count-map (into {} (map #(vec (take 2 %)) count-alist))
              title-map (into {} (map #(vector (first %) (last %)) count-alist))]
          ;; count-alist [[:id] [{...}] :title-id]
          ;; title-map: {(:id) id, (:name) name, (:entitlements :v-cores-production :assigned) production, (:entitlements :v-cores-sandbox :assigned) assigned, (:entitlements :static-ips :assigned) assigned, (:entitlements :network-connections :assigned) assigned, (:entitlements :vpns :assigned) assigned}
          
          (log/trace count-map)
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
    (do (log/trace "default-format-by" data)
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
          (let [sections [{:key :environments :fields [[:name] [:id :fmt short-uuid] [:type]] :title "Environments"}
                          {:key :apps :fields [[[:extra :env]] [:name] [:status] [[:application :status] :as "app-status"]] :title "Apps"}
                          {:key :assets :fields [[:asset-id] [:type] [:version]] :title "Assets"}
                          {:key :apis :fields [[[:extra :env]] [:asset-id] [:asset-version]] :title "APIs"}
                          {:key :gateways :fields [[[:extra :env]] [:name] [:id :fmt short-uuid]] :title "Gateways"}
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
                            :status #(:status %)
                            :target #(target->name org-id env-id (get-in % [:target :target-id])))))))

(defn -enrich-application
  "Enrich a list-level app with detail API data for -o wide display"
  [org env appi]
  (let [app-type (csk/->kebab-case-keyword
                  (or (-> appi :target :type)
                      (-> appi :target :provider)
                      :none))]
    (try
      (case app-type
        :mc
        (let [org-id (org->id org)
              env-id (env->id org env)
              app-id (:id appi)]
          (-> @(http/get (format (gen-url "/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments/%s") org-id env-id app-id)
                        {:headers (default-headers)})
              (parse-response)
              :body
              (as-> result
                  (first (add-extra-fields result
                                           :org org :env env
                                           :status (:status result)
                                           :target (target->name org-id env-id (get-in result [:target :target-id])))))))

        :server
        (let [org-id (org->id org)
              env-id (env->id org env)
              app-id (:id appi)]
          (-> @(http/get (format (gen-url "/hybrid/api/v1/applications/%s") app-id)
                        {:headers (assoc (default-headers)
                                         "X-ANYPNT-ORG-ID" org-id
                                         "X-ANYPNT-ENV-ID" env-id)})
              (parse-response)
              :body
              :data
              (as-> result
                  (first (add-extra-fields result
                                           :org org :env env
                                           :status (when (:started result) "STARTED")
                                           :target (get-in result [:target :name]))))))

        ;; default: return as-is
        appi)
      (catch Exception e
        (log/debug "enrich failed for" (:name appi) (ex-message e))
        appi))))

;; Flex Gateway and Runtime Fabric moved to yaac.core.gateway.

(def -get-container-applications (memoize -get-container-applications))



;; CloudHub 2.0 privatespaces moved to yaac.core.cloudhub2.

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
      (= 1 (count apis))
      (:id (first apis))
      (< 1 (count apis))
      (throw (e/multiple-api-name-found "Found multiple API instances" {:apis (mapv :id apis)}))
      :else
      ;; prefix match fallback on all APIs in the env
      (let [all-apis (-get-api-instances org env)
            matches (filter #(prefix-match? api (str (:id %))) all-apis)]
        (cond
          (= 1 (count matches)) (:id (first matches))
          (< 1 (count matches)) (throw (e/multiple-api-name-found "Multiple APIs match prefix" {:apis (mapv :id matches)}))
          :else (throw (e/api-not-found "No api found" {:api api})))))))


(defn target->id [org env target]
  (let [all (->> (pmap (fn [f] (f)) [#(-get-runtime-cloud-targets org) #(-get-servers org env)])
                 (apply concat))
        exact (->> all (filter #(or (= target (:name %)) (= target (str (:id %))))) first)]
    (or (:id exact)
        (let [matches (->> all (filter #(prefix-match? target (str (:id %)))))]
          (when (= 1 (count matches))
            (:id (first matches)))))))

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

;; provider->name lives in yaac.core.identity, which depends on this ns.
;; Resolve it lazily at call time to avoid a circular namespace dependency.
(defn- provider->name* [idp-id]
  (when-let [f (try (requiring-resolve 'yaac.core.identity/provider->name)
                    (catch Exception _ nil))]
    (f idp-id)))

;;; Get
(defn -get-users [org]
  (let [org (or org *org*)
        org-id (org->id org)]
    (-> @(http/get (format (gen-url "/accounts/api/organizations/%s/users") org-id)
                   {:headers (default-headers)})
         (parse-response)
         :body
         :data
         (add-extra-fields :idp #(provider->name* (get % :idprovider-id )))
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

(declare get-team-members get-team-roles)

(def ^:private team-list-fields
  [:team-name [:team-id :fmt short-uuid] :team-type [:org-id :fmt short-uuid]])
(def ^:private team-member-fields
  [:name [:id :fmt short-uuid] :identity-type :membership-type [:extra :team]])
(def ^:private team-role-fields
  [:name [:role-id :fmt short-uuid] :context-params [:extra :team]])

(defn get-teams
  "List teams, or — with --member / --role — the members or roles of one team.
     yaac get team
     yaac get team <name> --member
     yaac get team <name> --role"
  [{:keys [member role] :as opts}]
  (cond
    member (with-meta (get-team-members opts) {:fields team-member-fields})
    role   (with-meta (get-team-roles opts)   {:fields team-role-fields})
    :else  (with-meta (-get-teams)            {:fields team-list-fields})))

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

(defn- teams-base-url []
  (format (gen-url "/accounts/api/organizations/%s/teams")
          (:id (-get-root-organization))))

(defn -get-team-members [team-id]
  (-> @(http/get (str (teams-base-url) "/" team-id "/members")
                 {:headers (default-headers)})
      (parse-response)
      :body
      :data))

(defn get-team-members [{[team] :args}]
  ;; resolve the team fresh so a just-created team is found without -Z
  (let [team-id (binding [*no-cache* true] (team->id team))]
    (-> (-get-team-members team-id)
        (add-extra-fields :team (binding [*no-cache* true] (team->name team-id))))))

(defn -get-team-roles [team-id]
  (-> @(http/get (str (teams-base-url) "/" team-id "/roles")
                 {:headers (default-headers)})
      (parse-response)
      :body
      :data))

(defn get-team-roles [{[team] :args}]
  (let [team-id (binding [*no-cache* true] (team->id team))]
    (-> (-get-team-roles team-id)
        (add-extra-fields :team (binding [*no-cache* true] (team->name team-id))))))

(defn -get-roles []
  (-> @(http/get (gen-url "/accounts/api/roles?limit=1000")
                 {:headers (default-headers)})
      (parse-response)
      :body
      :data))

(def -get-roles (memoize-file -get-roles))

(defn get-roles [{:keys [args]}]
  (let [term (first args)]
    (cond->> (-get-roles)
      term (filter #(re-find (re-pattern (str "(?i)" (java.util.regex.Pattern/quote term)))
                             (str (:name %)))))))

(defn role->id
  "Resolve a role name (or role-id) to its role-id."
  [name-or-id]
  (let [roles (-get-roles)
        r (first (filter #(or (= name-or-id (:role-id %))
                              (= name-or-id (:name %)))
                         roles))]
    (or (:role-id r)
        (throw (e/no-item (str "Role not found: " name-or-id)
                          {:hint "yaac get role lists available role names"})))))


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

;; Identity Providers / Client Providers moved to yaac.core.identity.



(def -get-hybrid-applications (memoize -get-hybrid-applications))

(defn -get-deployed-applications [org env]
  (on-threads *no-multi-thread*
    (-get-container-applications org env)
    (-get-hybrid-applications org env)))

;; (def -get-deployed-applications (memoize -get-deployed-applications))

(defn name->apps [org env app]
  (let [exact (->> (-get-deployed-applications org env)
                   (filter #(or (= (:name %) app) (= (:id %) app))))]
    (if (seq exact)
      exact
      (->> (-get-deployed-applications org env)
           (filter #(prefix-match? app (str (:id %))))))))


(defn- -append-vcore-total
  "wide表示時にvCore/replicas合計のサマリ行を末尾に追加"
  [apps]
  (let [apps (vec apps)
        total-vcores (reduce + 0 (keep #(get-in % [:application :v-cores]) apps))
        total-replicas (reduce + 0 (keep #(get-in % [:target :replicas]) apps))]
    (if (seq apps)
      (conj apps {:name "TOTAL"
                  :application {:v-cores (/ (Math/round (* total-vcores 10000.0)) 10000.0)}
                  :target {:replicas total-replicas}})
      apps)))

(defn get-deployed-applications [{:keys [args no-multi-thread search-term output-format]
                                  :as opts}]
  (log/debug "Opts:" opts)
  (let [{:keys [all]} opts
        wide? (= :wide (keyword (or output-format "")))
        ;; 引数: 0=デフォルト, 1=org, 2=org+env, 3=org+env+search
        [org env search] (case (count args)
                           0 [*org* *env* nil]
                           1 [(first args) *env* nil]
                           2 [(first args) (second args) nil]
                           [(first args) (second args) (nth args 2 nil)])
        search-term (or search-term search)]
    (if all
      (cond->> (->> (-get-organizations)
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
        wide? ((comp -append-vcore-total #(pmap (fn [a] (-enrich-application (get-in a [:extra :org]) (get-in a [:extra :env]) a)) %))))
      (if-not (and org env)
        (throw (e/invalid-arguments "Org and Env need to be specified" {:args args
                                                                        :availables {:org (map :name (-get-organizations))
                                                                                    :env (map :name (-get-environments org))}}))
        (cond->> (->> (-get-deployed-applications org env)
                      (filter #(re-find (re-pattern (or (str/join search-term) ".")) (:name %))))
          wide? ((comp -append-vcore-total #(pmap (fn [a] (-enrich-application org env a)) %))))))))


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
           target-filter (fn [item]
                           (or (= :any runtime-target-id)
                               (= (-> item :target :id) runtime-target-id)
                               (= (-> item :target :target-id) runtime-target-id)
                               (= (-> item :target :name) runtime-target-id)))
           apps (->> xs
                     (filter #(and (target-filter %)
                                   (or (= (str (:id %)) id-or-name)
                                       (= (:name %) id-or-name)))))
           apps (if (seq apps) apps
                    ;; prefix match fallback
                    (->> xs
                         (filter #(and (target-filter %)
                                       (prefix-match? id-or-name (str (:id %)))))))]
       (cond
         (= 0 (count apps))  (throw (e/app-not-found "No app found" {:app id-or-name :available (map :id xs)}))
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

(def -get-container-application-specs (memoize -get-container-application-specs))

;; CloudHub 2.0 (entitlements, node ports, ps->id, transit gateway, VPN, connections)
;; moved to yaac.core.cloudhub2.
;; API Policy / Upstream management moved to yaac.core.policy.

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

;; Alerts (api/app/server) moved to yaac.core.alerts.
;; Metrics (observability/AMQL) moved to yaac.core.metrics.
;; Routing table for `yaac list / ls / get` is defined in yaac.core.routing.
