(ns analitica.domain.ozon-ads
  "Ozon campaign-efficiency analytics — spec 011 US3 (T040, P1).

   Reads the persisted per-campaign/per-day statistics (`ad_campaign_stats`,
   FR-013) and the attributed spend (`ad_spend`, FR-003/FR-004 from US1) and
   derives the efficiency ratios.

   FORMULAS (FR-014a — the Source of Truth; matches contracts/ingest-cli.edn
   :efficiency-report and tasks T034 byte-for-byte):

     CTR  = clicks / views          × 100   (%)
     CPC  = spend  / clicks                  (₽ per click)
     CPM  = spend  / views          × 1000   (₽ per 1000 views)
     CPO  = spend  / orders                  (₽ per order)
     CPS  = spend  / sales-units             (₽ per sold unit)
     CR   = orders / clicks         × 100   (%)
     ДРРз = spend  / orders-revenue × 100   (%, ORDERS basis — not realisation)
     ROAS = orders-revenue / spend           (return on ad spend — rev/spend,
                                              NOT (rev-spend)/spend)

   `sales-units` = Σ ordered quantity. The Ozon Performance stat set persists
   ordered UNITS in the `:orders` counter (Ozon reports ordered units, not a
   distinct order-count column), so CPS and CPO share the `:orders` denominator
   in the current schema but carry DISTINCT bases (see `metric-hints`). If a
   future stat set adds a separate order-count column, CPO switches to it while
   CPS keeps `:orders` (= ordered units), with no formula change here.

   N/A DISCIPLINE (FR-014a, consistent with GMROI): a ZERO denominator yields
   `nil` — rendered «—», NEVER 0 or ∞. A metric that IS computable but happens
   to be 0.0 (e.g. spend=0 with clicks>0 → CPC=0.0) stays a TRUE 0.0. An
   UNCOLLECTED counter (`:add-to-cart`, which the P0 slice never populated) is
   surfaced as `nil` so it is distinguishable from a counter that was collected
   and is genuinely 0.

   Per-article spend (FR-015): the `:per-article` grain rolls up `ad_spend`
   directly (Σ :spend GROUP BY article), so it is EXACTLY the US1 attribution —
   the efficiency report never re-derives spend, it reads the canon.

   See:
   - specs/011-ozon-performance-ads/research.md R9
   - specs/011-ozon-performance-ads/contracts/ingest-cli.edn :efficiency-report
   - docs/canonical-formulas.md §3.9 (P3 transparency)."
  (:require [analitica.db :as db]
            [analitica.util.math :as math]))

;; ---------------------------------------------------------------------------
;; Metric bases (P6 honesty) — surfaced on every report under :hints so the UI
;; can show what each ratio divides by. Keys mirror the metric keywords.
;; ---------------------------------------------------------------------------

(def metric-hints
  "Human-readable basis (denominator) of each efficiency metric (P6)."
  {:CTR  "clicks / views × 100 (%)"
   :CPC  "spend / clicks (₽ per click)"
   :CPM  "spend / views × 1000 (₽ per 1000 views)"
   :CPO  "spend / orders (₽ per order)"
   :CPS  "spend / sales-units (₽ per sold unit; sales-units = Σ ordered qty = :orders)"
   :CR   "orders / clicks × 100 (%)"
   :ДРРз "spend / orders-revenue × 100 (%, orders basis)"
   :ROAS "orders-revenue / spend (return on ad spend — rev/spend, not (rev−spend)/spend)"})

;; ---------------------------------------------------------------------------
;; Ratio helper — N/A on a zero/absent denominator (nil, never 0.0/∞).
;;
;; `math/safe-div` returns 0.0 on /0 (used elsewhere for per-unit averages),
;; which would MASK a missing denominator as a real 0. The efficiency report
;; needs the N/A discipline (FR-014a), so ratios divide via `ratio` below.
;; `math/percentage` already returns nil on /0, so the %-metrics reuse it.
;; ---------------------------------------------------------------------------

(defn- ratio
  "part / total (rounded to 2dp), or nil when total is zero/absent (N/A).
   Distinct from math/safe-div (which returns 0.0) — a zero denominator here
   is genuinely undefined, not a value of 0 (FR-014a N/A discipline)."
  [part total]
  (when (and total (not (zero? (double total))))
    (math/round2 (/ (double (or part 0.0)) (double total)))))

