(ns analitica.audit.aggregate-vs-raw-test
  "Tests for the :aggregate-vs-raw reconciliation rule."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.audit.test-helpers :as th]
            [analitica.audit.rules :as r]
            [analitica.audit.rule-impl :as impl]))

(use-fixtures :each th/with-isolated-db)

(def ^:private period {:from "2026-03-01" :to "2026-03-31"})
(def ^:private default-t {:rel 0.01 :abs 10.0})
(def ^:private wb-ctx (r/make-context {:period period :marketplace :wb :tolerance default-t}))

;; ---------------------------------------------------------------------------
;; Zero-delta case: only sales + returns, nothing else
;; ---------------------------------------------------------------------------

(deftest only-sales-and-returns-produce-no-discrepancy
  (th/insert-finance!
    [(th/finance-row :operation "sale"   :article "A" :for-pay 850.0)
     (th/finance-row :operation "sale"   :article "A" :for-pay 850.0)
     (th/finance-row :operation "return" :article "A" :for-pay -850.0)])
  (let [result (impl/aggregate-vs-raw wb-ctx)]
    ;; raw-sum = 850 + 850 - 850 = 850
    ;; agg-sum (from by-article) = (sales 850+850) - abs(return -850) = 1700 - 850 = 850
    ;; delta = 0 → no discrepancy
    (is (= [] result) (str "Expected empty, got: " (pr-str result)))))

;; ---------------------------------------------------------------------------
;; Non-sale/return operations drive delta (the B-002 scenario)
;; ---------------------------------------------------------------------------

(deftest logistics-only-rows-are-dropped-by-aggregation
  (th/insert-finance!
    [(th/finance-row :operation "sale"      :article "A" :for-pay 1000.0)
     (th/finance-row :operation "logistics" :article nil :for-pay -50.0)
     (th/finance-row :operation "storage"   :article nil :for-pay -30.0)])
  (let [[disc] (impl/aggregate-vs-raw wb-ctx)]
    (is (some? disc) "Non-sale operations should produce a discrepancy")
    (is (= :aggregate-vs-raw (:disc/rule-id disc)))
    (is (= :wb (:disc/marketplace disc)))
    ;; raw-sum = 1000 - 50 - 30 = 920
    ;; agg-sum = 1000 (only sales; logistics/storage filtered out)
    ;; delta-abs = 80
    (is (= 80.0 (get-in disc [:disc/delta :abs])))
    (is (= :rub (get-in disc [:disc/delta :unit])))
    (is (= :suspicious (:disc/classification disc))
        "80₽ delta well above 10₽ tolerance → suspicious")
    (is (contains? (set (map :operation (get-in disc [:disc/evidence :sample-dropped])))
                   "logistics"))))

(deftest account-level-ops-without-article-are-dropped
  ;; B-002 scenario: WB neutral payments have article=nil, operation="sale" technically
  ;; but live outside any article grouping when UE filters explicit articles.
  ;; For this rule, article=nil is NOT dropped (by-article keeps nil group).
  ;; So this test demonstrates a case where article-level filtering DOES NOT drop:
  (th/insert-finance!
    [(th/finance-row :operation "sale" :article "A"  :for-pay 1000.0)
     (th/finance-row :operation "sale" :article nil  :for-pay 500.0)])
  (let [result (impl/aggregate-vs-raw wb-ctx)]
    ;; raw-sum = 1500
    ;; agg-sum: by-article groups {nil [...], "A" [...]} → both kept → 1000 + 500 = 1500
    ;; delta = 0
    (is (= [] result)
        "by-article keeps nil-article groups at the P&L level; UE filtering is a separate rule")))

;; ---------------------------------------------------------------------------
;; Tolerance classification
;; ---------------------------------------------------------------------------

