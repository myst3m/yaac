(ns yaac.validate
  "Pre-deploy validation for Mule applications.

  Validates a Mule app — a packaged JAR, a project directory, or a single
  flow XML — and reports problems that would otherwise only surface at deploy
  time. The headline check is unresolved ${...} property placeholders: a
  placeholder counts as resolved when it is defined by

    - a configuration-properties file in the artifact
      (config-<env>.yaml selected via +mule.env=<env>),
    - a property passed on the command line as +key=value
      (the same syntax `yaac deploy` uses for global properties),
    - an OS environment variable or JVM system property, or
    - a Mule runtime placeholder (env.* / sys.* / app.* / mule.*).

  Also checks config-ref / flow-ref integrity and, when the connector schema
  cache is present, unknown attributes on connector elements."
  (:require [clojure.data.xml :as dx]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [yaac.connector :as conn]
            [yaac.error :as e]
            [yaac.yaml :as yaml])
  (:import [java.util.zip ZipFile ZipEntry]))

;; ---------------------------------------------------------------------------
;; XML helpers
;; ---------------------------------------------------------------------------

(defn- xml-elements
  "Walk an XML tree and emit every element (depth-first)."
  [el]
  (when (map? el)
    (cons el (mapcat xml-elements (:content el)))))

(defn- el-attr [el k] (get-in el [:attrs k]))

