(ns analitica.domain.preliminary
  "Preliminary revenue computation for marketplaces whose canonical
   reporting source (realization / settlement) is delayed.

   Specific case: Ozon publishes `/v2/finance/realization` monthly with
   a multi-week delay; the current month's `finance` table is empty
   even though sales are happening. `cash_flow_periods` (from
   `/v1/finance/cash-flow-statement/list`) updates weekly in near-realtime
   and `orders_amount + returns_amount` matches `finance.for_pay`
   sale−return net. Empirically verified for Feb/Mar 2026:
     - Feb finance.net=345,646₽ vs cf.orders+returns=345,646₽
     - Mar finance.net=427,726₽ vs cf.orders+returns=427,726₽
     - Apr finance.net=0 vs cf.orders+returns=340,583₽ (preliminary)

   Use this namespace as a fallback ONLY when the canonical source is
   empty — never to override a published realization."
  (:require [analitica.db :as db]
            [analitica.util.period :as period]))

(defn ozon-preliminary-totals
  "Aggregate Ozon cash_flow_periods rows overlapping [from..to] into a
   preliminary revenue map. Returns nil when no cash-flow rows exist
   for the period.

   The returned :revenue field uses the same definition as
   `finance.for_pay net` (sale − return), not `retail_amount`. This
   is what the cash-flow API publishes as `orders_amount` /
   `returns_amount`. For Ozon the seller's economically meaningful
   number is for_pay net (what's actually owed); using it as a
   stand-in for `revenue` in P&L gives a number in the same
   ballpark (within ~25%) and is dramatically better than 0."
  [{:keys [from to]}]
  (let [rows (try
               (db/query
                 ["SELECT period_begin, period_end,
                          orders_amount, returns_amount, invoice_transfer
                   FROM cash_flow_periods
                   WHERE source = 'ozon'
                     AND period_begin <= ? AND period_end >= ?"
                  to from])
               (catch Throwable _
                 ;; Test DBs may not have cash_flow_periods provisioned;
                 ;; treat as "no data" rather than propagating, so callers
                 ;; that opt into the overlay never break on missing table.
                 nil))]
    (when (seq rows)
      ;; Pro-rate amounts by day-overlap with [from..to] so that a
      ;; weekly UI slice doesn't double-count the same cash-flow bucket
      ;; that also bleeds into the adjacent week. For a full-period
      ;; window every row gets factor=1.0, preserving the original
      ;; aggregate.
      (let [prorated (period/pro-rate-rows
                       rows
                       {:from from :to to
                        :numeric-keys [:orders-amount :returns-amount
                                       :invoice-transfer]})
            orders   (reduce + 0.0 (keep :orders-amount prorated))
            returns  (reduce + 0.0 (keep :returns-amount prorated))
            ;; Settled / pending classification stays per-row (not
            ;; weighted) — it's about whether a bucket has been paid
            ;; out, not about money amounts.
            settled  (filter #(not (zero? (or (:invoice-transfer %) 0))) rows)
            pending  (filter #(zero? (or (:invoice-transfer %) 0)) rows)]
        {:revenue          (+ orders returns)
         :gross-orders     orders
         :gross-returns    returns
         :as-of            (->> rows (keep :period-end) sort last)
         :periods-count    (count rows)
         :settled-periods  (count settled)
         :pending-periods  (count pending)}))))

(defn maybe-overlay-preliminary
  "Given a pnl/calculate result map and context, if its `:revenue` is
   zero AND the marketplace is :ozon AND a concrete period is provided,
   overlay preliminary revenue from cash_flow_periods. Returns either
   the original map unchanged or one with `:revenue` replaced and
   `:revenue-source` / `:preliminary?` / `:preliminary-as-of` set.

   Caller opt-in. P&L / digest / per-MP cards should call this; raw
   exports / audit-internal flows should not (they need the canonical
   number, even if it's 0)."
  [pnl-result {:keys [period marketplace]}]
  (if (and (zero? (or (:revenue pnl-result) 0))
           (= :ozon marketplace)
           (map? period) (:from period) (:to period))
    (if-let [prelim (ozon-preliminary-totals period)]
      (assoc pnl-result
             :revenue           (:revenue prelim)
             :revenue-source    :preliminary
             :preliminary?      true
             :preliminary-as-of (:as-of prelim)
             :preliminary-pending-periods (:pending-periods prelim))
      pnl-result)
    pnl-result))
