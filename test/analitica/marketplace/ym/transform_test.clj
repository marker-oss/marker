(ns analitica.marketplace.ym.transform-test
  "Unit tests for YM transform layer.

   US1 (spec 003-finance-row-completeness) introduces per-item
   operation classification: item-level :itemStatus takes priority
   over order-level :status. This namespace exercises the full 9-row
   matrix from data-model.md §4."
  (:require [clojure.test :refer [deftest testing is]]
            [analitica.marketplace.ym.transform :as transform]))

;; ---------------------------------------------------------------------------
;; classify-item-operation — 9-row matrix from data-model.md §4
;;
;; Priority rules:
;;   1) item.details[0].itemStatus RETURNED       → "return"
;;   2) item.details[0].itemStatus REJECTED       → "cancelled"
;;   3) otherwise fall through to order.status:
;;      DELIVERED / PARTIALLY_DELIVERED           → "sale"
;;      CANCELLED_*                               → "cancelled"
;;      RETURNED (order-level, no :details)       → "return"
;;      anything else                             → "sale" (+ mu/log)
;; ---------------------------------------------------------------------------

(defn- classify
  "Thin wrapper because classify-item-operation is private — access via #'."
  [order item]
  (#'transform/classify-item-operation order item))

;; ---- Row 1: item RETURNED (any order.status) ------------------------------

(deftest item-returned-beats-any-order-status
  (testing "item.details itemStatus RETURNED → return, regardless of order.status"
    (let [item {:details [{:itemStatus "RETURNED"}]}]
      (is (= "return" (classify {:status "DELIVERED"} item))
          "post-delivery return edge — DELIVERED order with RETURNED item")
      (is (= "return" (classify {:status "RETURNED"} item)))
      (is (= "return" (classify {:status "CANCELLED_IN_DELIVERY"} item)))
      (is (= "return" (classify {:status "PARTIALLY_DELIVERED"} item))))))

;; ---- Row 2: item REJECTED (any order.status) ------------------------------

(deftest item-rejected-beats-any-order-status
  (testing "item.details itemStatus REJECTED → cancelled"
    (let [item {:details [{:itemStatus "REJECTED"}]}]
      (is (= "cancelled" (classify {:status "CANCELLED_IN_DELIVERY"} item)))
      (is (= "cancelled" (classify {:status "DELIVERED"} item)))
      (is (= "cancelled" (classify {:status "PARTIALLY_DELIVERED"} item))
          "PARTIALLY_DELIVERED order can have mixed items — REJECTED item still cancelled"))))

;; ---- Rows 3-4: no :details / DELIVERED ------------------------------------

(deftest no-details-delivered-is-sale
  (testing "item lacks :details, order DELIVERED → sale"
    (is (= "sale" (classify {:status "DELIVERED"} {})))
    (is (= "sale" (classify {:status "DELIVERED"} {:details []}))
        "empty :details vector is equivalent to missing")
    (is (= "sale" (classify {:status "DELIVERED"} {:details nil})))))

(deftest no-details-partially-delivered-is-sale
  (testing "item lacks :details, order PARTIALLY_DELIVERED → sale"
    (is (= "sale" (classify {:status "PARTIALLY_DELIVERED"} {})))))

;; ---- Rows 5-7: CANCELLED_* -----------------------------------------------

(deftest no-details-cancelled-statuses-are-cancelled
  (testing "every order-level CANCELLED_* maps to cancelled"
    (is (= "cancelled" (classify {:status "CANCELLED_BEFORE_PROCESSING"} {})))
    (is (= "cancelled" (classify {:status "CANCELLED_IN_PROCESSING"} {})))
    (is (= "cancelled" (classify {:status "CANCELLED_IN_DELIVERY"} {})))))

;; ---- Row 8: RETURNED order fallback ---------------------------------------

(deftest no-details-returned-order-is-return
  (testing "order.status RETURNED + no :details → return (fallback)"
    (is (= "return" (classify {:status "RETURNED"} {})))
    (is (= "return" (classify {:status "RETURNED"} {:details []})))))

;; ---- Row 9: unknown value / default ---------------------------------------

(deftest unknown-status-falls-back-to-sale-with-log
  (testing "unknown order.status → sale (safe default) with mu/log"
    ;; mu/log is a macro; it should not throw, and default stays "sale".
    (is (= "sale" (classify {:status "NEWLY_INVENTED_STATE"} {}))))
  (testing "unknown itemStatus (non-RETURNED / non-REJECTED) falls back to order-level"
    ;; itemStatus SHIPPED (hypothetical) — not in priority rules → order.status used.
    (is (= "sale" (classify {:status "DELIVERED"}
                            {:details [{:itemStatus "SHIPPED"}]}))))
  (testing "nil order.status + nil item defaults to sale"
    (is (= "sale" (classify {} {}))
        "missing order.status with no item.details → safe default \"sale\"")))

;; ---------------------------------------------------------------------------
;; ->finance-from-order-stats end-to-end :operation check
;; ---------------------------------------------------------------------------

(def ^:private buyer-price [{:type "BUYER" :total 1000.0}])

(deftest finance-from-order-stats-uses-per-item-classification
  (testing "DELIVERED order with one RETURNED item and one normal item → 2 rows, :operation is per-item"
    (let [order {:id 1
                 :creationDate "2026-03-15"
                 :status "DELIVERED"
                 :items [{:shopSku "A" :count 1 :prices buyer-price
                          :details [{:itemStatus "RETURNED"}]}
                         {:shopSku "B" :count 1 :prices buyer-price}]}
          [row-a row-b] (transform/->finance-from-order-stats [order])]
      (is (= "return" (:operation row-a))
          "first item marked RETURNED at item-level, regardless of DELIVERED order")
      (is (= "sale" (:operation row-b))
          "second item falls through to order-level DELIVERED → sale"))))

(deftest finance-cancelled-order-all-items-cancelled
  (testing "CANCELLED_BEFORE_PROCESSING order → every item :operation = cancelled"
    (let [order {:id 2
                 :creationDate "2026-03-15"
                 :status "CANCELLED_BEFORE_PROCESSING"
                 :items [{:shopSku "X" :count 1 :prices buyer-price}
                         {:shopSku "Y" :count 1 :prices buyer-price}]}
          rows (transform/->finance-from-order-stats [order])]
      (is (= 2 (count rows)))
      (is (every? #(= "cancelled" (:operation %)) rows)))))

;; ---------------------------------------------------------------------------
;; US2: bidFee → :ad-cost extraction + :for-pay formula update
;; See specs/003-finance-row-completeness/data-model.md §5, spec.md FR-005/006/019.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Ad-cost semantic correction (2026-04-24):
;;   Earlier design mistook `item.bidFee` (seller's bid CAP) for the actual
;;   ad-auction commission. The true charge is `commissions[AUCTION_PROMOTION]
;;   .actual`, which is the Vickrey second-price clear — on live data bidFee
;;   was ~351× larger than AUCTION_PROMOTION. :ad-cost now carries
;;   AUCTION_PROMOTION split across items, not bidFee.
;; ---------------------------------------------------------------------------

(deftest ad-commission-split-across-items
  (testing "AUCTION_PROMOTION commission split evenly across items of an order"
    (let [order {:id 1 :status "DELIVERED" :creationDate "2026-03-01"
                 :commissions [{:type "FEE" :actual 60}
                               {:type "AUCTION_PROMOTION" :actual 4.0}]
                 :items [{:shopSku "A" :bidFee 100 :prices [{:type "BUYER" :total 500}]}
                         {:shopSku "B" :bidFee 200 :prices [{:type "BUYER" :total 800}]}]}
          rows (transform/->finance-from-order-stats [order])]
      (is (= 2 (count rows)))
      (is (= 2.0 (:ad-cost (first rows)))  "AUCTION_PROMOTION 4 / 2 items = 2")
      (is (= 2.0 (:ad-cost (second rows))) "item B gets the other half")
      ;; :for-pay = BUYER − non-ad commissions (FEE 60 / 2 = 30 each).
      ;; AUCTION_PROMOTION is NOT in all-comm anymore — stays only in :ad-cost.
      (is (= 470.0 (:for-pay (first rows)))  "500 − 30 (FEE only, AUCTION_PROMO separate)")
      (is (= 770.0 (:for-pay (second rows))) "800 − 30"))))

(deftest cancelled-order-ad-commission-is-zero
  (testing "Cancelled-before-processing → no AUCTION_PROMOTION commission → ad-cost = 0"
    ;; Yandex doesn't charge AUCTION_PROMOTION on orders that never got
    ;; delivered (verified 2026-04-24: 78 CANCELLED_IN_DELIVERY orders had
    ;; bidFee sum 74,685 but AUCTION_PROMOTION sum 0.00). The item's bidFee
    ;; is just the reserved bid cap — never charged.
    (let [order {:id 2 :status "CANCELLED_BEFORE_PROCESSING" :creationDate "2026-03-01"
                 :commissions []      ; no AUCTION_PROMOTION — not charged
                 :payments []
                 :items [{:shopSku "X" :bidFee 500
                          :prices [{:type "BUYER" :total 0}]}]}
          rows (transform/->finance-from-order-stats [order])
          row (first rows)]
      (is (= "cancelled" (:operation row)))
      (is (= 0.0 (:ad-cost row)) "no AUCTION_PROMOTION commission ⇒ no ad-cost")
      (is (= 0.0 (:for-pay row)) "0 − 0 = 0"))))
