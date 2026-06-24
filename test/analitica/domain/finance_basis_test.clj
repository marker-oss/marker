(ns analitica.domain.finance-basis-test
  "P0-A Part A (specs/010) — date-basis composition of finance metrics.
   A high :flat fraction means a sub-period slice is largely guess-distributed
   (Ozon monthly realization split evenly), so the number is an estimate."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.finance :as finance]))

(deftest date-basis-split-fractions
  (testing "fractions of |for-pay| by event_date_source"
    (let [rows [{:event-date-source "api"    :for-pay 50.0}
                {:event-date-source "spread" :for-pay 30.0}
                {:event-date-source "flat"   :for-pay 20.0}]
          s    (finance/date-basis-split rows)]
      (is (== 0.5 (:api s)))
      (is (== 0.3 (:spread s)))
      (is (== 0.2 (:flat s))))))

(deftest date-basis-split-uses-absolute-value
  (testing "returns (refund) rows count by magnitude, not sign"
    (let [s (finance/date-basis-split [{:event-date-source "flat" :for-pay 80.0}
                                       {:event-date-source "flat" :for-pay -20.0}
                                       {:event-date-source "api"  :for-pay 100.0}])]
      ;; |flat| = 100, |api| = 100, total 200 → 0.5 / 0.5
      (is (== 0.5 (:flat s)))
      (is (== 0.5 (:api s))))))

(deftest date-basis-split-empty
  (is (= {:api 0.0 :spread 0.0 :flat 0.0} (finance/date-basis-split []))))

(deftest date-basis-split-nil-source-is-real
  (testing "missing source (WB/YM api rows) counts as real, not synthetic"
    (let [s (finance/date-basis-split [{:for-pay 10.0}])]
      (is (== 1.0 (:api s)))
      (is (== 0.0 (:flat s))))))
