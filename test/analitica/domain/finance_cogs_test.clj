(ns analitica.domain.finance-cogs-test
  "Regression tests for cogs / total-cost calculation in finance/by-article.

   Specifically guards against the latent `(max 1 (or quantity 1))` bug in
   `article-row`: when an Ozon realization row is split into fractional-
   quantity daily children by `ozon-distribute/redistribute-realization`,
   the floor at 1 silently multiplies cogs by N (number of children).

   Bug surfaced when a flat-distribution fallback was tried — Pulse Ozon
   profit dropped from +12k to -1.98M because cogs ballooned 30x."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.finance :as finance]
            [analitica.domain.cost-price :as cost-price]))

;; ---------------------------------------------------------------------------
;; Linear cogs scaling under fractional quantity
;; ---------------------------------------------------------------------------

(deftest cogs-scales-linearly-with-fractional-quantity
  ;; Given: cost-price = 100, two sale rows for the same article each
  ;; with quantity = 0.5 (the typical shape of a spread row that originally
  ;; had quantity = 1 and was split across 2 days).
  ;; Expected: total-cost = 100 (0.5 * 100 + 0.5 * 100), preserving the
  ;; original integer-quantity cost.
  ;; Bug: with `(max 1 quantity)`, 0.5 is floored to 1 → each child contributes
  ;; 100 → total = 200, doubling cogs.
  (with-redefs [cost-price/get-price (fn [_article _barcode] 100.0)]
    (let [rows [{:marketplace :ozon :rrd-id 1
                 :article "X" :operation "sale" :quantity 0.5
                 :retail-amount 50.0 :for-pay 40.0
                 :date-from "2026-04-01" :date-to "2026-04-30"
                 :event-date "2026-04-10"}
                {:marketplace :ozon :rrd-id 2
                 :article "X" :operation "sale" :quantity 0.5
                 :retail-amount 50.0 :for-pay 40.0
                 :date-from "2026-04-01" :date-to "2026-04-30"
                 :event-date "2026-04-15"}]
          row    (first (finance/by-article rows))]
      (testing "two 0.5-quantity rows should produce total-cost = 100 (1.0 unit × 100)"
        (is (= 100.0 (:total-cost row))
            (str "Expected linear scaling. Got " (:total-cost row) " — "
                 "the (max 1 quantity) floor is the bug."))))))

(deftest cogs-scales-linearly-with-many-fractional-children
  ;; The realistic case: Ozon spread row split across 30 days, each child
  ;; has quantity = 1/30 ≈ 0.0333. Total should equal original 1-unit cost.
  (with-redefs [cost-price/get-price (fn [_article _barcode] 500.0)]
    (let [rows (mapv (fn [i]
                       {:marketplace :ozon :rrd-id i
                        :article "Y" :operation "sale"
                        :quantity (/ 1.0 30.0)
                        :retail-amount (/ 100.0 30.0)
                        :for-pay (/ 80.0 30.0)
                        :date-from "2026-04-01" :date-to "2026-04-30"
                        :event-date (str "2026-04-" (format "%02d" i))})
                     (range 1 31))
          row  (first (finance/by-article rows))]
      (testing "30 children with quantity=1/30 should give total-cost = 500 (1.0 × 500)"
        (let [actual (:total-cost row)]
          (is (< 499.5 actual 500.5)
              (str "Expected ~500. Got " actual " — "
                   "if it's ~15000, the (max 1 quantity) bug is present (floor → 30 × 500).")))))))

(deftest cogs-handles-integer-quantity-as-before
  ;; Sanity: integer quantities still work after the fix.
  (with-redefs [cost-price/get-price (fn [_article _barcode] 250.0)]
    (let [rows [{:marketplace :wb :rrd-id 1
                 :article "Z" :operation "sale" :quantity 3
                 :retail-amount 300.0 :for-pay 250.0
                 :date-from "2026-04-01" :date-to "2026-04-07"
                 :event-date "2026-04-03"}]
          row  (first (finance/by-article rows))]
      (testing "integer qty=3 with cost=250 → total-cost = 750"
        (is (= 750.0 (:total-cost row)))))))