(deftest small-delta-within-tolerance-classified-expected
  (th/insert-finance!
    [(th/finance-row :operation "sale"      :article "A" :for-pay 1000.0)
     (th/finance-row :operation "logistics" :article nil :for-pay -5.0)])
  (let [[disc] (impl/aggregate-vs-raw wb-ctx)]
    (is (some? disc))
    (is (= 5.0 (get-in disc [:disc/delta :abs])))
    (is (= :expected (:disc/classification disc))
        "5₽ abs delta within 10₽ tolerance → expected (rounding/documented gap)")
    (is (re-find #"within tolerance" (:disc/classification-reason disc)))))

(deftest relative-tolerance-path
  ;; 5 rub on a 100k base is far below 1% rel threshold → :expected via rel
  (let [big-sale 100000.0]
    (th/insert-finance!
      [(th/finance-row :operation "sale"      :article "A" :for-pay big-sale)
       (th/finance-row :operation "logistics" :article nil :for-pay -50.0)])
    (let [[disc] (impl/aggregate-vs-raw wb-ctx)]
      (is (= 50.0 (get-in disc [:disc/delta :abs])))
      (is (<= 0.00049 (get-in disc [:disc/delta :rel]) 0.00051)
          "rel delta ≈ 0.0005 (50 / 100000)")
      (is (= :expected (:disc/classification disc))
          "abs 50₽ is over 10₽ but rel 0.05% is under 1% → expected (OR threshold)"))))

;; ---------------------------------------------------------------------------
;; Marketplace filter isolation
;; ---------------------------------------------------------------------------

(deftest marketplace-filter-excludes-other-mps
  (th/insert-finance!
    [(th/finance-row :operation "sale"      :article "A" :for-pay 1000.0 :marketplace "wb")
     (th/finance-row :operation "logistics" :article nil :for-pay -500.0 :marketplace "wb")
     (th/finance-row :operation "sale"      :article "B" :for-pay 3000.0 :marketplace "ozon")
     (th/finance-row :operation "logistics" :article nil :for-pay -2000.0 :marketplace "ozon")])
  (let [wb-result   (impl/aggregate-vs-raw wb-ctx)
        ozon-ctx    (r/make-context {:period period :marketplace :ozon :tolerance default-t})
        ozon-result (impl/aggregate-vs-raw ozon-ctx)]
    (is (= 500.0 (get-in (first wb-result)   [:disc/delta :abs])))
    (is (= 2000.0 (get-in (first ozon-result) [:disc/delta :abs])))))

(deftest marketplace-all-includes-everything
  (th/insert-finance!
    [(th/finance-row :operation "sale"      :article "A" :for-pay 1000.0 :marketplace "wb")
     (th/finance-row :operation "logistics" :article nil :for-pay -100.0 :marketplace "wb")
     (th/finance-row :operation "sale"      :article "B" :for-pay 2000.0 :marketplace "ozon")])
  (let [all-ctx (r/make-context {:period period :marketplace :all :tolerance default-t})
        [disc]  (impl/aggregate-vs-raw all-ctx)]
    ;; raw = 1000 + (-100) + 2000 = 2900; agg = 1000 + 2000 = 3000; delta = 100
    (is (= 100.0 (get-in disc [:disc/delta :abs])))))

;; ---------------------------------------------------------------------------
;; Empty / missing data
;; ---------------------------------------------------------------------------

(deftest empty-finance-produces-no-discrepancy
  (let [result (impl/aggregate-vs-raw wb-ctx)]
    (is (= [] result) "no data → no discrepancy, not a crash")))

;; ---------------------------------------------------------------------------
;; Evidence structure
;; ---------------------------------------------------------------------------

(deftest discrepancy-evidence-includes-counts-and-operations
  (th/insert-finance!
    [(th/finance-row :operation "sale"         :article "A" :for-pay 1000.0)
     (th/finance-row :operation "sale"         :article "B" :for-pay 2000.0)
     (th/finance-row :operation "logistics"    :article nil :for-pay -50.0)
     (th/finance-row :operation "storage"      :article nil :for-pay -30.0)
     (th/finance-row :operation "acquiring"    :article nil :for-pay -10.0)])
  (let [[disc] (impl/aggregate-vs-raw wb-ctx)
        ev     (:disc/evidence disc)]
    (is (= 5 (:raw-row-count ev)))
    (is (= 3 (:agg-row-count ev))
        "by-article groups into {nil, A, B} — nil-article row is kept at this layer")
    (is (= #{"logistics" "storage" "acquiring"}
           (set (map :operation (:sample-dropped ev)))))
    (is (= {"sale" 2 "logistics" 1 "storage" 1 "acquiring" 1}
           (:unique-operations ev)))))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(deftest register-all-puts-rule-in-registry
  (r/clear-registry!)
  (impl/register-all!)
  (let [rule (r/get-rule :aggregate-vs-raw)]
    (is (some? rule))
    (is (= :all (:rule/marketplace rule)))
    (is (= :critical (:rule/severity rule))))
  ;; Run through the public run-rule wrapper to confirm end-to-end
  (th/insert-finance!
    [(th/finance-row :operation "sale"      :article "A" :for-pay 1000.0)
     (th/finance-row :operation "logistics" :article nil :for-pay -100.0)])
  (let [rule   (r/get-rule :aggregate-vs-raw)
        result (r/run-rule rule wb-ctx)]
    (is (= 1 (count result)))
    (is (= 100.0 (get-in (first result) [:disc/delta :abs]))))
  (r/clear-registry!))
