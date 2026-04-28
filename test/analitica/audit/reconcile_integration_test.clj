(ns analitica.audit.reconcile-integration-test
  "End-to-end integration test for audit.core/run-reconcile! (T016).

   Uses the per-test isolated SQLite DB to populate finance/sales/orders,
   runs the full reconcile pipeline, and asserts:
     - report.rules-applied contains every registered rule (after registration)
     - report.report-id is deterministic across runs
     - counts and exit-code match contract (0 = clean, 1 = suspicious, 2 = unclassified)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.audit.test-helpers :as th]
            [analitica.audit.rules :as r]
            [analitica.audit.rule-impl :as impl]
            [analitica.audit.core :as core]))

(defn- with-rules-registered [f]
  (r/clear-registry!)
  (impl/register-all!)
  (try (f) (finally (r/clear-registry!))))

(use-fixtures :each th/with-isolated-db with-rules-registered)

(def ^:private period {:from "2026-03-01" :to "2026-03-31"})
(def ^:private default-t {:rel 0.01 :abs 10.0})

;; ---------------------------------------------------------------------------
;; Clean scenario: no suspicious, no unclassified
;; ---------------------------------------------------------------------------

(deftest run-reconcile-clean-returns-exit-0
  ;; Phase C rule :wb-finance-vs-sales-events also reconciles
  ;; SUM(finance.for_pay sale) ⟷ SUM(sales.for_pay sale-events). Match the
  ;; values so the clean fixture stays clean across all rules.
  (th/insert-finance!
    [(th/finance-row :operation "sale" :article "A" :for-pay 850.0)])
  (th/insert-sales!
    [(th/sales-row :article "A")])
  (th/insert-orders!
    [(th/orders-row :article "A")])
  (let [{:keys [report exit-code]} (core/run-reconcile!
                                     {:marketplace :wb
                                      :period period
                                      :tolerance default-t})]
    (is (= 0 exit-code) "clean run → exit 0")
    (is (map? report))
    (is (contains? report :report/id))
    (is (zero? (get-in report [:report/summary :counts :suspicious])))
    (is (zero? (get-in report [:report/summary :counts :unclassified])))))

;; ---------------------------------------------------------------------------
;; Suspicious → exit 1
;; ---------------------------------------------------------------------------

(deftest run-reconcile-with-suspicious-returns-exit-1
  (th/insert-finance!
    ;; E-3 (2026-04-28): :aggregate-vs-raw now uses per-rule tolerance
    ;; {:abs 1000 :rel 0.5}; need a much larger logistics drag to trip
    ;; (rel 50% on a 10000₽ base = 5000₽ logistics).
    [(th/finance-row :operation "sale"      :article "A" :for-pay 10000.0)
     (th/finance-row :operation "logistics" :article nil :for-pay -8000.0)])
  (let [{:keys [exit-code]} (core/run-reconcile!
                              {:marketplace :wb
                               :period period
                               :tolerance default-t})]
    (is (= 1 exit-code)
        "8k₽ gap on 10k base = 80% > rule's 50% rel tolerance → suspicious → exit 1")))

;; ---------------------------------------------------------------------------
;; Unclassified → exit 2 (takes precedence over suspicious)
;; ---------------------------------------------------------------------------

(deftest run-reconcile-with-unclassified-returns-exit-2
  (th/insert-finance!
    [(th/finance-row :operation "совершенно новый тип" :article "A" :for-pay 1000.0)])
  (let [{:keys [exit-code]} (core/run-reconcile!
                              {:marketplace :wb
                               :period period
                               :tolerance default-t})]
    (is (= 2 exit-code)
        "unknown operation → :unclassified → exit 2 (higher priority than :suspicious)")))

;; ---------------------------------------------------------------------------
;; report-id determinism
;; ---------------------------------------------------------------------------

(deftest report-id-is-stable-across-runs
  (th/insert-finance!
    [(th/finance-row :operation "sale" :article "A" :for-pay 1000.0)])
  (let [r1 (:report (core/run-reconcile! {:marketplace :wb :period period :tolerance default-t}))
        r2 (:report (core/run-reconcile! {:marketplace :wb :period period :tolerance default-t}))]
    (is (= (:report/id r1) (:report/id r2))
        "Same inputs + config → same report-id (SC-006)")))

;; ---------------------------------------------------------------------------
;; Rules applied list
;; ---------------------------------------------------------------------------

(deftest run-reconcile-applies-registered-rules
  (let [{:keys [report]} (core/run-reconcile! {:marketplace :wb
                                               :period period
                                               :tolerance default-t})]
    (is (seq (:report/rules-applied report)))
    (is (contains? (set (:report/rules-applied report)) :aggregate-vs-raw))))

;; ---------------------------------------------------------------------------
;; Filtering via --rules option
;; ---------------------------------------------------------------------------

(deftest run-reconcile-with-rules-subset-only-runs-those
  (th/insert-finance!
    [(th/finance-row :operation "sale" :article "A" :for-pay 1000.0)])
  (let [{:keys [report]} (core/run-reconcile! {:marketplace :wb
                                               :period period
                                               :tolerance default-t
                                               :rules [:aggregate-vs-raw]})]
    (is (= [:aggregate-vs-raw] (:report/rules-applied report)))))

;; ---------------------------------------------------------------------------
;; Empty period edge case (T027)
;; ---------------------------------------------------------------------------

(deftest run-reconcile-empty-period-returns-exit-0
  ;; No data inserted — should not crash, and returns a clean empty report.
  (let [{:keys [report exit-code]} (core/run-reconcile! {:marketplace :wb
                                                         :period period
                                                         :tolerance default-t})]
    (is (= 0 exit-code)
        "empty period is not an error — exit 0 with empty report")
    (is (map? report))
    (is (= #{} (set (:report/sources-available report))))))

;; ---------------------------------------------------------------------------
;; Bank reference integration
;; ---------------------------------------------------------------------------

(deftest run-reconcile-with-bank-sum-compares-to-for-pay
  (th/insert-finance!
    [(th/finance-row :operation "sale" :article "A" :for-pay 1000.0)])
  (let [{:keys [exit-code]} (core/run-reconcile! {:marketplace :wb
                                                  :period period
                                                  :tolerance default-t
                                                  :bank-input {:sum 1000.0}})]
    (is (= 0 exit-code) "bank = raw.for_pay → no suspicious from bank-delta")))

(deftest run-reconcile-bank-diverges-flips-exit-1
  (th/insert-finance!
    [(th/finance-row :operation "sale" :article "A" :for-pay 1000.0)])
  (let [{:keys [exit-code]} (core/run-reconcile! {:marketplace :wb
                                                  :period period
                                                  :tolerance default-t
                                                  :bank-input {:sum 2000.0}})]
    (is (= 1 exit-code) "bank differs by 1000₽ from raw → suspicious → exit 1")))
