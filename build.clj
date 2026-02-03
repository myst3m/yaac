(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.gitlab.myst3m/yaac)
(def version "0.9.0")
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s.jar" (name lib) version))
(def native-image-name (format "target/%s-%s" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (let [basis (b/create-basis {:project "deps.edn"})]
    (clean nil)
    (println "Copying sources...")
    (b/copy-dir {:src-dirs ["src" "resources"]
                 :target-dir class-dir})
    (println "Compiling yaac.cli...")
    (b/compile-clj {:basis basis
                    :ns-compile ['yaac.cli]
                    :compile-opts {:direct-linking true}
                    :class-dir class-dir})
    (println "Creating uber jar:" uber-file)
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'yaac.cli})
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
                               "--initialize-at-build-time=org.fusesource.jansi,java.sql.Date"
                               ;; Quick build mode (faster compile, slower runtime)
                               "-Ob"
                               ;; Resource limits
                               "-J-Xmx4g"
                               "-J-XX:ActiveProcessorCount=4"
                               ;; Debug
                               "-H:+ReportExceptionStackTraces"]})
    (println "Native image built:" native-image-name)))
