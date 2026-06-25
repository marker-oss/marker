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

;; ---------------------------------------------------------------------------
;; Date normalization — YM /stats/orders DD-MM-YYYY → ISO at the boundary
;;
;; Live YM API returns creationDate as "15-04-2026 21:55:46". Storing it raw
;; broke SQL ORDER BY/MIN/MAX (lex "01-04…" < "31-03…") and dashboard
;; last-sync-time. Tests guard the new normalization in ->order/->sale.
;; ---------------------------------------------------------------------------

(deftest order-creation-date-dd-mm-yyyy-with-time
  (let [out (transform/->orders
             [{:id 1 :status "DELIVERED" :creationDate "15-04-2026 21:55:46"
               :buyerTotal 1000 :items [{:offerId "A" :count 1}]}])]
    (is (= "2026-04-15T21:55:46" (:date (first out))))))

(deftest order-creation-date-dd-mm-yyyy-date-only
  (let [out (transform/->orders
             [{:id 1 :status "DELIVERED" :creationDate "01-04-2026"
               :buyerTotal 500 :items [{:offerId "A" :count 1}]}])]
    (is (= "2026-04-01" (:date (first out))))))

(deftest order-iso-date-passthrough
  (let [out (transform/->orders
             [{:id 1 :status "DELIVERED" :creationDate "2026-04-15T08:00:00"
               :buyerTotal 500 :items [{:offerId "A" :count 1}]}])]
    (is (= "2026-04-15T08:00:00" (:date (first out))))))

(deftest sale-creation-date-normalized
  (let [out (transform/->sales
             [{:id 1 :status "DELIVERED" :creationDate "31-03-2026 12:00:00"
               :buyerTotal 500 :forPay 470 :items [{:offerId "A" :count 1}]}])]
    (is (= "2026-03-31T12:00:00" (:date (first out))))))

;; ---------------------------------------------------------------------------
;; Real-shape sales materialization (M5)
;;
;; Live YM payload puts prices inside :items[].prices[] with type=BUYER /
;; type=MARKETPLACE. The legacy transformer read :forPay / :buyerTotal at
;; top-level — both nil in real data — so every YM sales row landed in
;; the DB with all price columns NULL. These tests pin the real shape.
;; ---------------------------------------------------------------------------

(def ^:private ym-delivered-order
  {:id           42
   :status       "DELIVERED"
   :creationDate "2026-04-15T10:00:00"
   :deliveryRegion {:name "Москва" :id 213}
   :items        [{:warehouse {:name "SHEGIDA 2" :id 1521756}
                   :bidFee    343
                   :prices    [{:type "MARKETPLACE" :costPerItem 2874.0 :total 2874.0}
                               {:type "BUYER"       :costPerItem 3766.0 :total 3766.0}]
                   :marketSku 100
                   :count     1
                   :shopSku   "A1"
                   :offerName "Платье повседневное"
                   :cisList   ["(01)04660322670280(21)5fhS1"]}]})

(deftest sale-row-extracts-nested-prices
  (let [[row] (transform/->sales [ym-delivered-order])]
    (is (= "A1"                  (:article row)))
    (is (= "Платье повседневное" (:subject row))    "offerName populates :subject")
    (is (= "SHEGIDA 2"           (:warehouse row))  "warehouse extracted from item")
    (is (= "Москва"              (:region row))     "region from order.deliveryRegion.name")
    (is (= 3766.0                (:total-price row)) "BUYER.total = gross buyer price")
    (is (= 3766.0                (:finished-price row)) "BUYER.costPerItem per unit")
    (is (= 2531.0 (:for-pay row))
        "MARKETPLACE.total (2874) minus bidFee (343) = net seller payout")
    (is (= :sale (:type row)))
    (is (= 1     (:quantity row)))))

(deftest sale-row-barcode-from-cislist
  (let [[row] (transform/->sales [ym-delivered-order])]
    (is (= "4660322670280" (:barcode row))
        "extract-barcode strips leading 0 from GS1 (01) GTIN")))

(deftest cancelled-order-produces-no-sales-rows
  (testing "fully-cancelled order: zero rows in sales — they were never settled"
    (let [out (transform/->sales
               [{:id 1 :status "CANCELLED_IN_DELIVERY" :creationDate "2026-04-15"
                 :items [{:shopSku "A" :count 1
                          :details [{:itemStatus "REJECTED"}]
                          :prices [{:type "BUYER" :total 100}
                                   {:type "MARKETPLACE" :total 80}]}]}])]
      (is (empty? out)))))

(deftest returned-order-produces-return-row
  (testing "RETURNED status with item-level RETURNED → :return type, prices extracted"
    (let [out (transform/->sales
               [{:id 7 :status "RETURNED" :creationDate "2026-04-15"
                 :deliveryRegion {:name "СПб"}
                 :items [{:shopSku "B" :count 1
                          :details [{:itemStatus "RETURNED"}]
                          :warehouse {:name "WH"}
                          :prices [{:type "BUYER" :costPerItem 500.0 :total 500.0}
                                   {:type "MARKETPLACE" :costPerItem 400.0 :total 400.0}]
                          :bidFee 50}]}])]
      (is (= 1 (count out)))
      (is (= :return (:type (first out))))
      (is (= 500.0 (:total-price (first out))))
      (is (= 350.0 (:for-pay (first out))) "MARKETPLACE 400 − bidFee 50 = 350"))))

