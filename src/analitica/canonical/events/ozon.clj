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

(defn- expand-units
  "Walk products[] and produce {:seq :units} where :units is a vector of
   per-unit maps with shared identity fields. The :seq value at the end
   is the next free item_seq (= total unit count)."
  [products]
  (reduce (fn [{:keys [seq units]} product]
            (let [unit-maps (product-units product seq)]
              {:seq   (+ seq (count unit-maps))
               :units (into units unit-maps)}))
          {:seq 0 :units []}
          (or products [])))

(def ^:private delivered-statuses
  "Posting statuses that mean the item reached the buyer. `returned` is
   here too — a returned item was first delivered."
  #{"delivered" "returned"})

(def ^:private cancelled-statuses
  "Posting statuses that mean the order was killed before delivery."
  #{"cancelled"})

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
            {:keys [units]} (expand-units (:products posting))]
        (mapv #(merge base %) units)))))

(defn- posting->delivered-events
  "Emit `delivered` events when posting status indicates the items reached
   the buyer (delivered or returned — returned implies a prior delivery).
   Event date comes from analytics_data.delivery_date_end. Returns [] when
   status doesn't qualify or the delivery date is missing."
  [posting raw-data-id]
  (let [status     (:status posting)
        delivery-ts (get-in posting [:analytics_data :delivery_date_end])
        event-date  (->date delivery-ts)]
    (if (or (nil? event-date)
            (not (contains? delivered-statuses status)))
      []
      (let [posting-num (:posting_number posting)
            base        {:marketplace "ozon"
                         :posting-id  posting-num
                         :event-type  "delivered"
                         :event-date  event-date
                         :event-ts    delivery-ts
                         :status      status
                         :raw-data-id raw-data-id}
            {:keys [units]} (expand-units (:products posting))]
        (mapv #(merge base %) units)))))

(defn- posting->cancelled-events
  "Emit `cancelled` events when posting status='cancelled'. Falls back to
   in_process_at as event_date — Ozon doesn't expose an explicit
   cancellation timestamp on /v3/posting/list responses (the
   `cancellation` block has reason and initiator but no datetime)."
  [posting raw-data-id]
  (let [status (:status posting)]
    (if (not (contains? cancelled-statuses status))
      []
      (let [in-process (:in_process_at posting)
            event-date (->date in-process)]
        (if (nil? event-date)
          []
          (let [posting-num (:posting_number posting)
                base        {:marketplace "ozon"
                             :posting-id  posting-num
                             :event-type  "cancelled"
                             :event-date  event-date
                             :event-ts    in-process
                             :status      status
                             :raw-data-id raw-data-id}
                {:keys [units]} (expand-units (:products posting))]
            (mapv #(merge base %) units)))))))

(defn- ts->date
  "Slice an Ozon transaction-list operation_date 'YYYY-MM-DD HH:mm:ss'
   to YYYY-MM-DD."
  [s]
  (when (and (string? s) (>= (count s) 10))
    (subs s 0 10)))

(defn transaction-op->returned-events
  "Emit `returned` events from one /v3/finance/transaction/list operation.
   Only operations with type='returns' qualify; one event per item in
   items[].

   ⚠️ NOT WIRED INTO materialize-ozon-events! (Phase 5c deferred). Live
   data shows 1 physical return → 5–10 transaction-list events
   (OperationItemReturn + OperationReturnGoodsFBSofRMS +
   ClientReturnAgentOperation, each repeated across overlapping chunks).
   Naive counting from transactions over-states returns 14× vs LK
   realization-aggregate (Apr 2026: 360 events vs LK 25 actual returns).
   For physical-return counting we should source from
   /v2/finance/realization rows (which already match LK). This pure
   normalizer is kept as the building block for refund-event tracking
   (separate concept) and tested for shape correctness.

   `sku-lookup` is {sku → offer_id}. When sku doesn't resolve we still
   emit the event — counting must happen even if article identity is
   missing — and flag it via article=nil for audit.

   Skips:
     - operations with type ≠ 'returns'
     - operations with no posting.posting_number (account-level)
     - operations with empty items[]"
  [operation sku-lookup raw-data-id]
  (let [op-type (:operation_type operation)
        type-   (:type operation)
        op-id   (:operation_id operation)
        posting-num (get-in operation [:posting :posting_number])
        items   (or (:items operation) [])
        op-date (:operation_date operation)
        ev-date (ts->date op-date)]
    (cond
      (not= "returns" type-)             []
      (or (nil? posting-num)
          (clojure.string/blank? posting-num)) []
      (empty? items)                     []
      (nil? ev-date)                     []
      :else
      (->> items
           (map-indexed
             (fn [idx item]
               (let [sku (:sku item)]
                 {:marketplace "ozon"
                  :posting-id  posting-num
                  :item-seq    idx
                  :sku         (some-> sku str)
                  :article     (or (get sku-lookup sku)
                                   (get sku-lookup (str sku)))
                  :event-type  "returned"
                  :event-date  ev-date
                  :event-ts    op-date
                  :status      op-type
                  :raw-data-id raw-data-id})))
           vec))))

(defn posting->events
  "Top-level normalizer: convert one Ozon posting into ALL relevant
   canonical item_events (ordered + delivered + cancelled).

   Returned events are NOT emitted here — they require correlation with
   /v2/finance/realization or transaction-list return rows for an actual
   return date and are handled by Phase 5c.

   Output ordering: ordered events first, then delivered, then cancelled
   — matches the lifecycle, but the canonical PK doesn't depend on order."
  [posting raw-data-id]
  (into []
        cat
        [(posting->ordered-events   posting raw-data-id)
         (posting->delivered-events posting raw-data-id)
         (posting->cancelled-events posting raw-data-id)]))
