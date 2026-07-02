(ns analitica.domain.plan-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.domain.plan :as plan]
            analitica.test-helpers))

;; ---------------------------------------------------------------------------
;; US4 — Per-SKU plan targets, precedence, variance, import parser
;; ---------------------------------------------------------------------------

(use-fixtures :once analitica.test-helpers/with-test-db)

(deftest run-rate-mid-month-uses-7d-velocity
  (testing "Mid-month: forecast = MTD + last-7d-avg × days-remaining"
    (let [r (plan/run-rate
              {:actual-mtd 312000.0
               :days-elapsed 22
               :days-in-month 30
               :last-7d-actual 70000.0})]
      (is (== 392000.0 r)))))

(deftest run-rate-early-month-falls-back-to-mtd-pace
  (testing "days-elapsed < 7: ignore last-7d-actual, extrapolate from MTD"
    (let [r (plan/run-rate
              {:actual-mtd 50000.0
               :days-elapsed 5
               :days-in-month 30
               :last-7d-actual 999999.0})]
      (is (== 300000.0 r)))))

(deftest run-rate-zero-actual-yields-zero
  (let [r (plan/run-rate
            {:actual-mtd 0.0 :days-elapsed 1
             :days-in-month 30 :last-7d-actual 0.0})]
    (is (zero? r))))

(deftest run-rate-last-day-equals-actual
  (let [r (plan/run-rate
            {:actual-mtd 500000.0 :days-elapsed 30
             :days-in-month 30 :last-7d-actual 100000.0})]
    (is (== 500000.0 r))))

(deftest run-rate-boundary-days-elapsed-7
  (testing "Exactly 7 elapsed: use last-7d-actual / 7"
    (let [r (plan/run-rate
              {:actual-mtd 70000.0 :days-elapsed 7
               :days-in-month 30 :last-7d-actual 70000.0})]
      (is (== 300000.0 r)))))

(deftest pace-multiplier-on-track-equals-1
  (let [m (plan/pace-multiplier
            {:actual-mtd 312000.0 :forecast 500000.0
             :target 500000.0 :days-remaining 8})]
    (is (== 1.0 m))))

(deftest pace-multiplier-behind-greater-than-1
  (let [m (plan/pace-multiplier
            {:actual-mtd 200000.0 :forecast 400000.0
             :target 600000.0 :days-remaining 8})]
    (is (== 2.0 m))))

(deftest pace-multiplier-ahead-less-than-1
  (let [m (plan/pace-multiplier
            {:actual-mtd 200000.0 :forecast 600000.0
             :target 400000.0 :days-remaining 8})]
    (is (== 0.5 m))))

(deftest pace-multiplier-zero-velocity-target-unmet-is-infinite
  (testing "No momentum + unmet target → POSITIVE_INFINITY (cannot reach target)"
    (let [m (plan/pace-multiplier
              {:actual-mtd 200000.0 :forecast 200000.0
               :target 600000.0 :days-remaining 8})]
      (is (= Double/POSITIVE_INFINITY m)))))

(deftest pace-multiplier-zero-mtd-with-unmet-target-is-infinite
  (testing "Bug #3 regression: actual=0 / forecast=0 / target>0 must NOT report 1.0"
    (let [m (plan/pace-multiplier
              {:actual-mtd 0.0 :forecast 0.0
               :target 600000.0 :days-remaining 20})]
      (is (= Double/POSITIVE_INFINITY m)))))

(deftest pace-multiplier-target-met-returns-1
  (testing "Already met or exceeded target → 1.0 regardless of momentum"
    (let [m (plan/pace-multiplier
              {:actual-mtd 700000.0 :forecast 700000.0
               :target 600000.0 :days-remaining 5})]
      (is (== 1.0 m)))))

(deftest pace-multiplier-last-day-target-unmet-is-infinite
  (testing "days-remaining=0 with unmet target → POSITIVE_INFINITY (no time to catch up)"
    (let [m (plan/pace-multiplier
              {:actual-mtd 500000.0 :forecast 500000.0
               :target 600000.0 :days-remaining 0})]
      (is (= Double/POSITIVE_INFINITY m)))))

(deftest pace-multiplier-last-day-target-met-returns-1
  (testing "days-remaining=0 with met target → 1.0"
    (let [m (plan/pace-multiplier
              {:actual-mtd 600000.0 :forecast 600000.0
               :target 600000.0 :days-remaining 0})]
      (is (== 1.0 m)))))

