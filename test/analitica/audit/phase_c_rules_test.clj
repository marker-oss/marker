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
;; :ym-buyer-price-anomaly (E-5)
;; ---------------------------------------------------------------------------

(def ^:private ym-ctx
  (assoc base-ctx :ctx/marketplace :ym))

(deftest ym-anomaly-no-op-for-non-ym
  (testing "Rule is no-op for :wb / :ozon scope"
    (with-redefs [db/query (fn [_] (throw (ex-info "should not be called" {})))]
      (is (empty? (ri/ym-buyer-price-anomaly (assoc base-ctx :ctx/marketplace :wb))))
      (is (empty? (ri/ym-buyer-price-anomaly (assoc base-ctx :ctx/marketplace :ozon)))))))

(deftest ym-anomaly-empty-when-no-rows
  (testing "No YM rows → no discrepancy"
    (with-redefs [db/query (constantly [])]
      (is (empty? (ri/ym-buyer-price-anomaly ym-ctx))))))

(deftest ym-anomaly-clean-rows-emit-nothing
  (testing "for_pay within 5× retail_amount → no discrepancy"
    (with-redefs [db/query (constantly
                             [{:marketplace "ym" :operation-kind "sale"
                               :for-pay 1500 :retail-amount 1000 :rrd-id 1}
                              {:marketplace "ym" :operation-kind "sale"
                               :for-pay 4500 :retail-amount 1000 :rrd-id 2}])]
      (is (empty? (ri/ym-buyer-price-anomaly ym-ctx))
          "1.5× and 4.5× both pass cap=5"))))

(deftest ym-anomaly-flags-extreme-buyer-price
  (testing "for_pay > 5 × retail_amount → :suspicious with outliers"
    (with-redefs [db/query (constantly
                             [{:marketplace "ym" :operation-kind "sale"
                               :for-pay 1700 :retail-amount 1 :rrd-id 99
                               :article "A" :event-date "2026-03-15"
                               :mp-commission 200 :delivery-cost 100}])]
      (let [discs (ri/ym-buyer-price-anomaly ym-ctx)]
        (is (= 1 (count discs)))
        (is (= :suspicious (:disc/classification (first discs))))
        (is (= :ym (:disc/marketplace (first discs))))
        (is (= 1 (-> discs first :disc/delta :abs))
            "outlier count goes into :delta.abs")
        (is (= 1 (-> discs first :disc/evidence :total)))))))

(deftest ym-anomaly-ignores-returns
  (testing "Only sale operation-kind is checked"
    (with-redefs [db/query (constantly
                             [{:marketplace "ym" :operation-kind "return"
                               :for-pay 1700 :retail-amount 1 :rrd-id 1}])]
      (is (empty? (ri/ym-buyer-price-anomaly ym-ctx))))))

(deftest ym-anomaly-skips-zero-retail
  (testing "retail_amount = 0 is not flagged (handled by other rules)"
    (with-redefs [db/query (constantly
                             [{:marketplace "ym" :operation-kind "sale"
                               :for-pay 1000 :retail-amount 0 :rrd-id 1}])]
      (is (empty? (ri/ym-buyer-price-anomaly ym-ctx))))))

(deftest ym-anomaly-filters-non-ym-rows-from-all-scope
  (testing "Under :all scope, rule still only inspects rows where marketplace = ym"
    (with-redefs [db/query (constantly
                             [{:marketplace "wb" :operation-kind "sale"
                               :for-pay 5000 :retail-amount 1 :rrd-id 1}
                              {:marketplace "ym" :operation-kind "sale"
                               :for-pay 1700 :retail-amount 1 :rrd-id 2}])]
      (let [discs (ri/ym-buyer-price-anomaly (assoc base-ctx :ctx/marketplace :all))]
        (is (= 1 (count discs))
            "WB row is filtered out before the cap check")
        (is (= 1 (-> discs first :disc/evidence :total)))))))

;; ---------------------------------------------------------------------------
;; :l2-cross-report-agreement (L2-C)
;;
;; This rule cross-checks Finance/UE/P&L outputs computed from the SAME
;; finance rows. We feed it identical fixture rows and assert the three
;; reports agree on strict metrics. The rule reaches into domain code
;; (finance/totals, ue/calculate, pnl/calculate) so we don't stub
;; deeper than db/query — that's the whole point of the rule.
;; ---------------------------------------------------------------------------

