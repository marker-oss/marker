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

(defn- already-spread? [row]
  ;; D1: 'spread' (real coverage) or 'flat' (no coverage, even-distributed)
  ;; mean the row is already a daily child. Re-spreading it would explode
  ;; rrd_ids and double-count.
  (#{"spread" "flat"} (:event-date-source row)))

(defn- spreadable-row?
  "An Ozon finance row whose event_date is artificially stamped at the
   start of the report month. Two flavours:
     - realization rows (sale/return) from /v2/finance/realization
     - orphan service rows from materialize-ozon-orphan-services!

   Idempotency: rows already daily-spread (event_date_source = 'spread'
   or 'flat') are skipped so respread-ozon-finance! can be re-run safely."
  [row]
  (and (ozon? (:marketplace row))
       (not (already-spread? row))
       (or (realization? (:operation-subtype row))
           (service-op? row))))

(defn- group-key
  "Group rows so one sales-distribution lookup serves every row in the
   bucket. SKU is part of the key for realization rows (they carry one);
   nil for orphan service rows so their distribution falls back to the
   article-only weights query."
  [row]
  [(:article row) (:nm-id row) (:date-from row) (:date-to row)])

(defn- enum-days
  "Inclusive seq of YYYY-MM-DD strings from `from` to `to`.
   Returns nil for degenerate input (unparseable date, from > to)."
  [from to]
  (try
    (let [f    (java.time.LocalDate/parse from)
          t    (java.time.LocalDate/parse to)
          n    (.until f t java.time.temporal.ChronoUnit/DAYS)]
      (when (>= n 0)
        (mapv #(str (.plusDays f %)) (range (inc n)))))
    (catch Throwable _ nil)))

(defn- flat-weights
  "Last-resort weights when the article has no posting/order coverage
   in the window: distribute revenue evenly across every day in
   [from..to]. Sums are still preserved (Σ weights = 1.0). Returns nil
   for a degenerate period."
  [from to]
  (when-let [days (enum-days from to)]
    (when (seq days)
      (let [n (count days)
            w (/ 1.0 n)]
        (into {} (map (fn [d] [d w]) days))))))

(defn- daily-sales-weights
  "Return {iso-day → weight} for an Ozon (article, sku?) between
   [from..to]. Weights normalise to 1.0.

   Source priority — first one that yields rows wins:
     1. `sales` (article, sku)         — finest grain, post-delivery.
     2. `sales` (article)              — fallback when sku missing.
     3. `orders` (article, sku)        — broader coverage; orders are
                                         logged earlier than sales (sales
                                         only appear after delivery, so
                                         late-month days often have
                                         orders but no sales yet).
     4. `orders` (article)             — fallback when sku missing.

   Returns nil if neither table has any matching rows in the window —
   the caller (`daily-weights`) then falls back to flat distribution."
  [article sku from to]
  ;; Each entry: [base-select-with-WHERE, having-clause]. The body
  ;; appends optional SKU filter and the GROUP-BY/HAVING tail.
  (let [sales-q "SELECT substr(date, 1, 10) AS day,
                        SUM(COALESCE(NULLIF(total_price, 0),
                                     price_with_disc, 0)) AS rev
                  FROM sales
                  WHERE marketplace = 'ozon'
                    AND article = ?
                    AND substr(date, 1, 10) BETWEEN ? AND ?"
        sales-h " GROUP BY substr(date, 1, 10)
                   HAVING SUM(COALESCE(NULLIF(total_price, 0),
                                       price_with_disc, 0)) > 0"
        ;; `orders` covers more days than `sales` because orders are
        ;; logged at order time (T+0) while sales need delivery (T+5+).
        ;; Per-day proportions are all we use, so the gross/net column
        ;; choice doesn't affect the output weights.
        orders-q "SELECT substr(date, 1, 10) AS day,
                         SUM(COALESCE(NULLIF(price_with_disc, 0),
                                      price, 0)) AS rev
                   FROM orders
                   WHERE marketplace = 'ozon'
                     AND article = ?
                     AND substr(date, 1, 10) BETWEEN ? AND ?"
        orders-h " GROUP BY substr(date, 1, 10)
                    HAVING SUM(COALESCE(NULLIF(price_with_disc, 0),
                                        price, 0)) > 0"
        run-q   (fn [base having extra params]
                  (try
                    (db/query (into [(str base extra having)] params))
                    (catch Throwable _ nil)))
        ;; Source priority: sales first (canonical, post-delivery),
        ;; orders as fallback when sales window is sparse. Within each
        ;; source, try (article, sku) first then (article).
        rows    (some (fn [[base h sku?]]
                        (let [r (if sku?
                                  (when sku
                                    (run-q base h " AND nm_id = ?"
                                           [article from to sku]))
                                  (run-q base h "" [article from to]))]
                          (when (seq r) r)))
                      [[sales-q  sales-h  true]
                       [sales-q  sales-h  false]
                       [orders-q orders-h true]
                       [orders-q orders-h false]])]
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

(defn- daily-weights
  "Return [source weights] tuple where source ∈ {:sales :orders :flat},
   distinguishing real coverage from last-resort flat distribution.
   Returns nil only on a degenerate window (unparseable dates).

   We don't preserve per-row source granularity (sales-by-sku vs
   sales-by-article vs orders-by-*) — the audit only needs to tell
   real coverage from synthesised."
  [article sku from to]
  (or (when-let [w (daily-sales-weights article sku from to)] [:spread w])
      (when-let [w (flat-weights from to)]                    [:flat   w])))

(defn- spread-row
  "Return seq of daily children for one realization row, scaled by
   `weights` ({day → factor}). Each child gets a unique rrd_id derived
   from the original. `tag` is the canonical event_date_source string
   ('spread' for real coverage, 'flat' for even-distributed fallback)."
  [row weights tag]
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
                       ;; through the DB. Tag goes into the hash so that
                       ;; spread-vs-flat children of the same source row
                       ;; never collide either.
                       :rrd-id (hash [:ozon-real-spread
                                      (:rrd-id row) day tag])
                       ;; D1: tag children so the next pass through
                       ;; spreadable-row? skips them (idempotency) and
                       ;; downstream audits can distinguish raw event
                       ;; dates from synthesised ones — and within
                       ;; synthesised, real coverage from flat guess.
                       :event-date-source tag))))
        weights))

(defn redistribute-realization
  "Pass `finance-data` through the spreader: replace each Ozon
   realization or orphan-service row with N daily children. Children
   are weighted by sales/orders coverage when available (tag = 'spread')
   and even-distributed across the period otherwise (tag = 'flat').
   Non-spreadable rows pass through unchanged.

   Pure on the input vector; performs DB queries via daily-weights
   for (article, sku) lookups (cached per group). The function name is
   kept for back-compat — semantics widened over time to cover orphan
   services (Bug F) and flat fallback (D1 Phase D)."
  [finance-data]
  (let [cache (atom {})]
    (reduce
      (fn [acc row]
        (if-not (spreadable-row? row)
          (conj acc row)
          (let [k     (group-key row)
                tw    (or (get @cache k)
                          (let [pair (daily-weights
                                       (:article row)
                                       (:nm-id row)
                                       (:date-from row)
                                       (:date-to row))]
                            (swap! cache assoc k pair)
                            pair))
                [tag weights] tw]
            (if (and tag (seq weights))
              (into acc (spread-row row weights (name tag)))
              ;; Truly degenerate (unparseable period etc.) — keep as-is.
              (conj acc row)))))
      []
      finance-data)))
