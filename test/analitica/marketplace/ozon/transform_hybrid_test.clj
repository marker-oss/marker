(ns analitica.marketplace.ozon.transform-hybrid-test
  "Unit tests for US3A Ozon hybrid transform — transaction-list
   operations[].services[] → FinanceRow per-article attribution.

   Covers:
   - T017 service-name → FinanceRow field mapping (18 types)
   - T018 basic tx-op → service-rows
   - T019 Marketing operation → :ad-cost via ozon-operation-mapping
   - T020 skip conditions (compensation / no posting_number / orphan sku)
   - T021 multi-item distribution (price×quantity weights + 50/50 fallback)

   See specs/003-finance-row-completeness/data-model.md §3."
  (:require [clojure.test :refer [deftest testing is]]
            [analitica.marketplace.ozon.transform :as transform]))

;; ---------------------------------------------------------------------------
;; T017: service-name mapping
;; ---------------------------------------------------------------------------

(deftest service-mapping-has-19-entries-covering-all-fields
  (testing "mapping table contains exactly the documented service names"
    (is (= 19 (count transform/ozon-service-mapping)))))

(deftest delivery-cost-services-map-correctly
  (testing "10 delivery/logistics services → :delivery-cost"
    (is (= :delivery-cost (transform/ozon-service-mapping
                            "MarketplaceServiceItemDirectFlowLogistic")))
    (is (= :delivery-cost (transform/ozon-service-mapping
                            "MarketplaceServiceItemRedistributionLastMileCourier")))
    (is (= :delivery-cost (transform/ozon-service-mapping
                            "MarketplaceServiceItemReturnFlowLogistic")))
    (is (= :delivery-cost (transform/ozon-service-mapping
                            "MarketplaceServiceItemRedistributionReturnsPVZ")))
    (is (= :delivery-cost (transform/ozon-service-mapping
                            "MarketplaceServiceItemDropoffSC")))
    (is (= :delivery-cost (transform/ozon-service-mapping
                            "MarketplaceServiceItemDropoffPVZ")))
    (is (= :delivery-cost (transform/ozon-service-mapping
                            "MarketplaceServiceItemRedistributionDropOffApvz")))
    (is (= :delivery-cost (transform/ozon-service-mapping
                            "MarketplaceServiceItemDeliveryToHandoverPlaceOzon")))
    (is (= :delivery-cost (transform/ozon-service-mapping
                            "MarketplaceServiceItemPackageRedistribution")))
    (is (= :delivery-cost (transform/ozon-service-mapping
                            "MarketplaceServiceProductMovementFromWarehouse")))))

(deftest temporary-storage-service-maps-to-storage-fee
  (testing "MarketplaceServiceItemTemporaryStorage (no Redistribution suffix) is
            also storage. Apr 2026 LK had 204 ₽ missing because we only mapped
            the Redistribution variant."
    (is (= :storage-fee (transform/ozon-service-mapping
                          "MarketplaceServiceItemTemporaryStorage")))))

(deftest acquiring-acceptance-storage-services-map-correctly
  (testing "specialised single-field services"
    (is (= :acquiring-fee (transform/ozon-service-mapping
                            "MarketplaceRedistributionOfAcquiringOperation")))
    (is (= :acceptance (transform/ozon-service-mapping
                         "MarketplaceServiceItemPackageMaterialsProvision")))
    (is (= :storage-fee (transform/ozon-service-mapping
                          "MarketplaceServiceItemTemporaryStorageRedistribution")))))

(deftest additional-payment-services-map-correctly
  (testing "5 residual services → :additional-payment"
    (is (= :additional-payment (transform/ozon-service-mapping
                                 "ItemAgentServiceStarsMembership")))
    (is (= :additional-payment (transform/ozon-service-mapping
                                 "MarketplaceServiceSellerReturnsCargoAssortment")))
    (is (= :additional-payment (transform/ozon-service-mapping
                                 "MarketplaceServiceItemReturnNotDelivToCustomer")))
    (is (= :additional-payment (transform/ozon-service-mapping
                                 "MarketplaceServiceItemReturnAfterDelivToCustomer")))
    (is (= :additional-payment (transform/ozon-service-mapping
                                 "MarketplaceServiceItemReturnPartGoodsCustomer")))))

(deftest classify-known-services-returns-correct-field
  (testing "classify-ozon-service returns mapped field for known names"
    (is (= :delivery-cost (transform/classify-ozon-service
                            "MarketplaceServiceItemDirectFlowLogistic")))
    (is (= :acquiring-fee (transform/classify-ozon-service
                            "MarketplaceRedistributionOfAcquiringOperation")))))

(deftest unknown-service-falls-back-to-additional-payment-with-log
  (testing "unknown service-name falls back to :additional-payment (with mu/log)"
    (is (= :additional-payment
           (transform/classify-ozon-service "NotAKnownService"))))
  (testing "nil service-name also falls back to :additional-payment"
    (is (= :additional-payment (transform/classify-ozon-service nil)))))

