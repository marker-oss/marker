(ns analitica.domain.stock-history-test
  "RFC-13 unit tests for stocks_history domain functions.
   Schema validation lives in
   `test/analitica/schema/normalized/stocks_history_test.clj`."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.stock :as stock]))

(defn- snap [date qty]
  {:snapshot-date date
   :marketplace   "wb"
   :article       "ART-1"
   :warehouse     "Коледино"
   :quantity      qty})

;; ---------------------------------------------------------------------------
;; velocity
;; ---------------------------------------------------------------------------

(deftest velocity-needs-two-snapshots
  (testing "Single snapshot returns nil"
    (is (nil? (stock/velocity [(snap "2026-04-20" 100)]))))
  (testing "Empty seq returns nil"
    (is (nil? (stock/velocity [])))))

(deftest velocity-positive-when-stock-declines
  (testing "Stock fell 100→40 in 10 days → 6/day"
    (let [rows [(snap "2026-04-20" 100) (snap "2026-04-30" 40)]]
      (is (= 6.0 (stock/velocity rows))))))

(deftest velocity-negative-when-stock-grows
  (testing "Stock grew 50→100 → velocity negative (restock happened)"
    (let [rows [(snap "2026-04-20" 50) (snap "2026-04-25" 100)]]
      (is (= -10.0 (stock/velocity rows))))))

(deftest velocity-zero-day-window-returns-nil
  (testing "Two snapshots same date → can't divide by 0 → nil"
    (let [rows [(snap "2026-04-25" 80) (snap "2026-04-25" 60)]]
      (is (nil? (stock/velocity rows))))))

;; ---------------------------------------------------------------------------
;; days-of-supply
;; ---------------------------------------------------------------------------

(deftest days-of-supply-current-stock-divided-by-velocity
  (testing "Current 40 units, velocity 4/day → 10 days left"
    (let [rows [(snap "2026-04-20" 80) (snap "2026-04-30" 40)]]
      (is (= 10.0 (stock/days-of-supply rows))))))

(deftest days-of-supply-zero-stock-returns-zero
  (testing "Stock already at 0"
    (let [rows [(snap "2026-04-20" 100) (snap "2026-04-30" 0)]]
      (is (= 0 (stock/days-of-supply rows))))))

(deftest days-of-supply-non-declining-velocity-infinite
  (testing "Stock grew → velocity ≤ 0 → :infinite (no restock pressure)"
    (let [rows [(snap "2026-04-20" 50) (snap "2026-04-25" 100)]]
      (is (= :infinite (stock/days-of-supply rows))))))

(deftest days-of-supply-empty-history-nil
  (testing "Single or empty history → nil"
    (is (nil? (stock/days-of-supply [])))
    (is (nil? (stock/days-of-supply [(snap "2026-04-20" 50)])))))

;; ---------------------------------------------------------------------------
;; stock-trend
;; ---------------------------------------------------------------------------

(deftest stock-trend-shape
  (testing "Trend returns from/to/delta/pct-change"
    (let [rows [(snap "2026-04-20" 100) (snap "2026-04-30" 40)]
          t (stock/stock-trend rows)]
      (is (= "2026-04-20" (:from-date t)))
      (is (= "2026-04-30" (:to-date t)))
      (is (= 100 (:from-qty t)))
      (is (= 40  (:to-qty t)))
      (is (= -60 (:delta t)))
      (is (= -60.0 (:pct-change t)) "(-60/100)*100 = -60"))))

(deftest stock-trend-zero-from-skips-pct
  (testing "from=0 → pct-change is nil (avoid division by zero)"
    (let [rows [(snap "2026-04-20" 0) (snap "2026-04-30" 50)]
          t (stock/stock-trend rows)]
      (is (nil? (:pct-change t))))))

(deftest stock-trend-single-snapshot
  (testing "1 row → from = to, delta = 0"
    (let [t (stock/stock-trend [(snap "2026-04-25" 80)])]
      (is (= 80 (:from-qty t)))
      (is (= 80 (:to-qty t)))
      (is (zero? (:delta t))))))
