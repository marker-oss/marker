(ns analitica.canonical.events.wb
  "WB raw → canonical item_event normalizer.

   WB's raw shape is item-level by design — every row in /api/v1/supplier/
   {orders,sales} represents one item-event. That's the natural fit for
   item_events; no quantity expansion needed.

     orders raw  → ordered + cancelled (when isCancel=true)
     sales  raw  → delivered (saleID starts with 'S')
                   returned  (saleID starts with 'R')

   posting_id = srid (the unique WB order document id, also used as the
   FK between orders and sales raw entries). item_seq = 0 always (1 unit
   per row at the source).

   Phase 5f of canonical event log."
  (:require [clojure.string :as str]))

(defn- ->date
  "Slice WB ISO timestamp 'YYYY-MM-DDTHH:mm:ss' to 'YYYY-MM-DD'."
  [ts]
  (when (and (string? ts) (>= (count ts) 10))
    (subs ts 0 10)))

(defn- valid-date? [d]
  ;; WB sentinel for «no date» is 0001-01-01T00:00:00 — drop it.
  (and (string? d)
       (not (str/starts-with? d "0001-"))))

(defn- order-base
  "Common fields shared by ordered/cancelled events for a WB order row."
  [order raw-data-id event-type event-date event-ts status]
  {:marketplace "wb"
   :posting-id  (:srid order)
   :item-seq    0
   :sku         (some-> (:nmId order) str)
   :article     (:supplierArticle order)
   :barcode     (:barcode order)
   :event-type  event-type
   :event-date  event-date
   :event-ts    event-ts
   :status      status
   :gross-price (some-> (:totalPrice order) double)
   :raw-data-id raw-data-id
   :quantity    1})

(defn order->events
  "Transform one WB order raw row into ordered + (optionally) cancelled
   events. Returns [] if the row has no usable date or no srid (the
   identity field — without it we can't dedupe)."
  [order raw-data-id]
  (let [srid       (:srid order)
        date       (:date order)
        ev-date    (->date date)
        is-cancel? (true? (:isCancel order))
        cancel-ts  (:cancelDate order)
        cancel-d   (when (valid-date? cancel-ts) (->date cancel-ts))]
    (if (or (nil? srid) (str/blank? srid) (nil? ev-date))
      []
      (cond-> [(order-base order raw-data-id "ordered" ev-date date
                           (if is-cancel? "cancelled" "ordered"))]
        is-cancel?
        (conj (order-base order raw-data-id "cancelled"
                          (or cancel-d ev-date) (or cancel-ts date)
                          "cancelled"))))))

(defn- sale-base
  [sale raw-data-id event-type event-date event-ts]
  {:marketplace "wb"
   :posting-id  (:srid sale)
   :item-seq    0
   :sku         (some-> (:nmId sale) str)
   :article     (:supplierArticle sale)
   :barcode     (:barcode sale)
   :event-type  event-type
   :event-date  event-date
   :event-ts    event-ts
   :status      (:saleID sale)
   :gross-price (some-> (:totalPrice sale) double)
   :raw-data-id raw-data-id
   :quantity    1})

(defn sale->events
  "Transform one WB sale raw row into a delivered or returned event
   based on the saleID prefix:
     S* → delivered (item reached the buyer & they kept it as of this
                     row's date — WB doesn't always emit a separate
                     'delivered' status, sales-table membership IS the
                     delivered signal)
     R* → returned

   Returns [] when srid/saleID/date are missing or unrecognised."
  [sale raw-data-id]
  (let [srid    (:srid sale)
        sid     (:saleID sale)
        date    (:date sale)
        ev-date (->date date)]
    (cond
      (or (nil? srid) (str/blank? srid)) []
      (nil? ev-date)                     []
      (nil? sid)                         []
      (str/starts-with? sid "S") [(sale-base sale raw-data-id "delivered" ev-date date)]
      (str/starts-with? sid "R") [(sale-base sale raw-data-id "returned"  ev-date date)]
      :else                       [])))
