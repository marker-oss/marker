(ns analitica.audit.bank-delta-test
  "Tests for the :bank-delta rule (T014 / T021).

   Semantics: compares external bank reference sum against SUM(finance.for_pay)
   over the audit period. When :ctx/bank-data is nil, the rule returns an empty
   seq (nothing to compare) — the orchestrator handles marking :bank-input as
   absent from :sources-available."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.audit.test-helpers :as th]
            [analitica.audit.rules :as r]
            [analitica.audit.rule-impl :as impl]))

(use-fixtures :each th/with-isolated-db)

(def ^:private period {:from "2026-03-01" :to "2026-03-31"})
(def ^:private default-t {:rel 0.01 :abs 10.0})

(deftest bank-sum-matches-produces-expected
  (th/insert-finance!
    [(th/finance-row :operation "sale" :article "A" :for-pay 500.0)
     (th/finance-row :operation "sale" :article "A" :for-pay 500.0)])
  (let [ctx (r/make-context {:period period
                             :marketplace :wb
                             :tolerance default-t
                             :bank-data {:sum 1000.0}})
        [d] (impl/bank-delta ctx)]
    (is (some? d) "bank-delta rule always emits a discrepancy when bank-data is present")
    (is (= :bank-delta (:disc/rule-id d)))
    (is (= :expected (:disc/classification d))
        "1000 = 1000 → :expected (zero delta well within 10₽ tolerance)")
    (is (= 0.0 (get-in d [:disc/delta :abs])))))

(deftest bank-sum-diverges-by-1000-is-suspicious
  (th/insert-finance!
    [(th/finance-row :operation "sale" :article "A" :for-pay 500.0)])
  (let [ctx (r/make-context {:period period
                             :marketplace :wb
                             :tolerance default-t
                             :bank-data {:sum 1500.0}})  ; 1000₽ gap
        [d] (impl/bank-delta ctx)]
    (is (= :suspicious (:disc/classification d)))
    (is (= 1000.0 (get-in d [:disc/delta :abs])))
    (is (= :rub (get-in d [:disc/delta :unit])))
    (is (= :raw-finance (get-in d [:disc/location :source-a])))
    (is (= :bank-input (get-in d [:disc/location :source-b])))))

(deftest bank-data-nil-produces-no-discrepancy
  (th/insert-finance!
    [(th/finance-row :operation "sale" :article "A" :for-pay 500.0)])
  (let [ctx (r/make-context {:period period
                             :marketplace :wb
                             :tolerance default-t
                             :bank-data nil})
        result (impl/bank-delta ctx)]
    (is (= [] result)
        "No bank reference → rule skipped, no discrepancy")))

(deftest bank-sum-within-tolerance-classified-expected
  (th/insert-finance!
    [(th/finance-row :operation "sale" :article "A" :for-pay 1000.0)])
  (let [ctx (r/make-context {:period period
                             :marketplace :wb
                             :tolerance default-t
                             :bank-data {:sum 1005.0}})  ; 5₽ gap
        [d] (impl/bank-delta ctx)]
    (is (= :expected (:disc/classification d))
        "5₽ < 10₽ abs tolerance → :expected")))

(deftest register-all-includes-bank-delta
  (r/clear-registry!)
  (impl/register-all!)
  (is (some? (r/get-rule :bank-delta)))
  (r/clear-registry!))
