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
       Σ amounts and Σ qty stay equal to the original.

   Bug F note: Ozon orphan service rows (`operation = service`, written
   by `materialize-ozon-orphan-services!`) are also month-aggregates
   stamped with `event_date = date_from`. They get the same treatment,
   grouped by article only since they carry no sku."
  (:require [analitica.db :as db]))

(defn- ozon? [v]
  (or (= :ozon v) (= "ozon" v)))

(defn- realization? [v]
  (or (= :realization v) (= "realization" v)))

(defn- service-op? [row]
  (or (= "service" (:operation row))
      (= :service  (:operation row))
      (= "service" (:operation-kind row))
      (= :service  (:operation-kind row))))

(defn- spreadable-row?
  "An Ozon finance row whose event_date is artificially stamped at the
   start of the report month. Two flavours:
     - realization rows (sale/return) from /v2/finance/realization
     - orphan service rows from materialize-ozon-orphan-services!"
  [row]
  (and (ozon? (:marketplace row))
       (or (realization? (:operation-subtype row))
           (service-op? row))))

(defn- group-key
  "Group rows so one sales-distribution lookup serves every row in the
   bucket. SKU is part of the key for realization rows (they carry one);
   nil for orphan service rows so their distribution falls back to the
   article-only weights query."
  [row]
  [(:article row) (:nm-id row) (:date-from row) (:date-to row)])

(defn- daily-sales-weights
  "Return {iso-day → weight} for an Ozon (article, sku?) between
   [from..to] using the `sales` table. Weights normalise to 1.0.
   Tries (article, sku) first, falls back to (article) only when
   sku is nil or yields no rows. Returns nil if the article has no
   sales coverage in the window at all."
  [article sku from to]
  (let [base-sql "SELECT substr(date, 1, 10) AS day,
                          SUM(COALESCE(NULLIF(total_price, 0),
                                       price_with_disc, 0)) AS rev
                   FROM sales
                   WHERE marketplace = 'ozon'
                     AND article = ?
                     AND substr(date, 1, 10) BETWEEN ? AND ?"
        run     (fn [extra params]
                  (try
                    (db/query (into [(str base-sql extra
                                          " GROUP BY substr(date, 1, 10)
                                            HAVING SUM(COALESCE(NULLIF(total_price, 0),
                                                                price_with_disc, 0)) > 0")]
                                    params))
                    (catch Throwable _ nil)))
        rows    (or (when sku
                      (let [r (run " AND nm_id = ?" [article from to sku])]
                        (when (seq r) r)))
                    (run "" [article from to]))]
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
   realization or orphan-service row with N daily children weighted by
   `sales` table coverage. Non-spreadable rows pass through unchanged.

   Pure on the input vector; performs DB queries via
   `daily-sales-weights` for (article, sku) lookups (cached per group).
   The function name is kept for backward compatibility — semantics
   widened in 2026-05 to cover orphan service rows too (Bug F)."
  [finance-data]
  (let [cache (atom {})]
    (reduce
      (fn [acc row]
        (if-not (spreadable-row? row)
          (conj acc row)
          (let [k (group-key row)
                weights (or (get @cache k)
                            (let [w (daily-sales-weights
                                      (:article row)
                                      (:nm-id row)
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
