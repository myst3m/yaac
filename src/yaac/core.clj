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
            [silvur.http :as http]
            [silvur.nio :as nio]
            [org.httpkit.client :refer [url-encode]]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [silvur.util :refer [json->edn edn->json]]
            [silvur.log :as log]
            [silvur.http]
            [org.httpkit.server :refer [run-server]]
            [clojure.data.json :as json]
            [org.httpkit.client :as hk]
            [yaac.util :as util]
            [yaac.error :as e]
            [clojure.data.xml :as dx]
            [clojure.set :as set]
            [clj-yaml.core :as yaml]
            [clojure.core.async :as async :refer [go <!!]]
            [yaac.core :as yc]            ))


(def ^:dynamic *org*)
(def ^:dynamic *env*)
(def ^:dynamic *deploy-target*)
(def ^:dynamic *no-cache*)
(def ^:dynamic *no-multi-thread*)
(def ^:dynamic *console*)
(def mule-business-group-id "68ef9520-24e9-4cf2-b2f5-620025690913")

(defmacro try-wrap [& body]
  `(try
     ~@body
     (catch Exception e# )))



(defn memoize-file [f]
  (let [memo-file (io/file (System/getenv "HOME") ".yaac" "cache")
        memo-key (str/replace (str f) #"@.*" "")]
    (letfn [(store-cache [f cache-map & args]
              (let [ret (apply f args)]
                (io/make-parents memo-file)
                (spit memo-file (assoc-in cache-map [memo-key args] ret))
                ret))]
      (memoize
       (fn [& args]
         (if (true? *no-cache*)
           (apply f args)
           (if (and (.exists memo-file) (false? *no-cache*))
             (let [cache (read-string (slurp memo-file))]
               (log/debug "cache file:" (str memo-file))
               (if-let [cached-ret (get-in cache [memo-key args])]
                 (do (log/debug "cache hit:" memo-key)
                     cached-ret)
                 (do (log/debug "cache miss:" memo-key)
                     (apply store-cache f cache args))))
             (apply store-cache f {} args))))))))

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


(defn slurp-pom-file [jar-path]
  (with-open [zis (ZipInputStream. (FileInputStream. (io/file jar-path)))]
    (let [pom-entry (first (filter #(re-find #".*/pom.xml" (.getName ^ZipEntry %)) (repeatedly #(.getNextEntry zis) )))
          pom-content (loop [buf (byte-array 1024)
                             s ""]
                        (let [size (.read zis buf)]
                          (if (not= -1  size)
                            (recur (byte-array 4096) (str s (subs (String. buf) 0 size)))
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
           (let [raw (json->edn csk-type body)
                 ex (e/error "Request failed" {:status status
                                               :message (or (:error (:message raw)) (:message raw) raw "general error")
                                               :raw raw})]
             (throw ex))
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
  (let [cred-file (io/file yc/default-credentials-path)]
    (spit cred-file (with-out-str
                      (json/pprint (cond-> (assoc-in (if (.exists cred-file)
                                                       (json->edn :raw (slurp cred-file))
                                                       {})
                                                     [name] {"client_id" id
                                                             "client_secret" secret
                                                             "grant_type" grant-type
                                                             })
                                     (seq scope) (assoc-in  [name "scope"] (or scope "full"))))))))


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
  (-> (http/get url {:headers (default-headers)})
      (parse-response)))


(defn -get-me []
  (-> (http/get "https://anypoint.mulesoft.com/accounts/api/me"
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
(defn usage [summary-options]
  (->> ["Usage: get <resource> ..."
        ""
        "Options:"
        ""
        summary-options
        ""
        "Resources:"
        ""
        "  - organization                          Get organizations"
        "  - environment [org]                     Get environements"
        "  - application [org] [env]               Get deployed application"
        "  - api [org] [env]                       Get API instances"
        "  - contract [org] [env]                  Get contracts"        
        "  - runtime-fabric [org]                  Get Runtime Fabric clusters"
        "  - server [org] [env]                    Get servers (onpremise)"
        "  - private-space  [org] [env]            Get CloudHub 2.0 Private Spaces "
        "  - asset [org] key1=val1 key2=val2 ...   Get assets in Anypoint Exchange"
        "  - user [org]                            Get users that belong to the organization"
        "  - connected-app                         Get connected applications"
        "  - runtime-target [org] [env]            Get runtime targets of RTF and CloudHub 2.0"
        "  - policy [org] [env] api"
        "  - entitlement                           Get entitilements for each runtime"
        "  - node-port [org]                       Get available node ports for apps using TCP"
        "                                          This function is required to be authenticated by 'Act as an user'"
        ""
        "Exchange Graph API Keys:"
        ""
        (str/join \newline (->> (partition 2 (:assets graphql-query-options-group))
                             (map #(format "  - %-25s %-10s" (name (first %)) (second %)))
                             (seq)))
        ""
        "Fields:"
        ""
        "A field name that starts with '+' is treated as the additional field."
        ""
        (str "  " (str/join " | " (map name graphql-result-fields)))
        ""
        ""
        "Examples:"
        ""
        "For every sub commands, short cuts are available as below"
        ""
        "> get org"
        "> get env T1"
        "> get app T1 Production"
        "> get api T1 Production"
        "> get cont T1 Production"
        "> get rtf T1 "
        "> get server T1 Production"
        "> get ps T1 "
        "> get asset types=app,rest-api limit=100"
        "> get asset T1"
        "> get asset T1 -F group-id,asset-id,name"
        "> get asset -F +organization-id -g T1 -a hello-api"
        "> get user T1"
        "> get ca"
        "> get ent"
        "> get np T1"
        ""]
       (str/join \newline)))

(def options [["-g" "--group NAME" "For asset query. Same as organization_ids=ID"]
              ["-a" "--asset NAME" "For asset query. Asset name"]
              ["-v" "--version VERSION" "For asset query. Asset version"]
              ["-q" "--search-term STRING" "Query string. Same as search-term=STRING"
               :parse-fn #(str/split % #",")]
              ["-A" "--all" "Query assets in all organizations or all applications"]
              ["-F" "--fields FIELDS" "Fields for assets list"
               :parse-fn #(mapv csk/->kebab-case-keyword (str/split % #","))]])

;; (defn get* [{:keys [args] :as opts}]
;;   (let []
;;     (println (get-usage summary))))

(defn- -get-organizations []
  (-> (-get-me) :body :user :member-of-organizations))

(defn- -get-environments [org]
  (let [org-id (and org (:id (first (filter #(= (name org) (:name %)) (-get-organizations)) )))]
    (if org-id
      (-> (http/get (format "https://anypoint.mulesoft.com/accounts/api/organizations/%s/environments"
                             org-id)
                     {:headers (default-headers)})
          (parse-response)
          :body
          :data)
      (throw (e/org-not-found "Not found organization" :org (or org-id org))))))

(def -get-environments (memoize-file -get-environments))

;; Exposed  get-* functions

;;; allow to receive option map for abstraction
(defn get-organizations [& _]
  (-get-organizations))


(defn get-environments [{[org] :args :as opts}]
  (-get-environments (or org *org*)))


;; Org/Env name does not require to throw exception since it is not used for query.
;;

(defn org->name [id-or-name]
  (let [xs (get-organizations)]
    (last (first (filter #((set %) id-or-name) (map (juxt :id :name) xs))))))

(defn env->name [org-id-or-name id-or-name]
  (let [xs (-get-environments (org->name org-id-or-name))]
    (last (first (filter #((set %) id-or-name) (map (juxt :id :name) xs))))))

;; No throw exception for get functions so as to query OOB assets
(defn org->id* [id-or-name]
  (or (->> (get-organizations)
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
                      (->> (concat (:organization-ids opts) [(or org *org*)])
                          (keep identity )
                          (mapv org->id )
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
    (-> (http/post "https://anypoint.mulesoft.com/graph/api/v2/graphql"
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
        :json (with-out-str (json/pprint (mapv #(dissoc % :extra) data)))
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
  (let []
    ;; Added group-id and asset-id as keys so as to query by asset(...)
    (-> (graphql-search-assets (cond-> opts
                                 group (assoc :group-id (org->id (or group *org*)))
                                 asset (assoc :asset-id asset)))
        (->> (filter #(or all (and (= (:organization-id %) (org->id (or org *org*)))
                                   (= (:group-id %) (or (try-wrap (org->id group))
                                                        group
                                                        (try-wrap (org->id org))
                                                        (try-wrap (org->id *org*))))))))
        (add-extra-fields :group-name #(org->name (:group-id %)))
        (->> (map #(assoc % :organization-name (or (org->name (:organization-id %)) "-")))))))



(declare -get-runtime-targets)

(defn target->name [org env target]
)

(defn -get-container-applications [org env]
  (when (and org env)
    (let [org-id (org->id org)
          env-id (env->id org env)]
      (-> (http/get (format "https://anypoint.mulesoft.com/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments"  org-id env-id)
                    {:headers (default-headers)})
          (parse-response)
          :body
          :items
          (add-extra-fields :org org :env env
                            :status #(get-in % [:application :status])
                            :target #(target->name org-id env-id (get-in % [:target :target-id])))))))

(def -get-container-applications (memoize -get-container-applications))

(defn -get-runtime-fabrics [org]
  (let [org (or org *org*)
        org-id (org->id org)]
    (if org-id
      (->> (http/get (format "https://anypoint.mulesoft.com/runtimefabric/api/organizations/%s/fabrics/" org-id)
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
      (->> (http/get (format "https://anypoint.mulesoft.com/runtimefabric/api/organizations/%s/privatespaces" org-id)
                     {:headers (default-headers)})
           (parse-response)
           :body
           :content)
      (throw (e/org-not-found "Not found organization" :org (or org-id org))))))

(defn get-cloudhub20-privatespaces [{[org] :args :as opts}]
  (-get-cloudhub20-privatespaces (or org *org*)))


(defn -get-api-instances 
  ([org env]
   (let [org-id (org->id org)
         env-id (env->id org env)]
     (->> (http/get (format "https://anypoint.mulesoft.com/apimanager/api/v1/organizations/%s/environments/%s/apis" org-id env-id)
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
    (->> {:headers (assoc (default-headers)
                          "X-ANYPNT-ORG-ID" org-id
                          "X-ANYPNT-ENV-ID" env-id)}
         (http/get "https://anypoint.mulesoft.com/hybrid/api/v1/servers")
         (parse-response)
         :body
         :data)))

(defn get-servers [{[org env] :args}]
  (let [org (or org *org*)
        env (or env *env*)]
    (-get-servers org env)))

(defn -get-runtime-cloud-targets [org-sym]
;; This function is required be authenticated by 'Act as an user'
  (let [org-id (org->id org-sym)]
    (if org-id
      (->> (http/get (format "https://anypoint.mulesoft.com/runtimefabric/api/organizations/%s/targets" org-id)
                      {:headers (default-headers)})
           (parse-response)
           :body
           ;; workaround for issue that  Anypoint returns multiple record if Private Space is associated to multiple envs.
           set
           vec)
      (throw (e/org-not-found "Not found organization" :org (or org-id org-sym))))))



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

;;; Get

(defn get-user [{[org] :args}]
  (let [org (or org *org*)
        org-id (org->id org)]
    (->> (http/get (format "https://anypoint.mulesoft.com/accounts/api/organizations/%s/users" org-id)
                    {:headers (default-headers)})
         (parse-response)
         :body
         :data)))

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


(def -get-runtime-cloud-targets (memoize-file -get-runtime-cloud-targets))
(def -get-servers (memoize-file -get-servers))

(defn get-runtime-targets [{[org env] :args}]
  (let [org (or org *org*)
        env (or env *env*)]
    (-get-runtime-targets org env)))

(def -get-runtime-targets (memoize-file -get-runtime-targets))




(defn get-connected-applications [_]
  (->> (http/get "https://anypoint.mulesoft.com/accounts/api/connectedApplications"
                   {:headers (default-headers)})
       (parse-response)
       :body
       :data))


(defn -get-api-proxies [org env api]
  (let [org-id (org->id org)
        env-id (env->id org env)
        api-id (yc/api->id org env api)]
    (-> (http/get (format "https://anypoint.mulesoft.com/proxies/xapi/v1/organizations/%s/environments/%s/apis/%s/deployments" org-id env-id api-id)
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
    (-> (http/get "https://anypoint.mulesoft.com/hybrid/api/v1/applications"
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
                                  [org env] :args :as opts}]
  (log/debug "Opts:" opts)
  (let [{:keys [all]} opts]
    (if all
      (->> (yc/get-organizations)
           (mapcat (fn [{g :name}]
                     (try
                       (->> (yc/-get-environments g)
                           (mapv (fn [{e :name}] [g e])))
                       (catch Exception e (log/debug (ex-cause e))))))
           (pmap (fn [[g e]] (try
                               (-get-deployed-applications g e)
                               (catch Exception e (log/debug (ex-cause e))))))
           (apply concat)
           (filter #(re-find (re-pattern (or search-term ".")) (:name %))))
      (let [org (or org *org*)
            env (or env *env*)]
        (if-not (and org env)
          (throw (e/invalid-arguments "Org and Env need to be specified" {:args args
                                                                          :availables {:org (map :name (-get-organizations))
                                                                                      :env (map :name (-get-environments org))}}))
          (->> (-get-deployed-applications org env)
               (filter #(re-find (re-pattern (or (str/join search-term) ".")) (:name %)))))))))


(defn -get-api-contracts [org env api]
  (let [org-id (org->id org)
        env-id (env->id org-id env)
        api-id (yc/api->id org env api)]
    (-> (http/get (format "https://anypoint.mulesoft.com/apimanager/api/v1/organizations/%s/environments/%s/apis/%s/contracts" org-id env-id api-id)
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
    (take-last 1 (sort-by :version (yc/get-assets {:group group :asset asset :types ["app"]})))

    (or (seq labels) search-term) ;; Graph Query in get-assets uses 'labels' instead of 'tags'...
    (cond-> {:group group :types ["app"]}
      (seq labels) (assoc  :labels labels)
      (seq search-term) (assoc :search-term search-term)
      :always (yc/get-assets ))
    
    :else
    (throw (e/invalid-arguments "An app and GAV are not specified. Otherwise you should specify tags to filter." (dissoc opts :summary)))))


(defn equal-kvs->map [resource-opts]
  (try
    (into {} (mapv #(vec (str/split % #"=")) resource-opts))
    (catch Exception e {})))


(defn target->id [org env id-or-name]
  (let [targets (filter #(or (= (:name %) id-or-name)
                             (= (:id %) id-or-name))
                        (-get-runtime-targets org env) )]
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
                                   ;; CH20
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
    (-> (http/get (format "https://anypoint.mulesoft.com/amc/application-manager/api/v2/organizations/%s/environments/%s/deployments/%s/specs"  org-id env-id deployment-id)
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
              (->> (http/get (format "https://anypoint.mulesoft.com/accounts/api/organizations/%s" (:id %))
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
              (-> (http/get (format "https://anypoint.mulesoft.com/runtimefabric/api/organizations/%s/privatespaces/%s/ports" org-id (:id %))
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
    (-> (http/get (format "https://anypoint.mulesoft.com/runtimefabric/api/organizations/%s/privatespaces/%s/transitgateways" org-id ps-id)
                  {:headers (default-headers)})
        (parse-response)
        :body)))

(defn -get-cloudhub20-vpns [org ps]
  (let [org-id (org->id (or org *org*))
        ps-id (ps->id org-id ps)]
    (-> (http/get (format "https://anypoint.mulesoft.com/runtimefabric/api/organizations/%s/privatespaces/%s/connections" org-id ps-id)
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
    (-> (http/get (format "https://anypoint.mulesoft.com/runtimefabric/api/organizations/%s/privatespaces/%s/transitgateways" org-id ps-id)
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
    (-> (http/get (format "https://anypoint.mulesoft.com/apimanager/api/v1/organizations/%s/environments/%s/apis/%s/policies"
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
    (-> (http/get (format "https://anypoint.mulesoft.com/apimanager/api/v1/organizations/%s/automated-policies"
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


;; Need Titanium subscription
(defn -get-monitoring-entity-id [org env entity-type]
  (let [org-id (org->id org)
        env-id (env->id org env)
        etype (keyword entity-type)]
    (-> (http/get (format "https://monitoring.anypoint.mulesoft.com/monitoring/archive/api/v1/organizations/%s/environments/%s/%s"
                          org env entity-type)
                  {:headers (default-headers)})
        (parse-response)
        :body)))



(defn password-test-post []
  (-> (http/post "https://login.salesforce.com/services/oauth2/token"
                 {:query-params {"grant_type" "password"
                                 "client_id" "3MVG9Rr0EZ2YOVMYfgg4NCfRt63Jo0z87mV.r3sp2J.mbmu2.RYiSzlC1zOApPYtxxYSb1fm.RMWHfzlBsF9f"
                                 "client_secret" "9CE17EA1D976F036B72E6FC04C208742C7EC6D3C9851F7876FC6AA23CA25AA2D"
                                 "username" "tmiyashita@tmiyashita-240319-795.demo"
                                 "password" "GNU%Emacs29"}})
      :body
      ))



(def route
  ["get" {:options options
          :usage usage}
   ["" {:help true}]   
   ["|-h" {:help true}]
   ["|" {:fields [:name :id :parent-name]
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
   
   ;; Get apis
   ["|" {:fields [:id :asset-id :exchange-asset-name :status :technology 
                  :product-version :asset-version]
         :handler get-api-instances}
    ["api"]
    ["api|{*args}"]
    ["api-instance"]
    ["api-instance|{*args}"]]

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
    ["ca"]
    ["ca|{*args}"]]
   
   ["|" {:fields [:username :id :last-name :email :org-type]
         :handler get-user}
    ["user"]
    ["user|{*args}"]]

   ["|" {:handler get-cloudhub20-connections}
    ["conn"]
    ["conn|{*args}"]
    ["connection"]
    ["connection|{*args}"]]
   
   ["|" {:handler get-api-policies}
    ["policy"]
    ["policy|{*args}"]
    ["pol"]
    ["pol|{*args}"]]])
