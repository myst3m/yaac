(ns yaac.incubate
  (:require [silvur
             [util :refer [json->edn edn->json]]
             [log :as log]]
            [zeph.client :as http]
            [reitit.core :as r]
            [yaac.core :refer [parse-response default-headers org->id load-session! gen-url] :as yc]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]))

;;;
(defn get-connected-apps [client-id]
  (->> @(http/get (format (gen-url "/accounts/api/connectedApplications/%s") client-id)
                  {:headers (default-headers)})
       (parse-response)))




(defn create-connected-application []
  (->> @(http/post (gen-url "/accounts/api/connectedApplications")
                  {:headers (default-headers)
                   :body (edn->json
                           {:scopes ["profile"],
                            :redirect_uris ["http://localhost:9180"],
                            :grant_types ["client_credentials"],
                            :client_name "SampleClient",
                            :client_id "aaaa",
                            :audience "internal"})})
       (parse-response)
       :body))