;; ---------------------------------------------------------------------------
;; lookup-plan — pure: takes pre-fetched rows
;; ---------------------------------------------------------------------------

(deftest lookup-plan-returns-per-mp-when-present
  (let [rows [{:period-month "2026-05" :marketplace "wb"  :metric "revenue" :target-value 450000.0}
              {:period-month "2026-05" :marketplace "all" :metric "revenue" :target-value 999999.0}]]
    (is (= 450000.0
           (plan/lookup-plan rows {:period-month "2026-05"
                                   :marketplace :wb
                                   :metric :revenue})))))

(deftest lookup-plan-falls-back-to-all
  (let [rows [{:period-month "2026-05" :marketplace "all" :metric "revenue" :target-value 700000.0}]]
    (is (= 700000.0
           (plan/lookup-plan rows {:period-month "2026-05"
                                   :marketplace :wb
                                   :metric :revenue})))))

(deftest lookup-plan-returns-nil-when-absent
  (is (nil? (plan/lookup-plan [] {:period-month "2026-05"
                                   :marketplace :wb
                                   :metric :revenue}))))

(deftest lookup-plan-different-month-returns-nil
  (let [rows [{:period-month "2026-04" :marketplace "wb" :metric "revenue" :target-value 1.0}]]
    (is (nil? (plan/lookup-plan rows {:period-month "2026-05"
                                       :marketplace :wb
                                       :metric :revenue})))))

;; ---------------------------------------------------------------------------
;; validate-row
;; ---------------------------------------------------------------------------

(deftest validate-row-accepts-valid
  (is (nil? (plan/validate-row {:period-month "2026-05"
                                :marketplace "wb" :metric "revenue"
                                :target-value 100.0}))))

(deftest validate-row-rejects-bad-month
  (is (some? (plan/validate-row {:period-month "2026-5"
                                  :marketplace "wb" :metric "revenue"
                                  :target-value 100.0}))))

(deftest validate-row-rejects-bad-marketplace
  (is (some? (plan/validate-row {:period-month "2026-05"
                                  :marketplace "amazon" :metric "revenue"
                                  :target-value 100.0}))))

(deftest validate-row-rejects-unknown-metric
  (is (some? (plan/validate-row {:period-month "2026-05"
                                  :marketplace "wb" :metric "made_up"
                                  :target-value 100.0}))))

(deftest validate-row-rejects-non-positive-target
  (is (some? (plan/validate-row {:period-month "2026-05"
                                  :marketplace "wb" :metric "revenue"
                                  :target-value -1.0})))
  (is (some? (plan/validate-row {:period-month "2026-05"
                                  :marketplace "wb" :metric "revenue"
                                  :target-value 0.0}))))

;; ---------------------------------------------------------------------------
;; save-plan! + fetch-plans — round-trip through SQLite
;; ---------------------------------------------------------------------------

(deftest save-plan-insert-then-fetch
  (testing "Insert a row and read it back"
    (plan/clear-month! "2026-05")
    (plan/save-plan! {:period-month "2026-05"
                      :marketplace  "wb"
                      :metric       "revenue"
                      :target-value 450000.0})
    (let [rows (plan/fetch-plans "2026-05")]
      (is (= 1 (count rows)))
      (is (= 450000.0 (-> rows first :target-value))))))

(deftest save-plan-update-overwrites
  (testing "Saving same key twice updates target_value"
    (plan/clear-month! "2026-05")
    (plan/save-plan! {:period-month "2026-05" :marketplace "wb"
                      :metric "revenue" :target-value 100.0})
    (plan/save-plan! {:period-month "2026-05" :marketplace "wb"
                      :metric "revenue" :target-value 200.0})
    (let [rows (plan/fetch-plans "2026-05")]
      (is (= 1 (count rows)))
      (is (= 200.0 (-> rows first :target-value))))))

(deftest save-plan-rejects-invalid
  (is (thrown? Exception
        (plan/save-plan! {:period-month "bad"
                          :marketplace "wb" :metric "revenue"
                          :target-value 1.0}))))

(deftest delete-plan-removes-row
  (plan/clear-month! "2026-05")
  (plan/save-plan! {:period-month "2026-05" :marketplace "wb"
                    :metric "revenue" :target-value 100.0})
  (plan/delete-plan! {:period-month "2026-05"
                      :marketplace "wb" :metric "revenue"})
  (is (zero? (count (plan/fetch-plans "2026-05")))))

