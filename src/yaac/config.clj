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


(ns yaac.config
  (:require [silvur
             [util :refer [json->edn edn->json]]
             [http :as http]
             [log :as log]]
            [reitit.core :as r]
            [clojure.zip :as z]
            [clojure.data.xml :as dx]
            [clojure.java.io :as io]
            [yaac.core :refer [parse-response default-headers org->id org->id* env->id org->name load-session!] :as yc]
            [yaac.error :as e]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [yaac.incubate :as ic]
            [clojure.spec.alpha :as s]
            [org.httpkit.client :as hk]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.data.json :as json]))

(defn usage [summary-options]
  (->> ["Usage: config key value [options]"
        ""
        "Configurate contexts like default org and env. This context is stored in ~/.yaac/current-context"
        ""
        "Options:"
        ""
        summary-options
        ""
        "Keys:"
        ""
        "  - credential       Set connected app's credential (interactive)"
        "  - context <org>    Set current organization to 'org'"
        "  - clear-cache      Clear local cache data"
        ""
        "Example:"
        ""
        "# Set a credential interactively"
        "  yaac config credential"
        ""
        "# Set current organization and environment to T1 and Production"
        "  yaac config context T1 Production"
        ""
        "# Clear local cache"
        "  yaac config clear-cache"

        ""]
       (str/join \newline)))

(def options [])

(defn current-context [& _]
  (read-string (slurp (io/file (System/getenv "HOME") ".yaac" "config"))))

;; (when-not keep-used-pom (io/delete-file n-path))
(defn config-context [{[org env deploy-target] :args
                       :as opts}]
  (let [config-file (io/file (System/getenv "HOME") ".yaac" "config")]
    (when-not (.exists config-file)
      (io/make-parents config-file))

    (when (and org env)
      (spit config-file (cond-> {:organization org :environment env}
                          deploy-target (assoc :deploy-target deploy-target))))
    (current-context)))


(defn config-credentials [_]
  (let [con (System/console)
        name (.readLine con "%s" (into-array ["app name: "]))
        id (.readLine con "%s" (into-array ["client id: "]))
        secret (.readLine con "%s" (into-array ["client secret: "]))
        auth-method-number (do (println "grant type:")
                               (println " 1. client credentials")
                               (println " 2. authorization code")
                               (println " 3. resource owner password")
                              (.readLine con "%s" (into-array ["> "])))
        auth-method (case (parse-long auth-method-number)
                      1 "client_credentials"
                      2 "authorization_code"
                      3 "password"
                      (do (println "use default")
                          "client_credentials"))
        scope (when (not= auth-method "client_credentials")
                (.readLine con "%s" (into-array ["scope: "])))
        
        cred-file (io/file yc/default-credentials-path)]
    (yc/-store-credentials! name id secret auth-method scope)
    {:status "OK"}))



(defn clear-cache
  ([]
   (clear-cache {}))
  ([_]
   (let [f (io/file (System/getenv "HOME") ".yaac" "cache")]
     (if (.exists f)
       (do (io/delete-file f)
           (log/debug "Clear cahce"))
       (log/debug "No cache file: " (str f)))
     {:status "OK"})))




