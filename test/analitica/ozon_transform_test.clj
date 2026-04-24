(ns analitica.ozon-transform-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [analitica.marketplace.ozon.transform :as transform]))

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