;; ---------------------------------------------------------------------------
;; Per-SKU round-trip
;; ---------------------------------------------------------------------------

(deftest save-plan-sku-insert-then-fetch
  (testing "Per-SKU row round-trips through SQLite"
    (plan/clear-month! "2026-06")
    (plan/save-plan! {:period-month "2026-06"
                      :marketplace  "wb"
                      :metric       "revenue"
                      :sku          "ART-001"
                      :target-value 120000.0})
    (let [rows (plan/fetch-plans "2026-06")]
      (is (= 1 (count rows)))
      (is (= "ART-001" (-> rows first :sku)))
      (is (= 120000.0  (-> rows first :target-value))))))

(deftest save-plan-sku-upsert-overwrites-same-key
  (testing "Same (period, mp, metric, sku) upserts — last write wins"
    (plan/clear-month! "2026-06")
    (plan/save-plan! {:period-month "2026-06" :marketplace "wb"
                      :metric "revenue" :sku "ART-001" :target-value 100.0})
    (plan/save-plan! {:period-month "2026-06" :marketplace "wb"
                      :metric "revenue" :sku "ART-001" :target-value 250.0})
    (let [rows (plan/fetch-plans "2026-06")]
      (is (= 1 (count rows)))
      (is (= 250.0 (-> rows first :target-value))))))

(deftest save-plan-sku-and-mp-aggregate-coexist
  (testing "Per-SKU row and MP-aggregate row (sku='') are distinct keys"
    (plan/clear-month! "2026-06")
    (plan/save-plan! {:period-month "2026-06" :marketplace "wb"
                      :metric "revenue" :target-value 500000.0})
    (plan/save-plan! {:period-month "2026-06" :marketplace "wb"
                      :metric "revenue" :sku "ART-001" :target-value 120000.0})
    (let [rows (plan/fetch-plans "2026-06")]
      (is (= 2 (count rows))))))

;; ---------------------------------------------------------------------------
;; lookup-plan-sku — most-specific precedence
;; ---------------------------------------------------------------------------

(deftest lookup-plan-sku-per-sku-beats-per-mp
  (testing "Per-SKU target (sku=ART-001) wins over per-MP aggregate (sku='')"
    (let [rows [{:period-month "2026-06" :marketplace "wb" :metric "revenue"
                 :sku "ART-001" :target-value 120000.0}
                {:period-month "2026-06" :marketplace "wb" :metric "revenue"
                 :sku "" :target-value 500000.0}]]
      (is (= 120000.0
             (plan/lookup-plan-sku rows {:period-month "2026-06"
                                         :marketplace  :wb
                                         :metric       :revenue
                                         :sku          "ART-001"}))))))

(deftest lookup-plan-sku-falls-back-to-per-mp
  (testing "When no per-SKU row exists, falls back to per-MP aggregate (sku='')"
    (let [rows [{:period-month "2026-06" :marketplace "wb" :metric "revenue"
                 :sku "" :target-value 500000.0}]]
      (is (= 500000.0
             (plan/lookup-plan-sku rows {:period-month "2026-06"
                                         :marketplace  :wb
                                         :metric       :revenue
                                         :sku          "ART-002"}))))))

(deftest lookup-plan-sku-falls-back-to-all-mp
  (testing "No per-SKU, no per-MP → falls back to all-MP aggregate"
    (let [rows [{:period-month "2026-06" :marketplace "all" :metric "revenue"
                 :sku "" :target-value 999000.0}]]
      (is (= 999000.0
             (plan/lookup-plan-sku rows {:period-month "2026-06"
                                         :marketplace  :wb
                                         :metric       :revenue
                                         :sku          "ART-001"}))))))

(deftest lookup-plan-sku-returns-nil-when-no-match
  (is (nil? (plan/lookup-plan-sku [] {:period-month "2026-06"
                                       :marketplace  :wb
                                       :metric       :revenue
                                       :sku          "ART-001"}))))

(deftest lookup-plan-sku-per-sku-per-mp-beats-per-sku-all-mp
  (testing "Full precedence: (mp, sku) > (mp, '') > (all, '')"
    (let [rows [{:period-month "2026-06" :marketplace "wb"  :metric "revenue"
                 :sku "ART-001" :target-value 120000.0}
                {:period-month "2026-06" :marketplace "wb"  :metric "revenue"
                 :sku "" :target-value 500000.0}
                {:period-month "2026-06" :marketplace "all" :metric "revenue"
                 :sku "" :target-value 999000.0}]]
      ;; most-specific wins
      (is (= 120000.0
             (plan/lookup-plan-sku rows {:period-month "2026-06"
                                         :marketplace  :wb
                                         :metric       :revenue
                                         :sku          "ART-001"})))
      ;; per-MP aggregate wins over all-MP when no per-SKU match
      (is (= 500000.0
             (plan/lookup-plan-sku rows {:period-month "2026-06"
                                         :marketplace  :wb
                                         :metric       :revenue
                                         :sku          "ART-999"}))))))

