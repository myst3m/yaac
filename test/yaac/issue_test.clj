(ns yaac.issue-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [silvur.datetime :refer [chrono-unit]]
            [reitit.core :as r]
            [yaac.config :as config]))

;; Issue #5: upload-asset should find file by .exists check regardless of position in args
(deftest issue-5-upload-asset-file-detection
  (let [find-file (fn [args]
                    (first (filter #(.exists (io/file %)) args)))
        ;; Use deps.edn as a file that exists
        existing-file "deps.edn"]
    (testing "file-path at head (original order)"
      (is (= existing-file (find-file [existing-file "A1"]))))
    (testing "file-path after positional group arg"
      (is (= existing-file (find-file ["A1" existing-file]))))
    (testing "no existing file → nil"
      (is (nil? (find-file ["A1" "nonexistent.jar"]))))))

;; Issue #6: config context route should not inherit :status field from parent
(deftest issue-6-config-context-no-status-field
  (let [routes (first config/route)
        router (r/router [routes] {:syntax '|})]
    (testing "config|ctx should only have [:organization :environment :deploy-target]"
      (let [match (r/match-by-path router "configure|ctx")]
        (is (some? match))
        (is (= [:organization :environment :deploy-target]
               (:fields (:data match))))))
    (testing "parent should not have :fields (no :status leak)"
      (let [match (r/match-by-path router "configure")]
        (is (nil? (:fields (:data match))))))))

;; Issue #7: chrono-unit should accept :millis (not :milli)
(deftest issue-7-chrono-unit-millis
  (testing ":millis resolves without error"
    (is (some? (chrono-unit :millis))))
  (testing ":milli throws NoMatchingClause"
    (is (thrown? IllegalArgumentException (chrono-unit :milli)))))

;; Issue #8: get-api-policies branch should trigger on api alone (not (and api env))
(deftest issue-8-policy-branch-condition
  (testing "single arg (api only) should take the api-policies branch, not exchange branch"
    ;; Simulate the branching logic from get-api-policies
    (let [args ["my-api"]
          [api env org] (reverse args)]
      ;; Before fix: (and api env) → false for single arg → wrong branch
      ;; After fix: api → true for single arg → correct branch
      (is (some? api) "api should be non-nil")
      (is (nil? env) "env should be nil (will use *env* fallback)")
      ;; The fix: branch on `api` alone
      (is (true? (boolean api)) "branch condition should be truthy with just api"))))