(deftest cogs-handles-zero-and-nil-quantity
  ;; Service rows often have quantity = 0 or nil. Cogs contribution = 0.
  (with-redefs [cost-price/get-price (fn [_article _barcode] 100.0)]
    (let [zero-row [{:marketplace :ozon :rrd-id 1
                     :article "S" :operation "sale" :quantity 0
                     :retail-amount 0.0 :for-pay 0.0
                     :date-from "2026-04-01" :date-to "2026-04-30"
                     :event-date "2026-04-10"}]
          nil-row  [{:marketplace :ozon :rrd-id 2
                     :article "T" :operation "sale" :quantity nil
                     :retail-amount 0.0 :for-pay 0.0
                     :date-from "2026-04-01" :date-to "2026-04-30"
                     :event-date "2026-04-10"}]]
      (testing "quantity=0 → cogs = 0"
        (is (= 0.0 (:total-cost (first (finance/by-article zero-row))))))
      (testing "quantity=nil → cogs = 0"
        (is (= 0.0 (:total-cost (first (finance/by-article nil-row)))))))))

;; ---------------------------------------------------------------------------
;; by-sku: same linear-scaling guarantee as by-article
;;
;; Regression: commit 2f8bd50 fixed `article-row` (by-article) but missed the
;; identical clamp at finance.clj:227 in `by-sku`. The SKU-detail page reads
;; via by-sku, so Ozon spread rows there were still 30× over-counted.
;; ---------------------------------------------------------------------------

(deftest by-sku-cogs-scales-linearly-with-fractional-quantity
  (with-redefs [cost-price/get-price (fn [_article _barcode] 100.0)]
    (let [rows [{:marketplace :ozon :rrd-id 1
                 :article "X" :barcode "BC1" :operation "sale" :quantity 0.5
                 :retail-amount 50.0 :for-pay 40.0
                 :date-from "2026-04-01" :date-to "2026-04-30"
                 :event-date "2026-04-10"}
                {:marketplace :ozon :rrd-id 2
                 :article "X" :barcode "BC1" :operation "sale" :quantity 0.5
                 :retail-amount 50.0 :for-pay 40.0
                 :date-from "2026-04-01" :date-to "2026-04-30"
                 :event-date "2026-04-15"}]
          row  (first (finance/by-sku rows))]
      (testing "by-sku: two 0.5-quantity rows should produce total-cost = 100"
        (is (= 100.0 (:total-cost row))
            (str "Expected linear scaling. Got " (:total-cost row) " — "
                 "the (max 1 quantity) floor at finance.clj:227 is the bug."))))))

(deftest by-sku-cogs-scales-linearly-with-many-fractional-children
  (with-redefs [cost-price/get-price (fn [_article _barcode] 500.0)]
    (let [rows (mapv (fn [i]
                       {:marketplace :ozon :rrd-id i
                        :article "Y" :barcode "BC2" :operation "sale"
                        :quantity (/ 1.0 30.0)
                        :retail-amount (/ 100.0 30.0)
                        :for-pay (/ 80.0 30.0)
                        :date-from "2026-04-01" :date-to "2026-04-30"
                        :event-date (str "2026-04-" (format "%02d" i))})
                     (range 1 31))
          row  (first (finance/by-sku rows))]
      (testing "by-sku: 30 children with quantity=1/30 should give total-cost = 500"
        (let [actual (:total-cost row)]
          (is (< 499.5 actual 500.5)
              (str "Expected ~500. Got " actual " — "
                   "if it's ~15000, the (max 1 quantity) bug is present.")))))))

(deftest by-sku-cogs-handles-zero-and-nil-quantity
  (with-redefs [cost-price/get-price (fn [_article _barcode] 100.0)]
    (let [zero-row [{:marketplace :ozon :rrd-id 1
                     :article "S" :barcode "BC3" :operation "sale" :quantity 0
                     :retail-amount 0.0 :for-pay 0.0
                     :date-from "2026-04-01" :date-to "2026-04-30"
                     :event-date "2026-04-10"}]
          nil-row  [{:marketplace :ozon :rrd-id 2
                     :article "T" :barcode "BC4" :operation "sale" :quantity nil
                     :retail-amount 0.0 :for-pay 0.0
                     :date-from "2026-04-01" :date-to "2026-04-30"
                     :event-date "2026-04-10"}]]
      (testing "by-sku: quantity=0 → cogs = 0"
        (is (= 0.0 (:total-cost (first (finance/by-sku zero-row))))))
      (testing "by-sku: quantity=nil → cogs = 0"
        (is (= 0.0 (:total-cost (first (finance/by-sku nil-row)))))))))

