(ns analitica.domain.sku
  "Per-SKU / per-article drill-down aggregator.

  sku-summary derives quantitative + cogs/revenue/margin metrics from
  the canonical finance table via finance/by-article (one article at a
  time). The sales table is only used for the sparkline (daily revenue
  by day) and recent-ops (operation log) — it has no quantity column
  and would mis-count multi-unit Ozon postings.

  Returns:
    {:article        string
     :nm-id          integer-or-nil
     :sales-count    int       ; unit count, sum of finance.quantity on sale rows
     :returns-count  int       ; unit count, sum of finance.quantity on return rows
     :revenue        double    ; sum of finance.retail_amount on sale rows (gross retail)
     :cogs           double    ; sum of cost_price × quantity on sale rows (linear)
     :margin-pct     double    ; (revenue - cogs) / revenue * 100, 0 if no revenue
     :roi            double    ; net profit ÷ cost-of-sales × 100, 0 if no cogs
     :daily-revenue  [{:date str :revenue double}]  ; one row per day, sales-derived
     :recent-ops     [{:date str :type str :marketplace str :amount double}]}"
  (:require [analitica.db :as db]
            [clojure.string :as str]
            [analitica.util.period :as period]
            [analitica.domain.finance :as finance]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- resolve-dates
  "Return [from to] strings. Defaults to last-30-days / today via period/resolve-preset."
  [from to]
  (let [[d-from d-to] (period/resolve-preset :last-30-days)]
    [(if (str/blank? from) (period/format-date d-from) from)
     (if (str/blank? to)   (period/format-date d-to)   to)]))

(defn- mp-clause [marketplace]
  (when marketplace " AND marketplace = ?"))

(defn- mp-params [base marketplace]
  (cond-> base marketplace (conj (name marketplace))))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(defn- fetch-sales-rows
  "Raw sales rows for one article."
  [article from to marketplace]
  (let [clause (mp-clause marketplace)
        params (mp-params [article from (str to "T23:59:59")] marketplace)]
    (db/query
     (into [(str "SELECT date, type, marketplace, for_pay, finished_price, nm_id
                  FROM sales
                  WHERE article = ? AND date >= ? AND date <= ?"
                 clause
                 " ORDER BY date DESC")]
           params))))

(defn- fetch-daily-revenue
  "Daily revenue (sum of for_pay on sale rows) for article."
  [article from to marketplace]
  (let [clause (mp-clause marketplace)
        params (mp-params [article from (str to "T23:59:59")] marketplace)]
    (db/query
     (into [(str "SELECT substr(date,1,10) AS day, SUM(for_pay) AS revenue
                  FROM sales
                  WHERE article = ? AND type = 'sale' AND date >= ? AND date <= ?"
                 clause
                 " GROUP BY day ORDER BY day")]
           params))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn sku-summary
  "Aggregate per-article metrics over [from to].
   article   — seller's internal article string (primary key in sales table)
   from / to — ISO date strings; default to last-30-days when blank/nil
   :marketplace — keyword (:wb/:ozon/:ym) or nil for all MPs

   :daily-revenue is sorted ASC by date (chronological, for sparkline rendering).
   :recent-ops is sorted DESC by date (newest first, for the operations table)."
  [article from to & {:keys [marketplace]}]
  (let [[from' to']   (resolve-dates from to)
        ;; Finance-derived: cogs, revenue, qty.
        ;; finance/by-article applies the canonical formulas (linear
        ;; cogs scaling, gross retail revenue) and handles Ozon's
        ;; daily-spread reconstruction transparently.
        fin-rows      (->> (finance/fetch-finance {:from from' :to to'}
                                                  :marketplace marketplace)
                           (filterv #(= article (or (:article %) ""))))
        agg           (first (finance/by-article fin-rows))
        sales-count   (long (or (:sales-qty   agg) 0))
        returns-count (long (or (:returns-qty agg) 0))
        revenue       (or (:revenue    agg) 0.0)
        for-pay       (or (:for-pay    agg) 0.0)
        cogs          (or (:total-cost agg) 0.0)
        margin-pct    (if (pos? revenue)
                        (* 100.0 (/ (- revenue cogs) revenue))
                        0.0)
        roi           (if (pos? cogs)
                        (* 100.0 (/ (- for-pay cogs) cogs))
                        0.0)
        ;; Sales-derived: nm-id, sparkline, recent operation log.
        rows          (fetch-sales-rows article from' to' marketplace)
        nm-id         (some :nm-id rows)
        daily-raw     (fetch-daily-revenue article from' to' marketplace)
        daily-revenue (mapv (fn [r] {:date    (:day r)
                                     :revenue (or (:revenue r) 0.0)})
                            daily-raw)
        recent-ops    (mapv (fn [r]
                              {:date        (subs (str (:date r)) 0 10)
                               :type        (name (or (:type r) :sale))
                               :marketplace (name (or (:marketplace r) :wb))
                               :amount      (let [fp (or (:for-pay r)
                                                         (:finished-price r) 0.0)
                                                  t  (name (or (:type r) :sale))]
                                              (if (= t "return") (- fp) fp))})
                            (take 10 rows))]
    {:article       article
     :nm-id         nm-id
     :sales-count   sales-count
     :returns-count returns-count
     :revenue       revenue
     :cogs          cogs
     :margin-pct    margin-pct
     :roi           roi
     :daily-revenue daily-revenue
     :recent-ops    recent-ops}))
