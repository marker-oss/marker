(ns analitica.ozon-transform-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [analitica.marketplace.ozon.transform :as transform]))

;; ---------------------------------------------------------------------------
;; Sale-type classification (P0-B, specs/010) — awaiting_deliver is in-flight
;; ---------------------------------------------------------------------------

(deftest awaiting-deliver-is-in-flight-not-sale
  ;; A not-yet-delivered FBS posting must never count as a realized buyout.
  ;; Must match domain.order-status, which treats only "delivered" as delivered.
  (is (= :in-flight (#'transform/sale-type "awaiting_deliver")))
  (is (= :sale      (#'transform/sale-type "delivered")))
  (is (= :return    (#'transform/sale-type "returned")))
  (is (= :cancelled (#'transform/sale-type "cancelled"))))

;; ---------------------------------------------------------------------------
;; Generators
;; ---------------------------------------------------------------------------

(def raw-map-gen
  "Generator for arbitrary raw API response maps."
  (gen/map gen/keyword gen/any))

(def raw-list-gen
  "Generator for a list of arbitrary raw API response maps."
  (gen/list raw-map-gen))

(def analytics-response-gen
  "Generator for analytics-data response format used by ->product-stats."
  (gen/let [rows (gen/list (gen/map gen/keyword gen/any))]
    {:result {:data rows}}))

;; ---------------------------------------------------------------------------
;; Property 1: marketplace field invariant (Ozon)
;; Validates: Requirements 5.7, 12.1
;; Tag: Feature: ozon-ym-marketplace-integration, Property 1: marketplace field invariant ozon
;; ---------------------------------------------------------------------------

(defspec ozon-marketplace-field-invariant 10
  ;; **Validates: Requirements 5.7, 12.1**
  (prop/for-all [raw-list raw-list-gen]
    (let [check-all-ozon (fn [maps]
                           (every? #(= :ozon (:marketplace %)) maps))
          sku-map {}]  ; Empty SKU map for testing
      (and (check-all-ozon (transform/->orders raw-list))
           (check-all-ozon (transform/->sales raw-list))
           (check-all-ozon (transform/->stocks raw-list))
           (check-all-ozon (transform/->prices raw-list))
           (check-all-ozon (transform/->finance-report raw-list sku-map))))))

;; ---------------------------------------------------------------------------
;; Property 2: length preservation (Ozon)
;; Validates: Requirements 12.3
;; Tag: Feature: ozon-ym-marketplace-integration, Property 2: length preservation ozon
;; ---------------------------------------------------------------------------

(defspec ozon-length-preservation 50
  ;; **Validates: Requirements 12.3**
  ;; Tag: Feature: ozon-ym-marketplace-integration, Property 2: length preservation ozon transform
  (prop/for-all [raw-list (gen/list (gen/map gen/keyword gen/any-printable))]
    (let [n (count raw-list)]
      (and (= n (count (transform/->orders raw-list)))
           (= n (count (transform/->stocks raw-list)))
           (= n (count (transform/->prices raw-list)))))))

;; ---------------------------------------------------------------------------
;; Property 3: required order keys (Ozon)
;; Validates: Requirements 5.1, 12.5
;; Tag: Feature: ozon-ym-marketplace-integration, Property 3: required order keys ozon
;; ---------------------------------------------------------------------------

(def required-order-keys
  #{:order-id :date :article :quantity :price :status :marketplace})

(defspec ozon-required-order-keys 50
  ;; **Validates: Requirements 5.1, 12.5**
  ;; Tag: Feature: ozon-ym-marketplace-integration, Property 3: required order keys ozon
  (prop/for-all [raw-list raw-list-gen]
    (every? (fn [order]
              (every? #(contains? order %) required-order-keys))
            (transform/->orders raw-list))))

;; ---------------------------------------------------------------------------
;; Property 4: required finance-line keys (Ozon)
;; Validates: Requirements 5.4, 12.6
;; Tag: Feature: ozon-ym-marketplace-integration, Property 4: required finance-line keys ozon
;; ---------------------------------------------------------------------------

(def required-finance-keys
  #{:date-from :date-to :article :for-pay :marketplace})

(defspec ozon-required-finance-line-keys 50
  ;; **Validates: Requirements 5.4, 12.6**
  ;; Tag: Feature: ozon-ym-marketplace-integration, Property 4: required finance-line keys ozon
  (prop/for-all [raw-list raw-list-gen]
    (let [sku-map {}]  ; Empty SKU map for testing
      (every? (fn [line]
                (every? #(contains? line %) required-finance-keys))
              (transform/->finance-report raw-list sku-map)))))

;; ---------------------------------------------------------------------------
;; Property 7: nil-field resilience (Ozon)
;; Validates: Requirements 5.8
;; Tag: Feature: ozon-ym-marketplace-integration, Property 7: nil-field resilience ozon
;; ---------------------------------------------------------------------------

(def nil-map-gen
  "Generator for a map where each value is either nil or absent (sparse map)."
  (gen/let [ks (gen/set gen/keyword {:max-elements 10})]
    (into {} (map (fn [k] [k nil]) ks))))

(def nil-list-gen
  "Generator for a list of nil-field maps."
  (gen/list nil-map-gen))

(defspec ozon-nil-field-resilience 50
  ;; **Validates: Requirements 5.8**
  ;; Tag: Feature: ozon-ym-marketplace-integration, Property 7: nil-field resilience ozon
  (prop/for-all [raw-list nil-list-gen]
    (try
      (let [sku-map {}  ; Empty SKU map for testing
            orders   (transform/->orders raw-list)
            sales    (transform/->sales raw-list)
            stocks   (transform/->stocks raw-list)
            prices   (transform/->prices raw-list)
            finance  (transform/->finance-report raw-list sku-map)]
        ;; All results must be maps (not thrown)
        (and (every? map? orders)
             (every? map? sales)
             (every? map? stocks)
             (every? map? prices)
             (every? map? finance)))
      (catch Exception _e false))))

;; ---------------------------------------------------------------------------
;; Realization seller-payout: bank_coinvestment + pick_up_point_coinvestment
;; Added Dec 2024 by Ozon; verified present on prod realization rows 2026-03.
;; Regression guard — dropping these fields silently understates :for-pay.
;; ---------------------------------------------------------------------------

(def ^:private rrow-with-coinvestment
  {:rows [{:item  {:offer_id "SKU-A" :sku "111" :barcode "BC-A"}
           :seller_price_per_instance 1000.0
           :delivery_commission
           {:amount                      1272.38
            :bonus                       1604.90
            :compensation                   0.00
            :stars                          0.00
            :bank_coinvestment             12.72
            :pick_up_point_coinvestment     5.00
            :quantity                       1
            :total                       2895.00
            :standard_fee                 400.00
            :price_per_instance          1000.0}
           :return_commission {:amount 0 :quantity 0 :total 0}}]
   :start_date "2026-03-01"
   :stop_date  "2026-03-31"})

(deftest realization-for-pay-includes-coinvestment-fields
  (let [rows (transform/->finance-from-realization rrow-with-coinvestment)
        sale (first (filter #(= "sale" (:operation %)) rows))]
    ;; amount 1272.38 + bonus 1604.90 + bank_coinvestment 12.72
    ;; + pick_up_point_coinvestment 5.00 = 2895.00
    (is (= 2895.00 (:for-pay sale))
        ":for-pay must include both coinvestment fields (Dec 2024 Ozon API)")))

;; ---------------------------------------------------------------------------
;; Realization commission/retail semantics — verified against LK Apr 2026
;;
;; Ozon's realization-row delivery_commission shape (verified on 1620 rows):
;;   amount + bonus + bank_coinvestment + pick_up_point_coinvestment  ≈
;;     seller_price_per_instance × quantity   (gross seller revenue)
;;   standard_fee × quantity = Ozon commission deducted from gross
;;   total = seller_price - standard_fee per unit (net after commission)
;;
;; LK «Доставка покупателю» row aggregate Apr 2026:
;;   col [9]  «За продажу до вычета комиссий» = 522,986   ← gross
;;   col [11] «Вознаграждение Ozon»           = -243,540  ← commission
;; Both confirmed against SUM(seller_price × q) and SUM(standard_fee × q)
;; respectively across raw realization rows.
;;
;; Old transform used `total` for retail_amount and computed mp_commission
;; as max(0, q*total - payout) which collapses to 0 because payout = gross
;; (bonus subsidies bring net cash up to gross). That hides commission from
;; P&L and makes K-перечислению overstated by ~commission.
;; ---------------------------------------------------------------------------

(def ^:private rrow-with-clear-commission
  "Realistic single row matching prod shape (April 2026 raw data sample).
   seller_price 3581, standard_fee 1539.83, payout = gross by design."
  {:rows [{:item  {:offer_id "SKU-B" :sku "222" :barcode "BC-B"}
           :seller_price_per_instance 3581
           :delivery_commission
           {:amount                      1755.24
            :bonus                       1808.21
            :bank_coinvestment             17.55
            :pick_up_point_coinvestment     0
            :stars                          0
            :compensation                   0
            :commission                     0
            :quantity                       1
            :total                       2041.17  ;; = seller_price - standard_fee
            :standard_fee                1539.83
            :price_per_instance          1755.24}
           :return_commission nil}]
   :start_date "2026-04-01"
   :stop_date  "2026-04-30"})

(deftest realization-mp-commission-from-standard-fee
  (let [[sale] (transform/->finance-from-realization rrow-with-clear-commission)]
    (is (= 1539.83 (:mp-commission sale))
        "mp_commission = standard_fee × quantity (Ozon's gross commission deduction).
         LK «Вознаграждение Ozon» column equals SUM(standard_fee × q) row-by-row,
         not max(0, q*total − payout) which collapses to 0 in promo periods.")))

(deftest realization-retail-amount-equals-gross-seller-price
  (let [[sale] (transform/->finance-from-realization rrow-with-clear-commission)]
    (is (= 3581.0 (double (:retail-amount sale)))
        "retail_amount = q × seller_price_per_instance (gross sale).
         Old code used q × total which gives net-after-commission and
         under-reports gross revenue by exactly the commission amount.")))

(def ^:private rrow-with-return
  "Single-unit return only (no sale half). standard_fee 100, gross 250.
   Verifies return rows compute mp_commission the same way as sales —
   refund of Ozon's commission when goods come back."
  {:rows [{:item  {:offer_id "SKU-R" :sku "333" :barcode "BC-R"}
           :seller_price_per_instance 250
           :delivery_commission nil
           :return_commission
           {:amount    150
            :bonus     100
            :quantity   1
            :total     150
            :standard_fee 100}}]
   :start_date "2026-04-01"
   :stop_date  "2026-04-30"})

(deftest realization-return-row-also-uses-standard-fee
  (let [[ret] (transform/->finance-from-realization rrow-with-return)]
    (is (= "return" (:operation ret)))
    (is (= 100.0 (double (:mp-commission ret)))
        "Return row mp_commission = standard_fee × q (positive, sign applied
         by downstream by-operation-kind aggregation).")
    (is (= 250.0 (double (:retail-amount ret)))
        "Return row retail_amount = q × seller_price_per_instance.")))