(defn- split-prefix
  "Return [namespace-token local-name] of a data.xml tag/attr keyword.
   data.xml emits namespaced names as :xmlns.<url-encoded-uri>/<local>."
  [tag]
  (let [s (if (keyword? tag) (subs (str tag) 1) (str tag))
        [a b] (str/split s #"/" 2)]
    (if b [a b] [nil a])))

(defn- decode-ns-uri [s]
  (when (and s (str/starts-with? s "xmlns."))
    (try (java.net.URLDecoder/decode (subs s (count "xmlns.")) "UTF-8")
         (catch Exception _ nil))))

(def ^:private mule-core-uri-prefixes
  ["http://www.mulesoft.org/schema/mule/core"
   "http://www.mulesoft.org/schema/mule/ee/"
   "http://www.mulesoft.org/schema/mule/documentation"])

(defn- mule-core-uri? [uri]
  (boolean (some #(str/starts-with? uri %) mule-core-uri-prefixes)))

(defn- uri->connector-name [uri]
  (when (and uri (not (mule-core-uri? uri)))
    (some-> (re-matches #"https?://www\.mulesoft\.org/schema/mule/([^/]+)(?:/.*)?" uri)
            second)))

(defn- tag-info
  "Return [connector-name local-name] for a data.xml tag.
   connector-name is nil for core/ee/documentation tags."
  [tag]
  (let [[prefix local] (split-prefix tag)]
    [(uri->connector-name (decode-ns-uri prefix)) local]))

;; ---------------------------------------------------------------------------
;; Placeholders & property sources
;; ---------------------------------------------------------------------------

(defn- placeholders-in
  "All ${...} placeholder names in an element's attribute values.
   Strips a leading `secure::` so secure properties resolve against the same
   defined-key set."
  [el]
  (->> (vals (:attrs el))
       (mapcat (fn [v]
                 (when (string? v)
                   (map (fn [[_ p]] (str/replace p #"^secure::" ""))
                        (re-seq #"\$\{([^}]+)\}" v)))))
       set))

(defn- runtime-resolvable?
  "Placeholders the Mule runtime resolves independently of config properties."
  [name]
  (or (str/starts-with? name "env.")
      (str/starts-with? name "sys.")
      (str/starts-with? name "app.")
      (str/starts-with? name "mule.")
      (= name "mule.home")))

(defn- flatten-keys
  "Flatten a nested keyword-keyed map into dotted-string keys."
  [m]
  (letfn [(walk [prefix v]
            (cond
              (map? v) (mapcat (fn [[k v2]]
                                 (let [k* (name k)
                                       p (if (str/blank? prefix) k* (str prefix "." k*))]
                                   (walk p v2))) v)
              :else [prefix]))]
    (set (walk "" m))))

(defn- property-keys-from-content
  "Parse property-file content into a set of dotted keys, by file extension."
  [fname content]
  (cond
    (or (str/ends-with? fname ".yaml") (str/ends-with? fname ".yml"))
    (try (flatten-keys (yaml/parse-string content))
         (catch Exception ex
           (log/debug "YAML parse failed for" fname (ex-message ex))
           #{}))

    (str/ends-with? fname ".properties")
    (set (map first (re-seq #"(?m)^\s*([A-Za-z_][\w.-]*)\s*=" content)))

    :else #{}))

(defn- deploy-props
  "Extract +key=value command-line properties into {\"key\" \"value\"}.
   Mirrors how `yaac deploy` reads global properties."
  [opts]
  (->> opts
       (filter (fn [[k _]] (str/starts-with? (name k) "+")))
       (map (fn [[k v]]
              [(subs (name k) 1)
               (if (coll? v) (str/join "," v) (str v))]))
       (into {})))

;; ---------------------------------------------------------------------------
;; Artifact model — a parsed app, however it was supplied
;; ---------------------------------------------------------------------------
;; {:mule-xmls  [{:name "main.xml" :root <parsed>} ...]
;;  :resources  {"config-ch2.yaml" "<content>" ...}}   ; basename -> content

(defn- mule-xml? [root]
  (= "mule" (second (split-prefix (:tag root)))))

(defn- safe-parse [name content]
  (try (dx/parse-str content)
       (catch Exception ex
         (log/debug "XML parse failed for" name (ex-message ex))
         nil)))

(defn- artifact-from-jar [^java.io.File jar]
  (with-open [zf (ZipFile. jar)]
    (let [entries (enumeration-seq (.entries zf))
          slurp-entry (fn [^ZipEntry en] (slurp (.getInputStream zf en)))]
      (reduce
        (fn [acc ^ZipEntry en]
          (let [nm (.getName en)
                base (last (str/split nm #"/"))]
            (cond
              (.isDirectory en) acc
              (str/ends-with? nm ".xml")
              (if-let [root (safe-parse nm (slurp-entry en))]
                (if (mule-xml? root)
                  (update acc :mule-xmls conj {:name nm :root root})
                  acc)
                acc)
              (re-find #"\.(ya?ml|properties)$" nm)
              (assoc-in acc [:resources base] (slurp-entry en))
              :else acc)))
        {:mule-xmls [] :resources {}}
        entries))))

(defn- artifact-from-dir [^java.io.File dir]
  (reduce
    (fn [acc ^java.io.File f]
      (let [nm (.getPath f)
            base (.getName f)]
        (cond
          (not (.isFile f)) acc
          (str/ends-with? nm ".xml")
          (if-let [root (safe-parse nm (slurp f))]
            (if (mule-xml? root)
              (update acc :mule-xmls conj {:name nm :root root})
              acc)
            acc)
          (re-find #"\.(ya?ml|properties)$" nm)
          (assoc-in acc [:resources base] (slurp f))
          :else acc)))
    {:mule-xmls [] :resources {}}
    (file-seq dir)))

(defn- artifact-from-xml [^java.io.File f]
  (let [base-dir (.getParentFile f)
        root (safe-parse (.getPath f) (slurp f))
        ;; sibling + src/main/resources property files
        res-dirs (filter some?
                         [base-dir
                          (some-> base-dir .getParentFile .getParentFile
                                  (io/file "resources"))
                          (io/file (or (some-> base-dir .getParentFile .getParentFile
                                               .getParentFile .getParentFile)
                                       base-dir)
                                   "src" "main" "resources")])
        resources (into {}
                        (for [^java.io.File d res-dirs
                              :when (and d (.isDirectory d))
                              ^java.io.File rf (.listFiles d)
                              :when (and (.isFile rf)
                                         (re-find #"\.(ya?ml|properties)$" (.getName rf)))]
                          [(.getName rf) (slurp rf)]))]
    {:mule-xmls (if (and root (mule-xml? root))
                  [{:name (.getPath f) :root root}]
                  [])
     :resources resources}))

(defn- load-artifact [path]
  (let [f (io/file path)]
    (cond
      (not (.exists f)) ::not-found
      (.isDirectory f) (artifact-from-dir f)
      (str/ends-with? (.getName f) ".jar") (artifact-from-jar f)
      (str/ends-with? (.getName f) ".xml") (artifact-from-xml f)
      :else ::unsupported)))

;; ---------------------------------------------------------------------------
;; Property resolution
;; ---------------------------------------------------------------------------

(defn- substitute
  "Replace ${k} in s using the given prop map (string keys). Leaves unknown
   placeholders intact. Used to resolve configuration-properties file= names
   like config-${mule.env}.yaml."
  [s props]
  (when s
    (str/replace s #"\$\{([^}]+)\}"
                 (fn [[whole k]] (str (get props k whole))))))

(defn- referenced-property-keys
  "Collect property keys defined by every configuration-properties file
   referenced in the artifact's Mule XMLs, resolving file= names against the
   supplied props (so config-${mule.env}.yaml picks up the right file)."
  [{:keys [mule-xmls resources]} props]
  (->> mule-xmls
       (mapcat (fn [{:keys [root]}]
                 (->> (xml-elements root)
                      (filter #(= "configuration-properties"
                                  (second (split-prefix (:tag %)))))
                      (keep #(el-attr % :file)))))
       (mapcat (fn [file-attr]
                 (let [fname (last (str/split (substitute file-attr props) #"/"))
                       content (get resources fname)]
                   (when-not content
                     (log/debug "configuration-properties file not found in artifact:" fname))
                   (when content
                     (property-keys-from-content fname content)))))
       (remove nil?)
       set))

;; ---------------------------------------------------------------------------
;; Checks
;; ---------------------------------------------------------------------------

(defn- check-placeholders [artifact props]
  (let [defined (into (referenced-property-keys artifact props)
                      (keys props))
        env-vars (set (keys (System/getenv)))
        sys-props (set (map str (.keySet (System/getProperties))))
        resolved? (fn [ph]
                    (or (runtime-resolvable? ph)
                        (defined ph)
                        (env-vars ph)
                        (sys-props ph)))]
    (->> (:mule-xmls artifact)
         (mapcat (fn [{:keys [name root]}]
                   (->> (xml-elements root)
                        (mapcat placeholders-in)
                        distinct
                        (remove resolved?)
                        (map (fn [ph]
                               {:file name :severity "error"
                                :kind :unresolved-placeholder
                                :message (str "No value for ${" ph "}")
                                :placeholder ph}))))))))

(defn- looks-like-config-element? [local]
  (and local (or (= "config" local)
                 (str/ends-with? local "-config")
                 (str/ends-with? local "Config"))))

(defn- check-cross-refs [{:keys [mule-xmls]}]
  ;; config-ref / flow-ref resolution is app-wide, so gather across all XMLs
  (let [roots (map :root mule-xmls)
        els (mapcat xml-elements roots)
        config-names (->> els
                          (filter #(looks-like-config-element? (second (split-prefix (:tag %)))))
                          (keep #(el-attr % :name)) set)
        flow-names (->> els
                        (filter #(#{"flow" "sub-flow"} (second (split-prefix (:tag %)))))
                        (keep #(el-attr % :name)) set)]
    (concat
      (->> els (keep #(el-attr % :config-ref)) distinct
           (remove config-names)
           (map (fn [r] {:severity "error" :kind :undefined-config-ref
                         :message (str "config-ref '" r "' is not defined")})))
      (->> els (filter #(= "flow-ref" (second (split-prefix (:tag %)))))
           (keep #(el-attr % :name)) distinct
           (remove flow-names)
           (map (fn [r] {:severity "error" :kind :undefined-flow-ref
                         :message (str "flow-ref name='" r "' has no matching flow/sub-flow")}))))))

(defn- infra-attr? [attr-keyword]
  (let [[prefix local] (split-prefix attr-keyword)
        uri (decode-ns-uri prefix)]
    (or (and (nil? prefix) (#{"name" "config-ref" "value"} local))
        (and uri (str/starts-with? uri "http://www.mulesoft.org/schema/mule/documentation"))
        (and uri (str/starts-with? uri "http://www.w3.org/2001/XMLSchema-instance")))))

(defn- schema-available? []
  (try (boolean (seq (:connectors (conn/schema-index))))
       (catch Exception _ false)))

(defn- check-unknown-attributes [{:keys [mule-xmls]}]
  (when (schema-available?)
    (mapcat
      (fn [{:keys [name root]}]
        (mapcat
          (fn [el]
            (let [[conn-name local] (tag-info (:tag el))]
              (when conn-name
                (when-let [c (conn/find-connector conn-name)]
                  (when-let [match (conn/find-element c local)]
                    (let [params (set (mapcat (fn [p] [(:name p) (conn/camel->kebab (:name p))])
                                              (:parameters match)))]
                      (->> (:attrs el)
                           (remove (fn [[k _]] (infra-attr? k)))
                           (keep (fn [[k _]]
                                   (let [[_ kn] (split-prefix k)]
                                     (when-not (params kn)
                                       {:file name :severity "warn"
                                        :kind :unknown-attribute
                                        :message (str "Unknown attribute '" kn "' on <" conn-name ":" local ">")})))))))))))
          (xml-elements root)))
      mule-xmls)))

;; ---------------------------------------------------------------------------
;; Handler
;; ---------------------------------------------------------------------------

(defn validate-app
  "Validate a Mule app (JAR / project dir / flow XML) for deploy readiness.
   Positional arg: the artifact path. +key=value args supply deploy-time
   properties (+mule.env selects config-<env>.yaml)."
  [{:keys [args] :as opts}]
  (let [path (first args)]
    (when-not path
      (throw (e/invalid-arguments
               "Usage: yaac validate <app.jar|dir|mule.xml> [+mule.env=ch2] [+key=value ...]"
               {:args args})))
    (let [props (deploy-props opts)
          artifact (load-artifact path)]
      (cond
        (= ::not-found artifact)
        [{:file path :severity "error" :kind :file-not-found
          :message (str "Not found: " path)}]

        (= ::unsupported artifact)
        [{:file path :severity "error" :kind :unsupported
          :message "Unsupported target (expected .jar, directory, or .xml)"}]

        (empty? (:mule-xmls artifact))
        [{:file path :severity "warn" :kind :no-mule-config
          :message "No Mule configuration XML found in target"}]

        :else
        (vec
          (concat (check-placeholders artifact props)
                  (check-cross-refs artifact)
                  (check-unknown-attributes artifact)))))))

;; ---------------------------------------------------------------------------
;; CLI scaffolding
;; ---------------------------------------------------------------------------

(defn usage [opts]
  (let [{:keys [summary help-all]} (if (map? opts) opts {:summary opts :help-all true})]
    (->> (concat
          ["Usage: validate <app.jar|dir|mule.xml> [+mule.env=<env>] [+key=value ...]"
           ""
           "Pre-deploy validation for a Mule application. Detects properties"
           "that have no value before you push to the runtime."
           ""
           "Options:"
           ""
           summary
           ""]
          (when help-all
            ["Targets:"
             ""
             "  <app.jar>     A packaged Mule app — scans flow XMLs + bundled config files"
             "  <dir>         A project directory — scans src/main/mule + src/main/resources"
             "  <mule.xml>    A single flow XML — scans it + sibling/resources config files"
             ""
             "Properties (same +key=value syntax as `yaac deploy`):"
             ""
             "  +mule.env=ch2          Selects config-ch2.yaml inside the artifact"
             "  +db.host=10.0.0.1      Marks db.host as provided at deploy time"
             ""
             "A ${placeholder} is considered resolved when defined by a"
             "configuration-properties file in the artifact, a +key=value"
             "argument, an env var / system property, or the Mule runtime"
             "(env.* / sys.* / app.* / mule.*)."
             ""
             "Checks:"
             "  - error  unresolved ${...} placeholder (no value anywhere)"
             "  - error  config-ref with no matching config"
             "  - error  flow-ref with no matching flow/sub-flow"
             "  - warn   unknown attribute on a connector element (needs schema cache)"
             ""
             "Examples:"
             ""
             "  yaac validate target/my-app.jar +mule.env=ch2"
             "  yaac validate . +mule.env=local"
             "  yaac validate src/main/mule/main.xml +mule.env=ch2 +db.host=db"
             ""]))
         (str/join \newline))))

(def options [])

(def route
  (for [op ["validate" "val"]]
    [op {:options options :usage usage}
     ["" {:help true}]
     ["|{*args}" {:handler validate-app
                  :fields [:file :severity :kind :message]}]]))
