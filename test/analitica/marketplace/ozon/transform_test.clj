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
