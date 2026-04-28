(ns analitica.marketplace.operation-kind-test
  "RFC-3, RFC-14, RFC-15 transform invariants.

  Asserts the two-level operation contract introduced 2026-04-28
  (see docs/concept-crosswalk.md §2.1, §11):

  - :operation-kind is a canonical keyword from
    {:sale :return :service :adjustment} (or nil for unknowns).
  - :operation-subtype preserves the raw MP classifier string for
    audit / UI drill-down.
  - :for-pay  ≥ 0 always; sign carried by :operation-kind under L2
    mp_payout. Service / adjustment rows have :for-pay = 0.
  - :quantity ≥ 0 always; the original direction lives in
    :operation-kind."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.marketplace.wb.transform :as wb]
            [analitica.marketplace.ozon.transform :as ozon]
            [analitica.marketplace.ym.transform :as ym]))

;; ---------------------------------------------------------------------------
;; WB
;; ---------------------------------------------------------------------------

(deftest wb-finance-sale-canonical
  (testing "WB sale row → kind :sale, subtype 'Продажа', for-pay/quantity ≥ 0"
    (let [row (wb/->finance-line
                {:rrd_id 1
                 :date_from "2026-03-01" :date_to "2026-03-07"
                 :sa_name "ART-1" :supplier_oper_name "Продажа"
                 :quantity 2 :ppvz_for_pay 500.0
                 :retail_amount 1000.0})]
      (is (= :sale (:operation-kind row)))
      (is (= "Продажа" (:operation-subtype row)))
      (is (= "sale" (:operation row)))
      (is (= 2 (:quantity row)))
      (is (= 500.0 (:for-pay row))))))

(deftest wb-finance-return-normalizes-sign
  (testing "WB return with negative ppvz_for_pay → for-pay ≥ 0"
    (let [row (wb/->finance-line
                {:rrd_id 2
                 :date_from "2026-03-01" :date_to "2026-03-07"
                 :sa_name "ART-1" :supplier_oper_name "Возврат"
                 :quantity 1 :ppvz_for_pay -300.0
                 :retail_amount 500.0})]
      (is (= :return (:operation-kind row)))
      (is (= "Возврат" (:operation-subtype row)))
      (is (= 300.0 (:for-pay row)) "for-pay normalized to abs")
      (is (= 1 (:quantity row))))))

(deftest wb-finance-return-with-negative-quantity
  (testing "WB return with negative raw quantity → quantity ≥ 0"
    (let [row (wb/->finance-line
                {:rrd_id 3
                 :date_from "2026-03-01" :date_to "2026-03-07"
                 :sa_name "ART-1" :supplier_oper_name "Возврат"
                 :quantity -1 :ppvz_for_pay -100.0
                 :retail_amount 200.0})]
      (is (= 1 (:quantity row)) "quantity normalized to abs"))))

(deftest wb-finance-service-row-zero-pay
  (testing "WB service row (Логистика) → kind :service, for-pay = 0"
    (let [row (wb/->finance-line
                {:rrd_id 4
                 :date_from "2026-03-01" :date_to "2026-03-07"
                 :sa_name nil :supplier_oper_name "Логистика"
                 :quantity 0 :ppvz_for_pay 0.0
                 :delivery_rub 50.0})]
      (is (= :service (:operation-kind row)))
      (is (= "Логистика" (:operation-subtype row)))
      (is (zero? (:for-pay row)))
      (is (= 50.0 (:delivery-cost row)) "service money lives in dedicated field"))))

(deftest wb-finance-adjustment-row
  (testing "WB compensation row → kind :adjustment"
    (let [row (wb/->finance-line
                {:rrd_id 5
                 :date_from "2026-03-01" :date_to "2026-03-07"
                 :sa_name "ART-1"
                 :supplier_oper_name "Компенсация ущерба"
                 :quantity 0 :ppvz_for_pay 0.0
                 :additional_payment 250.0})]
      (is (= :adjustment (:operation-kind row)))
      (is (= "Компенсация ущерба" (:operation-subtype row)))
      (is (zero? (:for-pay row))))))

