(ns analitica.audit.phase-c-rules-test
  "Phase C reconciliation rules — exercised against in-memory fixtures.

  The rules touch the live `db/query` fn, so we use the same with-redefs
  trick the existing audit tests use: stub `analitica.db/query` to return
  fixture rows, then call the rule fn directly. This keeps the tests
  hermetic and DB-free."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.audit.rule-impl :as ri]
            [analitica.audit.rules :as r]
            [analitica.db :as db]))

(def base-ctx
  {:ctx/period      {:from "2026-03-01" :to "2026-03-31"}
   :ctx/marketplace :ozon
   :ctx/tolerance   {:abs 100.0 :rel 0.01}
   :ctx/bank-data   nil
   :ctx/sku-scope   nil
   :ctx/db          nil})

;; ---------------------------------------------------------------------------
;; :ozon-finance-vs-cashflow
;; ---------------------------------------------------------------------------

(defn- stubbed-db-query [fixture-fn]
  (fn [sql-and-params]
    (fixture-fn (first sql-and-params))))

(deftest ozon-cashflow-empty-when-no-data
  (testing "Both sources empty → no discrepancy emitted"
    (with-redefs [db/query (stubbed-db-query (constantly []))]
      (is (empty? (ri/ozon-finance-vs-cashflow base-ctx))))))

(deftest ozon-cashflow-no-discrepancy-when-equal
  (testing "Net payout matches cash-flow payment → no discrepancy"
    (with-redefs [db/query (stubbed-db-query
                             (fn [sql]
                               (cond
                                 (.contains sql "FROM finance")
                                 [{:operation-kind "sale" :operation "sale" :for-pay 1000}
                                  {:operation-kind "return" :operation "return" :for-pay 200}]
                                 (.contains sql "FROM cash_flow_periods")
                                 [{:orders-amount 1000 :returns-amount -200}]
                                 :else [])))]
      (is (empty? (ri/ozon-finance-vs-cashflow base-ctx))
          "1000 sale - 200 return = 800; cash-flow = 800; Δ = 0"))))

(deftest ozon-cashflow-flags-discrepancy-out-of-tolerance
  (testing "Net payout vs cash-flow gap larger than tolerance → :suspicious"
    (with-redefs [db/query (stubbed-db-query
                             (fn [sql]
                               (cond
                                 (.contains sql "FROM finance")
                                 [{:operation-kind "sale" :operation "sale" :for-pay 1000}]
                                 (.contains sql "FROM cash_flow_periods")
                                 [{:orders-amount 500 :returns-amount 0}]
                                 :else [])))]
      (let [discs (ri/ozon-finance-vs-cashflow base-ctx)]
        (is (= 1 (count discs)))
        (is (= :suspicious (:disc/classification (first discs))))
        (is (= 500.0 (-> discs first :disc/delta :abs)))
        (is (= :ozon (:disc/marketplace (first discs))))))))

(deftest ozon-cashflow-no-op-for-other-marketplaces
  (testing "Rule is a no-op for non-Ozon scope"
    (with-redefs [db/query (fn [_] (throw (ex-info "should not be called" {})))]
      (is (empty? (ri/ozon-finance-vs-cashflow (assoc base-ctx :ctx/marketplace :wb)))))))

(deftest ozon-cashflow-tolerance-classifies-as-expected
  (testing "Small gap inside abs tolerance → :expected"
    (with-redefs [db/query (stubbed-db-query
                             (fn [sql]
                               (cond
                                 (.contains sql "FROM finance")
                                 [{:operation-kind "sale" :operation "sale" :for-pay 10000}]
                                 (.contains sql "FROM cash_flow_periods")
                                 [{:orders-amount 9950 :returns-amount 0}]
                                 :else [])))]
      (let [discs (ri/ozon-finance-vs-cashflow base-ctx)]
        (is (= 1 (count discs)))
        (is (= :expected (:disc/classification (first discs)))
            "Δ=50₽ < tolerance 100₽")))))

;; ---------------------------------------------------------------------------
;; :finance-row-internal-consistency
;; ---------------------------------------------------------------------------

(deftest within-row-no-outliers-emits-nothing
  (testing "Reasonable rows (for_pay ≤ 2.0 × retail_amount) produce no discrepancy"
    (with-redefs [db/query (constantly
                             [{:operation-kind "sale" :operation "sale"
                               :for-pay 800 :retail-amount 1000 :rrd-id 1}
                              {:operation-kind "sale" :operation "sale"
                               :for-pay 1900 :retail-amount 1000 :rrd-id 2}])]
      (is (empty? (ri/finance-row-internal-consistency
                    (assoc base-ctx :ctx/marketplace :all)))
          "1.9× passes — typical Ozon co-investment range"))))