(deftest by-sku-cogs-handles-integer-quantity-as-before
  (with-redefs [cost-price/get-price (fn [_article _barcode] 250.0)]
    (let [rows [{:marketplace :wb :rrd-id 1
                 :article "Z" :barcode "BC5" :operation "sale" :quantity 3
                 :retail-amount 300.0 :for-pay 250.0
                 :date-from "2026-04-01" :date-to "2026-04-07"
                 :event-date "2026-04-03"}]
          row  (first (finance/by-sku rows))]
      (testing "by-sku: integer qty=3 with cost=250 → total-cost = 750"
        (is (= 750.0 (:total-cost row)))))))

;; ---------------------------------------------------------------------------
;; FR-004: COGS must be charged on NET (sold - returned) units, not gross sold
;; ---------------------------------------------------------------------------

(deftest cogs-net-subtracts-returned-units
  ;; cost-per-unit=100, sold-qty=5, returned-qty=2 → net=3 → total-cost=300
  ;; before-would-be 500.0 (gross: 100*5, returns ignored)
  (with-redefs [cost-price/get-price (fn [_article _barcode] 100.0)]
    (let [rows [{:marketplace :ozon :rrd-id 1
                 :article "FR004" :operation "sale" :quantity 1
                 :retail-amount 200.0 :for-pay 160.0
                 :date-from "2026-04-01" :date-to "2026-04-30"
                 :event-date "2026-04-10"}
                {:marketplace :ozon :rrd-id 2
                 :article "FR004" :operation "sale" :quantity 1
                 :retail-amount 200.0 :for-pay 160.0
                 :date-from "2026-04-01" :date-to "2026-04-30"
                 :event-date "2026-04-11"}
                {:marketplace :ozon :rrd-id 3
                 :article "FR004" :operation "sale" :quantity 1
                 :retail-amount 200.0 :for-pay 160.0
                 :date-from "2026-04-01" :date-to "2026-04-30"
                 :event-date "2026-04-12"}
                {:marketplace :ozon :rrd-id 4
                 :article "FR004" :operation "sale" :quantity 1
                 :retail-amount 200.0 :for-pay 160.0
                 :date-from "2026-04-01" :date-to "2026-04-30"
                 :event-date "2026-04-13"}
                {:marketplace :ozon :rrd-id 5
                 :article "FR004" :operation "sale" :quantity 1
                 :retail-amount 200.0 :for-pay 160.0
                 :date-from "2026-04-01" :date-to "2026-04-30"
                 :event-date "2026-04-14"}
                {:marketplace :ozon :rrd-id 6
                 :article "FR004" :operation "return" :quantity 1
                 :retail-amount 200.0 :for-pay 160.0
                 :date-from "2026-04-01" :date-to "2026-04-30"
                 :event-date "2026-04-20"}
                {:marketplace :ozon :rrd-id 7
                 :article "FR004" :operation "return" :quantity 1
                 :retail-amount 200.0 :for-pay 160.0
                 :date-from "2026-04-01" :date-to "2026-04-30"
                 :event-date "2026-04-21"}]
          row (first (finance/by-article rows))]
      (testing "article-row: sold=5 returned=2 → net=3 → total-cost = 300.0 (before-would-be 500.0)"
        (is (= 300.0 (:total-cost row))
            (str "Expected 300.0 (100*3 net units). Got " (:total-cost row)
                 " — if 500.0, returns are not being subtracted from COGS."))))))

