(ns yaac.error
  (:require [taoensso.timbre :as log]
            [yaac.error :as e]))

(defmacro deferror [name state-id & [msg]]
  `(defn ~name
     ([] (~name {}))
     ([msg-or-map#]
      (if (string? msg-or-map#)
        (~name (or msg-or-map# ~msg) {:message (or msg-or-map# ~msg) :status ~state-id})
        (~name (or (:extra (:message msg-or-map#))) (:extra msg-or-map#))))
     ([msg# m# & m-rest#]
      ;; m# should be sequence
      (log/debug "Message:" msg#)
      (log/debug "raw:" m#)
      
      (doseq [x# m-rest#]
        (log/debug "raw:" x#))
      (ex-info msg# {:state ~state-id
                     :extra (assoc {}
                                   :status (or  (:status m#) ~state-id)
                                   :message (or (:message m#) msg# ~msg))}))))

(deferror org-not-found 1000)
(deferror env-not-found 1001)
(deferror app-not-found 1002)
(deferror api-not-found 1003)
(deferror target-not-found 1004)
(deferror team-not-found 1005)
(deferror connected-app-not-found 1006)

(deferror multiple-app-name-found 1100)
(deferror multiple-api-name-found 1101)
(deferror multiple-target-name-found 1102)
(deferror runtime-target-not-found 1103)
(deferror multiple-runtime-targets 1104)
(deferror multiple-private-sppace-found 1105)
(deferror multiple-connections 1106)
(deferror multiple-policies 1107)

(deferror no-asset-found 2000 "No asset found")

(deferror no-item 7999)
(deferror no-session-store 8000)
(deferror invalid-credentials 8001)
(deferror auth-method-not-supported 8002)
(deferror no-default-context 8003)
(deferror not-supported-file-type 8004)


(deferror error 9000 "Unexpected error")
(deferror invalid-arguments 9001 "Invalid arguments")
(deferror parse-error 9002)
(deferror not-implemented 9998)
(deferror unexpected-error 9999)



(defn multi-errors [errors]
  {:errors (map (fn [e]
                  {:extra {:status (or (-> e :extra :status)
                                       (-> e :status)
                                       9000)
                           :message (or (-> e :extra :message)
                                        (-> e :message)
                                        "Unexpected error")}})
                errors)})
