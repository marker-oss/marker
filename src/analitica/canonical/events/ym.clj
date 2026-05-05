(ns analitica.canonical.events.ym
  "YM raw → canonical item_event normalizer (Phase 5g).

   YM raw orders are posting-level (one row per order document) with
   items[] inside. Each item carries `count` (units of that SKU in
   the order); we expand to per-unit events to match the canonical
   1-row-per-unit invariant.

   Status taxonomy:
     PROCESSING / DELIVERY / PICKUP            → in-flight (ordered only)
     DELIVERED / PARTIALLY_DELIVERED            → ordered + delivered
     CANCELLED / CANCELLED_*                    → ordered + cancelled

   `returned` events for YM are NOT emitted yet — YM raw orders don't
   carry a clean return signal (returns surface in /v2/return endpoints
   we don't yet ingest). Volume is small (Apr 2026: 3 returns total),
   safe to defer. Once YM returns ingest exists, add a sibling fn here."
  (:require [clojure.string :as str]))

(defn- ->date [ts]
  (when (and (string? ts) (>= (count ts) 10))
    (subs ts 0 10)))

(def ^:private delivered-statuses
  #{"DELIVERED" "PARTIALLY_DELIVERED"})

(defn- cancelled-status? [s]
  (and (string? s) (str/starts-with? s "CANCELLED")))

(defn- item-price
  "YM item carries two price entries — MARKETPLACE (gross) and BUYER (paid).
   Use BUYER as the seller's settlement reference and fall back to
   MARKETPLACE when BUYER is absent (rare)."
  [item]
  (let [pricing (or (:prices item) [])
        by-type (into {} (map (juxt :type identity)) pricing)
        b       (get by-type "BUYER")
        m       (get by-type "MARKETPLACE")]
    (some-> (or (:costPerItem b) (:costPerItem m)) double)))

(defn- item-units
  "Expand one YM item into per-unit maps with shared identity fields.
   start-seq is the item_seq for the first unit; subsequent ones increment."
  [item start-seq]
  (let [n       (or (:count item) 1)
        article (:shopSku item)
        sku     (some-> (:marketSku item) str)
        price   (item-price item)]
    (mapv (fn [i]
            {:item-seq    (+ start-seq i)
             :article     article
             :sku         sku
             :barcode     nil
             :gross-price price
             :quantity    1})
          (range n))))

(defn- expand-units [items]
  (reduce (fn [{:keys [seq units]} item]
            (let [u (item-units item seq)]
              {:seq   (+ seq (count u))
               :units (into units u)}))
          {:seq 0 :units []}
          (or items [])))

(defn order->events
  "Transform one YM order raw row into ALL canonical item_events
   (ordered + delivered or cancelled depending on status). Returns []
   when no usable id / creationDate / items.

   Args:
     order        one element of /v2/campaigns/{id}/orders payload
     raw-data-id  audit pointer"
  [order raw-data-id]
  (let [order-id (:id order)
        creation (:creationDate order)
        ev-date  (->date creation)
        status   (:status order)
        upd-ts   (:statusUpdateDate order)
        upd-date (->date upd-ts)
        items    (:items order)]
    (if (or (nil? order-id) (nil? ev-date) (empty? items))
      []
      (let [{:keys [units]} (expand-units items)
            posting-id (str order-id)
            base       {:marketplace "ym"
                        :posting-id  posting-id
                        :status      status
                        :raw-data-id raw-data-id}
            ordered-evs (mapv #(merge base
                                      {:event-type "ordered"
                                       :event-date ev-date
                                       :event-ts   creation}
                                      %)
                              units)
            delivered-evs (when (contains? delivered-statuses status)
                            (mapv #(merge base
                                          {:event-type "delivered"
                                           :event-date (or upd-date ev-date)
                                           :event-ts   (or upd-ts creation)}
                                          %)
                                  units))
            cancelled-evs (when (cancelled-status? status)
                            (mapv #(merge base
                                          {:event-type "cancelled"
                                           :event-date (or upd-date ev-date)
                                           :event-ts   (or upd-ts creation)}
                                          %)
                                  units))]
        (into [] cat [ordered-evs (or delivered-evs []) (or cancelled-evs [])])))))
