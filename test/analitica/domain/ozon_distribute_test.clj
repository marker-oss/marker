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

(def ^:private inert-row
  "A non-spreadable row (no marker for monthly aggregation): pure
   transaction-list contribution that already has a real per-event
   event_date. Stays untouched."
  {:marketplace       :ozon
   :rrd-id            99
   :date-from         "2026-04-01"
   :date-to           "2026-04-30"
   :event-date        "2026-04-15"
   :article           "ART-A"
   :nm-id             1001
   :operation         "tx"
   :operation-subtype "transaction"
   :delivery-cost     50.0})

(def ^:private orphan-service-row
  "Bug F shape: orphan service row written by
   materialize-ozon-orphan-services! — operation = service, sku = nil,
   event_date stuck at start-of-month."
  {:marketplace   :ozon
   :rrd-id        77
   :date-from     "2026-04-01"
   :date-to       "2026-04-30"
   :event-date    "2026-04-01"
   :article       "ART-A"
   :nm-id         nil
   :operation     "service"
   :delivery-cost 70.0
   :storage-fee   30.0})

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

(deftest no-sales-coverage-spreads-flat
  ;; D1 Phase D (was: no-sales-coverage-keeps-original).
  ;; Previously a row with zero sales/orders coverage stayed month-stamped
  ;; (event_date = date_from). That made every weekly UI slice except the
  ;; first week of the month show 0 for these articles. Now we fall back
  ;; to flat distribution so revenue at least shows up proportionally
  ;; across the period — tagged event_date_source = 'flat' so audits can
  ;; tell guess-distributed rows from real-coverage ones.
  (testing "no coverage → flat spread across all days in [date-from..date-to]"
    (with-sales-rows []
      (fn []
        (let [out  (od/redistribute-realization [realization-row])
              n    30 ;; April has 30 days
              by-d (into {} (map (juxt :event-date identity) out))]
          (testing "one child per day in the period"
            (is (= n (count out))))
          (testing "all tagged event_date_source = 'flat'"
            (is (= #{"flat"} (set (map :event-date-source out)))))
          (testing "sums preserved exactly"
            (is (< (Math/abs (- 1000.0 (reduce + (map :retail-amount out)))) 0.01))
            (is (< (Math/abs (- 800.0  (reduce + (map :for-pay out)))) 0.01))
            (is (< (Math/abs (- 10.0   (reduce + (map :quantity out)))) 0.01)))
          (testing "even distribution: each day ≈ total/N"
            (let [d (by-d "2026-04-15")]
              (is (some? d))
              (is (< (Math/abs (- (/ 1000.0 n) (:retail-amount d))) 0.01))))
          (testing "first and last day present"
            (is (contains? by-d "2026-04-01"))
            (is (contains? by-d "2026-04-30"))))))))

(deftest sales-source-tagged-spread
  ;; Sanity: real-coverage children keep the 'spread' tag (not 'flat').
  (testing "sales-weight children tagged event_date_source = 'spread'"
    (with-sales-rows
      [{:day "2026-04-05" :rev 600.0}
       {:day "2026-04-20" :rev 400.0}]
      (fn []
        (let [out (od/redistribute-realization [realization-row])]
          (is (= #{"spread"} (set (map :event-date-source out)))))))))

(deftest idempotency-skips-flat-tagged-rows
  ;; If a row was previously flat-spread and re-fed through the spreader
  ;; (e.g. rerun materialize-finance), it must NOT be re-spread.
  (testing "row tagged event_date_source = 'flat' passes through untouched"
    (with-sales-rows []
      (fn []
        (let [pre-flat (assoc realization-row
                              :event-date-source "flat"
                              :event-date "2026-04-12")
              out      (od/redistribute-realization [pre-flat])]
          (is (= [pre-flat] out)))))))

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

(deftest inert-rows-pass-through
  (testing "Rows without the month-aggregate markers are not spread"
    (with-sales-rows
      [{:day "2026-04-05" :rev 1.0}]
      #(let [out (od/redistribute-realization [inert-row])]
         (is (= [inert-row] out))))))

(deftest orphan-service-row-spreads-by-article-weights
  (testing "Orphan service row (sku=nil) uses article-only sales weights"
    (with-sales-rows
      [{:day "2026-04-05" :rev 700.0}
       {:day "2026-04-12" :rev 300.0}]
      (fn []
        (let [out (od/redistribute-realization [orphan-service-row])]
          (is (= 2 (count out)))
          (let [by-day (into {} (map (juxt :event-date identity) out))]
            (is (< (Math/abs (- 49.0 (:delivery-cost (by-day "2026-04-05")))) 0.01))
            (is (< (Math/abs (- 21.0 (:delivery-cost (by-day "2026-04-12")))) 0.01))
            (is (< (Math/abs (- 21.0 (:storage-fee   (by-day "2026-04-05")))) 0.01))
            (is (< (Math/abs (- 9.0  (:storage-fee   (by-day "2026-04-12")))) 0.01))))))))

(deftest non-ozon-rows-pass-through
  (testing "WB / YM rows untouched even if marked subtype=realization"
    (with-sales-rows
      [{:day "2026-04-05" :rev 1.0}]
      #(let [wb-row (assoc realization-row :marketplace :wb)
             ym-row (assoc realization-row :marketplace :ym)
             out    (od/redistribute-realization [wb-row ym-row])]
         (is (= [wb-row ym-row] out))))))

(deftest mixed-input-only-spreadable-spread
  (testing "Spreadable rows expand; inert neighbours preserved"
    (with-sales-rows
      [{:day "2026-04-05" :rev 1.0}
       {:day "2026-04-06" :rev 1.0}]
      (fn []
        (let [out (od/redistribute-realization
                    [realization-row inert-row])]
          ;; 2 realization children + 1 untouched inert row
          (is (= 3 (count out)))
          (is (some (fn [r] (= 99 (:rrd-id r))) out)))))))