(deftest operation-type-mapping-marketing-to-ad-cost
  (testing "MarketplaceMarketingActionCostOperation → :ad-cost"
    (is (= :ad-cost
           (transform/ozon-operation-mapping
             "MarketplaceMarketingActionCostOperation")))))

;; ---------------------------------------------------------------------------
;; T018: basic tx-op → service-rows
;; ---------------------------------------------------------------------------

(def ^:private single-item-op
  "Synthetic operation with one item, one service → one row expected."
  {:operation_id 111
   :operation_type "OperationAgentDeliveredToCustomer"
   :operation_date "2026-03-15 10:00:00"
   :type "services"
   :amount -50.0
   :items [{:sku 1 :name "A" :price 500 :quantity 1}]
   :services [{:name "MarketplaceServiceItemDirectFlowLogistic" :price -50}]
   :posting {:posting_number "P1" :delivery_schema "FBO"}})

(deftest basic-tx-op-single-item-single-service
  (testing "one item + one service → one finance-row with :delivery-cost 50"
    (let [rows (transform/tx-op->service-rows single-item-op {1 "ART-A"})]
      (is (= 1 (count rows)))
      (let [row (first rows)]
        (is (= :ozon (:marketplace row)))
        (is (= "ART-A" (:article row)))
        (is (= 50.0 (:delivery-cost row))
            "service price sign is stripped — stored as positive 50.0")
        (is (= "2026-03-01" (:date-from row))
            "date-from derived to YYYY-MM first day")
        (is (= "2026-03-01" (:date-to row))
            "date-to same as date-from for monthly attribution")
        (is (some? (:rrd-id row)) "rrd-id populated")))))