(deftest cogs-net-clamps-at-zero-on-all-returns
  ;; sold-qty=2, returned-qty=5 → net=max(0,-3)=0 → total-cost=0.0 (never negative)
  ;; before-would-be 200.0 (gross: 100*2, returns ignored)
  (with-redefs [cost-price/get-price (fn [_article _barcode] 100.0)]
    (let [rows [{:marketplace :ozon :rrd-id 1
                 :article "FR004B" :operation "sale" :quantity 1
                 :retail-amount 200.0 :for-pay 160.0
                 :date-from "2026-04-01" :date-to "2026-04-30"
                 :event-date "2026-04-10"}
                {:marketplace :ozon :rrd-id 2
                 :article "FR004B" :operation "sale" :quantity 1
                 :retail-amount 200.0 :for-pay 160.0
                 :date-from "2026-04-01" :date-to "2026-04-30"
                 :event-date "2026-04-11"}
                {:marketplace :ozon :rrd-id 3
                 :article "FR004B" :operation "return" :quantity 1
                 :retail-amount 200.0 :for-pay 160.0
                 :date-from "2026-04-01" :date-to "2026-04-30"
                 :event-date "2026-04-15"}
                {:marketplace :ozon :rrd-id 4
                 :article "FR004B" :operation "return" :quantity 1
                 :retail-amount 200.0 :for-pay 160.0
                 :date-from "2026-04-01" :date-to "2026-04-30"
                 :event-date "2026-04-16"}
                {:marketplace :ozon :rrd-id 5
                 :article "FR004B" :operation "return" :quantity 1
                 :retail-amount 200.0 :for-pay 160.0
                 :date-from "2026-04-01" :date-to "2026-04-30"
                 :event-date "2026-04-17"}
                {:marketplace :ozon :rrd-id 6
                 :article "FR004B" :operation "return" :quantity 1
                 :retail-amount 200.0 :for-pay 160.0
                 :date-from "2026-04-01" :date-to "2026-04-30"
                 :event-date "2026-04-18"}
                {:marketplace :ozon :rrd-id 7
                 :article "FR004B" :operation "return" :quantity 1
                 :retail-amount 200.0 :for-pay 160.0
                 :date-from "2026-04-01" :date-to "2026-04-30"
                 :event-date "2026-04-19"}]
          row (first (finance/by-article rows))]
      (testing "article-row: sold=2 returned=5 → net clamped to 0 → total-cost = 0.0"
        (is (= 0.0 (:total-cost row))
            (str "Expected 0.0 (clamped: returns > sales). Got " (:total-cost row)
                 " — COGS must never be negative."))))))

