(ns yaac.core-test
  (:require [yaac.core :as yc]
            [yaac.login :as yl]
            [silvur.log :as log]
            [clojure.test :as t :refer (deftest is testing use-fixtures are)]))


;; Required to store credentials to .yaac/credentials like below
;; {"c2":	
;;  {"client_id":"your id",
;;   "client_secret":"your secret",
;;   "grant_type":"client_credentials"}}

(defmacro try-wrap [& body]
  `(try
     ~@body
     (catch Exception e# (ex-data e#))))

(defn login-fixture [f]
  (log/set-min-level! :warn)
  (yl/login {:args ["c2"]})
  (f))

(use-fixtures :once login-fixture)

(deftest login-test
  (testing "login"
    (is (some? (:access-token yc/default-credential)))))

(deftest get-test
  (testing "get org"
    (is (map? (first (yc/get-organizations)))))
  (testing "get environment"
    (is (map? (first (yc/get-environments {:args ["T1"]})))))
  (testing "get app"
    (let [{:keys [id extra]} (first (yc/get-deployed-applications {:args ["T1"  "Production"]}))]
      (is (= #{:org :env :status :target} (set (keys extra))))))
  (testing "get api instances"
    (let [{:keys [id extra]} (first (yc/get-api-instances {:args ["T1" "Production"]}))]
      (is (= #{:org :env :status :target} (set (keys extra)))))))

(deftest get-test-failed
  (testing "get environment - failed"
    (is (= {:extra {:status 1000, :message "Not found organization"}, :state 1000}
           (try (yc/get-environments {:args ["NOORG"]}) (catch Exception e (ex-data e))))))
  (testing "get app - failed"
    (are [org env]
        (= {:extra {:status 1000, :message "Not found organization"}, :state 1000}
           (try-wrap (yc/get-deployed-applications {:args [org env]})))
      "T1" "NOENV"
      "NOORG" "NOENV")
))

