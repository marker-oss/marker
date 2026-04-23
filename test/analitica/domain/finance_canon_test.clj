(ns analitica.domain.finance-canon-test
  "Per-metric-group tests anchored to canonical-formulas.md §Finance.

   Every deftest maps to one Finance.N block in the canon. If canon changes,
   this file changes in lockstep."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.finance :as finance]
            [analitica.util.math :as math]))

;; ---------------------------------------------------------------------------
;; Shared fixture: 3 articles on WB with known per-article aggregates.
;; Inlined from pnl_canon_test.clj to avoid cross-test coupling.
;; Article A: 5 sales × (retail=100, for-pay=80), 2 returns (for-pay=0)
;; Article B: 3 sales × (retail=50,  for-pay=42), 0 returns
;; Article C: 0 sales, 1 return (for-pay=0)
;; ---------------------------------------------------------------------------

(def fx
  "Finance rows fixture: WB, 3 articles (A/B/C), March 2026."
  (concat
    ;; Article A — sale rows (5 sales × 100)
    (for [i (range 5)]
      {:marketplace :wb :rrd-id (+ 100 i)
       :date-from "2026-03-01" :date-to "2026-03-07"
       :event-date "2026-03-03"
       :article "A" :operation "sale" :quantity 1
       :retail-amount 100.0 :retail-price 100.0
       :for-pay 80.0 :wb-commission 15.0 :wb-reward 15.0
       :delivery-cost 1.0 :storage-fee 0.5 :acceptance 0.2
       :penalty 0.0 :acquiring-fee 2.0 :deduction 0.1
       :additional-payment 0.0 :ad-cost 0.0})
    ;; Article A — return rows (2 returns, for-pay=0 per WB convention)
    (for [i (range 2)]
      {:marketplace :wb :rrd-id (+ 200 i)
       :date-from "2026-03-01" :date-to "2026-03-07"
       :event-date "2026-03-05"
       :article "A" :operation "return" :quantity 1
       :retail-amount 0.0 :retail-price 0.0
       :for-pay 0.0 :wb-commission 0.0 :wb-reward 0.0
       :delivery-cost 0.5 :storage-fee 0.0 :acceptance 0.0
       :penalty 0.0 :acquiring-fee 0.0 :deduction 0.0
       :additional-payment 0.0 :ad-cost 0.0})
    ;; Article B — 3 sales × 50
    (for [i (range 3)]
      {:marketplace :wb :rrd-id (+ 300 i)
       :date-from "2026-03-01" :date-to "2026-03-07"
       :event-date "2026-03-04"
       :article "B" :operation "sale" :quantity 1
       :retail-amount 50.0 :retail-price 50.0
       :for-pay 42.0 :wb-commission 8.0 :wb-reward 8.0
       :delivery-cost 0.5 :storage-fee 0.2 :acceptance 0.1
       :penalty 0.0 :acquiring-fee 1.0 :deduction 0.0
       :additional-payment 0.0 :ad-cost 0.0})
    ;; Article C — only returns (1 return, for-pay=0)
    (for [i (range 1)]
      {:marketplace :wb :rrd-id (+ 400 i)
       :date-from "2026-03-01" :date-to "2026-03-07"
       :event-date "2026-03-06"
       :article "C" :operation "return" :quantity 1
       :retail-amount 0.0 :retail-price 0.0
       :for-pay 0.0 :wb-commission 0.0 :wb-reward 0.0
       :delivery-cost 0.5 :storage-fee 0.0 :acceptance 0.0
       :penalty 0.0 :acquiring-fee 0.0 :deduction 0.0
       :additional-payment 0.0 :ad-cost 0.0})))

;; Pre-compute by-article once for the fixture; sort by :article for
;; deterministic lookup in tests.
(def by-art (finance/by-article fx :sort-key :article))

