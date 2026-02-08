(ns yaac.maven
  "Embedded Maven integration for building Mule projects.
   Uses Maven Embedder to run Maven goals without external mvn command."
  (:require [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [org.apache.maven.cli MavenCli]
           [java.io PrintStream ByteArrayOutputStream OutputStream]))

(def ^:private embedded-mule-plugin-version
  "The version of mule-maven-plugin embedded in yaac's uber jar."
  "4.6.1")

(def ^:private maven-noise
  "Patterns to filter from Maven output (SLF4J binding mismatch warnings)."
  ["UnsupportedSlf4jBindingConfiguration"
   "SLF4J binding actually used"
   "not supported by Maven"
   "Maven supported bindings"
   "slf4j-configuration.properties"
   "- org.slf4j."
   "- ch.qos."
   "- org.apache.logging."])

(defn- filtering-stream
  "Create an OutputStream that filters lines matching noise patterns."
  [^PrintStream target]
  (let [buf (StringBuilder.)]
    (proxy [OutputStream] []
      (write
        ([b]
         (if (instance? (Class/forName "[B") b)
           (let [s (String. ^bytes b)]
             (.append buf s)
             (when (str/includes? s "\n")
               (doseq [line (str/split-lines (.toString buf))]
                 (when-not (some #(str/includes? line %) maven-noise)
                   (.println target line)))
               (.setLength buf 0)))
           (let [c (char (bit-and (int b) 0xff))]
             (.append buf c)
             (when (= c \newline)
               (let [line (str/trimr (.toString buf))]
                 (when-not (some #(str/includes? line %) maven-noise)
                   (.println target line)))
               (.setLength buf 0)))))
        ([b off len]
         (let [s (String. ^bytes b (int off) (int len))]
           (.append buf s)
           (when (str/includes? s "\n")
             (doseq [line (str/split-lines (.toString buf))]
               (when-not (some #(str/includes? line %) maven-noise)
                 (.println target line)))
             (.setLength buf 0)))))
      (flush []
        (when (pos? (.length buf))
          (let [line (.toString buf)]
            (when-not (some #(str/includes? line %) maven-noise)
              (.print target line)
              (.flush target)))
          (.setLength buf 0))
        (.flush target)))))

(defn invoke!
  "Run Maven goals in the given project directory.
   Returns {:exit int, :out string, :err string}."
  [project-dir goals]
  (System/setProperty "maven.multiModuleProjectDirectory" project-dir)
  (let [cli (MavenCli.)
        out-buf (ByteArrayOutputStream.)
        err-buf (ByteArrayOutputStream.)
        exit (.doMain cli
                      (into-array String goals)
                      project-dir
                      (PrintStream. out-buf)
                      (PrintStream. err-buf))]
    {:exit exit
     :out (.toString out-buf)
     :err (.toString err-buf)}))

(defn- detect-java-home
  "Detect a valid JDK home for Maven compiler plugin.
   In native image, java.home points to the image dir, not a real JDK."
  []
  (let [candidates ["/usr/lib/jvm/java-17-openjdk-amd64"
                    "/usr/lib/jvm/java-17-openjdk"
                    "/usr/lib/jvm/java-17"]]
    (first (filter #(.exists (io/file % "bin" "javac")) candidates))))

(defn- ensure-build-props
  "Add required system properties for native image Maven builds if not already set."
  [goals]
  (let [has-prop? (fn [prefix] (some #(str/starts-with? % prefix) goals))
        extra (cond-> []
                ;; Auto-set java.home when not a real JDK (native image)
                (and (not (has-prop? "-Djava.home="))
                     (not (.exists (io/file (System/getProperty "java.home") "bin" "javac")))
                     (detect-java-home))
                (conj (str "-Djava.home=" (detect-java-home)))
                ;; Skip AST processing (causes ExceptionInInitializerError in native image)
                (not (has-prop? "-DskipAST"))
                (conj "-DskipAST=true")
                ;; Always skip tests in embedded Maven (MUnit can't load in native image)
                (not (has-prop? "-DskipTests"))
                (conj "-DskipTests"))]
    (into goals extra)))

(defn- read-pom
  "Read pom.xml content from project directory."
  [project-dir]
  (slurp (io/file project-dir "pom.xml")))

(defn- mule-project?
  "Check if project uses mule-application packaging."
  [project-dir]
  (boolean (re-find #"<packaging>mule-application</packaging>" (read-pom project-dir))))

(defn- detect-mule-plugin-version
  "Extract mule-maven-plugin version from pom.xml.
   Handles both literal <version> and ${property} references.
   Returns the version string or nil if not found."
  [project-dir]
  (let [pom (read-pom project-dir)
        ;; Match mule-maven-plugin artifactId followed by version tag
        plugin-re #"<artifactId>mule-maven-plugin</artifactId>\s*<version>([^<]+)</version>"
        m (re-find plugin-re pom)]
    (when m
      (let [raw-version (second m)]
        (if (str/starts-with? raw-version "${")
          ;; Resolve property reference
          (let [prop-name (subs raw-version 2 (dec (count raw-version)))
                prop-re (re-pattern (str "<" prop-name ">([^<]+)</" prop-name ">"))]
            (second (re-find prop-re pom)))
          raw-version)))))

(defn- rewrite-goals-for-mule
  "Rewrite goals to skip test phase for Mule projects.
   Replaces 'package' with 'process-test-classes' + direct mule:package goal
   to avoid loading MUnit plugin (which can't work in native image)."
  [goals]
  (let [phases #{"package" "install" "deploy" "verify" "integration-test"}
        target-phase (first (filter phases goals))]
    (if target-phase
      (let [flags (filterv #(str/starts-with? % "-") goals)
            non-flag-goals (filterv #(not (str/starts-with? % "-")) goals)
            pre-goals (filterv #(not (phases %)) non-flag-goals)
            mule-goal "org.mule.tools.maven:mule-maven-plugin:4.6.1:package"]
        (into (conj (vec pre-goals) "process-test-classes" mule-goal) flags))
      goals)))

(defn- run-maven
  "Execute MavenCli with filtered output streams. Returns exit code."
  [project-dir goals]
  (let [cli (MavenCli.)
        filtered-out (PrintStream. ^OutputStream (filtering-stream System/out))
        filtered-err (PrintStream. ^OutputStream (filtering-stream System/err))
        orig-out System/out
        orig-err System/err]
    (System/setOut filtered-out)
    (System/setErr filtered-err)
    (try
      (.doMain cli
               (into-array String goals)
               project-dir
               filtered-out
               filtered-err)
      (finally
        (.flush filtered-out)
        (.flush filtered-err)
        (System/setOut orig-out)
        (System/setErr orig-err)))))

(defn invoke-live!
  "Run Maven goals with live output to stdout/stderr.
   Filters SLF4J binding mismatch warnings from both streams.
   For Mule projects, automatically rewrites goals to skip test phase
   (MUnit plugin can't load in native image). Returns exit code."
  [project-dir goals]
  (System/setProperty "maven.multiModuleProjectDirectory" project-dir)
  (let [mule? (mule-project? project-dir)
        _ (when mule?
            (when-let [pom-version (detect-mule-plugin-version project-dir)]
              (when (not= pom-version embedded-mule-plugin-version)
                (binding [*out* *err*]
                  (println (str "WARNING: pom.xml specifies mule-maven-plugin "
                                pom-version ", but yaac uses embedded "
                                embedded-mule-plugin-version
                                ". Proceeding with " embedded-mule-plugin-version "."))))))
        goals (ensure-build-props goals)
        goals (if mule? (rewrite-goals-for-mule goals) goals)]
    (run-maven project-dir goals)))

(defn find-project-dir
  "Find Maven project directory. Returns canonical path or nil."
  [path]
  (let [dir (io/file (or path "."))]
    (when (and (.isDirectory dir)
               (.exists (io/file dir "pom.xml")))
      (.getCanonicalPath dir))))
