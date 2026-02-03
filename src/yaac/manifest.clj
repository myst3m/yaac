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

(ns yaac.manifest
  "Manifest-based multi-app deployment"
  (:require [yaac.yaml :as yaml]
            [yaac.core :as yc :refer [*org* *env* *deploy-target* *no-multi-thread*
                                      org->id env->id org->name env->name
                                      target->id target->name]]
            [yaac.deploy :as deploy]
            [yaac.error :as e]
            [yaac.util :refer [edn->json]]
            [malli.core :as m]
            [malli.error :as me]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.cli :refer [parse-opts]]
            [taoensso.timbre :as log])
  (:import [java.io File]
           [java.util Properties]))

;;; ============================================================
;;; Malli Schema
;;; ============================================================

(def TargetType
  [:enum :rtf :ch2 :hybrid "rtf" "ch2" "hybrid"])

(def RtfTarget
  [:map
   [:type [:enum :rtf "rtf"]]
   [:cpu {:optional true} [:vector :string]]
   [:mem {:optional true} [:vector :string]]])

(def Ch2Target
  [:map
   [:type [:enum :ch2 "ch2"]]
   [:instance-type {:optional true} :string]
   [:v-cores {:optional true} [:or :double :int]]])

(def HybridTarget
  [:map
   [:type [:enum :hybrid "hybrid"]]])

;; Unified target schema that accepts any target type
(def Target
  [:map
   [:type TargetType]
   [:cpu {:optional true} [:vector :string]]
   [:mem {:optional true} [:vector :string]]
   [:instance-type {:optional true} :string]
   [:v-cores {:optional true} [:or :double :int]]])

(def App
  [:map
   [:name :string]
   [:asset :string]  ;; group:artifact:version format
   [:target :string]
   [:replicas {:optional true} :int]
   [:properties {:optional true} [:map-of :keyword :any]]
   [:connects-to {:optional true} [:vector :string]]])

(def Manifest
  [:map
   [:organization :string]
   [:environment :string]
   [:targets [:map-of :keyword Target]]
   [:apps [:vector App]]])

;;; ============================================================
;;; Validation
;;; ============================================================

(defn validate-manifest
  "Validate manifest against schema. Returns {:valid true :data manifest} or {:valid false :errors ...}"
  [manifest]
  (if (m/validate Manifest manifest)
    {:valid true :data manifest}
    {:valid false
     :errors (me/humanize (m/explain Manifest manifest))}))

