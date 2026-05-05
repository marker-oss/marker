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

;; ---------------------------------------------------------------------------
;; Phase 5b: delivered + cancelled events
;; ---------------------------------------------------------------------------

(deftest delivered-event-emitted-when-status-delivered
  (testing "status='delivered' posting emits 1 delivered event per unit
            (in addition to the ordered event), dated by delivery_date_end"
    (let [events (ozon-events/posting->events single-item-posting 99)
          delivered (filter #(= "delivered" (:event-type %)) events)]
      (is (= 1 (count delivered)))
      (let [d (first delivered)]
        (is (= "ozon" (:marketplace d)))
        (is (= "0001-1" (:posting-id d)))
        (is (= 0 (:item-seq d)))
        (is (= "ART-A" (:article d)))
        (is (= "2026-04-18" (:event-date d))
            "event_date from analytics_data.delivery_date_end")
        (is (= "2026-04-18T18:00:00Z" (:event-ts d)))))))

(deftest delivered-event-also-emitted-for-returned-postings
  (testing "status='returned' means item was first delivered, then returned —
            both delivered and ordered events fire (returned itself is Phase 5c)"
    (let [posting (assoc single-item-posting :status "returned")
          events  (ozon-events/posting->events posting 1)]
      (is (= 1 (count (filter #(= "ordered" (:event-type %)) events))))
      (is (= 1 (count (filter #(= "delivered" (:event-type %)) events)))))))

(deftest delivered-event-not-emitted-when-status-cancelled
  (testing "cancelled postings never reached the buyer → no delivered event"
    (let [posting (assoc single-item-posting :status "cancelled")
          events  (ozon-events/posting->events posting 1)]
      (is (empty? (filter #(= "delivered" (:event-type %)) events))))))

(deftest delivered-event-not-emitted-when-status-in-flight
  (testing "delivering / awaiting_packaging are not yet settled — no delivered event"
    (doseq [s ["delivering" "awaiting_packaging" "awaiting_deliver"]]
      (let [posting (assoc single-item-posting :status s)
            events  (ozon-events/posting->events posting 1)]
        (is (empty? (filter #(= "delivered" (:event-type %)) events))
            (str "no delivered event for status=" s))))))

(deftest cancelled-event-emitted-when-status-cancelled
  (testing "status='cancelled' emits cancelled events (one per unit). Falls back
            to in_process_at when no explicit cancellation date is present."
    (let [posting (assoc single-item-posting :status "cancelled")
          events  (ozon-events/posting->events posting 1)
          cancelled (filter #(= "cancelled" (:event-type %)) events)]
      (is (= 1 (count cancelled)))
      (is (= "2026-04-15" (:event-date (first cancelled)))
          "fallback to in_process_at day when cancellation timestamp is absent"))))

(deftest cancelled-event-not-emitted-when-status-not-cancelled
  (testing "delivered / delivering / returned do not emit cancelled events"
    (doseq [s ["delivered" "delivering" "returned" "awaiting_packaging"]]
      (let [posting (assoc single-item-posting :status s)
            events  (ozon-events/posting->events posting 1)]
        (is (empty? (filter #(= "cancelled" (:event-type %)) events))
            (str "no cancelled event for status=" s))))))

;; ---------------------------------------------------------------------------
;; Phase 5c: returned events from transaction-list operations
;; ---------------------------------------------------------------------------

(def ^:private return-op
  "Single-item return operation from /v3/finance/transaction/list."
  {:operation_id   77001
   :operation_type "OperationItemReturn"
   :operation_date "2026-04-12 14:30:00"
   :type           "returns"
   :amount         -120.0
   :items          [{:name "Платье SHEGIDA" :sku 2152872479}]
   :services       []
   :posting        {:posting_number "07815547-0994-1"}})

(def ^:private sku-lookup
  "sku → offer_id (article) — what build-article-lookup produces in materialize."
  {2152872479 "ART-RET-A"
   1738937827 "ART-RET-B"})

(deftest return-op-emits-returned-event-per-item
  (testing "one return operation with one item → one returned event"
    (let [events (ozon-events/transaction-op->returned-events return-op sku-lookup 100)]
      (is (= 1 (count events)))
      (let [e (first events)]
        (is (= "ozon" (:marketplace e)))
        (is (= "07815547-0994-1" (:posting-id e)))
        (is (= "ART-RET-A" (:article e)))
        (is (= "2152872479" (:sku e)))
        (is (= "returned" (:event-type e)))
        (is (= "2026-04-12" (:event-date e)) "day slice of operation_date")
        (is (= "OperationItemReturn" (:status e)) "raw op_type as audit status")
        (is (= 100 (:raw-data-id e)))))))

(deftest return-op-multi-item-emits-row-per-item
  (testing "return op with 2 items → 2 returned events with item_seq 0 and 1"
    (let [op (assoc return-op :items
                    [{:name "A" :sku 2152872479}
                     {:name "B" :sku 1738937827}])
          events (ozon-events/transaction-op->returned-events op sku-lookup 1)]
      (is (= 2 (count events)))
      (is (= ["ART-RET-A" "ART-RET-B"] (mapv :article events)))
      (is (= [0 1] (mapv :item-seq events))))))

(deftest non-return-op-emits-nothing
  (testing "transaction-list operations of type other than 'returns' produce no
            returned events (sale, services, compensation, etc.)"
    (doseq [t ["orders" "services" "compensation"]]
      (let [op (assoc return-op :type t)]
        (is (empty? (ozon-events/transaction-op->returned-events op sku-lookup 1))
            (str "type=" t " must not emit returned"))))))

(deftest return-op-without-posting-number-skipped
  (testing "account-level returns (no posting_number) are dropped — the
            canonical event log requires identity to attribute to a unit"
    (let [op (assoc return-op :posting {:posting_number nil})]
      (is (empty? (ozon-events/transaction-op->returned-events op sku-lookup 1))))))

(deftest return-op-with-orphan-sku-still-emits-with-nil-article
  (testing "if sku doesn't resolve in the lookup we still emit the event —
            counting matters more than identity; article = nil flags it for audit"
    (let [op (assoc return-op :items [{:name "?" :sku 99999}])
          events (ozon-events/transaction-op->returned-events op sku-lookup 1)]
      (is (= 1 (count events)))
      (is (nil? (:article (first events))))
      (is (= "99999" (:sku (first events)))))))

;; ---------------------------------------------------------------------------
;; Phase 5c.5: returned events sourced from /v2/finance/realization
;;
;; Realization rows are the authoritative monthly aggregate Ozon publishes.
;; Each row has a return_commission.quantity reporting how many units of
;; that (article, sku, month) came back. We expand each into N per-unit
;; returned events with a synthesized posting_id so they have stable
;; identity for idempotent INSERT OR REPLACE.
;; ---------------------------------------------------------------------------

(def ^:private realization-row-with-return
  "One realization row mirroring live Ozon /v2/finance/realization shape."
  {:rowNumber 1
   :seller_price_per_instance 1500
   :item {:offer_id "ART-RZ-A" :sku 12345 :barcode "BC-RZ-A"}
   :delivery_commission {:quantity 5 :standard_fee 200 :amount 1300 :total 1300}
   :return_commission   {:quantity 2 :standard_fee 200 :amount 600  :total 600}})

(deftest realization-row-emits-returned-event-per-unit
  (testing "return_commission.quantity=2 → 2 returned events with item_seq 0,1
            and a stable synthetic posting_id."
    (let [events (ozon-events/realization-row->returned-events
                   realization-row-with-return "2026-04-01" 33)]
      (is (= 2 (count events)))
      (is (= [0 1] (mapv :item-seq events)))
      (is (every? #(= "ART-RZ-A" (:article %)) events))
      (is (every? #(= "12345"    (:sku %))     events))
      (is (every? #(= "returned" (:event-type %)) events))
      (is (every? #(= "2026-04-01" (:event-date %)) events)
          "all units share the realization period start date")
      (is (every? #(re-matches #"^realization-12345-2026-04-01$" (:posting-id %))
                  events)
          "synthetic posting_id ties to (sku, month) for idempotency")
      (is (every? #(= 33 (:raw-data-id %)) events)))))

(deftest realization-row-no-return-commission-emits-nothing
  (testing "rows without a return_commission section produce no events"
    (let [row (dissoc realization-row-with-return :return_commission)]
      (is (empty? (ozon-events/realization-row->returned-events row "2026-04-01" 1))))))

(deftest realization-row-zero-quantity-return-emits-nothing
  (testing "return_commission.quantity=0 → no events (counts must be honest)"
    (let [row (assoc-in realization-row-with-return [:return_commission :quantity] 0)]
      (is (empty? (ozon-events/realization-row->returned-events row "2026-04-01" 1))))))