(deftest within-row-flags-extreme-overshoot
  (testing "for_pay > 2.0 × retail_amount → :suspicious with outliers"
    (with-redefs [db/query (constantly
                             [{:operation-kind "sale" :operation "sale"
                               :for-pay 5000 :retail-amount 1000 :rrd-id 99
                               :marketplace "wb" :article "BAD"}])]
      (let [discs (ri/finance-row-internal-consistency
                    (assoc base-ctx :ctx/marketplace :all))]
        (is (= 1 (count discs)))
        (is (= :suspicious (:disc/classification (first discs))))
        (is (= 1 (-> discs first :disc/delta :abs))
            "outlier count goes into :delta.abs")))))

(deftest within-row-ignores-returns
  (testing "Returns are not checked (formula doesn't apply)"
    (with-redefs [db/query (constantly
                             [{:operation-kind "return" :operation "return"
                               :for-pay 5000 :retail-amount 1000 :rrd-id 1}])]
      (is (empty? (ri/finance-row-internal-consistency
                    (assoc base-ctx :ctx/marketplace :all)))))))

(deftest within-row-ignores-zero-retail
  (testing "retail_amount = 0 (service rows) → not flagged (cap test bypasses)"
    (with-redefs [db/query (constantly
                             [{:operation-kind "service" :operation "service"
                               :for-pay 0 :retail-amount 0 :rrd-id 1}])]
      (is (empty? (ri/finance-row-internal-consistency
                    (assoc base-ctx :ctx/marketplace :all)))))))

;; ---------------------------------------------------------------------------
;; :wb-finance-vs-sales-events
;; ---------------------------------------------------------------------------

(deftest wb-finance-sales-no-op-for-non-wb
  (testing "Rule is no-op when scope is :ozon"
    (with-redefs [db/query (fn [_] (throw (ex-info "should not be called" {})))]
      (is (empty? (ri/wb-finance-vs-sales-events base-ctx))))))

(deftest wb-finance-sales-no-discrepancy-when-equal
  (testing "WB endpoints agree → no discrepancy"
    (with-redefs [db/query (stubbed-db-query
                             (fn [sql]
                               (cond
                                 (.contains sql "FROM finance")
                                 [{:operation-kind "sale" :operation "sale" :for-pay 5000}]
                                 (.contains sql "FROM sales")
                                 [{:type "sale" :for-pay 5000}]
                                 :else [])))]
      (is (empty? (ri/wb-finance-vs-sales-events
                    (assoc base-ctx :ctx/marketplace :wb)))))))

(deftest wb-finance-sales-flags-large-skew
  (testing "Large gap (> 1% rel + > 100₽ abs) → :suspicious"
    (with-redefs [db/query (stubbed-db-query
                             (fn [sql]
                               (cond
                                 (.contains sql "FROM finance")
                                 [{:operation-kind "sale" :operation "sale" :for-pay 10000}]
                                 (.contains sql "FROM sales")
                                 [{:type "sale" :for-pay 5000}]
                                 :else [])))]
      (let [discs (ri/wb-finance-vs-sales-events
                    (assoc base-ctx :ctx/marketplace :wb))]
        (is (= 1 (count discs)))
        (is (= :suspicious (:disc/classification (first discs))))))))

;; ---------------------------------------------------------------------------
;; Registration smoke
;; ---------------------------------------------------------------------------

(deftest phase-c-rules-registered
  (testing "register-all! registers Phase C rules"
    (r/clear-registry!)
    (ri/register-all!)
    (is (some? (r/get-rule :ozon-finance-vs-cashflow)))
    (is (some? (r/get-rule :finance-row-internal-consistency)))
    (is (some? (r/get-rule :wb-finance-vs-sales-events)))))

(deftest phase-c-rules-marketplace-scoping
  (testing "Marketplace scope is honoured"
    (r/clear-registry!)
    (ri/register-all!)
    (is (some #(= :ozon-finance-vs-cashflow (:rule/id %))
              (r/rules-for-marketplace :ozon)))
    (is (not-any? #(= :ozon-finance-vs-cashflow (:rule/id %))
                  (r/rules-for-marketplace :wb)))
    (is (some #(= :wb-finance-vs-sales-events (:rule/id %))
              (r/rules-for-marketplace :wb)))
    (is (not-any? #(= :wb-finance-vs-sales-events (:rule/id %))
                  (r/rules-for-marketplace :ozon)))))
