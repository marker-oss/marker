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
            [analitica.util.period :as period]
            [analitica.util.safe :as safe])
  (:import [java.time YearMonth]
           [java.time.format DateTimeFormatter]))

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

;; ---------------------------------------------------------------------------
;; FR-P4.4 — per-month Ozon realization state
;; ---------------------------------------------------------------------------

(defn classify-months
  "Pure helper. For each month string in `months-vec` return
   `{:month m :state s}` where:
     :settled     — finance-counts-map has a positive count for m
     :preliminary — month is in cashflow-months-set (but no finance rows)
     :missing     — neither source has data

   Returns a vector; order is preserved; empty vec for empty input."
  [months-vec finance-counts-map cashflow-months-set]
  (mapv (fn [m]
          {:month m
           :state (cond
                    (pos? (get finance-counts-map m 0)) :settled
                    (contains? cashflow-months-set m)   :preliminary
                    :else                               :missing)})
        months-vec))

(defn ozon-monthly-realization-states
  "Return one `{:month \"YYYY-MM\" :state :settled|:preliminary|:missing}`
   per calendar month in [from..to] inclusive (date strings \"YYYY-MM-DD\").

   :settled     — at least one canonical finance (realization) row exists
                  for that month (marketplace='ozon', event_date non-null).
   :preliminary — no finance rows but cash_flow_periods overlap the month.
   :missing     — no data from either source."
  [from to]
  (let [fmt      (DateTimeFormatter/ofPattern "yyyy-MM")
        ym-from  (YearMonth/from (.parse fmt (subs from 0 7)))
        ym-to    (YearMonth/from (.parse fmt (subs to   0 7)))
        months   (loop [cur ym-from acc []]
                   (if (.isAfter cur ym-to)
                     acc
                     (recur (.plusMonths cur 1)
                            (conj acc (.format cur fmt)))))

        ;; Count of Ozon canonical finance rows per YYYY-MM.
        ;; Ozon realization rows carry event_date = start-of-month
        ;; (after ozon-distribute); group by that.
        finance-rows
        (safe/safely
          (db/query
            ["SELECT strftime('%Y-%m', event_date) AS month,
                     COUNT(*)                      AS cnt
               FROM finance
               WHERE marketplace = 'ozon'
                 AND event_date IS NOT NULL
               GROUP BY 1"])
          []
          ::ozon-finance-count-failed)

        finance-counts
        (into {} (map (fn [r] [(or (:month r) (:MONTH r)) (or (:cnt r) 0)])
                      finance-rows))

        ;; Months covered by cash_flow_periods (source='ozon') that
        ;; overlap at least one of our target months — same overlap
        ;; predicate as ozon-preliminary-totals but for the full range.
        cf-rows
        (safe/safely
          (db/query
            ["SELECT period_begin, period_end
               FROM cash_flow_periods
               WHERE source = 'ozon'
                 AND period_begin <= ? AND period_end >= ?"
             to from])
          []
          ::ozon-cashflow-months-failed)

        ;; A cash-flow bucket belongs to month M when any day of M
        ;; falls within [period_begin..period_end]. We enumerate all
        ;; YYYY-MM values the bucket touches and intersect with our
        ;; target month list for efficiency.
        cashflow-months
        (into #{}
              (for [row   cf-rows
                    :let  [pb (or (:period-begin row) (:period_begin row))
                           pe (or (:period-end   row) (:period_end   row))]
                    :when (and pb pe)
                    m     months
                    :let  [m-begin (str m "-01")
                           m-end   (str m "-31")]
                    :when (and (<= (compare pb m-end)   0)
                               (>= (compare pe m-begin) 0))]
                m))]

    (classify-months months finance-counts cashflow-months)))

(defn maybe-overlay-preliminary
  "Given a pnl/calculate result map and context, if its `:revenue` is
   zero AND the marketplace is :ozon AND a concrete period is provided,
   overlay preliminary revenue from cash_flow_periods. Returns either
   the original map unchanged or one with `:revenue` replaced and
   `:revenue-source` / `:preliminary?` / `:preliminary-as-of` set.

   LT5 honesty: when the overlay fires AND canonical commission/COGS are
   absent (zero), stamps `:cost-source :preliminary-missing` and
   `:preliminary-cost-fields #{:cogs :commission}` on the result.
   Logistics/storage that ARE published remain real (no flag).
   The domain pnl-result numbers stay numeric (0, not nil) — nil is
   applied only in the presentation/envelope layer (marker.clj cost-line).

   Caller opt-in. P&L / digest / per-MP cards should call this; raw
   exports / audit-internal flows should not (they need the canonical
   number, even if it's 0)."
  [pnl-result {:keys [period marketplace]}]
  (if (and (zero? (or (:revenue pnl-result) 0))
           (= :ozon marketplace)
           (map? period) (:from period) (:to period))
    (if-let [prelim (ozon-preliminary-totals period)]
      (let [base (assoc pnl-result
                        :revenue           (:revenue prelim)
                        :revenue-source    :preliminary
                        :preliminary?      true
                        :preliminary-as-of (:as-of prelim)
                        :preliminary-pending-periods (:pending-periods prelim))
            ;; Commission and COGS are absent in a hollow Ozon realization
            ;; window — zero here means "not published", not a real zero.
            ;; Stamp a marker so the presentation layer can render
            ;; "нет данных" rather than fabricating profit from partial costs.
            commission-absent? (zero? (or (:mp-commission pnl-result) 0))
            cogs-absent?       (zero? (or (:cogs pnl-result) 0))]
        (if (or commission-absent? cogs-absent?)
          (assoc base
                 :cost-source            :preliminary-missing
                 :preliminary-cost-fields (cond-> #{}
                                            cogs-absent?       (conj :cogs)
                                            commission-absent? (conj :commission)))
          base))
      pnl-result)
    pnl-result))
