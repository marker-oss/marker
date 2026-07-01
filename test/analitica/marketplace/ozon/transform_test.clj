(ns analitica.marketplace.ozon.transform-test
  "Per-feature tests for the Ozon postings → sales materializer (M6).

   The legacy ->sale read only `products[0].price` and hardcoded the rest
   to nil, leaving `for_pay`, `finished_price`, `price_with_disc`,
   `subject`, `warehouse` (wrong key), and `brand` NULL on every row.
   These tests pin the real-shape extraction."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.marketplace.ozon.transform :as transform]))

(def ^:private delivered-posting
  "Trimmed-down shape mirroring live Ozon postings response, single product."
  {:posting_number "0001-1"
   :status         "delivered"
   :in_process_at  "2026-04-15T10:00:00Z"
   :products       [{:offer_id "A1"
                     :name     "Платье повседневное"
                     :sku      111
                     :quantity 1
                     :price    "4275"}]
   :financial_data {:products [{:product_id        111
                                :payout            2265.75
                                :customer_price    3140.0
                                :commission_amount 1100.0
                                :price             4275}]}
   :analytics_data {:warehouse "SHEGIDA основной"
                    :region    "Москва"}})

(deftest delivered-posting-uses-delivery-date-end-as-sale-date
  ;; Ozon postings have two relevant dates: in_process_at (when buyer placed
  ;; the order) and analytics_data.delivery_date_end (when goods arrive at
  ;; the buyer). For Pulse "revenue by day" the cash-flow event is delivery,
  ;; not order placement. Old code used in_process_at, attributing March-
  ;; placed Apr-delivered postings to March in Pulse — and missing them
  ;; entirely whenever ingest-ozon-postings! pulled by April-only window.
  (testing ":date comes from delivery_date_end when present"
    (let [posting (-> delivered-posting
                      (assoc :in_process_at "2026-03-28T10:00:00Z")
                      (assoc-in [:analytics_data :delivery_date_end]
                                "2026-04-05T18:00:00Z"))
          [row]   (transform/->sales [posting])]
      (is (= "2026-04-05T18:00:00Z" (:date row))
          ":date must reflect when the goods arrived at the buyer, not
           when the order was placed."))))

(deftest delivered-posting-falls-back-to-in-process-at
  (testing ":date falls back to in_process_at when delivery_date_end is missing"
    (let [posting (assoc-in delivered-posting
                            [:analytics_data :delivery_date_end] nil)
          [row]   (transform/->sales [posting])]
      (is (= "2026-04-15T10:00:00Z" (:date row))
          "When Ozon hasn't populated delivery_date_end (rare for delivered
           postings), the in_process_at field is the safe fallback."))))

(deftest delivered-posting-extracts-financial-data
  (let [[row] (transform/->sales [delivered-posting])]
    (is (= "A1"                  (:article row)))
    (is (= "Платье повседневное" (:subject row)) "products[0].name → :subject")
    (is (= "SHEGIDA основной"    (:warehouse row))
        "warehouse from :warehouse — the legacy code read :warehouse_name and got NULL")
    (is (= "Москва"              (:region row)))
    (is (= "4275"                (:total-price row)) "products[0].price = gross retail")
    (is (= 2265.75               (:for-pay row))
        "financial_data.products[0].payout — what the seller actually receives")
    (is (= 3140.0                (:finished-price row))
        "financial_data.products[0].customer_price — buyer's final paid price")
    (is (= 3140.0                (:price-with-disc row)))
    (is (= :sale                 (:type row)))
    (is (= 1                     (:quantity row)))))

(deftest cancelled-posting-produces-no-sales-rows
  (testing "cancelled postings are not settled events — drop them from sales"
    (let [out (transform/->sales [(assoc delivered-posting :status "cancelled")])]
      (is (empty? out)))))

(deftest in-flight-posting-produces-no-sales-rows
  (testing "delivering / awaiting_packaging — orders not yet settled either way"
    (is (empty? (transform/->sales [(assoc delivered-posting :status "delivering")])))
    (is (empty? (transform/->sales [(assoc delivered-posting :status "awaiting_packaging")])))))

(deftest returned-posting-produces-return-row
  (testing "if Ozon ever surfaces 'returned' status, capture it as :return"
    (let [[row] (transform/->sales [(assoc delivered-posting :status "returned")])]
      (is (= :return (:type row)))
      (is (= 2265.75 (:for-pay row))))))

(deftest multi-product-posting-emits-row-per-product
  (testing "Ozon postings can carry several products; one sales row per product"
    (let [posting (-> delivered-posting
                      (assoc :products
                             [{:offer_id "A" :sku 111 :quantity 1 :price 100}
                              {:offer_id "B" :sku 222 :quantity 2 :price 200}])
                      (assoc-in [:financial_data :products]
                                [{:product_id 111 :payout 80  :customer_price 90}
                                 {:product_id 222 :payout 160 :customer_price 180}]))
          out (transform/->sales [posting])]
      (is (= 2 (count out)))
      (is (= ["A" "B"]            (mapv :article out)))
      (is (= ["0001-1-0" "0001-1-1"] (mapv :sale-id out))
          "sale-id is posting_number-idx so multi-product orders avoid UNIQUE collisions")
      (is (= [80.0 160.0]         (mapv :for-pay out))))))

(deftest financial-data-matched-by-sku-not-just-position
  (testing "If Ozon reorders financial_data.products, match by SKU/product_id"
    (let [posting (-> delivered-posting
                      (assoc :products
                             [{:offer_id "A" :sku 111 :quantity 1 :price 100}
                              {:offer_id "B" :sku 222 :quantity 1 :price 200}])
                      ;; financial_data in REVERSE order from products
                      (assoc-in [:financial_data :products]
                                [{:product_id 222 :payout 160 :customer_price 180}
                                 {:product_id 111 :payout 80  :customer_price 90}]))
          out (transform/->sales [posting])]
      (is (= 80.0  (:for-pay (first  out))) "Article A still gets its 80, not 160")
      (is (= 160.0 (:for-pay (second out))) "Article B gets its 160"))))

(deftest missing-financial-data-degrades-gracefully
  (testing "Posting with no financial_data: row still emitted, payout nil — never silently 0"
    (let [out (transform/->sales [(dissoc delivered-posting :financial_data)])]
      (is (= 1 (count out)))
      (is (nil? (:for-pay (first out))))
      (is (nil? (:finished-price (first out)))))))

(deftest empty-products-array-produces-no-rows
  (testing "Posting with no products array: zero rows (degenerate Ozon edge case)"
    (let [out (transform/->sales [(assoc delivered-posting :products [])])]
      (is (empty? out)))))

;; ---------------------------------------------------------------------------
;; FR-007 — OperationAgentDeliveredToCustomerCanceled must be a return
;; ---------------------------------------------------------------------------

(deftest delivered-canceled-is-return
  ;; before-would-be "sale"
  (testing "OperationAgentDeliveredToCustomerCanceled normalizes to \"return\", not \"sale\""
    (is (= "return"
           (#'analitica.marketplace.ozon.transform/normalize-ozon-operation
            "OperationAgentDeliveredToCustomerCanceled")))))

(def ^:private canceled-op-raw
  "Minimal raw Ozon finance transaction for a Canceled delivery."
  {:operation_id   "op-canceled-1"
   :operation_type "OperationAgentDeliveredToCustomerCanceled"
   :operation_date "2026-04-10 00:00:00"
   :amount         -1500.0
   :accruals_for_sale 1500.0
   :sale_commission -200.0
   :delivery_charge 0
   :items          [{:sku 111 :quantity 1}]
   :services       []
   :type           "transaction"})

(def ^:private client-return-op-raw
  "Minimal raw Ozon finance transaction for a ClientReturnAgentOperation."
  {:operation_id   "op-return-2"
   :operation_type "ClientReturnAgentOperation"
   :operation_date "2026-04-11 00:00:00"
   :amount         -1500.0
   :accruals_for_sale 1500.0
   :sale_commission -200.0
   :delivery_charge 0
   :items          [{:sku 111 :quantity 1}]
   :services       []
   :type           "transaction"})

(deftest canceled-no-double-count-with-return
  ;; Verify that one Canceled op → one return line, and a separate
  ;; ClientReturnAgentOperation (different operation_id) → its own return line.
  ;; Neither is dropped, merged, or produces a sale line. No double-counting.
  (testing "two distinct raw ops each produce exactly one return finance-line"
    (let [sku-map {111 "ART-001"}
          lines   (transform/->finance-report
                   [canceled-op-raw client-return-op-raw]
                   sku-map)]
      (is (= 2 (count lines))
          "two ops → two finance lines, no merging")
      (is (every? #(= "return" (:operation %)) lines)
          "both lines must be :operation \"return\"")
      (is (= #{"op-canceled-1" "op-return-2"}
             (set (map :rrd-id lines)))
          "each line carries its own distinct rrd-id (operation_id), no merging"))))
