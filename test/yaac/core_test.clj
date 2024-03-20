(ns yaac.core-test
  (:require [yaac.core :as sut]
            [clojure.test :as t :refer (deftest is testing use-fixtures are)]))


(defn login-fixture [f]
  (sut/login "c2")
  (f))

(deftest login-test
  (testing "login"
    (is (some? (:access-token sut/default-credential)))))

(deftest get-test
  (testing "get org"
    (is (map? (first (sut/get-organizations)))))
  (testing "get environment"
    (is (map? (first (sut/get-environments "T1")))))
  (testing "get asset"
))

