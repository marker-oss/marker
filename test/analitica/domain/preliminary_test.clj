(ns analitica.domain.preliminary-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.db :as db]
            [analitica.domain.preliminary :as p]))

(def ^:private apr-period {:from "2026-04-01" :to "2026-04-30"})

(def ^:private apr-cf-rows
  [{:period-begin "2026-04-01" :period-end "2026-04-05"
    :orders-amount 146939.0 :returns-amount -45282.0 :invoice-transfer 27253.0}
   {:period-begin "2026-04-06" :period-end "2026-04-12"
    :orders-amount 114328.0 :returns-amount -21539.0 :invoice-transfer 35687.0}
   {:period-begin "2026-04-13" :period-end "2026-04-19"
    :orders-amount 107479.0 :returns-amount -20212.0 :invoice-transfer 22317.0}
   {:period-begin "2026-04-20" :period-end "2026-04-26"
    :orders-amount  70385.0 :returns-amount -11515.0 :invoice-transfer 21010.0}])

;; ---------------------------------------------------------------------------
;; ozon-preliminary-totals
;; ---------------------------------------------------------------------------

(deftest empty-when-no-cashflow-rows
  (with-redefs [db/query (constantly [])]
    (is (nil? (p/ozon-preliminary-totals apr-period)))))

(deftest swallows-db-error-and-returns-nil
  (testing "Missing table or other DB exception → nil (graceful degradation)"
    (with-redefs [db/query (fn [_] (throw (RuntimeException. "no such table")))]
      (is (nil? (p/ozon-preliminary-totals apr-period))))))

(deftest sums-orders-and-returns-into-revenue
  (testing "Apr 2026 Ozon production sample produces 340,583₽ preliminary revenue"
    (with-redefs [db/query (constantly apr-cf-rows)]
      (let [r (p/ozon-preliminary-totals apr-period)]
        (is (= 4 (:periods-count r)))
        (is (= 4 (:settled-periods r)))
        (is (= 0 (:pending-periods r)))
        (is (= "2026-04-26" (:as-of r)))
        (is (== 340583.0 (Math/round ^double (:revenue r)))
            "orders 439,131 + returns -98,548 = 340,583")
        (is (== 439131.0 (Math/round ^double (:gross-orders r))))
        (is (== -98548.0 (Math/round ^double (:gross-returns r))))))))

(deftest counts-pending-and-settled-periods
  (testing "Periods with invoice_transfer = 0 are flagged as pending"
    (let [mixed (concat apr-cf-rows
                        [{:period-begin "2026-04-27" :period-end "2026-04-30"
                          :orders-amount 50000.0 :returns-amount -10000.0
                          :invoice-transfer 0.0}])]
      (with-redefs [db/query (constantly mixed)]
        (let [r (p/ozon-preliminary-totals apr-period)]
          (is (= 5 (:periods-count r)))
          (is (= 4 (:settled-periods r)))
          (is (= 1 (:pending-periods r))))))))

(deftest handles-missing-fields-gracefully
  (testing "nil orders_amount / returns_amount are treated as 0"
    (with-redefs [db/query (constantly [{:period-begin "2026-04-01"
                                          :period-end   "2026-04-05"
                                          :orders-amount nil
                                          :returns-amount nil
                                          :invoice-transfer nil}])]
      (let [r (p/ozon-preliminary-totals apr-period)]
        (is (zero? (:revenue r)))
        (is (= 1 (:pending-periods r)))))))

;; ---------------------------------------------------------------------------
;; maybe-overlay-preliminary
;; ---------------------------------------------------------------------------

(deftest overlay-applies-when-revenue-zero-and-ozon
  (testing "0₽ Ozon P&L gets preliminary revenue overlaid"
    (with-redefs [db/query (constantly apr-cf-rows)]
      (let [r (p/maybe-overlay-preliminary
                {:revenue 0 :for-pay 0 :sales-qty 0}
                {:period apr-period :marketplace :ozon})]
        (is (= :preliminary (:revenue-source r)))
        (is (true? (:preliminary? r)))
        (is (= "2026-04-26" (:preliminary-as-of r)))
        (is (== 340583.0 (Math/round ^double (:revenue r))))
        (is (= 0 (:for-pay r)) "other fields untouched")))))

(deftest overlay-no-op-when-revenue-already-positive
  (testing "Realization-derived revenue is preserved (never override published data)"
    (let [calls (atom 0)]
      (with-redefs [db/query (fn [_] (swap! calls inc) [])]
        (let [r (p/maybe-overlay-preliminary
                  {:revenue 281835.0 :for-pay 345646.0}
                  {:period apr-period :marketplace :ozon})]
          (is (== 281835.0 (:revenue r)))
          (is (nil? (:revenue-source r)))
          (is (nil? (:preliminary? r)))
          (is (zero? @calls) "DB not even queried — short-circuit before lookup"))))))

(deftest overlay-skips-non-ozon-marketplaces
  (testing "WB and YM don't trigger fallback (their realization is realtime)"
    (with-redefs [db/query (constantly apr-cf-rows)]
      (doseq [mp [:wb :ym :all nil]]
        (let [r (p/maybe-overlay-preliminary
                  {:revenue 0 :for-pay 0}
                  {:period apr-period :marketplace mp})]
          (is (zero? (:revenue r)))
          (is (nil? (:preliminary? r))))))))

(deftest overlay-skips-when-period-missing-or-invalid
  (with-redefs [db/query (constantly apr-cf-rows)]
    (doseq [period [nil :last-30-days {:from "2026-04-01"} {}]]
      (let [r (p/maybe-overlay-preliminary
                {:revenue 0}
                {:period period :marketplace :ozon})]
        (is (zero? (:revenue r)))
        (is (nil? (:preliminary? r)))))))

(deftest overlay-no-op-when-no-cashflow-data
  (testing "If cash-flow has no data either, leave revenue at 0 (don't fabricate)"
    (with-redefs [db/query (constantly [])]
      (let [r (p/maybe-overlay-preliminary
                {:revenue 0}
                {:period apr-period :marketplace :ozon})]
        (is (zero? (:revenue r)))
        (is (nil? (:preliminary? r)))))))
