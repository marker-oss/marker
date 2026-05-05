(ns analitica.canonical.events.query
  "Read-side of the canonical event log. All call sites that need to know
   «сколько заказано / доставлено / отменено» go through here so the
   answer is identical whether asked by Pulse, UE, or a CSV export.

   No MP-specific logic — that's what normalizers (per-MP namespaces)
   are for. Counters here are pure SQL aggregates over item_events."
  (:require [analitica.db :as db]))

(defn- mp-clause
  "Build the SQL fragment that filters by marketplace. `mp` may be:
     :wb / :ozon / :ym  → narrows to that marketplace
     nil / :all         → no filter (cross-MP totals)"
  [mp]
  (cond
    (nil? mp)              ""
    (= :all mp)            ""
    :else                  (str " AND marketplace = '" (name mp) "'")))

(defn- count-events
  "Count item_events rows for the given event_type in [from..to],
   optionally narrowed by marketplace."
  [event-type from to mp]
  (let [sql (str "SELECT COUNT(*) AS n FROM item_events"
                 " WHERE event_type = ?"
                 "   AND event_date BETWEEN ? AND ?"
                 (mp-clause mp))
        rows (db/query [sql event-type from to])]
    (long (or (:n (first rows)) 0))))

(defn units-ordered
  "Items placed in orders during [from..to]. Counts each unit-row in
   item_events (one per unit, expanded from posting × product × quantity)."
  ([from to]    (units-ordered from to nil))
  ([from to mp] (count-events "ordered" from to mp)))

(defn units-delivered
  "Items that reached the buyer during [from..to]. Includes items that
   were later returned (returned implies delivered first)."
  ([from to]    (units-delivered from to nil))
  ([from to mp] (count-events "delivered" from to mp)))

(defn units-cancelled
  "Items cancelled during [from..to]. Event_date defaults to in_process_at
   when no explicit cancel timestamp is available."
  ([from to]    (units-cancelled from to nil))
  ([from to mp] (count-events "cancelled" from to mp)))

(defn units-returned
  "Items physically returned during [from..to]. Sourced from realization
   (Phase 5c.5) — wiring via materialize-ozon-events! is currently
   deferred, so this returns 0 until that lands."
  ([from to]    (units-returned from to nil))
  ([from to mp] (count-events "returned" from to mp)))

(defn buyout-rate-true
  "True buyout in the marketplace-analytics sense:
       (delivered − returned) / ordered

   Returns nil when ordered=0 (avoids divide-by-zero / fake 0%).
   This is the «Выкуп» that LK shows; differs from the legacy
   `pnl/:buyout-rate` which is `delivered / (delivered + returned)`."
  ([from to]    (buyout-rate-true from to nil))
  ([from to mp]
   (let [ord  (units-ordered   from to mp)
         del  (units-delivered from to mp)
         ret  (units-returned  from to mp)]
     (when (pos? ord)
       (* 100.0 (/ (double (- del ret)) (double ord)))))))

(defn counts-summary
  "All-in-one map for dashboards.
     {:ordered :delivered :cancelled :returned :buyout-rate-true}"
  ([from to]    (counts-summary from to nil))
  ([from to mp]
   {:ordered          (units-ordered   from to mp)
    :delivered        (units-delivered from to mp)
    :cancelled        (units-cancelled from to mp)
    :returned         (units-returned  from to mp)
    :buyout-rate-true (buyout-rate-true from to mp)}))
