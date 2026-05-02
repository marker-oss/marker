(ns analitica.db-cash-flow-test
  "Regression for `db/cash-flow-adjustments` period semantics.

   Prior behaviour: `WHERE period_begin >= from AND period_end <= to`
   (strict containment). Cash-flow rows are weekly buckets aligned to
   the marketplace's reporting calendar (Ozon publishes Mon-Sun weeks,
   plus a stub at each month boundary). When the UI sliced a month
   into arbitrary 7-day windows, almost no full bucket fitted inside
   any single window and Σ(weekly cf-adjustments) = 0 even though
   monthly cf-adjustments was the real total.

   Fixed semantics: overlap query + day-pro-rate. Each row contributes
   its values scaled by `intersect_days / period_days`. Σ(weekly slices)
   reconciles with the full-period total."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.db :as db]))

(def ^:private apr-rows
  "Mirrors live Ozon cash-flow shape for April 2026: month-boundary stub
   + four Mon-Sun weeks. Every row carries 100₽ subscription so the
   pro-rate math is easy to verify by inspection."
  [{:period-begin "2026-04-01" :period-end "2026-04-05" :subscription 100.0}
   {:period-begin "2026-04-06" :period-end "2026-04-12" :subscription 100.0}
   {:period-begin "2026-04-13" :period-end "2026-04-19" :subscription 100.0}
   {:period-begin "2026-04-20" :period-end "2026-04-26" :subscription 100.0}])

(defn- adj [from to]
  (with-redefs [db/query (fn [_] apr-rows)]
    (db/cash-flow-adjustments "ozon" from to)))

(deftest monthly-aligned-window-covers-every-row
  (testing "Whole period [04-01..04-30] covers all four rows fully → 4×100 = 400"
    (is (== 400.0 (:subscription (adj "2026-04-01" "2026-04-30"))))))

(deftest non-aligned-week-pro-rates-overlapping-rows
  (testing "[04-08..04-14] overlaps period [04-06..04-12] (5 of 7 days)
            and period [04-13..04-19] (2 of 7 days) → 5/7 + 2/7 = 1.0×100"
    (let [v (:subscription (adj "2026-04-08" "2026-04-14"))]
      (is (< (Math/abs (- 100.0 v)) 0.0001)))))

(deftest sum-of-weekly-slices-reconciles-to-monthly
  (testing "Σ(four ISO weeks of April) reconciles to monthly minus
            the days that fall outside w1..w4. Cash-flow buckets that
            partially fall after w4 (period 4 covers 04-20..04-26 fully
            so no spillover; but period 1 stub covers 04-01..04-05 fully
            within w1) — the only true gap is days 04-29..04-30 with no
            row, so Σ should equal monthly when stub fits in w1."
    (let [m   (:subscription (adj "2026-04-01" "2026-04-30"))
          w1  (:subscription (adj "2026-04-01" "2026-04-07"))
          w2  (:subscription (adj "2026-04-08" "2026-04-14"))
          w3  (:subscription (adj "2026-04-15" "2026-04-21"))
          w4  (:subscription (adj "2026-04-22" "2026-04-28"))
          sum (+ w1 w2 w3 w4)]
      (is (<= sum m))
      (is (< (Math/abs (- sum m)) 0.0001)
          (str "monthly=" m " sum=" sum)))))

(deftest empty-window-returns-zeros
  (testing "Window outside any cash-flow period returns 0 across all keys"
    (with-redefs [db/query (fn [_] [])]
      (let [a (db/cash-flow-adjustments "ozon" "2025-01-01" "2025-01-31")]
        (is (every? zero? (vals a)))))))