(deftest cogs-p7-per-article-sum-equals-period-total
  ;; P7 consistency: Σ per-article :total-cost over by-article == period :total-cost from totals.
  ;; Two articles: A (sold=3, returned=1, cost=100 → net=2 → cost=200)
  ;;               B (sold=4, returned=0, cost=50  → net=4 → cost=200)
  ;; Expected period total-cost = 400.0
  (with-redefs [cost-price/get-price (fn [article _barcode]
                                       (case article
                                         "A-P7" 100.0
                                         "B-P7" 50.0
                                         0.0))]
    (let [rows [{:marketplace :ozon :rrd-id 1  :article "A-P7" :operation "sale"   :quantity 1 :retail-amount 300.0 :for-pay 240.0 :date-from "2026-04-01" :date-to "2026-04-30" :event-date "2026-04-05"}
                {:marketplace :ozon :rrd-id 2  :article "A-P7" :operation "sale"   :quantity 1 :retail-amount 300.0 :for-pay 240.0 :date-from "2026-04-01" :date-to "2026-04-30" :event-date "2026-04-06"}
                {:marketplace :ozon :rrd-id 3  :article "A-P7" :operation "sale"   :quantity 1 :retail-amount 300.0 :for-pay 240.0 :date-from "2026-04-01" :date-to "2026-04-30" :event-date "2026-04-07"}
                {:marketplace :ozon :rrd-id 4  :article "A-P7" :operation "return" :quantity 1 :retail-amount 300.0 :for-pay 240.0 :date-from "2026-04-01" :date-to "2026-04-30" :event-date "2026-04-20"}
                {:marketplace :ozon :rrd-id 5  :article "B-P7" :operation "sale"   :quantity 1 :retail-amount 150.0 :for-pay 120.0 :date-from "2026-04-01" :date-to "2026-04-30" :event-date "2026-04-05"}
                {:marketplace :ozon :rrd-id 6  :article "B-P7" :operation "sale"   :quantity 1 :retail-amount 150.0 :for-pay 120.0 :date-from "2026-04-01" :date-to "2026-04-30" :event-date "2026-04-06"}
                {:marketplace :ozon :rrd-id 7  :article "B-P7" :operation "sale"   :quantity 1 :retail-amount 150.0 :for-pay 120.0 :date-from "2026-04-01" :date-to "2026-04-30" :event-date "2026-04-07"}
                {:marketplace :ozon :rrd-id 8  :article "B-P7" :operation "sale"   :quantity 1 :retail-amount 150.0 :for-pay 120.0 :date-from "2026-04-01" :date-to "2026-04-30" :event-date "2026-04-08"}]
          articles         (finance/by-article rows)
          per-article-sum  (reduce + 0.0 (map :total-cost articles))
          ;; totals does not emit :total-cost directly; P7 is: Σ per-article == Σ by-article
          ;; (the period rollup is computed identically from the same by-article result)
          a-row            (first (filter #(= "A-P7" (:article %)) articles))
          b-row            (first (filter #(= "B-P7" (:article %)) articles))]
      (testing "A: sold=3 returned=1 → net=2 → total-cost = 200.0"
        (is (= 200.0 (:total-cost a-row))
            (str "A expected 200.0, got " (:total-cost a-row))))
      (testing "B: sold=4 returned=0 → net=4 → total-cost = 200.0"
        (is (= 200.0 (:total-cost b-row))
            (str "B expected 200.0, got " (:total-cost b-row))))
      (testing "P7: Σ per-article :total-cost = 400.0"
        (is (= 400.0 per-article-sum)
            (str "Expected 400.0, got " per-article-sum))))))

(deftest cogs-per-line-heterogeneous-costs-reconcile
  ;; Adversarial-review guard (Lens 1/2, FR-004): a single :article can span
  ;; barcodes with DIFFERENT 1C costs (a size-suffixed finance article misses the
  ;; strict 1C key → each line resolves to its own per-barcode cost). COGS MUST sum
  ;; each line at its OWN cost, NOT collapse to the first sale-line's cost × net-units
  ;; (that understated ~80% and broke P7 between by-sku and by-article).
  (with-redefs [cost-price/get-price (fn [_article barcode]
                                       (case barcode
                                         "BC-CHEAP"  100.0
                                         "BC-PRICEY" 900.0
                                         0.0))]
    (let [rows [{:marketplace :wb :rrd-id 1 :article "HET" :barcode "BC-CHEAP"  :operation "sale" :quantity 1 :retail-amount 200.0 :for-pay 160.0 :date-from "2026-04-01" :date-to "2026-04-30" :event-date "2026-04-05"}
                {:marketplace :wb :rrd-id 2 :article "HET" :barcode "BC-PRICEY" :operation "sale" :quantity 1 :retail-amount 999.0 :for-pay 800.0 :date-from "2026-04-01" :date-to "2026-04-30" :event-date "2026-04-06"}]
          by-art  (first (finance/by-article rows))
          sku-sum (reduce + 0.0 (map :total-cost (finance/by-sku rows)))]
      (testing "by-article sums each line at its own cost (100+900=1000), not first-cost×2 (200)"
        (is (= 1000.0 (:total-cost by-art))
            (str "Expected 1000.0 (100+900). Got " (:total-cost by-art)
                 " — 200.0 would mean per-line costs were collapsed to the first line's cost.")))
      (testing "P7: Σ by-sku :total-cost == by-article :total-cost"
        (is (= (:total-cost by-art) sku-sum)
            (str "by-article " (:total-cost by-art) " vs Σ by-sku " sku-sum)))
      (testing "order-independent: reversed input yields the same by-article total-cost"
        (is (= 1000.0 (:total-cost (first (finance/by-article (reverse rows))))))))))