(def ^:private clean-sale-row
  "A minimally-valid finance fixture row that all three reports
   (Finance/UE/P&L) can ingest without divergence."
  {:rrd-id 1 :marketplace "wb" :article "ART-1"
   :operation-kind "sale" :operation "sale"
   :event-date "2026-03-15" :date-from "2026-03-15" :date-to "2026-03-15"
   :retail-amount 1000.0 :retail-amount-without-discount 1000.0
   :for-pay 800.0 :quantity 1 :sale-id "S1"
   :mp-commission 100.0 :delivery-cost 50.0 :acquiring-fee 10.0
   :storage-fee 0.0 :acceptance 0.0 :penalty 0.0 :deduction 0.0 :additional 0.0
   :ad-cost 0.0 :spp-prc 0.0
   :nm-id "1001" :brand "B" :subject "S"})

(deftest l2-cross-report-clean-fixture-no-strict-drift
  (testing "Identical input → no drift on strict metrics (revenue, qty, etc.)"
    (with-redefs [db/query (constantly [clean-sale-row])]
      (let [discs (ri/l2-cross-report-agreement
                    (assoc base-ctx :ctx/marketplace :wb))
            strict-suspicious
            (->> discs
                 (filter #(= :suspicious (:disc/classification %)))
                 (filter #(#{"revenue" "sales-qty" "returns-qty"
                             "for-pay" "acquiring" "acceptance"}
                            (-> % :disc/location :field))))]
        (is (empty? strict-suspicious)
            "strict metrics must agree across Finance/UE/P&L on the same rows")))))

(deftest l2-cross-report-account-level-drift-classified-expected
  (testing "Account-level row (article=\"\") is dropped by UE → drift flagged :expected"
    (let [account-row (assoc clean-sale-row
                             :rrd-id 2 :article "" :sale-id "S-acct"
                             :for-pay 0.0 :retail-amount 0.0
                             :storage-fee 110000.0 :quantity 0)]
      (with-redefs [db/query (constantly [clean-sale-row account-row])]
        (let [discs (ri/l2-cross-report-agreement
                      (assoc base-ctx :ctx/marketplace :wb))
              storage-discs
              (filter #(= "storage" (-> % :disc/location :field)) discs)]
          (is (every? #(= :expected (:disc/classification %)) storage-discs)
              "storage drift is design-by-construction, not :suspicious"))))))

;; ---------------------------------------------------------------------------
;; Registration smoke
;; ---------------------------------------------------------------------------

(deftest phase-c-rules-registered
  (testing "register-all! registers Phase C rules"
    (r/clear-registry!)
    (ri/register-all!)
    (is (some? (r/get-rule :ozon-finance-vs-cashflow)))
    (is (some? (r/get-rule :finance-row-internal-consistency)))
    (is (some? (r/get-rule :wb-finance-vs-sales-events)))
    (is (some? (r/get-rule :ym-buyer-price-anomaly)))
    (is (some? (r/get-rule :l2-cross-report-agreement)))))

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
                  (r/rules-for-marketplace :ozon)))
    (is (some #(= :ym-buyer-price-anomaly (:rule/id %))
              (r/rules-for-marketplace :ym)))
    (is (not-any? #(= :ym-buyer-price-anomaly (:rule/id %))
                  (r/rules-for-marketplace :wb)))))

(deftest phase-c-rules-tolerance-overrides
  (testing "Per-rule tolerance overrides land in the registry"
    (r/clear-registry!)
    (ri/register-all!)
    (is (= {:abs 1000.0 :rel 0.5}
           (:rule/tolerance (r/get-rule :aggregate-vs-raw))))
    (is (= {:abs 100.0 :rel 0.1}
           (:rule/tolerance (r/get-rule :wb-finance-vs-sales-events))))
    (is (= {:abs 1.0 :rel 0.001}
           (:rule/tolerance (r/get-rule :l2-cross-report-agreement))))))
