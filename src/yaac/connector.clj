(ns yaac.connector
  "Mule connector schema browser.

  Schema data lives in ~/.yaac/schema/ — fully independent of the mulet
  CLI. `yaac connector collect` populates it directly from the local
  Maven repository. App validation against these schemas lives in
  yaac.validate."
  (:require [clojure.data.xml :as dx]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.nippy :as nippy]
            [taoensso.timbre :as log]
            [yaac.error :as e]))

(def schema-cache-dir
  (str (System/getProperty "user.home") "/.yaac/schema"))

(def yaac-cache-file
  (str (System/getProperty "user.home") "/.yaac/connector-schema.nippy"))

(def ^:private registry-file
  (str schema-cache-dir "/registry.edn"))

;; ---------------------------------------------------------------------------
;; Registry / XML parsing
;; ---------------------------------------------------------------------------

(defn- file-mtime [path]
  (let [f (io/file path)]
    (when (.exists f) (.lastModified f))))

(defn- ensure-cache-available! []
  (when-not (.exists (io/file registry-file))
    (throw (e/invalid-arguments
             (str "Connector schema cache not found at " schema-cache-dir
                  ". Run `yaac connector collect` first to populate it.")
             {:cache-dir schema-cache-dir}))))

(defn load-registry
  "Read registry.edn and return the list of connector entries.
   Each entry: {:name :artifact-id :version :schema-file}."
  []
  (ensure-cache-available!)
  (-> registry-file slurp edn/read-string :connectors))

(defn- tag-name [el]
  (some-> el :tag name))

(defn- el-attr [el k]
  (get-in el [:attrs k]))

(defn- cdata->str
  "Pull text content out of a <description> element (handles CDATA + plain text)."
  [el]
  (when el
    (->> (:content el)
         (keep (fn [c]
                 (cond
                   (string? c) c
                   ;; data.xml represents CDATA as a CData record; (str ..) is enough
                   :else (str c))))
         (str/join)
         str/trim)))

