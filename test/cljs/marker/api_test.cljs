(ns marker.api-test
  "Unit tests for marker.api Transit encode/decode helpers.
   These are pure unit tests — no HTTP requests, no browser needed.
   Run via: shadow-cljs compile test"
  (:require [cljs.test :refer [deftest is testing]]
            [marker.api :as api]))

;; ---------------------------------------------------------------------------
;; Transit roundtrip
;; ---------------------------------------------------------------------------

(deftest encode-decode-roundtrip-simple-map
  (testing "roundtrip a simple keyword-keyed map"
    (let [m {:foo "bar" :n 42 :b true}
          s (api/encode-transit m)
          d (api/decode-transit s)]
      (is (string? s)    "encode produces a string")
      (is (= m d)        "decoded value equals original"))))

(deftest encode-decode-roundtrip-nested
  (testing "roundtrip a nested map with vectors and numbers"
    (let [m {:kpis {:revenue {:value 1234567.0 :delta-pct 5.2 :spark [1 2 3]}
                    :orders  {:value 320 :delta-pct -1.1 :spark []}}
             :alerts [{:kind "danger" :title "Test" :body "body" :cta "CTA"}]}
          s (api/encode-transit m)
          d (api/decode-transit s)]
      (is (= m d) "nested roundtrip equals original"))))

(deftest encode-decode-nil-values
  (testing "nil values survive roundtrip"
    (let [m {:roas nil :plan nil :delta-pct nil}
          d (api/decode-transit (api/encode-transit m))]
      (is (= m d) "nil values preserved"))))

(deftest encode-decode-keyword-keys
  (testing "keyword keys are preserved through Transit"
    (let [m {:marker/page :pulse :marker/mp-filter [:wb :ozon :ym]}
          d (api/decode-transit (api/encode-transit m))]
      (is (= :pulse              (:marker/page d))       "namespaced keyword value preserved")
      (is (= [:wb :ozon :ym]    (:marker/mp-filter d))   "keyword vector preserved"))))

(deftest encode-decode-empty-collections
  (testing "empty vector and map roundtrip correctly"
    (is (= [] (api/decode-transit (api/encode-transit []))))
    (is (= {} (api/decode-transit (api/encode-transit {}))))))

;; ---------------------------------------------------------------------------
;; build-params helper
;; ---------------------------------------------------------------------------

(deftest build-params-all-mps
  (testing "all 3 MPs → no :mp param (nil)"
    (let [p (api/build-params {:mp-filter [:wb :ozon :ym] :period "Последние 30 дней" :compare false})]
      (is (nil? (:mp p)) "all MPs should not set ?mp="))))

(deftest build-params-subset-mps
  (testing "subset of MPs → :mp param set"
    (let [p (api/build-params {:mp-filter [:wb :ozon] :period "Сегодня" :compare false})]
      (is (= "wb,ozon" (:mp p)) "two MPs encoded as comma-joined string"))))

(deftest build-params-single-mp
  (testing "single MP → :mp param is just that mp name"
    (let [p (api/build-params {:mp-filter [:ym] :period "Сегодня" :compare false})]
      (is (= "ym" (:mp p))))))

(deftest build-params-compare-flag
  (testing "compare true → :compare \"true\""
    (let [p (api/build-params {:mp-filter [:wb :ozon :ym] :period "Последние 30 дней" :compare true})]
      (is (= "true" (:compare p))))))

(deftest build-params-no-compare
  (testing "compare false → :compare key absent"
    (let [p (api/build-params {:mp-filter [:wb :ozon :ym] :period "Последние 30 дней" :compare false})]
      (is (nil? (:compare p))))))
