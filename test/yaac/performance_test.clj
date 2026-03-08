(ns yaac.performance-test
  "Performance testing for HTTP connection pooling"
  (:require [yaac.core :as yc]
            [yaac.login :as yl]
            [taoensso.timbre :as log]
            [clojure.test :refer :all]
            [clojure.core.async :as async]))

(defn time-operation [f]
  (let [start (System/currentTimeMillis)]
    (f)
    (- (System/currentTimeMillis) start)))

(defn login-fixture [f]
  (log/set-min-level! :warn)
  (yl/login {:args ["c2"]})
  (f))

(use-fixtures :once login-fixture)

(deftest connection-pooling-performance
  (testing "Performance comparison with connection pooling"
    ;; Test 1: Sequential requests
    (let [sequential-time
          (time-operation
           #(dotimes [_ 10]
              (yc/get-organizations {})))]
      (println "Sequential 10 requests:" sequential-time "ms")

      ;; Test 2: Parallel requests using pmap
      (let [parallel-time
            (time-operation
             #(doall (pmap (fn [_] (yc/get-organizations {}))
                          (range 10))))]
        (println "Parallel 10 requests:" parallel-time "ms")

        ;; Both should complete without errors
        (is (>= sequential-time 0))
        (is (>= parallel-time 0))))))

(deftest stress-test-connection-pool
  (testing "Stress test with many concurrent requests"
    ;; Create 50 concurrent requests
    (let [results (async/chan 50)
          start-time (System/currentTimeMillis)]

      ;; Launch concurrent requests
      (doseq [i (range 50)]
        (async/go
          (try
            (let [result (yc/get-organizations {})]
              (async/>! results {:success true :index i}))
            (catch Exception e
              (async/>! results {:success false :index i :error (.getMessage e)})))))

      ;; Collect results
      (let [collected (atom [])]
        (dotimes [_ 50]
          (swap! collected conj (async/<!! results)))

        (let [elapsed (- (System/currentTimeMillis) start-time)
              successful (filter :success @collected)
              failed (remove :success @collected)]

          (println "Stress test completed in:" elapsed "ms")
          (println "Successful requests:" (count successful))
          (println "Failed requests:" (count failed))

          ;; Most requests should succeed
          (is (> (count successful) 45)))))))
