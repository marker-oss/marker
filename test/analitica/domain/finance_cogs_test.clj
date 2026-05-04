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
