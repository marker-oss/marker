(ns analitica.domain.sku
  "Per-SKU / per-article drill-down aggregator.

  sku-summary pulls from sales + finance + cost_prices for a single
  article over [from to]. Uses direct parameterised DB queries so only
  one article's rows are loaded.

  Returns:
    {:article        string
     :nm-id          integer-or-nil
     :sales-count    int
     :returns-count  int
     :revenue        double   ; sum of for_pay on sale rows
     :cogs           double   ; cost_price * sales-count
     :margin-pct     double   ; (revenue - cogs) / revenue * 100, 0 if no revenue
     :roi            double   ; revenue / cogs, 0 if no cogs
     :daily-revenue  [{:date str :revenue double}]  ; one row per day
     :recent-ops     [{:date str :type str :marketplace str :amount double}]}"
  (:require [analitica.db :as db]
            [analitica.util.period :as period]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- today-str []
  (subs (str (java.time.LocalDate/now)) 0 10))

(defn- thirty-days-ago []
  (subs (str (.minusDays (java.time.LocalDate/now) 29)) 0 10))

(defn- resolve-dates
  "Return [from to] strings. Defaults to last-30-days."
  [from to]
  [(or (when (seq from) from) (thirty-days-ago))
   (or (when (seq to) to) (today-str))])

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

(defn- fetch-cost-price
  "Most recent cost_price for article. Returns nil if not found."
  [article]
  (first
   (db/query ["SELECT cost_price FROM cost_prices WHERE article = ? LIMIT 1"
               article])))

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
   :marketplace — keyword (:wb/:ozon/:ym) or nil for all MPs"
  [article from to & {:keys [marketplace]}]
  (let [[from' to']   (resolve-dates from to)
        rows          (fetch-sales-rows article from' to' marketplace)
        sales-rows    (filter #(= "sale"   (name (or (:type %) ""))) rows)
        return-rows   (filter #(= "return" (name (or (:type %) ""))) rows)
        sales-count   (count sales-rows)
        returns-count (count return-rows)
        revenue       (reduce + 0.0 (map #(or (:for-pay %) 0.0) sales-rows))
        nm-id         (some :nm-id rows)
        cp-row        (fetch-cost-price article)
        cogs          (if cp-row
                        (* (or (:cost-price cp-row) 0.0) sales-count)
                        0.0)
        margin-pct    (if (pos? revenue)
                        (* 100.0 (/ (- revenue cogs) revenue))
                        0.0)
        roi           (if (pos? cogs)
                        (/ revenue cogs)
                        0.0)
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
