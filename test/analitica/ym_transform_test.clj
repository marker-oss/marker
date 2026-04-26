(ns analitica.ym-transform-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [analitica.marketplace.ym.transform :as transform]))

;; ---------------------------------------------------------------------------
;; Generators
;; ---------------------------------------------------------------------------

(def raw-map-gen
  "Generator for arbitrary raw API response maps."
  (gen/map gen/keyword gen/any))

(def raw-list-gen
  "Generator for a list of arbitrary raw API response maps."
  (gen/list raw-map-gen))

;; ---------------------------------------------------------------------------
;; Property 1: marketplace field invariant (YM)
;; Validates: Requirements 6.7, 12.2
;; Tag: Feature: ozon-ym-marketplace-integration, Property 1: marketplace field invariant ym
;; ---------------------------------------------------------------------------

(defspec ym-marketplace-field-invariant 50
  ;; **Validates: Requirements 6.7, 12.2**
  (prop/for-all [raw-list raw-list-gen]
    (let [check-all-ym (fn [maps]
                         (every? #(= :ym (:marketplace %)) maps))]
      (and (check-all-ym (transform/->orders raw-list))
           (check-all-ym (transform/->sales raw-list))
           (check-all-ym (transform/->stocks raw-list))
           (check-all-ym (transform/->prices raw-list))
           (check-all-ym (transform/->finance-report raw-list))))))

;; ---------------------------------------------------------------------------
;; Property 2: length preservation (YM)
;; Validates: Requirements 12.4
;; Tag: Feature: ozon-ym-marketplace-integration, Property 2: length preservation ym transform
;; ---------------------------------------------------------------------------

(defspec ym-length-preservation 50
  ;; **Validates: Requirements 12.4**
  ;; Note: ->stocks is NOT length-preserving per-input — it takes a list of
  ;; warehouses and flattens offers/stocks inside, so it's excluded here.
  ;; Only ->orders and ->prices are simple row-per-input transforms.
  (prop/for-all [raw-list (gen/list (gen/map gen/keyword gen/any-printable))]
    (let [n (count raw-list)]
      (and (= n (count (transform/->orders raw-list)))
           (= n (count (transform/->prices raw-list)))))))

;; ---------------------------------------------------------------------------
;; Property 3: required order keys (YM)
;; Validates: Requirements 6.1, 12.5
;; Tag: Feature: ozon-ym-marketplace-integration, Property 3: required order keys ym
;; ---------------------------------------------------------------------------

(defspec ym-required-order-keys 50
  ;; **Validates: Requirements 6.1, 12.5**
  (prop/for-all [raw-list raw-list-gen]
    (let [required-keys #{:order-id :date :article :quantity :price :status :marketplace}]
      (every? #(every? (fn [k] (contains? % k)) required-keys)
              (transform/->orders raw-list)))))

;; ---------------------------------------------------------------------------
;; Property 4: required finance-line keys (YM)
;; Validates: Requirements 6.4, 12.6
;; Tag: Feature: ozon-ym-marketplace-integration, Property 4: required finance-line keys ym
;; ---------------------------------------------------------------------------

(defspec ym-required-finance-line-keys 50
  ;; **Validates: Requirements 6.4, 12.6**
  (prop/for-all [raw-list raw-list-gen]
    (let [required-keys #{:date-from :date-to :article :for-pay :marketplace}]
      (every? #(every? (fn [k] (contains? % k)) required-keys)
              (transform/->finance-report raw-list)))))

;; ---------------------------------------------------------------------------
;; Property 5: YM sale type mapping
;; Validates: Requirements 6.2, 8.5
;; Tag: Feature: ozon-ym-marketplace-integration, Property 5: ym sale type mapping
;; ---------------------------------------------------------------------------

(def return-status-gen
  (gen/elements ["RETURNED" "PARTIALLY_RETURNED"]))

;; Per M5: ->sales now emits item-level rows and drops cancelled events
;; entirely. Non-return statuses that still produce a `:sale` row are the
;; positive delivery confirmations. CANCELLED_* and PROCESSING/DELIVERY
;; produce zero rows now and are tested separately in
;; analitica.marketplace.ym.transform-test/cancelled-order-produces-no-sales-rows.
(def settled-sale-status-gen
  (gen/elements ["DELIVERED" "PARTIALLY_DELIVERED" "PICKUP"]))

(defspec ym-sale-type-return-mapping 50
  ;; **Validates: Requirements 6.2, 8.5** — RETURNED/PARTIALLY_RETURNED → :return.
  ;; Force at least one item with no :details so the order-level fallback in
  ;; classify-item-operation fires and a row is emitted.
  (prop/for-all [status return-status-gen
                 base-map raw-map-gen]
    (let [raw (assoc base-map :status status :items [{:shopSku "X" :count 1}])
          result (first (transform/->sales [raw]))]
      (= :return (:type result)))))

(defspec ym-sale-type-sale-mapping 50
  ;; **Validates: Requirements 6.2, 8.5** — settled delivery statuses → :sale.
  (prop/for-all [status settled-sale-status-gen
                 base-map raw-map-gen]
    (let [raw (assoc base-map :status status :items [{:shopSku "X" :count 1}])
          result (first (transform/->sales [raw]))]
      (= :sale (:type result)))))

;; ---------------------------------------------------------------------------
;; Property 7: nil-field resilience (YM)
;; Validates: Requirements 6.8
;; Tag: Feature: ozon-ym-marketplace-integration, Property 7: nil-field resilience ym
;; ---------------------------------------------------------------------------

(def sparse-map-gen
  "Generator for maps where values may be nil."
  (gen/map gen/keyword (gen/one-of [gen/any (gen/return nil)])))

(def sparse-list-gen
  (gen/list sparse-map-gen))

(defspec ym-nil-field-resilience 50
  ;; **Validates: Requirements 6.8**
  (prop/for-all [raw-list sparse-list-gen]
    (try
      (transform/->orders raw-list)
      (transform/->sales raw-list)
      (transform/->stocks raw-list)
      (transform/->prices raw-list)
      (transform/->finance-report raw-list)
      true
      (catch Exception _ false))))
