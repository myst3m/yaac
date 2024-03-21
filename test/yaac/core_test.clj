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

