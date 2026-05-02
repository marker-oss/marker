(ns analitica.domain.ozon-distribute-test
  "Bug-E regression: Ozon `/v2/finance/realization` rows should be spread
   over their actual sale days (using `sales` table weights) so that a
   weekly UI slice no longer collapses all monthly revenue into the
   first ISO week."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.db :as db]
            [analitica.domain.ozon-distribute :as od]))

(def ^:private realization-row
  "A typical Ozon realization row: month-aggregate, event_date stuck at
   the report's start-of-month."
  {:marketplace       :ozon
   :rrd-id            42
   :date-from         "2026-04-01"
   :date-to           "2026-04-30"
   :event-date        "2026-04-01"
   :article           "ART-A"
   :nm-id             1001
   :operation         "sale"
   :operation-kind    :sale
   :operation-subtype "realization"
   :quantity          10
   :retail-amount     1000.0
   :for-pay           800.0
   :mp-commission     200.0})

(def ^:private non-realization-row
  "A transaction-list service row — should pass through untouched."
  {:marketplace       :ozon
   :rrd-id            99
   :date-from         "2026-04-01"
   :date-to           "2026-04-30"
   :event-date        "2026-04-15"
   :article           "ART-A"
   :nm-id             1001
   :operation-subtype "service"
   :delivery-cost     50.0})

(defn- with-sales-rows [rows f]
  (with-redefs [db/query (fn [_] rows)]
    (f)))

(deftest realization-row-spreads-by-sales-weights
  (testing "Two sale-days with rev 600/400 → row splits 60/40"
    (with-sales-rows
      [{:day "2026-04-05" :rev 600.0}
       {:day "2026-04-20" :rev 400.0}]
      #(let [out (od/redistribute-realization [realization-row])]
         (is (= 2 (count out)) "one child per sale-day")
         (let [by-day (into {} (map (juxt :event-date identity) out))
               d1    (by-day "2026-04-05")
               d2    (by-day "2026-04-20")]
           (is (some? d1))
           (is (some? d2))
           (is (< (Math/abs (- 600.0 (:retail-amount d1))) 0.01))
           (is (< (Math/abs (- 400.0 (:retail-amount d2))) 0.01))
           (is (< (Math/abs (- 480.0 (:for-pay d1))) 0.01))
           (is (< (Math/abs (- 320.0 (:for-pay d2))) 0.01)))))))

(deftest sums-preserved-exactly
  (testing "Σ over children = original row, regardless of weights"
    (with-sales-rows
      [{:day "2026-04-03" :rev 333.0}
       {:day "2026-04-11" :rev 333.0}
       {:day "2026-04-25" :rev 334.0}]
      #(let [out (od/redistribute-realization [realization-row])]
         (is (< (Math/abs (- 1000.0 (reduce + (map :retail-amount out)))) 0.01))
         (is (< (Math/abs (- 800.0  (reduce + (map :for-pay out)))) 0.01))
         (is (< (Math/abs (- 200.0  (reduce + (map :mp-commission out)))) 0.01))
         (is (< (Math/abs (- 10.0   (reduce + (map :quantity out)))) 0.01))))))

(deftest no-sales-coverage-keeps-original
  (testing "If sales table has no rows for the SKU, the realization row
            passes through unchanged (degraded behaviour, not a crash)"
    (with-sales-rows []
      #(let [out (od/redistribute-realization [realization-row])]
         (is (= [realization-row] out))))))

(deftest unique-rrd-ids
  (testing "Each daily child gets a distinct rrd_id so PK collisions
            never happen on round-trip through the DB"
    (with-sales-rows
      [{:day "2026-04-05" :rev 1.0}
       {:day "2026-04-06" :rev 1.0}
       {:day "2026-04-07" :rev 1.0}]
      #(let [out (od/redistribute-realization [realization-row])
             ids (mapv :rrd-id out)]
         (is (= 3 (count (distinct ids))))
         (is (every? integer? ids))))))

(deftest non-realization-rows-pass-through
  (testing "transaction-list service rows are NOT spread"
    (with-sales-rows
      [{:day "2026-04-05" :rev 1.0}]
      #(let [out (od/redistribute-realization [non-realization-row])]
         (is (= [non-realization-row] out))))))

(deftest non-ozon-rows-pass-through
  (testing "WB / YM rows untouched even if marked subtype=realization"
    (with-sales-rows
      [{:day "2026-04-05" :rev 1.0}]
      #(let [wb-row (assoc realization-row :marketplace :wb)
             ym-row (assoc realization-row :marketplace :ym)
             out    (od/redistribute-realization [wb-row ym-row])]
         (is (= [wb-row ym-row] out))))))

(deftest mixed-input-only-realization-spread
  (testing "Realization rows spread; non-realization neighbours preserved"
    (with-sales-rows
      [{:day "2026-04-05" :rev 1.0}
       {:day "2026-04-06" :rev 1.0}]
      (fn []
        (let [out (od/redistribute-realization
                    [realization-row non-realization-row])]
          ;; 2 children + 1 untouched service row
          (is (= 3 (count out)))
          (is (some (fn [r] (= 99 (:rrd-id r))) out)))))))
