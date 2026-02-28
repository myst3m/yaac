(ns yaac.upload-test
  (:require [clojure.test :refer [deftest is testing]]
            [yaac.core :as yc]
            [yaac.upload :as upload]))

;; Access private fn
(def resolve-version @#'upload/resolve-version)

(defmacro with-assets
  "Mock yc/get-assets to return the given versions."
  [versions & body]
  `(with-redefs [yc/get-assets (fn [_#] (mapv (fn [v#] {:version v#}) ~versions))]
     ~@body))

(deftest resolve-version-test
  (testing "バージョン未指定 → 既存の最新+1"
    (with-assets ["1.0.0" "1.0.1"]
      (is (= "1.0.2" (resolve-version "g" "a" nil nil)))))

  (testing "バージョン未指定、既存なし → fallback をそのまま返す"
    (with-assets []
      (is (= "0.0.1" (resolve-version "g" "a" nil "0.0.1")))))

  (testing "指定バージョンが既存にない → そのまま返す"
    (with-assets ["1.0.0"]
      (is (= "2.0.0" (resolve-version "g" "a" "2.0.0" nil)))))

  (testing "指定バージョンが衝突 → patch+1"
    (with-assets ["1.0.0"]
      (is (= "1.0.1" (resolve-version "g" "a" "1.0.0" nil)))))

  (testing "連続衝突 → 空きまでインクリメント"
    (with-assets ["1.0.0" "1.0.1" "1.0.2"]
      (is (= "1.0.3" (resolve-version "g" "a" "1.0.0" nil)))))

  (testing "バージョン未指定、fallback も nil、既存なし → nil"
    (with-assets []
      (is (nil? (resolve-version "g" "a" nil nil)))))

  (testing "バージョン未指定、fallback が既存と衝突 → patch+1"
    (with-assets ["0.0.1"]
      (is (= "0.0.2" (resolve-version "g" "a" nil "0.0.1"))))))
