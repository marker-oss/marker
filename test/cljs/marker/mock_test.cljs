(ns marker.mock-test
  "Unit tests for marker.mock — verifies shape, counts, and determinism.
   Run via: shadow-cljs compile test"
  (:require [cljs.test :refer [deftest is testing]]
            [marker.mock :as mock]))

;; ---------------------------------------------------------------------------
;; Counts
;; ---------------------------------------------------------------------------

(deftest sku-count
  (testing "skus contains exactly 32 entries"
    (is (= 32 (count mock/skus)))))

(deftest top-movers-count
  (testing "top-movers has 5 entries"
    (is (= 5 (count mock/top-movers)))))

(deftest top-fallers-count
  (testing "top-fallers has 5 entries"
    (is (= 5 (count mock/top-fallers)))))

;; ---------------------------------------------------------------------------
;; SKU shape
;; ---------------------------------------------------------------------------

(deftest sku-required-keys
  (testing "every SKU has required keys"
    (doseq [s mock/skus]
      (is (string?  (:id s))        (str (:id s) " :id must be string"))
      (is (string?  (:name s))      (str (:id s) " :name must be string"))
      (is (seq      (:mp s))        (str (:id s) " :mp must be non-empty"))
      (is (pos?     (:revenue s))   (str (:id s) " :revenue must be positive"))
      (is (= 30     (count (:spark s))) (str (:id s) " :spark must have 30 points")))))

(deftest sku-mp-keywords
  (testing "every SKU :mp vector contains only known keyword MPs"
    (let [valid #{:wb :ozon :ym}]
      (doseq [s mock/skus]
        (is (every? valid (:mp s))
            (str (:id s) " :mp contains unknown value"))))))

;; ---------------------------------------------------------------------------
;; Determinism — re-running gen produces the same first SKU
;; ---------------------------------------------------------------------------

(deftest determinism
  (testing "first SKU id is always SKU-1200"
    (is (= "SKU-1200" (:id (first mock/skus)))))

  (testing "first SKU spark has length 30"
    (is (= 30 (count (:spark (first mock/skus))))))

  (testing "top-movers are sorted descending by delta-pct"
    (let [deltas (mapv :delta-pct mock/top-movers)]
      (is (= deltas (sort > deltas)))))

  (testing "top-fallers are sorted ascending by delta-pct"
    (let [deltas (mapv :delta-pct mock/top-fallers)]
      (is (= deltas (sort < deltas))))))

;; ---------------------------------------------------------------------------
;; Series
;; ---------------------------------------------------------------------------

(deftest series-lengths
  (testing "all four series have 30 points"
    (is (= 30 (count mock/revenue-series)))
    (is (= 30 (count mock/profit-series)))
    (is (= 30 (count mock/orders-series)))
    (is (= 30 (count mock/ads-series)))))

(deftest series-positive
  (testing "revenue and profit series are all positive (floor = base * 0.4)"
    (is (every? pos? mock/revenue-series))
    (is (every? pos? mock/profit-series))))

;; ---------------------------------------------------------------------------
;; Alerts / forecast shape
;; ---------------------------------------------------------------------------

(deftest alerts-shape
  (testing "alerts has 3 entries with :kind :title :body :cta"
    (is (= 3 (count mock/alerts)))
    (doseq [a mock/alerts]
      (is (string? (:kind a)))
      (is (string? (:title a)))
      (is (string? (:body a)))
      (is (string? (:cta a))))))

(deftest forecast-shape
  (testing "forecast has :month-plan :month-fact :projection > 0"
    (is (pos? (:month-plan mock/forecast)))
    (is (pos? (:month-fact mock/forecast)))
    (is (pos? (:projection mock/forecast)))))
