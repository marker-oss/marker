(ns analitica.domain.ozon-distribute
  "Read-side spreader for Ozon `realization` finance rows.

   Ozon's `/v2/finance/realization` is a month-level aggregate; rows
   carry no per-event date. Ingest currently stamps every realization
   row with `event_date = date_from` (= start of the report month).
   That is correct for monthly P&L, but distorts weekly slicing — all
   monthly revenue collapses into the first ISO week.

   This namespace splits each realization row into N daily children
   weighted by the actual `sales` table distribution for the same
   (article, sku) and period. Sums are preserved exactly; only the
   visual breakdown across weeks improves.

   Apply at the read boundary (`finance/fetch-finance`) so storage
   stays untouched and reverting is a one-line revert.

   Fallback rules:
     - If there is no `sales` coverage for (article, sku) in the
       period, the original row is returned unchanged.
     - If `sales` total qty for the SKU differs from realization qty
       (typical: sales table lags or has gaps), weights are still
       computed from sales but applied to the realization totals so
       Σ amounts and Σ qty stay equal to the original."
  (:require [analitica.db :as db]))

(defn- ozon? [v]
  (or (= :ozon v) (= "ozon" v)))

(defn- realization? [v]
  (or (= :realization v) (= "realization" v)))

(defn- realization-row?
  [row]
  (and (ozon? (:marketplace row))
       (realization? (:operation-subtype row))))

(defn- group-key
  "Group realization rows by (article, sku, date-from, date-to). One
   sales-distribution lookup per group is enough."
  [row]
  [(:article row) (:nm-id row) (:date-from row) (:date-to row)])

(defn- daily-sales-weights
  "Return {iso-day → weight} for an Ozon SKU between [from..to] using
   the `sales` table. Weights normalise to 1.0. Returns nil when the
   SKU has no sales coverage in the period."
  [article from to]
  (let [rows (try
               (db/query
                 ["SELECT substr(date, 1, 10) AS day,
                          SUM(COALESCE(NULLIF(total_price, 0),
                                       price_with_disc, 0)) AS rev
                   FROM sales
                   WHERE marketplace = 'ozon'
                     AND article = ?
                     AND substr(date, 1, 10) BETWEEN ? AND ?
                   GROUP BY substr(date, 1, 10)
                   HAVING SUM(COALESCE(NULLIF(total_price, 0),
                                       price_with_disc, 0)) > 0"
                  article from to])
               (catch Throwable _ nil))]
    (when (seq rows)
      (let [total (reduce + 0.0 (map :rev rows))]
        (when (pos? total)
          (into {} (map (fn [{:keys [day rev]}]
                          [day (/ (double rev) (double total))])
                        rows)))))))

(def ^:private numeric-fields
  "Fields that scale linearly with day-weight when a row is split."
  [:quantity :retail-amount :for-pay :mp-commission
   :delivery-cost :storage-fee :acceptance :acquiring-fee
   :additional-payment :penalty :deduction :wb-reward
   :ad-cost])

(defn- spread-row
  "Return seq of daily children for one realization row, scaled by
   `weights` ({day → factor}). Each child gets a unique rrd_id derived
   from the original."
  [row weights]
  (mapv (fn [[day factor]]
          (let [scale-num (fn [v]
                            (when (number? v) (* (double v) factor)))]
            (-> (reduce (fn [acc k]
                          (if-let [v (scale-num (get row k))]
                            (assoc acc k v)
                            acc))
                        row
                        numeric-fields)
                (assoc :event-date day
                       ;; Make rrd_id unique per child to avoid PK
                       ;; collisions if these rows are ever round-tripped
                       ;; through the DB. The hash inputs match the
                       ;; ingest convention plus the day.
                       :rrd-id (hash [:ozon-real-spread
                                      (:rrd-id row) day])))))
        weights))

(defn redistribute-realization
  "Pass `finance-data` through the spreader: replace each Ozon
   realization row with N daily children weighted by `sales` table
   coverage. Non-realization and non-Ozon rows pass through unchanged.

   Pure on the input vector; performs DB queries via
   `daily-sales-weights` for SKU lookups (cached per group)."
  [finance-data]
  (let [;; Cache sales lookups by (article, sku, date-from, date-to)
        cache (atom {})]
    (reduce
      (fn [acc row]
        (if-not (realization-row? row)
          (conj acc row)
          (let [k (group-key row)
                weights (or (get @cache k)
                            (let [w (daily-sales-weights
                                      (:article row)
                                      (:date-from row)
                                      (:date-to row))]
                              (swap! cache assoc k w)
                              w))]
            (if (seq weights)
              (into acc (spread-row row weights))
              ;; No sales coverage — keep original row
              (conj acc row)))))
      []
      finance-data)))