;; ---------------------------------------------------------------------------
;; variance computation
;; ---------------------------------------------------------------------------

(deftest variance-plan-nil-yields-nil-fields
  (testing "plan=nil → variance-abs and variance-pct both nil (not −100%)"
    (let [row (plan/compute-variance {:sku "ART-001" :metric :revenue
                                       :plan nil :actual 50000.0})]
      (is (nil? (:variance-abs row)))
      (is (nil? (:variance-pct row))))))

(deftest variance-plan-zero-actual-is-full-miss
  (testing "plan>0, actual=0 → variance-abs=−plan, variance-pct=−100.0"
    (let [row (plan/compute-variance {:sku "ART-001" :metric :revenue
                                       :plan 100000.0 :actual 0.0})]
      (is (= -100000.0 (:variance-abs row)))
      (is (= -100.0    (:variance-pct row))))))

(deftest variance-normal-calculation
  (testing "Normal case: actual=120000, plan=100000 → abs=+20000, pct=+20.0"
    (let [row (plan/compute-variance {:sku "ART-001" :metric :revenue
                                       :plan 100000.0 :actual 120000.0})]
      (is (= 20000.0 (:variance-abs row)))
      (is (= 20.0    (:variance-pct row))))))

(deftest variance-underperformance
  (testing "actual=80000, plan=100000 → abs=−20000, pct=−20.0"
    (let [row (plan/compute-variance {:sku "ART-001" :metric :revenue
                                       :plan 100000.0 :actual 80000.0})]
      (is (= -20000.0 (:variance-abs row)))
      (is (= -20.0    (:variance-pct row))))))

(deftest variance-plan-zero-pct-nil
  (testing "plan=0 (edge) → variance-pct=nil to avoid divide-by-zero"
    (let [row (plan/compute-variance {:sku "ART-001" :metric :revenue
                                       :plan 0.0 :actual 50000.0})]
      ;; plan=0 is unusual (import rejects non-positive), but guard is present
      (is (nil? (:variance-pct row))))))

(deftest variance-preserves-sku-and-metric
  (testing "PlanFactRow shape: :sku and :metric are passed through"
    (let [row (plan/compute-variance {:sku "ART-001" :metric :orders
                                       :plan 500.0 :actual 450.0})]
      (is (= "ART-001" (:sku row)))
      (is (= :orders   (:metric row))))))

;; ---------------------------------------------------------------------------
;; import parser (parse-import-rows)
;; ---------------------------------------------------------------------------

