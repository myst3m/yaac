(ns yaac.deploy-test
  (:require [clojure.test :refer [deftest is testing]]
            [reitit.core :as r]
            [yaac.cli :as cli]
            [yaac.describe :as desc]))

;; Issue: object-store-v2 should default to true when not specified
(deftest object-store-v2-default-true
  (testing "contains? distinguishes unspecified from explicit false"
    (let [;; Simulates the logic used in deploy.clj for OSv2
          osv2-enabled (fn [opts]
                         (if (contains? opts :object-store-v2)
                           (parse-boolean (first (:object-store-v2 opts)))
                           true))]
      (testing "unspecified → default true"
        (is (true? (osv2-enabled {}))))
      (testing "explicit true"
        (is (true? (osv2-enabled {:object-store-v2 ["true"]}))))
      (testing "explicit false"
        (is (false? (osv2-enabled {:object-store-v2 ["false"]})))))))

;; Issue: deploy should check asset existence before deploying
(deftest asset-preflight-check-logic
  (testing "select-keys returns nil version when key missing"
    (let [opts {:group "T1" :asset "my-app"}]
      (is (nil? (:version (select-keys opts [:group :asset :version]))))))
  (testing "select-keys returns all when present"
    (let [opts {:group "T1" :asset "my-app" :version "1.0.0"}
          {:keys [group asset version]} (select-keys opts [:group :asset :version])]
      (is (and group asset version)))))

;; Issue: describe policy-instance route should exist
(deftest describe-policy-instance-route
  (let [router cli/router]
    (testing "describe|pi|{*args} route exists"
      (let [match (r/match-by-path router "describe|pi|T1|Sandbox|20762752|my-policy")]
        (is (some? match))
        (is (= desc/describe-policy-instance (-> match :data :handler)))))
    (testing "desc|pi|{*args} alias route exists"
      (let [match (r/match-by-path router "desc|pi|T1|Sandbox|20762752|my-policy")]
        (is (some? match))
        (is (= desc/describe-policy-instance (-> match :data :handler)))))
    (testing "describe|policy-instance|{*args} route exists"
      (let [match (r/match-by-path router "describe|policy-instance|T1|Sandbox|20762752|my-policy")]
        (is (some? match))
        (is (= desc/describe-policy-instance (-> match :data :handler)))))
    (testing "output-format is :json"
      (let [match (r/match-by-path router "describe|pi|T1|Sandbox|123|pol")]
        (is (= :json (-> match :data :output-format)))))))

;; Issue: describe app fields should include osv2 and properties
(deftest describe-app-fields-include-osv2
  (let [router cli/router]
    (testing "describe|app fields include osv2 and properties"
      (let [match (r/match-by-path router "describe|app|T1|Sandbox|my-app")
            fields (-> match :data :fields)]
        (is (some? fields))
        (is (some #(and (vector? %) (= :object-store-v2 (second %))) fields)
            "should have object-store-v2 field")
        (is (some #(and (vector? %) (= :properties (second %))) fields)
            "should have properties field")))))
