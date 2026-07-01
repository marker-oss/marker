(ns analitica.domain.finance-test
  "Spec 012 US2/US3 — :net-sales aggregation in domain/finance by-article and
   the mp-payout (:for-pay) aggregate with negative YM sale rows.

   The corrected YM basis carries a per-line :net-sales (BUYER × qty); WB/Ozon
   rows have no :net-sales (nil). by-article must nil-coalesce so WB/Ozon
   articles carry :net-sales 0.0 and their :revenue (:retail-amount) is
   unaffected. YM sale rows may have a NEGATIVE :for-pay (loss-making SKU); the
   :for-pay aggregate (Σsale − Σreturn) must sum those negatives, not clamp."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.finance :as finance]))

;; ---- T021: by-article :net-sales aggregation (nil-safe) -------------------

(deftest by-article-net-sales-aggregation
  (testing "YM article carries Σ :net-sales; WB/Ozon carry 0.0 with :revenue intact"
    (let [rows [;; YM: two sale lines for one article, gross (retail-amount) =
                ;; BUYER + subsidy, net-sales = BUYER
                {:marketplace :ym :article "YM-1" :operation-kind :sale
                 :quantity 1 :retail-amount 3000.0 :net-sales 2000.0 :for-pay 1800.0}
                {:marketplace :ym :article "YM-1" :operation-kind :sale
                 :quantity 1 :retail-amount 1500.0 :net-sales 1000.0 :for-pay 900.0}
                ;; WB: no :net-sales key at all (== gross); revenue = retail-amount
                {:marketplace :wb :article "WB-1" :operation-kind :sale
                 :quantity 1 :retail-amount 500.0 :for-pay 420.0}
                ;; Ozon: :net-sales explicitly nil
                {:marketplace :ozon :article "OZ-1" :operation-kind :sale
                 :quantity 1 :retail-amount 700.0 :net-sales nil :for-pay 560.0}]
          rows-by (into {} (map (juxt :article identity) (finance/by-article rows)))
          ym  (get rows-by "YM-1")
          wb  (get rows-by "WB-1")
          oz  (get rows-by "OZ-1")]
      (is (= 3000.0 (:net-sales ym)) "YM :net-sales = Σ BUYER×qty (2000 + 1000)")
      (is (= 4500.0 (:revenue ym))   "YM :revenue = Σ retail-amount (gross)")
      (is (= 0.0 (:net-sales wb)) "WB has no :net-sales → coalesced to 0.0")
      (is (= 500.0 (:revenue wb))  "WB :revenue unchanged by :net-sales addition")
      (is (= 0.0 (:net-sales oz)) "Ozon :net-sales nil → coalesced to 0.0")
      (is (= 700.0 (:revenue oz))  "Ozon :revenue unchanged")))

  (testing "empty-article-row carries :net-sales 0.0"
    ;; articles list with an article that has no matching finance rows → the
    ;; empty-article-row path fires and must carry :net-sales 0.0.
    (let [rows    []
          out     (finance/by-article rows :articles ["MISSING-1"])
          missing (first out)]
      (is (= "MISSING-1" (:article missing)))
      (is (contains? missing :net-sales) "empty-article-row must include :net-sales")
      (is (= 0.0 (:net-sales missing))))))

;; ---- T026: mp-payout aggregate with negative YM sale rows -----------------

(deftest mp-payout-aggregate-with-negatives
  (testing ":for-pay aggregate = Σsale − Σreturn; negative sale rows sum, not clamped"
    (let [rows [;; one profitable sale, one loss-making sale (negative for-pay),
                ;; one return (positive +abs on transform layer)
                {:marketplace :ym :article "NEG-1" :operation-kind :sale
                 :quantity 1 :retail-amount 3000.0 :net-sales 3000.0 :for-pay 1200.0}
                {:marketplace :ym :article "NEG-1" :operation-kind :sale
                 :quantity 1 :retail-amount 2544.0 :net-sales 2544.0 :for-pay -569.0}
                {:marketplace :ym :article "NEG-1" :operation-kind :return
                 :quantity 1 :retail-amount 1000.0 :net-sales 1000.0 :for-pay 400.0}]
          row (first (finance/by-article rows))]
      (is (= "NEG-1" (:article row)))
      ;; Σsale = 1200 + (-569) = 631 ; Σreturn = 400 ; net = 631 − 400 = 231.
      ;; If the negative sale were clamped (Math/abs), Σsale would be 1769 and
      ;; the aggregate would be 1369 — this asserts it is NOT clamped.
      (is (= 231.0 (:for-pay row))
          "Σfor_pay[sale] (1200 + -569) − Σfor_pay[return] (400) = 231"))))