(def ^:private valid-skus #{"ART-001" "ART-002" "ART-003"})

(deftest import-parser-accepts-valid-rows
  (testing "Valid CSV rows are all loaded, outcome totals correct"
    (let [raw  [{:sku "ART-001" :metric "revenue"      :target-value "100000"}
                {:sku "ART-002" :metric "gross_profit" :target-value "20000"}
                {:sku "ART-003" :metric "orders"       :target-value "50"}]
          out  (plan/parse-import-rows raw {:period-month "2026-06"
                                            :marketplace  "wb"
                                            :known-skus   valid-skus})]
      (is (= 3 (:total out)))
      (is (= 3 (:loaded out)))
      (is (= 0 (:rejected out)))
      (is (empty? (:errors out))))))

(deftest import-parser-rejects-unknown-sku
  (testing "SKU not in catalogue → rejected with reason"
    (let [raw [{:sku "UNKNOWN-X" :metric "revenue" :target-value "5000"}]
          out (plan/parse-import-rows raw {:period-month "2026-06"
                                           :marketplace  "wb"
                                           :known-skus   valid-skus})]
      (is (= 1 (:rejected out)))
      (is (= 0 (:loaded out)))
      (is (= "UNKNOWN-X" (-> out :errors first :sku))))))

(deftest import-parser-rejects-unknown-metric
  (testing "Metric string not in canonical slug set → rejected"
    (let [raw [{:sku "ART-001" :metric "made_up_metric" :target-value "5000"}]
          out (plan/parse-import-rows raw {:period-month "2026-06"
                                           :marketplace  "wb"
                                           :known-skus   valid-skus})]
      (is (= 1 (:rejected out)))
      (is (some? (-> out :errors first :reason))))))

(deftest import-parser-rejects-non-positive-target
  (testing "target_value ≤ 0 → rejected"
    (let [raw-neg  [{:sku "ART-001" :metric "revenue" :target-value "-1"}]
          raw-zero [{:sku "ART-001" :metric "revenue" :target-value "0"}]
          out-neg  (plan/parse-import-rows raw-neg  {:period-month "2026-06"
                                                     :marketplace  "wb"
                                                     :known-skus   valid-skus})
          out-zero (plan/parse-import-rows raw-zero {:period-month "2026-06"
                                                     :marketplace  "wb"
                                                     :known-skus   valid-skus})]
      (is (= 1 (:rejected out-neg)))
      (is (= 1 (:rejected out-zero))))))

(deftest import-parser-rejects-non-numeric-target
  (testing "target_value not parseable as number → rejected"
    (let [raw [{:sku "ART-001" :metric "revenue" :target-value "abc"}]
          out (plan/parse-import-rows raw {:period-month "2026-06"
                                           :marketplace  "wb"
                                           :known-skus   valid-skus})]
      (is (= 1 (:rejected out))))))

(deftest import-parser-coerces-comma-decimal
  (testing "target_value with comma separator (e.g. '100,50') is parsed as 100.5"
    (let [raw [{:sku "ART-001" :metric "revenue" :target-value "100,50"}]
          out (plan/parse-import-rows raw {:period-month "2026-06"
                                           :marketplace  "wb"
                                           :known-skus   valid-skus})]
      (is (= 1 (:loaded out)))
      (is (== 100.5 (-> out :rows first :target-value))
          "parsed value should equal 100.5"))))

(deftest import-parser-coerces-interim-metric-strings
  (testing "Interim strings coerce to canonical keyword slugs"
    (let [raw [{:sku "ART-001" :metric "gross_profit"      :target-value "20000"}
               {:sku "ART-002" :metric "profit_margin_pct" :target-value "15"}
               {:sku "ART-003" :metric "ad_spend"          :target-value "5000"}]
          out (plan/parse-import-rows raw {:period-month "2026-06"
                                           :marketplace  "wb"
                                           :known-skus   valid-skus})]
      (is (= 3 (:loaded out)))
      (let [metrics (mapv :metric (:rows out))]
        (is (= :gross-margin  (nth metrics 0)))
        (is (= :margin-pct    (nth metrics 1)))
        (is (= :advertising   (nth metrics 2)))))))

(deftest import-parser-mixed-ok-bad-returns-both
  (testing "Mix of valid and invalid rows: outcome correctly splits ok/bad"
    (let [raw [{:sku "ART-001" :metric "revenue" :target-value "100000"}
               {:sku "GHOST"   :metric "revenue" :target-value "999"}
               {:sku "ART-002" :metric "orders"  :target-value "bad"}]
          out (plan/parse-import-rows raw {:period-month "2026-06"
                                           :marketplace  "wb"
                                           :known-skus   valid-skus})]
      (is (= 3 (:total out)))
      (is (= 1 (:loaded out)))
      (is (= 2 (:rejected out)))
      (is (= 2 (count (:errors out)))))))

(deftest import-parser-duplicate-last-wins
  (testing "Duplicate (period,mp,metric,sku) within file → last-wins on output rows"
    (let [raw [{:sku "ART-001" :metric "revenue" :target-value "100000"}
               {:sku "ART-001" :metric "revenue" :target-value "200000"}]
          out (plan/parse-import-rows raw {:period-month "2026-06"
                                           :marketplace  "wb"
                                           :known-skus   valid-skus})]
      (is (= 2 (:total out)))
      ;; both are "valid" — last-wins dedup happens at upsert level;
      ;; parse-import-rows reports both as loaded (dedup is DB responsibility)
      (is (= 2 (:loaded out))))))

(deftest import-parser-empty-sku-string-is-rejected
  (testing "Empty SKU string is rejected as unknown SKU"
    (let [raw [{:sku "" :metric "revenue" :target-value "50000"}]
          out (plan/parse-import-rows raw {:period-month "2026-06"
                                           :marketplace  "wb"
                                           :known-skus   valid-skus})]
      (is (= 1 (:rejected out))))))