(defn- parse-parameter [param-el]
  {:name (el-attr param-el :name)
   :description (->> (:content param-el)
                     (some #(when (= "description" (tag-name %)) %))
                     cdata->str)})

(defn- parse-element
  "Parse a <config>/<operation>/<source>/<type> element into {:name :description :parameters}."
  [el]
  (let [children (:content el)
        desc (some #(when (= "description" (tag-name %)) %) children)
        params-el (some #(when (= "parameters" (tag-name %)) %) children)]
    {:name (or (el-attr el :name) (el-attr el :type-name))
     :description (cdata->str desc)
     :parameters (mapv parse-parameter
                       (filter #(= "parameter" (tag-name %))
                               (:content params-el)))}))

(defn- parse-section
  "Extract <section-tag>/<child-tag>* under the root document."
  [root section-tag child-tag]
  (when-let [section (some #(when (= section-tag (tag-name %)) %) (:content root))]
    (->> (:content section)
         (filter #(= child-tag (tag-name %)))
         (mapv parse-element))))

(defn parse-extension-xml
  "Parse a cached connector schema XML and return
   {:configs [..] :operations [..] :sources [..] :types [..]}."
  [xml-string]
  (let [root (dx/parse-str xml-string)]
    {:configs (or (parse-section root "configs" "config") [])
     :operations (or (parse-section root "operations" "operation") [])
     :sources (or (parse-section root "sources" "source") [])
     :types (or (parse-section root "types" "type") [])}))

(defn- load-connector-from-disk
  "Read & parse a single connector XML."
  [{:keys [name schema-file] :as entry}]
  (let [path (str schema-cache-dir "/" schema-file)
        f (io/file path)]
    (if (.exists f)
      (merge entry (parse-extension-xml (slurp f)))
      (do (log/warn "Schema file missing for connector" name "->" path)
          (assoc entry :configs [] :operations [] :sources [] :types [])))))

(defn- build-schema-index
  "Build full in-memory schema for all connectors."
  []
  (let [entries (load-registry)]
    {:built-at (System/currentTimeMillis)
     :registry-mtime (file-mtime registry-file)
     :connectors (mapv load-connector-from-disk entries)}))

;; ---------------------------------------------------------------------------
;; Nippy cache
;; ---------------------------------------------------------------------------

(defn- cache-fresh? []
  (when-let [cache-mtime (file-mtime yaac-cache-file)]
    (let [reg-mtime (file-mtime registry-file)]
      (and reg-mtime (>= cache-mtime reg-mtime)))))

(defn- write-cache! [index]
  (let [f (io/file yaac-cache-file)]
    (.mkdirs (.getParentFile f))
    (nippy/freeze-to-file f index)))

(defn- read-cache []
  (try
    (nippy/thaw-from-file (io/file yaac-cache-file))
    (catch Exception e
      (log/debug "Failed to read connector cache:" (ex-message e))
      nil)))

(def ^:private *cached-index (atom nil))

(defn schema-index
  "Return the parsed schema for all connectors. Uses nippy cache when fresh."
  []
  (or @*cached-index
      (reset! *cached-index
              (or (when (cache-fresh?) (read-cache))
                  (let [idx (build-schema-index)]
                    (write-cache! idx)
                    idx)))))

(defn invalidate-cache! []
  (reset! *cached-index nil)
  (let [f (io/file yaac-cache-file)]
    (when (.exists f) (.delete f))))

;; ---------------------------------------------------------------------------
;; Lookups
;; ---------------------------------------------------------------------------

(defn find-connector [name]
  (first (filter #(= name (:name %)) (:connectors (schema-index)))))

(defn- element-categories
  "Return [[:configs configs] [:operations ops] ...] of a connector."
  [connector]
  [[:config (:configs connector)]
   [:operation (:operations connector)]
   [:source (:sources connector)]
   [:type (:types connector)]])

(defn camel->kebab
  "camelCase / PascalCase -> kebab-case. Pure ASCII letters/digits."
  [s]
  (when s
    (-> s
        (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
        (str/replace #"([A-Z]+)([A-Z][a-z])" "$1-$2")
        (str/lower-case))))

(defn- name-match?
  "Schema names live in camelCase, XML in kebab-case. Compare lenient."
  [schema-name xml-local]
  (or (= schema-name xml-local)
      (= (camel->kebab schema-name) xml-local)
      (= schema-name (camel->kebab xml-local))))

(defn find-element
  "Find an element by name across configs/operations/sources/types in a connector,
   lenient about camelCase ↔ kebab-case."
  [connector el-name]
  (some (fn [[kind elems]]
          (when-let [m (first (filter #(name-match? (:name %) el-name) elems))]
            (assoc m :kind kind)))
        (element-categories connector)))

;; ---------------------------------------------------------------------------
;; Public handlers: list / show / search
;; ---------------------------------------------------------------------------

(defn connector-list
  "List all connectors known to the schema cache."
  [_opts]
  (->> (:connectors (schema-index))
       (sort-by :name)
       (mapv (fn [c]
               {:name (:name c)
                :version (:version c)
                :artifact-id (:artifact-id c)
                :configs (count (:configs c))
                :operations (count (:operations c))
                :sources (count (:sources c))}))))

(defn- ->row [conn-name kind el]
  {:connector conn-name
   :kind (name kind)
   :name (:name el)
   :description (:description el)})

(defn- show-connector
  "Return all configs/operations/sources/types of a connector as a flat seq."
  [name]
  (let [c (find-connector name)]
    (when-not c
      (throw (e/invalid-arguments
               (str "Unknown connector: " name)
               {:available (mapv :name (:connectors (schema-index)))})))
    (concat
      (map #(->row (:name c) :config %) (:configs c))
      (map #(->row (:name c) :operation %) (:operations c))
      (map #(->row (:name c) :source %) (:sources c))
      (map #(->row (:name c) :type %) (:types c)))))

(defn- show-element
  "Return the parameters of a single element as a flat seq."
  [conn-name el-name]
  (let [c (find-connector conn-name)
        _ (when-not c
            (throw (e/invalid-arguments
                     (str "Unknown connector: " conn-name)
                     {:available (mapv :name (:connectors (schema-index)))})))
        el (find-element c el-name)
        _ (when-not el
            (throw (e/invalid-arguments
                     (str "Unknown element '" el-name "' in connector '" conn-name "'")
                     {:configs (mapv :name (:configs c))
                      :operations (mapv :name (:operations c))
                      :sources (mapv :name (:sources c))})))]
    (mapv (fn [p]
            {:connector conn-name
             :element el-name
             :kind (name (:kind el))
             :parameter (:name p)
             :description (:description p)})
          (:parameters el))))

(defn connector-show
  "Dispatch:
    show <name>            => list configs/operations/sources/types
    show <name> <element>  => list parameters of that element"
  [{:keys [args]}]
  (case (count args)
    1 (show-connector (first args))
    2 (show-element (first args) (second args))
    (throw (e/invalid-arguments
             "Usage: yaac connector show <name> [element]"
             {:args args}))))

(defn- match? [keyword text]
  (and text (str/includes? (str/lower-case text) (str/lower-case keyword))))

(defn- search-connector [keyword c]
  (let [hit? (fn [el] (or (match? keyword (:name el))
                          (match? keyword (:description el))))
        param-rows (fn [parent-kind parent-name params]
                     (->> params
                          (filter hit?)
                          (mapv (fn [p]
                                  {:connector (:name c)
                                   :kind "param"
                                   :parent (str (name parent-kind) ":" parent-name)
                                   :name (:name p)
                                   :description (:description p)}))))]
    (concat
      ;; element-level hits
      (for [[kind elems] (element-categories c)
            el elems
            :when (hit? el)]
        {:connector (:name c)
         :kind (name kind)
         :parent ""
         :name (:name el)
         :description (:description el)})
      ;; parameter-level hits
      (mapcat (fn [[kind elems]]
                (mapcat #(param-rows kind (:name %) (:parameters %)) elems))
              (element-categories c)))))

(defn connector-search
  "Search across configs/operations/sources/types/params of all connectors."
  [{:keys [args]}]
  (when (not= 1 (count args))
    (throw (e/invalid-arguments
             "Usage: yaac connector search <keyword>"
             {:args args})))
  (let [kw (first args)]
    (->> (:connectors (schema-index))
         (mapcat #(search-connector kw %))
         vec)))

;; ---------------------------------------------------------------------------
;; collect — extract connector schemas from the local Maven repository
;; ---------------------------------------------------------------------------

(def ^:private m2-repo
  (or (System/getenv "M2_REPO")
      (str (System/getProperty "user.home") "/.m2/repository")))

(def ^:private uuid-segment-re
  #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

(defn- mule-plugin-jar? [^java.io.File f]
  (and (.isFile f)
       (str/ends-with? (.getName f) "-mule-plugin.jar")))

(defn- m2-coordinates
  "Derive [group artifact version] from a JAR path under the m2 repo.
   m2 layout: <repo>/<group/as/dirs>/<artifact>/<version>/<file>.jar"
  [^java.io.File jar]
  (let [rel (-> (.getCanonicalPath jar)
                (str/replace-first (str (.getCanonicalPath (io/file m2-repo)) "/") ""))
        segs (str/split rel #"/")
        n (count segs)]
    (when (>= n 4)
      (let [version (nth segs (- n 2))
            artifact (nth segs (- n 3))
            group (str/join "." (take (- n 3) segs))]
        [group artifact version]))))

(defn- anypoint-custom-connector?
  "Anypoint Exchange custom connectors live under a UUID group id — their
   schema format differs, so skip them."
  [group]
  (boolean (some #(re-matches uuid-segment-re %)
                 (str/split (str group) #"\."))))

(defn- parse-version
  "\"1.11.0\" -> [1 11 0]; non-numeric parts sort low."
  [v]
  (mapv #(or (parse-long %) 0)
        (re-seq #"\d+" (str v))))

(defn- find-mule-plugin-jars []
  (let [repo (io/file m2-repo)]
    (when (.isDirectory repo)
      (->> (file-seq repo)
           (filter mule-plugin-jar?)
           (keep (fn [jar]
                   (when-let [[group artifact version] (m2-coordinates jar)]
                     (when-not (anypoint-custom-connector? group)
                       {:jar jar :group group :artifact artifact :version version}))))))))

(defn- latest-per-artifact
  "Keep only the highest version of each artifact."
  [entries]
  (->> entries
       (group-by :artifact)
       (vals)
       (mapv (fn [versions]
               ;; version vectors are compared with `compare`; max-key needs
               ;; a Number, so sort and take the last instead.
               (last (sort-by #(parse-version (:version %)) versions))))))

(defn- jar-schema-entry
  "Find the schema XML entry inside a connector JAR.
   Primary: META-INF/*-extension-descriptions.xml
   Fallback: META-INF/module-*.xml (XML-SDK connectors, e.g. SAP), excluding *-catalog."
  [^java.util.zip.ZipFile zf]
  (let [entries (enumeration-seq (.entries zf))
        names (map (fn [^java.util.zip.ZipEntry e] (.getName e)) entries)
        primary (some #(when (re-find #"extension-descriptions\.xml$" %) %) names)
        fallback (some #(when (re-find #"(?i)/module-(?!.*-catalog).*\.xml$" (str "/" %)) %)
                       names)]
    (or primary fallback)))

(defn- artifact->short-name
  "mule-http-connector -> http; munit-tools -> munit-tools."
  [artifact]
  (-> artifact
      (str/replace #"^mule-" "")
      (str/replace #"-connector$" "")))

(defn- collect-one!
  "Extract the schema XML from one connector JAR into the cache dir.
   Returns a result map, or nil if no schema entry was found."
  [{:keys [^java.io.File jar artifact version]}]
  (with-open [zf (java.util.zip.ZipFile. jar)]
    (when-let [entry-name (jar-schema-entry zf)]
      (let [^java.util.zip.ZipFile zf zf
            xml (slurp (.getInputStream zf (.getEntry zf entry-name)))
            ;; tag the resolved version into the XML as a comment
            xml* (if (str/starts-with? xml "<?xml")
                   (str/replace-first xml #"\?>" (str "?>\n<!-- Version: " version " -->"))
                   (str "<!-- Version: " version " -->\n" xml))
            out-name (str artifact ".xml")
            out-file (io/file schema-cache-dir out-name)]
        (.mkdirs (io/file schema-cache-dir))
        (spit out-file xml*)
        {:name (artifact->short-name artifact)
         :artifact-id artifact
         :version version
         :schema-file out-name}))))

(defn connector-collect
  "Scan the local Maven repository for *-mule-plugin.jar, extract each
   connector's schema XML into ~/.yaac/schema/, and (re)write registry.edn."
  [_opts]
  (let [jars (find-mule-plugin-jars)]
    (when (empty? jars)
      (throw (e/invalid-arguments
               (str "No *-mule-plugin.jar found under " m2-repo
                    ". Build or resolve a Mule app first to populate ~/.m2.")
               {:m2-repo m2-repo})))
    (let [collected (->> (latest-per-artifact jars)
                         (keep collect-one!)
                         (sort-by :name)
                         vec)]
      (spit (io/file schema-cache-dir "registry.edn")
            (pr-str {:connectors collected}))
      (invalidate-cache!)
      (conj (vec collected)
            {:name "—"
             :artifact-id (str (count collected) " connectors")
             :version "written to"
             :schema-file schema-cache-dir}))))

;; ---------------------------------------------------------------------------
;; CLI scaffolding
;; ---------------------------------------------------------------------------

(defn usage [opts]
  (let [{:keys [summary help-all]} (if (map? opts) opts {:summary opts :help-all true})]
    (->> (concat
          ["Usage: connector <command> [options]"
           ""
           "Mule connector schema browser."
           "Schema cache lives at ~/.yaac/schema/ — run `yaac connector"
           "collect` once to populate it from your local Maven repository."
           ""
           "Options:"
           ""
           summary
           ""]
          (when help-all
            ["Commands:"
             ""
             "  collect                              Extract connector schemas from ~/.m2 into the cache"
             "  list                                 List all connectors"
             "  show <name>                          Show configs/operations/sources of a connector"
             "  show <name> <element>                Show parameters of one element"
             "  search <keyword>                     Search across connectors/elements/params"
             "  refresh                              Invalidate the local nippy cache"
             ""
             "Examples:"
             ""
             "  yaac connector collect"
             "  yaac connector list"
             "  yaac connector show http"
             "  yaac connector show http request"
             "  yaac connector search timeout"
             ""
             "collect: scans ~/.m2/repository (override with $M2_REPO) for"
             "  *-mule-plugin.jar, keeps the latest version of each, and extracts"
             "  META-INF/*-extension-descriptions.xml into ~/.yaac/schema/."
             "  Anypoint Exchange custom connectors (UUID group ids) are skipped."
             ""
             "To validate a Mule app against these schemas (and check for"
             "unresolved ${...} properties before deploy), use `yaac validate`."
             ""]))
         (str/join \newline))))

(def options [])

(defn connector-refresh [_opts]
  (invalidate-cache!)
  [{:message (str "Cache cleared: " yaac-cache-file)}])

(def route
  ["connector" {:options options :usage usage}
   ["" {:help true}]
   ["|collect" {:handler connector-collect
                :fields [:name :version :artifact-id :schema-file]}]
   ["|list" {:handler connector-list
             :fields [:name :version :configs :operations :sources :artifact-id]}]
   ["|show" {:help true}]
   ["|show|{*args}" {:handler connector-show
                     :fields [:connector :kind :name :element :parameter :description]}]
   ["|search|{*args}" {:handler connector-search
                       :fields [:connector :kind :parent :name :description]}]
   ["|refresh" {:handler connector-refresh
                :fields [:message]}]])

