(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.jar JarFile]))

(def lib 'io.gitlab.myst3m/yaac)
(def version "0.9.1")
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s.jar" (name lib) version))
(def native-image-name (format "target/%s-%s" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn- merge-plexus-components
  "Pre-merge META-INF/plexus/components.xml from all classpath JARs into class-dir."
  [basis]
  (let [components
        (->> (:classpath-roots basis)
             (filter #(str/ends-with? (str %) ".jar"))
             (mapcat (fn [jar-path]
                       (try
                         (let [jf (JarFile. (str jar-path))
                               entry (.getEntry jf "META-INF/plexus/components.xml")]
                           (when entry
                             (let [content (slurp (.getInputStream jf entry))
                                   ;; Extract <component>...</component> blocks
                                   matches (re-seq #"(?s)<component>.*?</component>" content)]
                               matches)))
                         (catch Exception _ nil)))))]
    (when (seq components)
      (let [out-dir (io/file class-dir "META-INF" "plexus")
            merged (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        "<component-set>\n  <components>\n"
                        (str/join "\n" (map #(str "    " %) components))
                        "\n  </components>\n</component-set>\n")]
        (.mkdirs out-dir)
        (spit (io/file out-dir "components.xml") merged)))))

(defn uber [_]
  (let [basis (b/create-basis {:project "deps.edn"})]
    (clean nil)
    (println "Copying sources...")
    (b/copy-dir {:src-dirs ["src" "resources"]
                 :target-dir class-dir})
    (println "Compiling Java patches (ClassRealm)...")
    (b/javac {:src-dirs ["src-java"]
              :class-dir class-dir
              :basis basis
              :javac-opts ["-source" "17" "-target" "17"]})
    (println "Compiling yaac.cli...")
    (b/compile-clj {:basis basis
                    :ns-compile ['yaac.cli]
                    :compile-opts {:direct-linking true}
                    :class-dir class-dir})
    ;; Pre-merge Plexus components.xml from Maven JARs
    (merge-plexus-components basis)
    (println "Creating uber jar:" uber-file)
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'yaac.cli
             :conflict-handlers {"META-INF/sisu/javax.inject.Named" :append
                                 "META-INF/plexus/components.xml" :ignore}})
    ;; Fix SLF4J provider: munit-remote fat JAR bundles log4j-slf4j2-impl which
    ;; registers an SLF4J 2.x ServiceProvider, overriding our Timbre bridge.
    ;; Replace the service file with an empty one so SLF4J falls back to StaticLoggerBinder.
    (let [svc-dir (io/file class-dir "META-INF" "services")
          svc-file (io/file svc-dir "org.slf4j.spi.SLF4JServiceProvider")]
      (spit svc-file "")
      (b/process {:command-args ["jar" "uf" uber-file
                                 "-C" class-dir
                                 "META-INF/services/org.slf4j.spi.SLF4JServiceProvider"]}))
    (println "Done!")))

(defn native-image
  "Build native image using GraalVM.
   Requires: GraalVM with native-image installed.

   Usage: clj -T:build native-image"
  [_]
  ;; First build uberjar
  (uber nil)

  ;; Then run native-image
  (let [graalvm-home (or (System/getenv "GRAALVM_HOME") "/opt/graal")
        native-image-bin (str graalvm-home "/bin/native-image")]
    (println "Building native image...")
    (b/process {:command-args [native-image-bin
                               "-jar" uber-file
                               "-o" native-image-name
                               ;; GraalVM options
                               "--features=clj_easy.graal_build_time.InitClojureClasses"
                               "--no-fallback"
                               ;; Build time init for libraries storing Java objects in Vars
                               "--initialize-at-build-time=org.fusesource.jansi,java.sql.Date,org.slf4j.impl.StaticLoggerBinder,com.github.fzakaria.slf4j.timbre"
                               ;; Enable URL protocols for Maven plugin classloading
                               "--enable-url-protocols=jar,http,https"
                               ;; Quick build mode (faster compile, slower runtime)
                               "-Ob"
                               ;; Resource limits
                               "-J-Xmx8g"
                               "-J-XX:ActiveProcessorCount=4"
                               ;; Debug
                               "-H:+ReportExceptionStackTraces"]})
    (println "Native image built:" native-image-name)))