(defn campaign-metrics
  "Given one aggregated campaign stat map (counters summed over the period),
   return the map merged with the derived FR-014a ratio metrics.

   Raw counters expected (nil-coalesced to 0 where a value is present but nil):
     :views :clicks :orders :orders-revenue :spend  (and :bonus-spend)
   `:add-to-cart` is passed through UNCHANGED — if it is absent (uncollected in
   the P0 slice) it stays nil (N/A), distinguishable from a collected 0.

   Every ratio is nil when its denominator is zero (N/A, never 0 or ∞)."
  [{:keys [views clicks orders orders-revenue spend add-to-cart] :as stat}]
  (let [views  (or views 0)
        clicks (or clicks 0)
        orders (or orders 0)
        rev    (double (or orders-revenue 0.0))
        spend  (double (or spend 0.0))
        ;; sales-units = ordered units. The persisted stat set carries ordered
        ;; UNITS in :orders (Ozon reports units, not a separate order count).
        sales-units orders]
    (assoc stat
           ;; :add-to-cart preserved as-is: absent ⇒ nil (N/A, uncollected).
           :add-to-cart add-to-cart
           :CTR  (math/percentage clicks views)          ;; clicks/views × 100
           :CPC  (ratio spend clicks)                    ;; spend/clicks
           :CPM  (when (pos? views)                      ;; spend/views × 1000
                   (math/round2 (* 1000.0 (/ spend (double views)))))
           :CPO  (ratio spend orders)                    ;; spend/orders
           :CPS  (ratio spend sales-units)               ;; spend/sales-units
           :CR   (math/percentage orders clicks)         ;; orders/clicks × 100
           :ДРРз (math/percentage spend rev)             ;; spend/orders-rev × 100
           :ROAS (ratio rev spend))))                    ;; orders-rev/spend

;; ---------------------------------------------------------------------------
;; Aggregation
;; ---------------------------------------------------------------------------

(defn- sum-counters
  "Sum the raw stat counters across a campaign's daily rows into one map,
   keeping campaign identity. `:add-to-cart` sums only when every contributing
   row collected it; if ANY row lacks it (uncollected in P0) the sum is nil
   (N/A) so an uncollected counter never masquerades as a real 0."
  [rows]
  (let [first-row (first rows)
        num       (fn [k] (reduce (fn [a r] (+ a (double (or (get r k) 0.0)))) 0.0 rows))
        int-sum   (fn [k] (long (reduce (fn [a r] (+ a (long (or (get r k) 0)))) 0 rows)))
        atc-all?  (every? #(some? (:add-to-cart %)) rows)]
    {:campaign-id    (:campaign-id first-row)
     :campaign-type  (:campaign-type first-row)
     :campaign-name  (:campaign-name first-row)
     :views          (int-sum :views)
     :clicks         (int-sum :clicks)
     :add-to-cart    (when atc-all? (int-sum :add-to-cart))
     :orders         (int-sum :orders)
     :orders-revenue (math/round2 (num :orders-revenue))
     :spend          (math/round2 (num :spend))
     :bonus-spend    (math/round2 (num :bonus-spend))}))

(defn- per-campaign-rows
  "Aggregate ad_campaign_stats rows (day grain) into per-campaign metric rows."
  [stat-rows]
  (->> stat-rows
       (group-by :campaign-id)
       (map (fn [[_cid rows]] (campaign-metrics (sum-counters rows))))
       (sort-by :campaign-id)
       vec))

(defn- per-article-rows
  "Roll up ad_spend into per-article spend (FR-015): Σ :spend GROUP BY article,
   EXACTLY the US1 attribution (this reads the canon, it does not re-derive).
   Account-level rows (article nil) are grouped under a nil article key."
  [ad-spend-rows]
  (->> ad-spend-rows
       (group-by :article)
       (map (fn [[article rows]]
              {:article     article
               :spend       (math/round2 (reduce (fn [a r] (+ a (double (or (:spend r) 0.0)))) 0.0 rows))
               :bonus-spend (math/round2 (reduce (fn [a r] (+ a (double (or (:bonus-spend r) 0.0)))) 0.0 rows))}))
       (sort-by (fn [r] [(nil? (:article r)) (:article r)]))
       vec))

(defn efficiency-report
  "Campaign-efficiency report for `[from to]` on `:marketplace` (default :ozon).

   Returns:
     {:period        [from to]
      :marketplace   :ozon
      :per-campaign  [<campaign stat + CTR/CPC/CPM/CPO/CPS/CR/ДРРз/ROAS> …]
      :per-article   [{:article a :spend ₽ :bonus-spend ₽} …]   ;; == US1 attribution
      :hints         metric-hints}

   `:per-campaign` is derived from `ad_campaign_stats`; `:per-article` rolls up
   `ad_spend` (FR-015 — exact equality with US1). All ratios are N/A (nil) on a
   zero denominator (FR-014a)."
  [[from to] & {:keys [marketplace] :or {marketplace :ozon}}]
  (let [stat-rows (db/get-ad-campaign-stats marketplace from to)
        spend-rows (db/get-ad-spend marketplace from to)]
    {:period       [from to]
     :marketplace  marketplace
     :per-campaign (per-campaign-rows stat-rows)
     :per-article  (per-article-rows spend-rows)
     :hints        metric-hints}))
