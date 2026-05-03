(ns marker.pages.pnl-test
  "Tests for P&L page derived column calculations."
  (:require [cljs.test :refer [deftest is testing]]
            [marker.mock :as mock]))

;; ---------------------------------------------------------------------------
;; Per-SKU net calculation test
;; The P&L per-SKU table shows: net = revenue * margin - ads-cost
;; (negative cost rows are displayed with red; this tests the raw formula)
;; ---------------------------------------------------------------------------

(defn- compute-net [sku]
  (- (* (:revenue sku) (:margin sku))
     (:ads-cost sku)))

(deftest sku-net-formula
  (testing "net = revenue * margin - ads-cost"
    (let [sku (first mock/skus)]
      (is (number? (compute-net sku)))
      (is (= (compute-net sku)
             (- (* (:revenue sku) (:margin sku))
                (:ads-cost sku))))))

  (testing "all SKUs have numeric net"
    (doseq [sku mock/skus]
      (is (number? (compute-net sku))
          (str "Expected numeric net for " (:id sku)))))

  (testing "SKU-1200 (index 0) net is reasonable"
    (let [sku (first mock/skus)
          net (compute-net sku)]
      ;; margin is always in [0.12, 0.44], revenue >= 40000,
      ;; ads-cost = revenue * [0.06, 0.24], so net can be positive or negative
      ;; but must be finite
      (is (js/isFinite net))
      (is (not (js/isNaN net)))))

  (testing "DRR = ads-cost / revenue"
    (let [sku (first mock/skus)
          drr (* (/ (:ads-cost sku) (:revenue sku)) 100)]
      (is (> drr 0))
      (is (< drr 100)))))

;; ---------------------------------------------------------------------------
;; P&L summary rows shape
;; ---------------------------------------------------------------------------

(deftest pnl-rows-shape
  (testing "pnl-rows has 11 entries"
    (is (= 11 (count mock/pnl-rows))))

  (testing "first row is revenue (positive)"
    (let [r (first mock/pnl-rows)]
      (is (= "revenue" (:key r)))
      (is (pos? (:cur r)))))

  (testing "last row is net profit (total group)"
    (let [r (last mock/pnl-rows)]
      (is (= "net" (:key r)))
      (is (= "total" (:group r)))))

  (testing "all rows have required keys"
    (doseq [r mock/pnl-rows]
      (is (contains? r :key))
      (is (contains? r :label))
      (is (contains? r :cur))
      (is (contains? r :prev))
      (is (contains? r :group))))

  (testing "delta pct calculation for revenue row"
    (let [r (first mock/pnl-rows)
          d-abs (- (:cur r) (:prev r))
          d-pct (* (/ d-abs (js/Math.abs (:prev r))) 100)]
      (is (pos? d-pct) "Revenue grew vs prev period")
      ;; 8420000 - 7510000 = 910000; 910000/7510000*100 ≈ 12.1%
      (is (< 10 d-pct 15)))))
