(ns analitica.domain.plan-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.domain.plan :as plan]
            analitica.test-helpers))

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

(deftest pace-multiplier-zero-velocity-degenerate
  (testing "Forecast equals MTD (no momentum) → 1.0 to avoid div/0"
    (let [m (plan/pace-multiplier
              {:actual-mtd 200000.0 :forecast 200000.0
               :target 600000.0 :days-remaining 8})]
      (is (== 1.0 m)))))

(deftest pace-multiplier-last-day
  (testing "days-remaining 0 → 1.0 (cannot accelerate today)"
    (let [m (plan/pace-multiplier
              {:actual-mtd 500000.0 :forecast 500000.0
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