(deftest wb-finance-unknown-operation-kind-nil
  (testing "WB unknown operation → kind nil; consumers fall back to legacy filter"
    (let [row (wb/->finance-line
                {:rrd_id 6
                 :date_from "2026-03-01" :date_to "2026-03-07"
                 :sa_name "ART-1" :supplier_oper_name "Загадочная Операция"
                 :quantity 0 :ppvz_for_pay 0.0})]
      (is (nil? (:operation-kind row)))
      (is (= "Загадочная Операция" (:operation-subtype row))))))

(deftest wb-sale-event-row-quantity-positive
  (testing "WB return event (saleID R…) keeps :type :return but :quantity ≥ 0"
    (let [row (wb/->sale {:saleID "R12345" :forPay -100 :supplierArticle "A"})]
      (is (= :return (:type row)))
      (is (= 1 (:quantity row)) "quantity sign no longer encodes direction")
      (is (= 100.0 (:for-pay row)) "for-pay normalized to abs"))))

;; ---------------------------------------------------------------------------
;; Ozon
;; ---------------------------------------------------------------------------

(deftest ozon-realization-rows-have-operation-kind
  (testing "Ozon realization → sale + return rows with operation-kind"
    (let [resp {:header {:start_date "2026-03-01" :stop_date "2026-03-31"}
                :rows   [{:item {:offer_id "A1" :sku 100 :barcode "B1"}
                          :seller_price_per_instance 100.0
                          :delivery_commission {:quantity 2 :amount 150 :total 200}
                          :return_commission   {:quantity 1 :amount 70  :total 100}}]}
          rows (ozon/->finance-from-realization resp)
          sale (first (filter #(= :sale (:operation-kind %)) rows))
          ret  (first (filter #(= :return (:operation-kind %)) rows))]
      (is sale "sale row produced")
      (is ret  "return row produced")
      (is (= "realization" (:operation-subtype sale)))
      (is (= "realization" (:operation-subtype ret)))
      (is (>= (:for-pay sale) 0))
      (is (>= (:for-pay ret) 0)))))

;; ---------------------------------------------------------------------------
;; YM
;; ---------------------------------------------------------------------------

(deftest ym-cancelled-order-classified-as-adjustment
  (testing "YM CANCELLED_BEFORE_PROCESSING → kind :adjustment, for-pay = 0"
    (let [order {:id 999
                 :status "CANCELLED_BEFORE_PROCESSING"
                 :creationDate "2026-03-15"
                 :commissions [{:type "AUCTION_PROMOTION" :actual 100}]
                 :items [{:shopSku "A1"
                          :count 1
                          :prices [{:type "BUYER" :total 0 :costPerItem 0}]
                          :bidFee 100}]}
          rows (ym/->finance-from-order-stats [order])
          row  (first rows)]
      (is (= :adjustment (:operation-kind row)))
      (is (= "CANCELLED_BEFORE_PROCESSING" (:operation-subtype row)))
      (is (zero? (:for-pay row)) "cancelled rows have for-pay = 0; loss lives in :ad-cost"))))

(deftest ym-delivered-order-classified-as-sale
  (testing "YM DELIVERED → kind :sale, for-pay ≥ 0"
    (let [order {:id 100
                 :status "DELIVERED"
                 :creationDate "2026-03-15"
                 :commissions [{:type "FEE" :actual 50}
                               {:type "DELIVERY_TO_CUSTOMER" :actual 30}]
                 :items [{:shopSku "A1"
                          :count 1
                          :prices [{:type "BUYER" :total 1000 :costPerItem 1000}]}]}
          rows (ym/->finance-from-order-stats [order])
          row  (first rows)]
      (is (= :sale (:operation-kind row)))
      (is (= "DELIVERED" (:operation-subtype row)))
      (is (>= (:for-pay row) 0)))))

(deftest ym-returned-order-classified-as-return
  (testing "YM RETURNED → kind :return, for-pay ≥ 0"
    (let [order {:id 200
                 :status "RETURNED"
                 :creationDate "2026-03-15"
                 :commissions [{:type "FEE" :actual 50}]
                 :items [{:shopSku "A1"
                          :count 1
                          :prices [{:type "BUYER" :total 1000 :costPerItem 1000}]}]}
          rows (ym/->finance-from-order-stats [order])
          row  (first rows)]
      (is (= :return (:operation-kind row)))
      (is (>= (:for-pay row) 0)))))

;; ---------------------------------------------------------------------------
;; RFC-7 / RFC-8 / RFC-10 — YM commission attribution to canonical fields
;; ---------------------------------------------------------------------------

(deftest ym-rfc-10-agency-and-payment-transfer-go-to-acquiring
  (testing "RFC-10: AGENCY + PAYMENT_TRANSFER → :acquiring-fee, FEE alone in :mp-commission"
    (let [order {:id 300
                 :status "DELIVERED"
                 :creationDate "2026-03-15"
                 :commissions [{:type "FEE"              :actual 50}
                               {:type "AGENCY"           :actual 7}
                               {:type "PAYMENT_TRANSFER" :actual 13}
                               {:type "DELIVERY_TO_CUSTOMER" :actual 30}]
                 :items [{:shopSku "A1"
                          :count 1
                          :prices [{:type "BUYER" :total 1000 :costPerItem 1000}]}]}
          row (first (ym/->finance-from-order-stats [order]))]
      (is (= 50.0 (double (:mp-commission row)))
          "FEE only — AGENCY no longer here")
      (is (= 20.0 (double (:acquiring-fee row)))
          "AGENCY (7) + PAYMENT_TRANSFER (13) = 20"))))

(deftest ym-rfc-7-returned-orders-storage-populated
  (testing "RFC-7: RETURNED_ORDERS_STORAGE → :storage-fee (was nil before)"
    (let [order {:id 310
                 :status "RETURNED"
                 :creationDate "2026-03-15"
                 :commissions [{:type "FEE" :actual 50}
                               {:type "RETURNED_ORDERS_STORAGE" :actual 25}]
                 :items [{:shopSku "A1"
                          :count 1
                          :prices [{:type "BUYER" :total 1000 :costPerItem 1000}]}]}
          row (first (ym/->finance-from-order-stats [order]))]
      (is (= 25.0 (double (:storage-fee row)))))))

(deftest ym-rfc-8-sorting-intake-and-return-processing-go-to-acceptance
  (testing "RFC-8: SORTING + INTAKE_SORTING + RETURN_PROCESSING → :acceptance"
    (let [order {:id 320
                 :status "DELIVERED"
                 :creationDate "2026-03-15"
                 :commissions [{:type "FEE" :actual 50}
                               {:type "SORTING"           :actual 10}
                               {:type "INTAKE_SORTING"    :actual 7}
                               {:type "RETURN_PROCESSING" :actual 3}]
                 :items [{:shopSku "A1"
                          :count 1
                          :prices [{:type "BUYER" :total 1000 :costPerItem 1000}]}]}
          row (first (ym/->finance-from-order-stats [order]))]
      (is (= 20.0 (double (:acceptance row)))
          "10+7+3 = 20"))))

(deftest ym-delivery-includes-express-and-crossregional
  (testing "DELIVERY_TO_CUSTOMER + EXPRESS_DELIVERY + CROSSREGIONAL → :delivery-cost"
    (let [order {:id 330
                 :status "DELIVERED"
                 :creationDate "2026-03-15"
                 :commissions [{:type "DELIVERY_TO_CUSTOMER"         :actual 30}
                               {:type "EXPRESS_DELIVERY_TO_CUSTOMER" :actual 50}
                               {:type "CROSSREGIONAL_DELIVERY"       :actual 20}]
                 :items [{:shopSku "A1"
                          :count 1
                          :prices [{:type "BUYER" :total 1000 :costPerItem 1000}]}]}
          row (first (ym/->finance-from-order-stats [order]))]
      (is (= 100.0 (double (:delivery-cost row)))))))

(deftest ym-no-storage-acceptance-emits-nil
  (testing "Without RETURNED_ORDERS_STORAGE / SORTING etc — fields stay nil (not 0.0)"
    (let [order {:id 340
                 :status "DELIVERED"
                 :creationDate "2026-03-15"
                 :commissions [{:type "FEE" :actual 50}]
                 :items [{:shopSku "A1"
                          :count 1
                          :prices [{:type "BUYER" :total 1000 :costPerItem 1000}]}]}
          row (first (ym/->finance-from-order-stats [order]))]
      (is (nil? (:storage-fee row)) "no RETURNED_ORDERS_STORAGE → nil")
      (is (nil? (:acceptance row))  "no SORTING family → nil"))))
