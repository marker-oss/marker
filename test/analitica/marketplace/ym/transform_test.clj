(ns analitica.marketplace.ym.transform-test
  "Unit tests for YM transform layer.

   US1 (spec 003-finance-row-completeness) introduces per-item
   operation classification: item-level :itemStatus takes priority
   over order-level :status. This namespace exercises the full 9-row
   matrix from data-model.md §4."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
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

;; ===========================================================================
;; Spec 012 — YM revenue / for-pay price-basis alignment (US1)
;;
;; CORRECTED basis (owner-approved 2026-07-01, after ground-truth reconciliation
;; against the TrueStats anchor). The ORIGINAL spec said gross = MARKETPLACE —
;; DISPROVED: the YM MARKETPLACE price field is a per-item SUBSIDY proxy, not a
;; pre-discount price. Resolved formula:
;;   net-sales     = BUYER × qty                         (== TS `sales`)
;;   subsidy       = Σ SUBSIDY-type ACCRUAL (dedup'd), split per-item
;;   gross         = (BUYER + subsidy) × qty  -> :retail-amount  (== TS `realisation`)
;;   :retail-price = BUYER + subsidy (per-unit)
;;   for_pay       = BUYER − all-comm  (subsidy is a bridge, NOT payout income)
;;                   sale branch: no Math/abs (negative allowed); return: +abs; adjustment: 0.0
;; ===========================================================================

(def ^:private basis-fixture
  (delay (edn/read-string (slurp "test/resources/ym/ym-orders-basis.edn"))))

(defn- fixture-rows []
  (transform/->finance-from-order-stats @basis-fixture))

(defn- row-for [rows sku]
  (first (filter #(= sku (:article %)) rows)))

;; ---- T010: for_pay ≤ gross for every SKU (INV-1 / FR-006 / SC-001) --------

(deftest for-pay-le-gross-per-sku
  (testing "every finance row: :for-pay ≤ :retail-amount (gross) — hard invariant"
    (let [rows (fixture-rows)]
      (is (seq rows) "fixture must produce rows")
      (doseq [row rows]
        (is (<= (double (:for-pay row)) (double (:retail-amount row)))
            (str "SKU " (:article row)
                 " for-pay " (:for-pay row)
                 " must be ≤ gross " (:retail-amount row)))))))

;; ---- T011: negative payout allowed (INV-2 / FR-005 / SC-006) --------------

(deftest negative-payout-allowed
  (testing "loss-making SKU (BUYER − commissions < 0) → sale row :for-pay < 0, NOT Math/abs'd"
    (let [row (row-for (fixture-rows) "LOSS-B")]
      (is (some? row) "loss-making fixture row present")
      (is (= :sale (:operation-kind row)))
      ;; BUYER 2544 − (FEE 900 + PAYMENT_TRANSFER 213 + DELIVERY 2000) = -569.0
      (is (neg? (double (:for-pay row)))
          (str ":for-pay must be negative (real cash impact), got " (:for-pay row)))
      (is (< (double (:for-pay row)) -500.0)
          "≈ -569, confirms no Math/abs clamp"))))

;; ---- T012: subsidy is a bridge, not payout income (INV-3 / FR-004) --------

(deftest subsidy-is-bridge-not-income
  (testing "subsidy NOT added to for_pay; gross = net + subsidy (net + subsidy ≈ retail-amount)"
    (let [row (row-for (fixture-rows) "SUB-A")]
      (is (some? row))
      ;; net = BUYER 3741, subsidy 2804, gross should be 6545.
      (is (= 3741.0 (double (:net-sales row))) "net-sales = BUYER × qty")
      (is (= 6545.0 (double (:retail-amount row))) "gross = (BUYER + subsidy) × qty")
      (is (<= (Math/abs (- (+ (double (:net-sales row)) 2804.0)
                           (double (:retail-amount row))))
              0.01)
          "bridge closes: net + subsidy ≈ gross")
      ;; for_pay = BUYER − all-comm (non-ad). Subsidy must NOT inflate it.
      ;; all-comm = 756.19 + 104.72 + 0.12 + 425.25 = 1286.28 → for_pay ≈ 2454.72.
      (is (<= (Math/abs (- (double (:for-pay row)) 2454.72)) 0.01)
          (str "for_pay = BUYER − all-comm (subsidy excluded), got " (:for-pay row)))
      (is (< (double (:for-pay row)) (double (:net-sales row)))
          "for_pay < net (subsidy did not lift payout above buyer-paid)"))))

;; ---- T013: dedup subsidies (INV-4 / FR-007 / FR-008) ----------------------

(deftest dedup-subsidies
  (testing "4 ACCRUAL entries (2× SUBSIDY dup + 2× YANDEX_CASHBACK) → each logical subsidy once"
    ;; DEDUP-C: BUYER 2000, subsidies 2×{SUBSIDY ACCRUAL 1000} + 2×{CASHBACK ACCRUAL 150}.
    ;; dedup by (type, operationType) FIRST-per-group → one SUBSIDY/ACCRUAL 1000.
    ;; Cashback is NOT part of gross subsidy. gross = 2000 + 1000 = 3000.
    (let [row (row-for (fixture-rows) "DEDUP-C")]
      (is (some? row))
      (is (= 2000.0 (double (:net-sales row))) "net = BUYER × qty")
      (is (= 3000.0 (double (:retail-amount row)))
          "gross = BUYER + ONE deduped SUBSIDY (1000), not 2×1000 nor cashback-inflated"))
    ;; Direct helper check on the raw subsidies vector.
    (let [subs [{:amount 1000.0 :type "SUBSIDY" :operationType "ACCRUAL"}
                {:amount 1000.0 :type "SUBSIDY" :operationType "ACCRUAL"}
                {:amount 150.0 :type "YANDEX_CASHBACK" :operationType "ACCRUAL"}
                {:amount 150.0 :type "YANDEX_CASHBACK" :operationType "ACCRUAL"}]
          deduped (#'transform/dedup-subsidies subs)]
      ;; deduped total (SUBSIDY only, first-per-group) = 1000.
      (is (= 1000.0 (double deduped))
          "dedup-subsidies counts one SUBSIDY/ACCRUAL, excludes cashback from gross bridge"))))

;; ---- T014: gross = BUYER + subsidy, net = BUYER (R4 / FR-001 / FR-002) -----
;; ⚠️ Supersedes the OLD `sale-row-extracts-nested-prices` expectation that
;;    :retail-amount = BUYER. That was against the legacy ->sales path AND is
;;    now the wrong basis for the finance path. This is the finance-path test.

(deftest gross-is-buyer-plus-subsidy-net-is-buyer
  (testing "BUYER + subsidy = gross; BUYER = net; retail-price = BUYER + subsidy per-unit"
    (let [row (row-for (fixture-rows) "SUB-A")]
      (is (some? row))
      (is (= 3741.0 (double (:net-sales row)))    ":net-sales = BUYER × qty")
      (is (= 6545.0 (double (:retail-amount row))) ":retail-amount = (BUYER + subsidy) × qty")
      (is (= 6545.0 (double (:retail-price row)))  ":retail-price = BUYER + subsidy per-unit (qty=1)"))))

;; ---- T015: missing subsidy → gross = BUYER (no inflation) -----------------

(deftest missing-subsidy-gross-is-buyer
  (testing "order with no subsidy → gross = BUYER (no inflation); invariant holds"
    (let [row (row-for (fixture-rows) "NOSUB-D")]
      (is (some? row))
      (is (= 1500.0 (double (:net-sales row))))
      (is (= 1500.0 (double (:retail-amount row))) "no subsidy → gross == BUYER")
      (is (= 1500.0 (double (:retail-price row))))
      (is (<= (double (:for-pay row)) (double (:retail-amount row)))
          "invariant for_pay ≤ gross still holds"))))

;; ---- T016: adjustment (cancelled) → for_pay 0.0 ---------------------------

(deftest adjustment-row-zero-payout
  (testing "CANCELLED_BEFORE_PROCESSING → :operation-kind :adjustment, :for-pay 0.0"
    (let [row (row-for (fixture-rows) "CANC-E")]
      (is (some? row))
      (is (= :adjustment (:operation-kind row)))
      (is (= 0.0 (double (:for-pay row))) "cancelled → payout clamped to 0.0"))))

;; ---- count>1 guard: ×qty applied exactly once -----------------------------

(deftest multi-count-qty-applied-once
  (testing "count=2 order → gross/net scale by qty exactly once (not double-applied)"
    (let [order {:id 900010 :status "DELIVERED" :creationDate "2026-04-12"
                 :statusUpdateDate "2026-04-22T10:00:00.000+03:00"
                 :subsidies [{:amount 500.0 :type "SUBSIDY" :operationType "ACCRUAL"}]
                 :commissions [{:type "FEE" :actual 100.0}]
                 :items [{:shopSku "QTY2" :count 2
                          :prices [{:type "MARKETPLACE" :costPerItem 500.0 :total 1000.0}
                                   {:type "BUYER" :costPerItem 1000.0 :total 2000.0}]}]}
          [row] (transform/->finance-from-order-stats [order])]
      ;; price-by-type returns :total (line total = 2000 for BUYER, i.e. already ×qty).
      ;; The corrected block must NOT multiply the line-total by qty again.
      ;; net = BUYER line-total 2000; subsidy 500 (order-level, added once).
      (is (= 2 (:quantity row)))
      (is (= 2000.0 (double (:net-sales row)))
          "net = BUYER :total (line-total), qty not double-applied")
      (is (= 2500.0 (double (:retail-amount row)))
          "gross = BUYER line-total 2000 + subsidy 500 = 2500")
      (is (<= (double (:for-pay row)) (double (:retail-amount row)))))))

;; ---- T025 (US3): event-date is statusUpdateDate-based, unmoved by basis ----
;; INV-8: the corrected monetary block must not shift :event-date. event-date
;; is derived from statusUpdateDate (subs 0..10); changing the monetary inputs
;; (prices/subsidy/commissions) leaves it identical, so re-materialization does
;; NOT move rows between months.

(deftest event-date-unchanged
  (testing ":event-date = statusUpdateDate[0..10] on the basis fixture (SUB-A April 30)"
    (let [rows (transform/->finance-from-order-stats
                (edn/read-string (slurp "test/resources/ym/ym-orders-basis.edn")))
          by-sku (into {} (map (juxt :article identity) rows))]
      ;; Fixture statusUpdateDate values → expected event-date per SKU.
      (is (= "2026-04-30" (:event-date (get by-sku "SUB-A"))))
      (is (= "2026-04-20" (:event-date (get by-sku "LOSS-B"))))
      (is (= "2026-04-15" (:event-date (get by-sku "DEDUP-C"))))
      (is (= "2026-04-18" (:event-date (get by-sku "NOSUB-D"))))
      (is (= "2026-04-30" (:event-date (get by-sku "CANC-E"))))))

  (testing "changing the monetary block (prices/subsidy/commissions) does not move event-date"
    (let [base  {:id 700001 :status "DELIVERED" :creationDate "2026-04-01"
                 :statusUpdateDate "2026-04-25T14:00:00.000+03:00"
                 :subsidies [{:amount 300.0 :type "SUBSIDY" :operationType "ACCRUAL"}]
                 :commissions [{:type "FEE" :actual 100.0}]
                 :items [{:shopSku "ED-1" :count 1
                          :prices [{:type "BUYER" :total 1000.0}
                                   {:type "MARKETPLACE" :total 300.0}]}]}
          ;; a second order with a WILDLY different monetary profile but the
          ;; SAME statusUpdateDate — event-date must be identical.
          bumped (-> base
                     (assoc :subsidies [{:amount 9999.0 :type "SUBSIDY" :operationType "ACCRUAL"}])
                     (assoc :commissions [{:type "FEE" :actual 5000.0}])
                     (assoc-in [:items 0 :prices]
                               [{:type "BUYER" :total 8000.0}
                                {:type "MARKETPLACE" :total 4000.0}]))
          [r1] (transform/->finance-from-order-stats [base])
          [r2] (transform/->finance-from-order-stats [bumped])]
      (is (= "2026-04-25" (:event-date r1)))
      (is (= (:event-date r1) (:event-date r2))
          "event-date is independent of the monetary basis (statusUpdateDate-based)")
      ;; sanity: the monetary values DID change (so the test isn't vacuous).
      (is (not= (:retail-amount r1) (:retail-amount r2))))))
