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


(ns yaac.upload
  (:import [java.util.zip ZipEntry ZipFile])
  (:require [silvur
             [util :refer [json->edn edn->json]]
             [http :as http]
             [log :as log]]
            [reitit.core :as r]
            [clojure.zip :as z]
            [clojure.data.xml :as dx]
            [clojure.java.io :as io]
            [yaac.core :refer [parse-response default-headers
                               org->id org->id*
                               env->id org->name load-session!] :as yc]
            [yaac.error :as e]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [yaac.incubate :as ic]
            [clojure.spec.alpha :as s]
            [org.httpkit.client :as hk]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.core.match :refer [match]]
            ))

(defn usage [summary-options]
  (->> ["Usage: upload <asset> <file> [options]"
        ""
        "Upload assets to Anypoint Exchange."
        ""
        "Options:"
        ""
        summary-options
        ""
        "Resources:"
        ""
        "  - asset <file> [options]      Upload file as the specified type with option -t"
        ""
        "As a default, if file is jar file, it is uploaded as mule-application."
        "Each file is identified with the first 6 bytes as a signature."
        ""
        "Example:"
        ""
        "# Specify Group Name, Artifact ID and version. The Group Id is converted from Group name."
        ""
        "  yaac upload asset my-app.jar -g T1 -a my-app -v 0.0.1 -t app"
        ""
        "# Use specified Group and Artifact Id from pom.xml in Jar, default type is handled as app for JAR file"
        ""
        "  yaac upload asset mule-app.jar"
        ""
        "# Upload RAML API Spec"
        ""
        " yaac upload asset account.raml -g T1 -a account-api -v 0.0.1"
        ""
        "Hint:"
        " Labels can be added by using yaac update asset command. See help."
        ""
        ""]
       (str/join \newline)))


(def options [["-g" "--group GROUP-" "Group name. Normally BG name"]
              ["-a" "--asset ASSET" "Asset name"]
              ["-v" "--version VERSION" "Asset version"]
              ["-t" "--asset-type TYPE" "Asset type. app|plugin|raml"
               :default "app"]
              ;; ["-t" "--tags TAGS" "Comma separated tags as app,demo,..."]
              ;; ["-P" "--pom-path PATH" "POM file"]
              ;; [nil "--keep-used-pom" "Do not delete pom used for upload"
              ;;  :default false]
              [nil "--api-version VERSION" "API Version"
               :default "v1"]])



;; (when-not keep-used-pom (io/delete-file n-path))
(defn upload-jar [{:keys [group asset version labels asset-type]
                   [jar-path] :args
                   :as opts}]
  (let [xml-str (yc/slurp-pom-file jar-path)
        root-loc (dx/parse-str xml-str)
        {g :group-id a :artifact-id v :version} (yc/pom-get-gav (z/xml-zip root-loc))
        group-id (or (org->id* group) g)
        artifact-id (or asset a)
        latest-version  (some-> (->> (yc/get-assets {:group group-id :asset artifact-id})
                                     (keep :version))
                                (sort)
                                (last)
                                (str/split #"\.")
                                (vec)
                                (update-in [2] (comp inc parse-long))
                                (->> (str/join ".")))
        version (or version
                    latest-version
                    v)
        n-path (str "pom-" (rand-int 10000) ".xml")
        
        url (format "https://anypoint.mulesoft.com/exchange/api/v2/organizations/%s/assets/%s/%s/%s" group-id group-id artifact-id version)

        type-field (condp = (keyword asset-type)
                     :app "mule-application"
                     :plugin "mule-plugin"
                     "mule-application")
        ;; If pom file is specified, tags cannot be added...
        multipart (cond-> [{:name (str "files." type-field ".jar") :content (io/file jar-path)
                            :filename "file.jar"}]
                    ;; Umm, this field is 'tags' even though query field is labels...
                    (not (seq labels)) (conj {:name "files.pom" :content (io/file n-path) :filename "pom.xml"})
                    labels (conj {:name "tags" :content (str/join "," labels)}))]

    (spit n-path (if (or group asset version)
                   (yc/assoc-pom (z/xml-zip root-loc) {:name artifact-id ;; name is overwritten by Platform. It should be updated later by PATH ...
                                                       :artifact-id artifact-id
                                                       :group-id group-id
                                                       :version version})
                   xml-str))
    
    
    (log/debug "Upload application")
    (log/debug "URL:" url)
    (log/debug "multipart:"  multipart)
    (log/debug "POM: " n-path)
    
    (-> (http/post url {:headers {"Authorization" (str "Bearer " (:access-token yc/default-credential))
                                   "x-sync-publication" true
                                   "Content-Type" "multipart/form-data"}
                        :timeout 300000 ;; timeout 300s
                        :multipart multipart})
        
        (parse-response)
        :body
        (as-> result
            (do (io/delete-file n-path)
                result))
        (yc/add-extra-fields :group-name (org->name group-id)))))

(defn upload-raml [{:keys [group asset version api-version]
                    :or {api-version "v1"}
                    [raml-path] :args
                    :as opts}]
  (if (and group asset version raml-path)
    (let [group-id (org->id* group)
          artifact-id asset]
      (-> (http/post (format "https://anypoint.mulesoft.com/exchange/api/v2/organizations/%s/assets/%s/%s/%s" group-id group-id artifact-id version)
                     {:headers {"Authorization" (str "Bearer " (:access-token yc/default-credential))
                                "x-sync-publication" true
                                "Content-Type" "multipart/form-data"}
                      :multipart
                      [{:name "name" :content asset} ;; name is overwritten by Platform, therefore it should be updated by PATCH...
                       {:name "properties.apiVersion" :content api-version}
                       {:name "properties.mainFile" :content "api.raml"}
                       {:name "files.raml.raml" :content (io/file raml-path)
                        :filename "api.raml"}]})
          
          (parse-response)
          :body
          (yc/add-extra-fields :group-name (org->name group-id))))
    (throw (e/invalid-arguments "group, asset, version and raml path are required"))))

;; https://en.wikipedia.org/wiki/List_of_file_signatures

(defn identify-file-type [file-path]
  (let [is (io/input-stream file-path)
        buf (byte-array 16)
        ]
    (.read is buf)
    (.close is)
    
    (letfn [(jar? [file-path]
              (with-open [zf (ZipFile. (io/file file-path))]
                (some? (.getEntry zf "META-INF"))))]
      
      (match (vec buf)
             [0x50 0x4B 0x03 0x04 & _] (if (jar? file-path) :JAR :ZIP)
             [0x23 0x25 0x52 0x41 0x4d 0x4c & _] :RAML))))


(defn upload-asset [{:keys [group asset version api-version asset-type]
                    :or {api-version "v1"}
                     [file-path] :args
                    :as opts}]
  (log/debug "File: " file-path)
  (if-not (.exists (io/file file-path))
    (throw (e/invalid-arguments "No file specified" {:file file-path}))
    (let [file-type (identify-file-type file-path)]
      (case file-type
        :JAR (upload-jar opts)
        :RAML (upload-raml opts)
        :else (throw (e/not-supported-file-type "No JAR/ZIP or RAML" {:file file-path}))))))

(def route
  (for [op ["up" "upload"]]
    [op {:options options
         :usage usage}
     ["" {:help true}]   
     ["|-h" {:help true}]
     ["|asset" {:help true}]
     ["|asset|{*args}" {:fields [:organization-id :group-id [:extra :group-name] :name :asset-id  :type :version]
                        :handler upload-asset}]]))
