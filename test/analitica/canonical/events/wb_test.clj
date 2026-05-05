(ns analitica.canonical.events.wb-test
  "Tests for WB raw → canonical item_event normalizer (Phase 5f).
   WB raw is item-level: each row in orders/sales is one item-event."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.canonical.events.wb :as wb-ev]))

(def ^:private wb-order
  "Trimmed WB orders raw row, mirroring /api/v1/supplier/orders shape."
  {:srid              "18664592119923430.0.0"
   :date              "2026-04-15T10:00:00"
   :supplierArticle   "ART-WB-A"
   :nmId              67986099
   :barcode           "4660322671737"
   :totalPrice        6500
   :priceWithDisc     4013.75
   :isCancel          false
   :cancelDate        "0001-01-01T00:00:00"})

(deftest order-emits-single-ordered-event
  (testing "non-cancelled WB order → 1 ordered event with item-level shape"
    (let [events (wb-ev/order->events wb-order 11)]
      (is (= 1 (count events)))
      (let [e (first events)]
        (is (= "wb" (:marketplace e)))
        (is (= "18664592119923430.0.0" (:posting-id e))
            "posting_id = srid (the WB order document id)")
        (is (= 0 (:item-seq e)) "WB orders are item-level — seq always 0")
        (is (= "ART-WB-A" (:article e)))
        (is (= "67986099" (:sku e)) "nmId stored as text for cross-MP keying")
        (is (= "4660322671737" (:barcode e)))
        (is (= "ordered" (:event-type e)))
        (is (= "2026-04-15" (:event-date e)))
        (is (= 6500.0 (:gross-price e)))
        (is (= 11 (:raw-data-id e)))))))

(deftest cancelled-order-emits-both-ordered-and-cancelled
  (testing "isCancel=true WB order emits ordered (placement) AND cancelled
            (closure) — both real events in the lifecycle"
    (let [order (assoc wb-order
                       :isCancel true
                       :cancelDate "2026-04-18T12:30:00")
          events (wb-ev/order->events order 1)
          types  (set (map :event-type events))]
      (is (= 2 (count events)))
      (is (= #{"ordered" "cancelled"} types))
      (let [c (first (filter #(= "cancelled" (:event-type %)) events))]
        (is (= "2026-04-18" (:event-date c))
            "cancelled event_date from cancelDate, not the order date")))))

(deftest cancelled-without-cancel-date-falls-back-to-order-date
  (testing "if isCancel=true but cancelDate is the WB sentinel '0001-01-01',
            cancelled event uses the order date as fallback"
    (let [order (assoc wb-order :isCancel true)
          events (wb-ev/order->events order 1)
          c      (first (filter #(= "cancelled" (:event-type %)) events))]
      (is (= "2026-04-15" (:event-date c))))))

(deftest order-without-srid-skipped
  (testing "no srid → nothing to dedup against, drop the event"
    (is (empty? (wb-ev/order->events (dissoc wb-order :srid) 1)))
    (is (empty? (wb-ev/order->events (assoc wb-order :srid "") 1)))))

(deftest order-without-date-skipped
  (testing "no date → no event_date → drop"
    (is (empty? (wb-ev/order->events (dissoc wb-order :date) 1)))))

(def ^:private wb-sale
  "Trimmed WB sales raw row — saleID prefix S = sale, R = return."
  {:srid              "16415437619697751.5.0"
   :saleID            "S21394340095"
   :date              "2026-04-12T08:46:53"
   :supplierArticle   "ART-WB-B"
   :nmId              13468199
   :barcode           "2037080058506"
   :totalPrice        4650
   :forPay            2274.13})

(deftest sale-with-S-prefix-emits-delivered
  (testing "saleID starting with S → delivered event"
    (let [events (wb-ev/sale->events wb-sale 22)]
      (is (= 1 (count events)))
      (let [e (first events)]
        (is (= "delivered" (:event-type e)))
        (is (= "16415437619697751.5.0" (:posting-id e)))
        (is (= "ART-WB-B" (:article e)))
        (is (= "S21394340095" (:status e)) "raw saleID kept for audit")
        (is (= "2026-04-12" (:event-date e)))))))

(deftest sale-with-R-prefix-emits-returned
  (testing "saleID starting with R → returned event"
    (let [sale (assoc wb-sale :saleID "R21394340095")
          events (wb-ev/sale->events sale 1)]
      (is (= 1 (count events)))
      (is (= "returned" (:event-type (first events)))))))

(deftest sale-with-unknown-prefix-emits-nothing
  (testing "saleID with unrecognised prefix → drop (ambiguous semantics)"
    (let [sale (assoc wb-sale :saleID "X12345")]
      (is (empty? (wb-ev/sale->events sale 1))))))

(deftest sale-without-srid-skipped
  (is (empty? (wb-ev/sale->events (dissoc wb-sale :srid) 1)))
  (is (empty? (wb-ev/sale->events (assoc wb-sale :srid "") 1))))