(deftest service-refund-row-stored-as-negative-cost
  ;; Ozon convention: service.price < 0 → seller charged (cost), > 0 → refund.
  ;; Old transform Math/abs'd both, collapsing refunds INTO charges → costs
  ;; double-counted. LK Apr 2026 acquiring net = -3,054 ₽; we got +6,141 ₽
  ;; before this fix because refund 1,544 ₽ added instead of subtracted.
  (testing "positive service.price (refund) is stored as NEGATIVE cost so it
            offsets earlier positive charges in aggregate sums"
    (let [refund-op (-> single-item-op
                        (assoc :operation_id 112)
                        (assoc :services [{:name "MarketplaceServiceItemDirectFlowLogistic"
                                           :price 50}]))     ;; positive = refund
          rows (transform/tx-op->service-rows refund-op {1 "ART-A"})]
      (is (= 1 (count rows)))
      (is (= -50.0 (:delivery-cost (first rows)))
          "Refund of 50 ₽ from Ozon must store as -50 cost (revenue),
           not +50 like a charge."))))

(deftest charge-and-refund-aggregate-to-net-zero
  (testing "one charge -50 and one refund +50 across two ops aggregate
            to net cost of 0 ₽ (matches LK net-deduction semantics)"
    (let [charge {:operation_id 201
                  :operation_type "OperationAgentDeliveredToCustomer"
                  :operation_date "2026-04-10 10:00:00"
                  :type "services"
                  :amount -50
                  :items [{:sku 1 :name "A" :price 500 :quantity 1}]
                  :services [{:name "MarketplaceRedistributionOfAcquiringOperation"
                              :price -50}]
                  :posting {:posting_number "P1" :delivery_schema "FBO"}}
          refund (-> charge
                     (assoc :operation_id 202)
                     (assoc :services [{:name "MarketplaceRedistributionOfAcquiringOperation"
                                        :price 50}]))
          rows (mapcat #(transform/tx-op->service-rows % {1 "ART-A"}) [charge refund])
          net (reduce + 0.0 (keep :acquiring-fee rows))]
      (is (= 0.0 net)
          "charge -50 and refund +50 must net to 0; old abs-based code
           gave +100 (collapsed both into deductions)."))))

(deftest string-sku-article-lookup-also-works
  (testing "article-lookup with string sku keys also resolves"
    (let [op (assoc single-item-op :items [{:sku "1" :name "A"}])
          rows (transform/tx-op->service-rows op {"1" "ART-A"})]
      (is (= 1 (count rows)))
      (is (= "ART-A" (:article (first rows)))))))

;; ---------------------------------------------------------------------------
;; T019: Marketing op → :ad-cost
;; ---------------------------------------------------------------------------

(deftest marketing-op-amount-attributes-to-ad-cost
  (testing "MarketplaceMarketingActionCostOperation → :ad-cost using op.amount"
    (let [op {:operation_id 222
              :operation_type "MarketplaceMarketingActionCostOperation"
              :operation_date "2026-03-20 00:00:00"
              :type "other"
              :amount -200
              :items [{:sku 9 :name "X"}]
              :services []
              :posting {:posting_number "P9"}}
          rows (transform/tx-op->service-rows op {9 "ART-X"})]
      (is (= 1 (count rows)))
      (let [row (first rows)]
        (is (= "ART-X" (:article row)))
        (is (= 200.0 (:ad-cost row))
            "Math/abs of op.amount → stored as positive ad-cost")))))

;; ---------------------------------------------------------------------------
;; T020: skip conditions
;; ---------------------------------------------------------------------------

(deftest skip-compensation-type-ops
  (testing "operation with :type \"compensation\" → empty"
    (let [op (assoc single-item-op :type "compensation")]
      (is (= [] (transform/tx-op->service-rows op {1 "ART-A"}))))))

(deftest skip-ops-without-posting-number
  (testing "missing posting.posting_number → empty (account-level op)"
    (let [op (assoc single-item-op :posting {:delivery_schema "FBO"})]
      (is (= [] (transform/tx-op->service-rows op {1 "ART-A"}))))
    (testing "nil posting → empty"
      (let [op (assoc single-item-op :posting nil)]
        (is (= [] (transform/tx-op->service-rows op {1 "ART-A"})))))))

(deftest skip-ops-with-empty-items
  (testing "operation with items=[] and service-level services → empty"
    (let [op (assoc single-item-op :items [])]
      (is (= [] (transform/tx-op->service-rows op {1 "ART-A"}))))))

(deftest skip-ops-with-empty-services-and-no-op-mapping
  (testing "no services[] AND operation_type without op-mapping → empty"
    (let [op (assoc single-item-op :services [])]
      (is (= [] (transform/tx-op->service-rows op {1 "ART-A"}))))))

(deftest skip-orphan-sku-not-in-lookup
  (testing "sku not found in article-lookup → empty (orphan posting)"
    (is (= [] (transform/tx-op->service-rows single-item-op {999 "ART-999"})))))

;; ---------------------------------------------------------------------------
;; T021: multi-item distribution (proportional + equal-split fallback)
;; ---------------------------------------------------------------------------

(def ^:private multi-item-op
  "2 items, one service: price 500 × 1 vs 1500 × 1 → 25/75 of abs(service.price=100)."
  {:operation_id 333
   :operation_type "OperationAgentDeliveredToCustomer"
   :operation_date "2026-03-10 00:00:00"
   :type "services"
   :amount -100
   :items [{:sku 1 :name "A" :price 500 :quantity 1}
           {:sku 2 :name "B" :price 1500 :quantity 1}]
   :services [{:name "MarketplaceServiceItemDirectFlowLogistic" :price -100}]
   :posting {:posting_number "P2"}})

(deftest multi-item-service-distribution-proportional-to-price-quantity
  (testing "2 items with price weights 500 and 1500 → 25 and 75 of abs(100)"
    (let [rows (transform/tx-op->service-rows multi-item-op {1 "ART-A" 2 "ART-B"})
          by-article (into {} (map (juxt :article :delivery-cost) rows))]
      (is (= 2 (count rows)))
      (is (= 25.0 (get by-article "ART-A")))
      (is (= 75.0 (get by-article "ART-B"))))))

(deftest multi-item-equal-split-fallback-when-weights-zero
  (testing "all prices 0 → 50/50 equal split"
    (let [op (assoc multi-item-op
                    :items [{:sku 1 :name "A" :price 0 :quantity 1}
                            {:sku 2 :name "B" :price 0 :quantity 1}])
          rows (transform/tx-op->service-rows op {1 "ART-A" 2 "ART-B"})
          by-article (into {} (map (juxt :article :delivery-cost) rows))]
      (is (= 2 (count rows)))
      (is (= 50.0 (get by-article "ART-A")))
      (is (= 50.0 (get by-article "ART-B"))))))

(deftest multi-item-fallback-when-price-and-quantity-missing
  (testing "items lacking :price and :quantity → equal split (defensive)"
    (let [op (assoc multi-item-op
                    :items [{:sku 1 :name "A"}
                            {:sku 2 :name "B"}])
          rows (transform/tx-op->service-rows op {1 "ART-A" 2 "ART-B"})
          costs (mapv :delivery-cost rows)]
      (is (= 2 (count rows)))
      (is (every? #(= 50.0 %) costs)))))

;; ---------------------------------------------------------------------------
;; service-rrd-id determinism
;; ---------------------------------------------------------------------------

(deftest service-rrd-id-is-deterministic
  (testing "same natural key → same hash across calls"
    (is (= (transform/service-rrd-id 111 1 "S")
           (transform/service-rrd-id 111 1 "S"))))
  (testing "different keys → different hashes (collision-resistant for this tuple)"
    (is (not= (transform/service-rrd-id 111 1 "S")
              (transform/service-rrd-id 111 1 "T")))
    (is (not= (transform/service-rrd-id 111 1 "S")
              (transform/service-rrd-id 111 2 "S")))
    (is (not= (transform/service-rrd-id 111 1 "S")
              (transform/service-rrd-id 222 1 "S")))))