(defn- find-art [article] (first (filter #(= article (:article %)) by-art)))

;; ---------------------------------------------------------------------------
;; Finance.1 — article-row per-article aggregates (pass-through SUMs)
;; ---------------------------------------------------------------------------

(deftest group-1-pass-throughs
  ;; Article A: 5 sales (retail=100, for-pay=80, wb-commission=15, wb-reward=15,
  ;;              delivery=1, storage=0.5, acceptance=0.2, acquiring=2, deduction=0.1)
  ;;          + 2 returns (delivery=0.5, wb-reward=0, wb-commission=0)
  (let [a (find-art "A")]
    (testing "revenue = 5×100 = 500"
      (is (= 500.0 (:revenue a))))
    (testing "wb-commission = 5×15 = 75 (sales-lines only)"
      (is (= 75.0 (:wb-commission a))))
    ;; wb-reward spans ALL lines: 5×15 (sales) + 2×0 (returns) = 75
    (testing "wb-reward = 5×15 + 2×0 = 75 (all lines)"
      (is (= 75.0 (:wb-reward a))))
    ;; acquiring spans ALL lines: 5×2 (sales) + 2×0 (returns) = 10
    (testing "acquiring = 5×2 = 10 (all lines)"
      (is (= 10.0 (:acquiring a))))
    (testing "sales-pay = 5×80 = 400"
      (is (= 400.0 (:sales-pay a))))
    (testing "returns-pay = 2×0 = 0"
      (is (= 0.0 (:returns-pay a))))
    ;; logistics spans ALL lines: 5×1 (sales) + 2×0.5 (returns) = 6
    (testing "logistics = 5×1 + 2×0.5 = 6"
      (is (= 6.0 (:logistics a))))
    ;; acceptance spans ALL lines: 5×0.2 + 2×0 = 1
    (testing "acceptance = 5×0.2 = 1.0"
      (is (= 1.0 (:acceptance a))))
    ;; deduction spans ALL lines: 5×0.1 + 2×0 = 0.5
    (testing "deduction = 5×0.1 = 0.5"
      (is (= 0.5 (:deduction a)))))

  ;; Article B: 3 sales (retail=50, for-pay=42, wb-commission=8, wb-reward=8,
  ;;              delivery=0.5, storage=0.2, acceptance=0.1, acquiring=1, deduction=0)
  (let [b (find-art "B")]
    (testing "B revenue = 3×50 = 150"
      (is (= 150.0 (:revenue b))))
    (testing "B wb-reward = 3×8 = 24"
      (is (= 24.0 (:wb-reward b))))
    (testing "B logistics = 3×0.5 = 1.5"
      (is (= 1.5 (:logistics b))))
    (testing "B acceptance = 3×0.1 = 0.3"
      (is (= 0.3 (:acceptance b)))))

  ;; Article C: return-only — all sale-filtered sums must be 0
  (let [c (find-art "C")]
    (testing "C revenue = 0 (no sales)"
      (is (= 0.0 (:revenue c))))
    (testing "C wb-commission = 0 (no sales)"
      (is (= 0.0 (:wb-commission c))))
    (testing "C sales-pay = 0 (no sales)"
      (is (= 0.0 (:sales-pay c))))
    ;; logistics spans ALL lines including the return row: 1×0.5 = 0.5
    (testing "C logistics = 0.5 (from return row delivery-cost)"
      (is (= 0.5 (:logistics c))))))

;; ---------------------------------------------------------------------------
;; Finance.2 — :spp-amount derivative
;; ---------------------------------------------------------------------------

(deftest group-2-spp-amount
  ;; spp-amount = sales-pay − revenue
  ;; Article A: 5×80 − 5×100 = 400 − 500 = −100
  (let [a (find-art "A")]
    (testing "A spp-amount = sales-pay − revenue = 400 − 500 = −100"
      (is (= -100.0 (:spp-amount a)))))
  ;; Article B: 3×42 − 3×50 = 126 − 150 = −24
  (let [b (find-art "B")]
    (testing "B spp-amount = 126 − 150 = −24"
      (is (= -24.0 (:spp-amount b)))))
  ;; Article C: no sales → 0 − 0 = 0
  (let [c (find-art "C")]
    (testing "C spp-amount = 0 (no sales)"
      (is (= 0.0 (:spp-amount c))))))

;; ---------------------------------------------------------------------------
;; Finance.3 — :storage coalescence
;; ---------------------------------------------------------------------------

(deftest group-3-storage
  ;; Without storage-by-article: row-level SUM of storage-fee
  ;; Article A: 5 sale rows × 0.5 + 2 return rows × 0 = 2.5
  ;; Article B: 3 sale rows × 0.2 = 0.6
  ;; Article C: 1 return row × 0 = 0.0
  (let [a (find-art "A")
        b (find-art "B")
        c (find-art "C")]
    (testing "A storage (row-level) = 5×0.5 = 2.5"
      (is (= 2.5 (:storage a))))
    (testing "B storage (row-level) = 3×0.2 = 0.6"
      (is (= 0.6 (:storage b))))
    (testing "C storage (row-level) = 0.0 (return rows have storage-fee=0)"
      (is (= 0.0 (:storage c)))))

  ;; With storage-by-article: override wins regardless of row values
  (let [sba {"A" 99.99 "B" 0.0 "C" 5.0}
        rows (finance/by-article fx :storage-by-article sba :sort-key :article)
        a    (first (filter #(= "A" (:article %)) rows))
        b    (first (filter #(= "B" (:article %)) rows))
        c    (first (filter #(= "C" (:article %)) rows))]
    (testing "A storage (coalesced) = 99.99"
      (is (= 99.99 (:storage a))))
    (testing "B storage (coalesced) = 0.0"
      (is (= 0.0 (:storage b))))
    (testing "C storage (coalesced) = 5.0"
      (is (= 5.0 (:storage c))))))

;; ---------------------------------------------------------------------------
;; Finance.4 — :for-pay net (sales − |returns|)
;; ---------------------------------------------------------------------------

(deftest group-4-for-pay
  ;; Article A: SUM(for-pay sales) − |SUM(for-pay returns)| = 5×80 − |2×0| = 400
  (testing "A for-pay = 5×80 − |0| = 400"
    (is (= 400.0 (:for-pay (find-art "A")))))
  ;; Article B: 3×42 − |0| = 126
  (testing "B for-pay = 3×42 = 126"
    (is (= 126.0 (:for-pay (find-art "B")))))
  ;; Article C: return-only, for-pay=0 on all rows → 0 − |0| = 0
  (testing "C for-pay = 0 (return-only, WB for-pay=0 on returns)"
    (is (= 0.0 (:for-pay (find-art "C"))))))

;; ---------------------------------------------------------------------------
;; Finance.5 — :cost-price and :total-cost
;; ---------------------------------------------------------------------------

(deftest group-5-cost-price
  ;; No cost_prices DB table in the in-memory fixture — cost-price/get-price
  ;; returns nil → 0.0 for all articles.
  (testing "A cost-price = 0.0 (no DB cost_prices entry)"
    (is (= 0.0 (:cost-price (find-art "A")))))
  (testing "A total-cost = 0.0"
    (is (= 0.0 (:total-cost (find-art "A")))))
  (testing "B cost-price = 0.0"
    (is (= 0.0 (:cost-price (find-art "B")))))
  (testing "C cost-price = 0.0 (no sales lines)"
    (is (= 0.0 (:cost-price (find-art "C")))))
  (testing "C total-cost = 0.0 (no sales lines)"
    (is (= 0.0 (:total-cost (find-art "C"))))))

;; ---------------------------------------------------------------------------
;; Finance.6 — empty-article-row fallback
;; ---------------------------------------------------------------------------

(deftest group-6-empty-article-row
  ;; Pass :articles including "D" which has no rows in the fixture.
  (let [rows (finance/by-article fx
                                 :articles ["A" "B" "C" "D"]
                                 :sort-key :article)
        d (first (filter #(= "D" (:article %)) rows))]
    (testing "D appears in output even with no finance data"
      (is (some? d)))
    (testing "D for-pay = 0.0"
      (is (= 0.0 (:for-pay d))))
    (testing "D revenue = 0.0"
      (is (= 0.0 (:revenue d))))
    (testing "D sales-qty = 0"
      (is (= 0 (:sales-qty d))))
    (testing "D returns-qty = 0"
      (is (= 0 (:returns-qty d))))
    (testing "D cost-price = 0.0"
      (is (= 0.0 (:cost-price d))))
    (testing "D total-cost = 0.0"
      (is (= 0.0 (:total-cost d)))))

  ;; With storage-by-article, the empty row still picks up storage
  (let [sba {"D" 7.77}
        rows (finance/by-article fx
                                 :articles ["A" "B" "C" "D"]
                                 :storage-by-article sba
                                 :sort-key :article)
        d (first (filter #(= "D" (:article %)) rows))]
    (testing "D storage = 7.77 from storage-by-article despite no rows"
      (is (= 7.77 (:storage d))))))

;; ---------------------------------------------------------------------------
;; Finance.7 — totals period rollup
;; ---------------------------------------------------------------------------

(deftest group-7-totals
  (let [t (finance/totals fx)]
    ;; for-pay = A(400) + B(126) + C(0) = 526
    (testing "total-for-pay = 526.0"
      (is (= 526.0 (:total-for-pay t))))
    ;; revenue = A(500) + B(150) = 650
    (testing "total-revenue = 650.0"
      (is (= 650.0 (:total-revenue t))))
    ;; wb-reward: A(75) + B(24) = 99
    (testing "total-wb-reward = 99.0"
      (is (= 99.0 (:total-wb-reward t))))
    ;; logistics: A(6) + B(1.5) + C(0.5) = 8.0
    (testing "total-logistics = 8.0"
      (is (= 8.0 (:total-logistics t))))
    ;; sales-qty: A(5) + B(3) + C(0) = 8
    (testing "total-sales-qty = 8"
      (is (= 8 (:total-sales-qty t))))
    ;; returns-qty: A(2) + B(0) + C(1) = 3
    (testing "total-returns-qty = 3"
      (is (= 3 (:total-returns-qty t))))
    ;; articles-count: 3 distinct articles
    (testing "articles-count = 3"
      (is (= 3 (:articles-count t))))))

;; ---------------------------------------------------------------------------
;; Finance.8 — by-report-id weekly split
;; ---------------------------------------------------------------------------

(deftest group-8-by-report-id
  ;; All fixture rows share the same date-from/date-to → one group per
  ;; report-id. The fixture has no :report-id field → all rows group
  ;; under nil (single catch-all group, as for Ozon/YM).
  (let [reports (finance/by-report-id fx)]
    (testing "single report group (all rows have nil report-id)"
      (is (= 1 (count reports))))
    (let [r (first reports)]
      (testing "report-id is nil"
        (is (nil? (:report-id r))))
      ;; for-pay here is raw SUM(for-pay) across all rows, not net-of-returns.
      ;; All rows: 5×80 (sales A) + 2×0 (returns A) + 3×42 (sales B) + 1×0 (return C)
      ;; = 400 + 0 + 126 + 0 = 526
      (testing "for-pay = raw SUM of all row :for-pay = 526"
        (is (= 526.0 (:for-pay r))))
      (testing "lines count = total fixture rows (5+2+3+1=11)"
        (is (= 11 (:lines r))))))

  ;; Fixture with explicit report-ids to verify grouping
  (let [rows (concat
               (for [i (range 3)]
                 {:article "X" :operation "sale" :for-pay 10.0
                  :report-id "W1" :date-from "2026-03-01" :date-to "2026-03-07"})
               (for [i (range 2)]
                 {:article "X" :operation "sale" :for-pay 20.0
                  :report-id "W2" :date-from "2026-03-08" :date-to "2026-03-14"}))
        reports (finance/by-report-id rows)]
    (testing "two distinct report-ids → two groups"
      (is (= 2 (count reports))))
    (let [w1 (first (filter #(= "W1" (:report-id %)) reports))
          w2 (first (filter #(= "W2" (:report-id %)) reports))]
      (testing "W1 for-pay = 3×10 = 30"
        (is (= 30.0 (:for-pay w1))))
      (testing "W2 for-pay = 2×20 = 40"
        (is (= 40.0 (:for-pay w2)))))))

;; ---------------------------------------------------------------------------
;; Finance.11 — Reconciliation with P&L and UE
;; ---------------------------------------------------------------------------

(deftest group-reconcile-with-pnl-and-ue
  (let [finance-for-pay (reduce + 0.0 (map :for-pay by-art))]
    (testing "SUM(:for-pay) across by-article = 526.0 (A=400 + B=126 + C=0)"
      (is (= 526.0 (math/round2 finance-for-pay))))

    (testing "Finance for-pay matches P&L :for-pay within 0.1 RUB"
      (require 'analitica.domain.pnl)
      (let [pnl-calc @(resolve 'analitica.domain.pnl/calculate)
            pnl      (pnl-calc fx)
            delta    (Math/abs (- finance-for-pay (:for-pay pnl)))]
        (is (< delta 0.1)
            (str "Finance SUM for-pay " finance-for-pay
                 " vs P&L for-pay " (:for-pay pnl)
                 " delta=" delta))))

    (testing "Finance for-pay matches UE SUM(:for-pay) within 0.1 RUB"
      (require 'analitica.domain.unit-economics)
      (let [ue-calc @(resolve 'analitica.domain.unit-economics/calculate)
            ue-rows (ue-calc fx)
            ue-fp   (reduce + 0.0 (map :for-pay ue-rows))
            delta   (Math/abs (- finance-for-pay ue-fp))]
        (is (< delta 0.1)
            (str "Finance SUM for-pay " finance-for-pay
                 " vs UE SUM for-pay " ue-fp
                 " delta=" delta))))))
