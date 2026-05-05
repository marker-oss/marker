(ns analitica.canonical.events.ozon
  "Ozon raw posting → canonical item-event normalizer.

   Phase 5a (2026-05-05): emits `ordered` events. One row per item-unit:
   a posting with N products of total Q units yields Q ordered events with
   item_seq 0..Q-1.

   Phase 5b will add `delivered` (from analytics_data.delivery_date_end)
   and `cancelled` (from status), Phase 5c — `returned`. See
   specs/004-canonical-item-events/data-model.md for the full plan.

   Pure transformation — no DB access. The companion `materialize`
   namespace runs this against raw_data and INSERT-OR-REPLACEs into
   item_events, keyed by (marketplace, posting_id, item_seq, event_type)."
  (:require [clojure.string :as str]))

(defn- ->date
  "Slice an Ozon ISO timestamp 'YYYY-MM-DDTHH:mm:ssZ' to 'YYYY-MM-DD'.
   Returns nil for nil / too-short input — caller drops the event."
  [ts]
  (when (and (string? ts) (>= (count ts) 10))
    (subs ts 0 10)))

(defn- parse-price
  "Ozon posting prices arrive as strings like \"4275.0000\". Coerce to double.
   Returns nil for nil / unparseable input — caller still emits the event
   without a gross_price (we'd rather count the order than drop it)."
  [v]
  (cond
    (nil? v)    nil
    (number? v) (double v)
    (string? v) (try (Double/parseDouble (str/trim v))
                     (catch Exception _ nil))))

(defn- product-units
  "Expand one product map into its N unit-events seeded with shared fields.
   `start-seq` is the item_seq for the first unit; subsequent units increment.
   Returns vector of {:sku :article :barcode :gross-price :quantity 1 :item-seq N} maps."
  [product start-seq]
  (let [q       (or (:quantity product) 1)
        article (:offer_id product)
        sku     (some-> (:sku product) str)
        barcode (:barcode product)
        price   (parse-price (:price product))]
    (mapv (fn [i]
            {:item-seq    (+ start-seq i)
             :article     article
             :sku         sku
             :barcode     barcode
             :gross-price price
             :quantity    1})
          (range q))))

(defn posting->ordered-events
  "Transform one raw Ozon posting into a vector of `ordered` item events —
   one event per unit (per-item, expanding products[].quantity).

   Returns [] if the posting has no `in_process_at` (no event date → not
   a real ordering moment we can count).

   Args:
     posting     raw Ozon posting map (one element of /v3/posting/{fbo,fbs}/list)
     raw-data-id audit pointer to raw_data.id

   Each event map has the canonical item_events shape:
     {:marketplace :posting-id :item-seq :sku :article :barcode
      :event-type :event-date :event-ts :quantity :gross-price
      :status :raw-data-id}"
  [posting raw-data-id]
  (let [in-process (:in_process_at posting)
        event-date (->date in-process)]
    (if (nil? event-date)
      []
      (let [posting-num (:posting_number posting)
            status      (:status posting)
            base        {:marketplace "ozon"
                         :posting-id  posting-num
                         :event-type  "ordered"
                         :event-date  event-date
                         :event-ts    in-process
                         :status      status
                         :raw-data-id raw-data-id}
            products    (or (:products posting) [])]
        (->> products
             (reduce (fn [{:keys [seq events]} product]
                       (let [units (product-units product seq)]
                         {:seq    (+ seq (count units))
                          :events (into events (map #(merge base %) units))}))
                     {:seq 0 :events []})
             :events)))))