(defn parse-asset
  "Parse asset string 'group:artifact:version' into map"
  [asset-str]
  (let [parts (str/split asset-str #":")]
    (when (= 3 (count parts))
      {:group (nth parts 0)
       :artifact (nth parts 1)
       :version (nth parts 2)})))

;;; ============================================================
;;; Helpers
;;; ============================================================

(defn normalize-type
  "Normalize target type to keyword"
  [t]
  (if (string? t) (keyword t) t))

;;; ============================================================
;;; URL Generation for connects-to
;;; ============================================================

(defn resolve-app-url
  "Resolve URL for an app based on its target type and port"
  [app target-config all-apps]
  (let [port (get-in app [:properties :http.port] 8081)
        app-name (:name app)
        target-type (normalize-type (:type target-config))]
    (case target-type
      :rtf (format "http://%s.svc.cluster.local:%s" app-name port)
      :ch2 (format "https://%s.cloudhub.io" app-name)
      :hybrid (format "http://%s:%s" app-name port)
      (format "http://%s:%s" app-name port))))

(defn generate-connection-properties
  "Generate properties for connects-to references"
  [app targets all-apps]
  (let [base-props (let [p (:properties app)]
                     (if (map? p) p {}))]
    (if-let [connections (:connects-to app)]
      (reduce (fn [props conn-name]
                (if-let [target-app (first (filter #(= (:name %) conn-name) all-apps))]
                  (let [target-config (get targets (keyword (:target target-app)))
                        url (resolve-app-url target-app target-config all-apps)]
                    (assoc props (keyword (str conn-name ".url")) url))
                  props))
              base-props
              connections)
      base-props)))

;;; ============================================================
;;; Deploy Functions
;;; ============================================================

(defn build-deploy-opts
  "Build options for deploy function from manifest app config"
  [org env app target-name target-config]
  (let [{:keys [group artifact version]} (parse-asset (:asset app))
        props (:properties app)
        target-type (normalize-type (:type target-config))
        ;; Convert properties to +key format for deploy
        prop-opts (reduce-kv (fn [m k v]
                               (assoc m (keyword (str "+" (name k))) [(str v)]))
                             {}
                             props)]
    (merge {:args [org env (:name app)]
            :target [target-name]
            :group group
            :asset artifact
            :version version
            :replicas [(str (or (:replicas app) 1))]}
           prop-opts
           ;; Target-specific options
           (case target-type
             :rtf (cond-> {:cpu (or (:cpu target-config) ["450m" "550m"])
                           :mem (or (:mem target-config) ["1200Mi" "1200Mi"])}
                    (:runtime-version target-config)
                    (assoc :runtime-version [(:runtime-version target-config)]))
             :ch2 {:instance-type [(or (:instance-type target-config) "small")]}
             :hybrid {}
             {}))))

(defn deploy-single-app
  "Deploy a single app from manifest"
  [org env app targets all-apps]
  (let [target-name (:target app)
        target-config (get targets (keyword target-name))]
    (when-not target-config
      (throw (e/invalid-arguments (str "Target not found: " target-name))))
    (let [;; Merge generated connection properties
          app-with-connections (assoc app :properties
                                      (generate-connection-properties app targets all-apps))
          opts (build-deploy-opts org env app-with-connections target-name target-config)]
      (log/info "Deploying:" (:name app) "to" target-name)
      (log/debug "Deploy opts:" opts)
      (try
        (deploy/deploy-application opts)
        (catch Exception ex
          (let [data (ex-data ex)]
            [{:extra {:name (:name app)
                      :asset (:asset app)
                      :target target-name
                      :status (or (:status data)
                                  (-> data :extra :status)
                                  "error")
                      :message (or (ex-message ex)
                                   (:message data)
                                   (-> data :extra :message)
                                   "Unknown error")}}]))))))

;;; ============================================================
;;; Scan Functions - Generate manifest from Mule app directories
;;; ============================================================

(defn- parse-pom-xml
  "Parse pom.xml and extract groupId, artifactId, version using regex (GraalVM-friendly)"
  [pom-file]
  (try
    (let [content (slurp pom-file)
          ;; Extract first occurrence of each tag (project-level, not dependencies)
          extract (fn [tag]
                    (when-let [m (re-find (re-pattern (str "<" tag ">([^<]+)</" tag ">")) content)]
                      (second m)))]
      {:group-id (extract "groupId")
       :artifact-id (extract "artifactId")
       :version (extract "version")
       :packaging (extract "packaging")})
    (catch Exception e
      (log/debug "Failed to parse pom.xml:" (.getMessage e))
      nil)))

(defn- parse-properties-file
  "Parse .properties file into map"
  [file]
  (try
    (with-open [reader (io/reader file)]
      (let [props (Properties.)]
        (.load props reader)
        (into {} (for [[k v] props] [(keyword k) v]))))
    (catch Exception e
      (log/debug "Failed to parse properties file:" (.getMessage e))
      {})))

(defn- parse-yaml-config
  "Parse YAML config file into map, flattening nested keys"
  [file]
  (try
    (let [data (yaml/parse-file (.getPath ^java.io.File file))]
      ;; Flatten nested maps with dot notation
      (letfn [(flatten-map [m prefix]
                (reduce-kv
                 (fn [acc k v]
                   (let [new-key (if prefix
                                   (keyword (str (name prefix) "." (name k)))
                                   k)]
                     (if (map? v)
                       (merge acc (flatten-map v new-key))
                       (assoc acc new-key v))))
                 {}
                 m))]
        (flatten-map data nil)))
    (catch Exception e
      (log/debug "Failed to parse YAML config:" (.getMessage e))
      {})))

(defn- find-config-file
  "Find config file in Mule app directory"
  [app-dir config-properties-opt]
  (let [resources-dir (io/file app-dir "src" "main" "resources")
        config-dir (io/file resources-dir "config")
        ;; Search order: config/config.yaml, config/config.properties, config.yaml, config.properties
        candidates [(io/file config-dir "config.yaml")
                    (io/file config-dir "config.yml")
                    (io/file config-dir "config.properties")
                    (io/file resources-dir "config.yaml")
                    (io/file resources-dir "config.yml")
                    (io/file resources-dir "config.properties")]]
    (or
     ;; First try default locations
     (first (filter #(.exists ^java.io.File %) candidates))
     ;; Then try --config-properties option
     (when config-properties-opt
       (let [custom-file (io/file app-dir config-properties-opt)]
         (when (.exists ^java.io.File custom-file) custom-file))))))

(defn- parse-config-file
  "Parse config file (YAML or properties)"
  [^java.io.File file]
  (when file
    (cond
      (or (str/ends-with? (.getName file) ".yaml")
          (str/ends-with? (.getName file) ".yml"))
      (parse-yaml-config file)

      (str/ends-with? (.getName file) ".properties")
      (parse-properties-file file)

      :else {})))

(defn- is-mule-app?
  "Check if directory is a Mule application"
  [dir]
  (let [pom-file (io/file dir "pom.xml")]
    (when (.exists ^java.io.File pom-file)
      (let [pom (parse-pom-xml pom-file)]
        (= "mule-application" (:packaging pom))))))

(defn- scan-mule-app
  "Scan a single Mule app directory and extract info"
  [^java.io.File app-dir config-properties-opt]
  (let [pom-file (io/file app-dir "pom.xml")
        pom (parse-pom-xml pom-file)
        config-file (find-config-file app-dir config-properties-opt)
        props (parse-config-file config-file)]
    (when (= "mule-application" (:packaging pom))
      {:name (:artifact-id pom)
       :asset (str (:group-id pom) ":" (:artifact-id pom) ":" (:version pom))
       :target "TARGET_NAME"  ; Placeholder - user needs to fill in
       :properties (when (seq props) props)
       :_source (.getName app-dir)})))

(defn- scan-directory
  "Scan directory for Mule applications"
  [base-dir config-properties-opt]
  (let [dir (io/file base-dir)
        subdirs (.listFiles ^java.io.File dir)]
    (->> subdirs
         (filter #(.isDirectory ^java.io.File %))
         (filter is-mule-app?)
         (map #(scan-mule-app % config-properties-opt))
         (filter some?)
         (sort-by :name))))

(defn- generate-manifest-yaml
  "Generate manifest YAML from scanned apps"
  [apps org env]
  (let [manifest {:organization (or org "YOUR_ORG")
                  :environment (or env "YOUR_ENV")
                  :targets {:TARGET_NAME {:type "ch2"
                                          :instance-type "small"}}
                  :apps (mapv #(dissoc % :_source) apps)}]
    (yaml/generate-string manifest)))

(defn- scan-and-generate
  "Scan directory and generate manifest YAML"
  [{:keys [args config-properties scan] :as opts}]
  (when scan
    (let [scan-dir (or (first args) ".")
          apps (scan-directory scan-dir (first config-properties))]
      (if (empty? apps)
        (throw (e/invalid-arguments "No Mule applications found in directory"))
        (do
          (println "# Generated manifest from scanned Mule applications")
          (println "# Found" (count apps) "apps:" (str/join ", " (map :name apps)))
          (println "# TODO: Update TARGET_NAME with actual target from 'yaac get rtt'")
          (println)
          (print (generate-manifest-yaml apps *org* *env*))
          (flush)
          ;; Return empty to avoid table formatting
          [])))))

;;; ============================================================
;;; Deploy Manifest
;;; ============================================================

(defn deploy-manifest
  "Deploy all apps from manifest"
  [{:keys [args only dry-run scan] :as opts}]
  ;; Handle --scan mode
  (if scan
    (scan-and-generate opts)
    ;; Normal deploy mode
    (let [[manifest-path] args
          _ (when-not manifest-path
              (throw (e/invalid-arguments "Manifest file path required. Use --scan to generate from Mule apps.")))
          manifest (yaml/parse-file manifest-path)
          {:keys [valid errors data]} (validate-manifest manifest)]
      (if-not valid
        (throw (e/invalid-arguments (str "Invalid manifest: " (pr-str errors))))
        (let [{:keys [organization environment targets apps]} data
              ;; Filter apps if --only specified
              filtered-apps (if only
                              (let [only-set (set (str/split (first only) #","))]
                                (filter #(contains? only-set (:name %)) apps))
                              apps)]
          (if dry-run
            ;; Dry run - just show what would be deployed
            (mapv (fn [app]
                    (let [target-name (:target app)
                          target-config (get targets (keyword target-name))
                          target-type (normalize-type (:type target-config))
                          ;; Ensure properties is a map
                          app-props (let [p (:properties app)]
                                      (if (map? p) p {}))
                          props (generate-connection-properties
                                  (assoc app :properties app-props)
                                  targets apps)]
                      {:extra {:name (:name app)
                               :asset (:asset app)
                               :target target-name
                               :type (if target-type (name target-type) "unknown")
                               :properties (pr-str props)
                               :status "dry-run"}}))
                  filtered-apps)
            ;; Actual deploy
            (let [results (if *no-multi-thread*
                            (mapv #(deploy-single-app organization environment % targets apps) filtered-apps)
                            (vec (pmap #(deploy-single-app organization environment % targets apps) filtered-apps)))]
              (apply concat results))))))))

;;; ============================================================
;;; CLI
;;; ============================================================

(def options
  [["-n" "--dry-run" "Show what would be deployed without deploying"
    :default false]
   [nil "--only APPS" "Deploy only specified apps (comma-separated)"
    :parse-fn #(vector %)]
   [nil "--scan" "Scan directory for Mule apps and generate manifest YAML"
    :default false]
   [nil "--config-properties FILE" "Config file path relative to each app dir"
    :parse-fn #(vector %)]])

(defn usage [opts]
  (let [{:keys [summary help-all]} (if (map? opts) opts {:summary opts :help-all true})]
    (->> (concat
          ["Usage: deploy manifest <manifest.yaml> [options]"
           "       deploy manifest --scan [directory] [options]"
           ""
           "Deploy multiple applications from a YAML manifest file."
           ""
           "Options:"
           ""
           summary
           ""]
          (when help-all
            ["Scan Mode (--scan):"
             "  Scan a directory for Mule applications and generate manifest YAML."
             "  Config files are searched in order:"
             "    1. src/main/resources/config/config.yaml"
             "    2. src/main/resources/config.properties"
             "    5. --config-properties FILE"
             ""
             "Manifest YAML Schema:"
             ""
             "  organization: T1"
             "  environment: Production"
             "  targets:"
             "    cloudhub-ap-northeast-1:"
             "      type: ch2"
             "      instance-type: small"
             "    my-rtf-cluster:"
             "      type: rtf"
             "      cpu: [500m, 1000m]"
             "      mem: [1200Mi, 1200Mi]"
             "  apps:"
             "    - name: customer-sapi"
             "      asset: T1:customer-sapi:1.0.0"
             "      target: my-rtf-cluster"
             ""
             "Target Types:"
             "  rtf | ch2 | hybrid"
             ""])
          ["Example:"
           ""
           "# Deploy from manifest"
           "  > yaac deploy manifest deploy.yaml"
           ""
           "# Dry run"
           "  > yaac deploy manifest deploy.yaml --dry-run"
           ""
           "# Scan directory to generate manifest"
           "  > yaac deploy manifest --scan ./system-apis"
           ""])
         (str/join \newline))))

(def route
  (for [op ["dep" "deploy"]]
    [op {}
     ["|manifest" {:help true
                   :options options
                   :usage usage}]
     ["|manifest|{*args}" {:options options
                           :usage usage
                           :fields [[:extra :name] [:extra :asset] [:extra :target] [:extra :status] [:extra :message]]
                           :handler deploy-manifest}]]))
