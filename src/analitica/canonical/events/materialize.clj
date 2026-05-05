(ns analitica.canonical.events.materialize
  "Read raw_data → emit canonical item_events. One namespace per source-MP
   normalizer is invoked here; this layer owns the IO + idempotency.

   Idempotency: INSERT OR REPLACE keyed by the natural composite PK
   (marketplace, posting_id, item_seq, event_type) — re-running for the
   same period overwrites nothing critical and adds no duplicates."
  (:require [analitica.db :as db]
            [analitica.canonical.events.ozon :as ozon-ev]
            [analitica.canonical.events.wb   :as wb-ev]
            [next.jdbc :as jdbc])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(def ^:private now-fmt
  (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(defn- now-str []
  (.format (LocalDateTime/now) now-fmt))

(def ^:private item-event-columns
  "Order MUST match the INSERT statement below."
  [:marketplace :posting_id :item_seq :sku :article :barcode
   :event_type :event_date :event_ts :quantity :related_event_id
   :gross_price :status :raw_data_id :ingested_at])

(defn- ev->row
  "Translate a normalized event map (kebab keys, per ozon-ev) into a
   positional row vector matching `item-event-columns`."
  [ev]
  (let [now (now-str)]
    [(:marketplace ev)
     (:posting-id ev)
     (or (:item-seq ev) 0)
     (:sku ev)
     (:article ev)
     (:barcode ev)
     (:event-type ev)
     (:event-date ev)
     (:event-ts ev)
     (or (:quantity ev) 1)
     (:related-event-id ev)
     (:gross-price ev)
     (:status ev)
     (:raw-data-id ev)
     now]))

(defn- insert-events!
  "Bulk INSERT OR REPLACE into item_events. Idempotent via composite PK."
  [events]
  (when (seq events)
    (let [cols (clojure.string/join "," (map name item-event-columns))
          ph   (clojure.string/join "," (repeat (count item-event-columns) "?"))
          sql  (str "INSERT OR REPLACE INTO item_events (" cols ") VALUES (" ph ")")
          rows (mapv ev->row events)]
      (jdbc/with-transaction [tx (db/ds)]
        (doseq [row rows]
          (jdbc/execute-one! tx (into [sql] row))))
      (count rows))))

(defn- realization-batch->returned-events
  "Walk one realization raw_data batch and emit returned events from each
   row's return_commission section (Phase 5c.5)."
  [{:keys [data id]}]
  (let [header     (:header data)
        month-first (:start_date header)
        rows       (or (:rows data) [])]
    (when month-first
      (mapcat #(ozon-ev/realization-row->returned-events % month-first id) rows))))

(defn materialize-ozon-events!
  "Read raw Ozon data and emit canonical item_events:
     - ordered/delivered/cancelled from `postings`
     - returned                    from `realization` (sku-level monthly
       aggregates that match LK Накопления / UE «Возвращено»)

   INSERT OR REPLACE keyed by (marketplace, posting_id, item_seq,
   event_type) so re-runs are idempotent. Realization-source returns
   use a synthesized posting_id so they don't collide with real ones.

   Args: from, to — ISO dates bounding the work window."
  [from to]
  (let [post-batches  (db/get-raw-range "ozon" :postings from to)
        post-events   (into []
                            (mapcat (fn [{:keys [data id]}]
                                      (mapcat #(ozon-ev/posting->events % id)
                                              (or data []))))
                            post-batches)

        real-batches  (db/get-raw-range "ozon" :realization from to)
        real-events   (into [] (mapcat realization-batch->returned-events) real-batches)

        all-events    (into post-events real-events)
        n             (insert-events! all-events)
        by-type       (frequencies (map :event-type all-events))]
    (println (str "Materialized canonical Ozon item_events: "
                  (count all-events) " events ("
                  (clojure.string/join ", "
                    (map (fn [[t n]] (str t " " n)) by-type))
                  ") from " (count post-batches) " posting + "
                  (count real-batches) " realization batches"))
    n))

(defn materialize-wb-events!
  "Read raw WB data and emit canonical item_events:
     orders raw → ordered + cancelled (when isCancel=true)
     sales  raw → delivered (saleID 'S*') + returned (saleID 'R*')

   WB raw is item-level by design — every row is one event, no quantity
   expansion needed. INSERT OR REPLACE keyed by composite PK so re-runs
   are idempotent.

   Args: from, to — ISO dates bounding the work window."
  [from to]
  (let [order-batches (db/get-raw-range "wb" :orders from to)
        order-events  (into []
                            (mapcat (fn [{:keys [data id]}]
                                      (mapcat #(wb-ev/order->events % id)
                                              (or data []))))
                            order-batches)
        sale-batches  (db/get-raw-range "wb" :sales from to)
        sale-events   (into []
                            (mapcat (fn [{:keys [data id]}]
                                      (mapcat #(wb-ev/sale->events % id)
                                              (or data []))))
                            sale-batches)
        all-events    (into order-events sale-events)
        n             (insert-events! all-events)
        by-type       (frequencies (map :event-type all-events))]
    (println (str "Materialized canonical WB item_events: "
                  (count all-events) " events ("
                  (clojure.string/join ", "
                    (map (fn [[t n]] (str t " " n)) by-type))
                  ") from " (count order-batches) " orders + "
                  (count sale-batches) " sales batches"))
    n))
