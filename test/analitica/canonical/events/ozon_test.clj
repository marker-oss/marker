(ns analitica.canonical.events.ozon-test
  "Tests for the Ozon raw-posting → canonical item_event normalizer.
   Phase 5a covers the `ordered` event type — one row per (posting, item-unit).
   Per `specs/004-canonical-item-events/data-model.md`."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.canonical.events.ozon :as ozon-events]))

(def ^:private single-item-posting
  "Mirror of live Ozon postings: one product, quantity 1, status delivered."
  {:posting_number "0001-1"
   :status         "delivered"
   :in_process_at  "2026-04-15T10:00:00Z"
   :shipment_date  "2026-04-16T08:00:00Z"
   :products       [{:offer_id "ART-A"
                     :sku      111
                     :barcode  "BC-A"
                     :quantity 1
                     :price    "1000.00"}]
   :analytics_data {:delivery_date_end "2026-04-18T18:00:00Z"
                    :warehouse "WH-1"}})

(deftest ordered-event-emitted-per-unit-single-item-posting
  (testing "single-product posting with quantity=1 → exactly 1 ordered event"
    (let [events (ozon-events/posting->ordered-events single-item-posting 42)]
      (is (= 1 (count events)))
      (let [e (first events)]
        (is (= "ozon" (:marketplace e)))
        (is (= "0001-1"     (:posting-id e)))
        (is (= 0            (:item-seq e)))
        (is (= "ART-A"      (:article e)))
        (is (= "111"        (:sku e)) "sku stored as text for cross-MP keying")
        (is (= "BC-A"       (:barcode e)))
        (is (= "ordered"    (:event-type e)))
        (is (= "2026-04-15" (:event-date e)) "event_date is YYYY-MM-DD slice of in_process_at")
        (is (= "2026-04-15T10:00:00Z" (:event-ts e)) "full timestamp preserved")
        (is (= 1            (:quantity e)))
        (is (= 1000.0       (:gross-price e)) "per-unit gross from products[].price")
        (is (= "delivered"  (:status e)) "raw status snapshot for audit")
        (is (= 42           (:raw-data-id e)))))))

(deftest ordered-event-quantity-expanded-into-per-unit-rows
  (testing "products[].quantity=3 → 3 separate ordered events with item_seq 0,1,2"
    (let [posting (assoc-in single-item-posting [:products 0 :quantity] 3)
          events  (ozon-events/posting->ordered-events posting 1)]
      (is (= 3 (count events)))
      (is (= [0 1 2] (mapv :item-seq events))
          "item_seq enumerates units within the posting")
      (is (every? #(= 1 (:quantity %)) events)
          "each row carries quantity=1 — the unit of the canonical model")
      (is (every? #(= "ART-A" (:article %)) events)))))

(deftest ordered-event-multi-product-posting-emits-row-per-unit
  (testing "posting with 2 products A(q=2) + B(q=1) → 3 events, seq 0/1/2"
    (let [posting (assoc single-item-posting
                         :products
                         [{:offer_id "A" :sku 1 :quantity 2 :price "100"}
                          {:offer_id "B" :sku 2 :quantity 1 :price "200"}])
          events  (ozon-events/posting->ordered-events posting 7)]
      (is (= 3 (count events)))
      (is (= [0 1 2]   (mapv :item-seq events)))
      (is (= ["A" "A" "B"] (mapv :article events))
          "first product's units exhaust seq 0..N-1, then next product")
      (is (= [100.0 100.0 200.0] (mapv :gross-price events))))))

(deftest ordered-event-cancelled-posting-still-emits
  (testing "cancelled postings are still real orders — they get ordered events.
            cancelled events are emitted by a separate normalizer (Phase 5b)."
    (let [posting (assoc single-item-posting :status "cancelled")
          events  (ozon-events/posting->ordered-events posting 1)]
      (is (= 1 (count events)))
      (is (= "ordered" (:event-type (first events))))
      (is (= "cancelled" (:status (first events)))))))

(deftest ordered-event-skips-posting-with-no-in-process-at
  (testing "raw posting missing in_process_at is dropped (no event_date — invalid event)"
    (let [posting (dissoc single-item-posting :in_process_at)
          events  (ozon-events/posting->ordered-events posting 1)]
      (is (empty? events)))))
