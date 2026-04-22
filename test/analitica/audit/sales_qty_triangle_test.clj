(ns analitica.audit.sales-qty-triangle-test
  "Tests for the :sales-qty-triangle reconciliation rule (T012 / T019).

   Semantics: compare sales qty per article across three sources:
     - finance : SUM(quantity) WHERE operation IN {sale, Продажа}
     - sales   : COUNT(sale_id) WHERE type='S' (not a return)
     - orders  : COUNT(order_id) WHERE status looks 'sold' (we accept waiting + delivered;
                 cancelled and declined are excluded)
   A divergence on ≥ 1 article → one discrepancy per (article,source-pair) that mismatches."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.audit.test-helpers :as th]
            [analitica.audit.rules :as r]
            [analitica.audit.rule-impl :as impl]))

(use-fixtures :each th/with-isolated-db)

(def ^:private period {:from "2026-03-01" :to "2026-03-31"})
(def ^:private default-t {:rel 0.01 :abs 10.0})
(def ^:private wb-ctx (r/make-context {:period period :marketplace :wb :tolerance default-t}))

;; ---------------------------------------------------------------------------
;; Happy path — all three sources agree
;; ---------------------------------------------------------------------------

(deftest full-match-produces-no-discrepancies
  (th/insert-finance!
    [(th/finance-row :operation "sale" :article "A" :quantity 1)
     (th/finance-row :operation "sale" :article "A" :quantity 1)])
  (th/insert-sales!
    [(th/sales-row :article "A" :type "S")
     (th/sales-row :article "A" :type "S")])
  (th/insert-orders!
    [(th/orders-row :article "A" :status "waiting")
     (th/orders-row :article "A" :status "waiting")])
  (let [result (impl/sales-qty-triangle wb-ctx)]
    (is (= [] result) (str "Expected empty, got: " (pr-str result)))))

;; ---------------------------------------------------------------------------
;; Divergence: 1 article differs between sources
;; ---------------------------------------------------------------------------

(deftest one-article-off-by-one-produces-discrepancy
  (th/insert-finance!
    [(th/finance-row :operation "sale" :article "X" :quantity 1)
     (th/finance-row :operation "sale" :article "X" :quantity 1)
     (th/finance-row :operation "sale" :article "X" :quantity 1)])  ; finance=3
  (th/insert-sales!
    [(th/sales-row :article "X" :type "S")
     (th/sales-row :article "X" :type "S")])  ; sales=2, diverges by 1
  (th/insert-orders!
    [(th/orders-row :article "X" :status "waiting")
     (th/orders-row :article "X" :status "waiting")
     (th/orders-row :article "X" :status "waiting")])  ; orders=3
  (let [result (impl/sales-qty-triangle wb-ctx)
        art-X  (filter #(= "X" (get-in % [:disc/location :article])) result)]
    (is (seq art-X) "expected at least one discrepancy for article X")
    (let [d (first art-X)]
      (is (= :sales-qty-triangle (:disc/rule-id d)))
      (is (= :qty (get-in d [:disc/delta :unit])))
      (is (= "X" (get-in d [:disc/location :article])))
      (is (= 1.0 (double (get-in d [:disc/delta :abs])))
          (str "delta should be 1 (finance 3 vs sales 2), got: "
               (pr-str (:disc/delta d)))))))

;; ---------------------------------------------------------------------------
;; Source availability reporting (FR-008)
;; ---------------------------------------------------------------------------

(deftest missing-source-reported-as-absent-but-no-discrepancy
  ;; Only finance is populated — sales & orders tables empty.
  (th/insert-finance!
    [(th/finance-row :operation "sale" :article "A" :quantity 2)])
  (let [result (impl/sales-qty-triangle wb-ctx)]
    ;; FR-008: if sources are missing, rule still produces no discrepancy
    ;; (nothing to triangulate), but we note the absence via rule metadata
    ;; (the rule-level :sources-available is computed by the orchestrator;
    ;;  here we just verify rule doesn't blow up and returns empty).
    (is (= [] result)
        "empty sales+orders → no triangulation possible, no false positive")))

;; ---------------------------------------------------------------------------
;; Edge case: article appears only in one source
;; ---------------------------------------------------------------------------

(deftest article-only-in-finance-produces-diff-vs-sales-and-orders
  (th/insert-finance!
    [(th/finance-row :operation "sale" :article "FIN-ONLY" :quantity 2)])
  (th/insert-sales!
    [(th/sales-row :article "OTHER" :type "S")])
  (th/insert-orders!
    [(th/orders-row :article "OTHER" :status "waiting")])
  (let [result (impl/sales-qty-triangle wb-ctx)
        fin-only-disc (filter #(= "FIN-ONLY" (get-in % [:disc/location :article])) result)]
    (is (seq fin-only-disc)
        "finance has 2 units of FIN-ONLY, sales/orders have 0 → divergence")
    (is (every? #(= :qty (get-in % [:disc/delta :unit])) fin-only-disc))))

;; ---------------------------------------------------------------------------
;; Classification: small mismatch within tolerance
;; ---------------------------------------------------------------------------

(deftest small-qty-delta-classified-expected
  ;; abs=1 unit vs 10 abs tolerance — 1 unit delta is within tolerance → :expected.
  ;; Need at least 2 populated sources for triangulation.
  (th/insert-finance!
    [(th/finance-row :operation "sale" :article "A" :quantity 2)])
  (th/insert-sales!
    [(th/sales-row :article "A" :type "S")])
  (th/insert-orders! [])
  (let [result (impl/sales-qty-triangle wb-ctx)]
    ;; finance=2, sales=1 (orders silent since empty) → 1-unit delta vs 10 abs → :expected.
    (is (seq result) "2 sources populated + 1 unit delta → at least one discrepancy")
    (is (every? #(= :expected (:disc/classification %)) result)
        "1 unit delta < 10 tolerance → expected")))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(deftest register-all-includes-sales-qty-triangle
  (r/clear-registry!)
  (impl/register-all!)
  (let [rule (r/get-rule :sales-qty-triangle)]
    (is (some? rule) "rule should be registered")
    (is (= [:finance :sales :orders] (:rule/sources rule))))
  (r/clear-registry!))
