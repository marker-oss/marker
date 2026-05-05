(ns analitica.canonical.events.materialize
  "Read raw_data → emit canonical item_events. One namespace per source-MP
   normalizer is invoked here; this layer owns the IO + idempotency.

   Idempotency: INSERT OR REPLACE keyed by the natural composite PK
   (marketplace, posting_id, item_seq, event_type) — re-running for the
   same period overwrites nothing critical and adds no duplicates."
  (:require [analitica.db :as db]
            [analitica.canonical.events.ozon :as ozon-ev]
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

(defn materialize-ozon-events!
  "Read all `postings` raw_data rows whose [date_from..date_to] overlaps
   the requested window, normalize each posting to ordered events, and
   INSERT OR REPLACE into item_events.

   Args:
     period — [from to] vector or {:from :to} map (resolved upstream)

   Returns count of events written."
  [from to]
  (let [batches (db/get-raw-range "ozon" :postings from to)
        events  (into []
                      (mapcat (fn [{:keys [data id]}]
                                (mapcat #(ozon-ev/posting->events % id)
                                        (or data []))))
                      batches)
        n       (insert-events! events)
        by-type (frequencies (map :event-type events))]
    (println (str "Materialized canonical Ozon item_events: "
                  (count events) " events ("
                  (clojure.string/join ", "
                    (map (fn [[t n]] (str t " " n)) by-type))
                  ") from " (count batches) " raw batches"))
    n))
