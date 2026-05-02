(ns analitica.domain.plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.plan :as plan]))

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