(deftest multi-item-order-produces-row-per-item
  (testing "Multi-item order with mixed item-level statuses splits cleanly"
    (let [out (transform/->sales
               [{:id 9 :status "DELIVERED" :creationDate "2026-04-15"
                 :items [{:shopSku "X" :count 1
                          :prices [{:type "BUYER" :costPerItem 100 :total 100}
                                   {:type "MARKETPLACE" :costPerItem 80 :total 80}]}
                         {:shopSku "Y" :count 1
                          :details [{:itemStatus "REJECTED"}]
                          :prices [{:type "BUYER" :costPerItem 200 :total 200}
                                   {:type "MARKETPLACE" :costPerItem 160 :total 160}]}
                         {:shopSku "Z" :count 1
                          :details [{:itemStatus "RETURNED"}]
                          :prices [{:type "BUYER" :costPerItem 300 :total 300}
                                   {:type "MARKETPLACE" :costPerItem 240 :total 240}]}]}])]
      (is (= 2 (count out)) "REJECTED item dropped; X and Z surface")
      (is (= #{"X" "Z"} (set (map :article out))))
      (is (= #{:sale :return} (set (map :type out)))))))

(deftest sale-id-is-stable-and-unique-per-item
  (testing "Multi-item orders need distinct sale-ids — ::sales has UNIQUE on sale_id"
    (let [out (transform/->sales
               [{:id 99 :status "DELIVERED" :creationDate "2026-04-15"
                 :items [{:shopSku "A" :count 1 :prices []}
                         {:shopSku "B" :count 1 :prices []}]}])]
      (is (= ["99-0" "99-1"] (mapv :sale-id out))
          "sale-id derived as orderId-itemIndex"))))

(deftest missing-prices-degrades-gracefully
  (testing "Item with no :prices array: row still emitted with nil price fields"
    (let [out (transform/->sales
               [{:id 1 :status "DELIVERED" :creationDate "2026-04-15"
                 :items [{:shopSku "A" :count 1}]}])]
      (is (= 1 (count out)))
      (is (nil? (:total-price (first out))))
      (is (nil? (:for-pay (first out)))
          "No MARKETPLACE price → nil for-pay; never silently 0"))))

;; ---------------------------------------------------------------------------
;; FR-P4.5: price-basis-mismatch? flag
;;
;; YM revenue uses the MARKETPLACE price (shown to customer) while for-pay
;; uses the BUYER price minus commissions. When they differ beyond rounding,
;; the margin denominator (MARKETPLACE) and numerator (BUYER-based) are on
;; different bases, overstating seller economics. We flag — never reconcile.
;; ---------------------------------------------------------------------------

(deftest price-basis-mismatch-flagged-when-buyer-differs-from-marketplace
  (testing "BUYER total 800 ≠ MARKETPLACE total 1000 → :price-basis-mismatch? true"
    (let [order {:id 10 :status "DELIVERED" :creationDate "2026-04-15"
                 :commissions []
                 :items [{:shopSku "SKU1" :count 1
                          :prices [{:type "MARKETPLACE" :total 1000.0}
                                   {:type "BUYER"       :total 800.0}]}]}
          [row] (transform/->finance-from-order-stats [order])]
      (is (true? (:price-basis-mismatch? row))
          "MARKETPLACE 1000 ≠ BUYER 800 → flagged"))))

(deftest price-basis-mismatch-false-when-prices-equal
  (testing "BUYER total 1000 = MARKETPLACE total 1000 → :price-basis-mismatch? false"
    (let [order {:id 11 :status "DELIVERED" :creationDate "2026-04-15"
                 :commissions []
                 :items [{:shopSku "SKU2" :count 1
                          :prices [{:type "MARKETPLACE" :total 1000.0}
                                   {:type "BUYER"       :total 1000.0}]}]}
          [row] (transform/->finance-from-order-stats [order])]
      (is (false? (:price-basis-mismatch? row))
          "equal prices → no mismatch"))))

(deftest price-basis-mismatch-false-when-marketplace-absent
  (testing "Only BUYER price present (no MARKETPLACE entry) → :price-basis-mismatch? false"
    (let [order {:id 12 :status "DELIVERED" :creationDate "2026-04-15"
                 :commissions []
                 :items [{:shopSku "SKU3" :count 1
                          :prices [{:type "BUYER" :total 900.0}]}]}
          [row] (transform/->finance-from-order-stats [order])]
      (is (false? (:price-basis-mismatch? row))
          "missing MARKETPLACE → cannot compare → no flag"))))

(deftest price-basis-mismatch-false-within-epsilon
  (testing "BUYER 1000.005 vs MARKETPLACE 1000.0 — within 0.01 epsilon → false"
    (let [order {:id 13 :status "DELIVERED" :creationDate "2026-04-15"
                 :commissions []
                 :items [{:shopSku "SKU4" :count 1
                          :prices [{:type "MARKETPLACE" :total 1000.0}
                                   {:type "BUYER"       :total 1000.005}]}]}
          [row] (transform/->finance-from-order-stats [order])]
      (is (false? (:price-basis-mismatch? row))
          "difference 0.005 < epsilon 0.01 → no flag"))))
