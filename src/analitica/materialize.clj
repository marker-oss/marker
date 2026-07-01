(ns analitica.materialize
  "Materialize layer: read raw data from raw_data table, transform, write to analytical tables.
   Works entirely offline — no API calls needed."
  (:require [analitica.db :as db]
            [analitica.domain.finance-row :as frow]
            [analitica.domain.ozon-distribute :as ozon-distribute]
            [analitica.schema.normalized.stocks :as stocks-schema]
            [analitica.sync :as sync]
            [analitica.marketplace.wb.transform :as wb-t]
            [analitica.marketplace.ozon.transform :as ozon-t]
            [analitica.marketplace.ym.transform :as ym-t]
            [analitica.util.time :as t]
            [analitica.util.safe :as safe]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn- log-bad-finance-rows!
  "Side-effecting: log any rows that didn't match the canonical finance-row
   contract. Does NOT filter them out — we persist whatever transform
   produced, because raw_data is the source of truth and we can always
   re-materialize after fixing the schema. Returns `rows` unchanged."
  [rows source]
  (let [{:keys [bad]} (frow/validate-rows rows)]
    (when (seq bad)
      (println (str "  [finance-row validation] " source ": "
                    (count bad) "/" (count rows) " rows failed canonical contract"))
      (println (frow/summarize-bad bad)))
    rows))

(defn- log-bad-stocks-rows!
  "Side-effecting: log any stocks rows that didn't match the canonical
   StocksRow contract. Same persist-anyway policy as finance — raw_data
   is the source of truth. Returns `rows` unchanged."
  [rows source]
  (let [{:keys [bad]} (stocks-schema/validate-rows rows)]
    (when (seq bad)
      (println (str "  [stocks-row validation] " source ": "
                    (count bad) "/" (count rows) " rows failed canonical contract")))
    rows))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- resolve-period [period]
  (cond
    (keyword? period) (t/period period)
    (vector? period)  period
    :else             [(:from period) (:to period)]))

(defn- load-raw
  "Load all raw data items for source/entity_type in date range.
   Returns a flat seq of all items across all batches."
  [source entity-type from to]
  (let [batches (db/get-raw-range source entity-type from to)]
    (mapcat :data batches)))

(defn- load-raw-exact
  "Load raw data for exact date match (point-in-time entities like stocks/prices).
   Returns the data directly."
  [source entity-type date]
  (db/get-raw source entity-type date date))

;; ---------------------------------------------------------------------------
;; Orders
;; ---------------------------------------------------------------------------

(defn- transform-orders [source raw-items]
  (case source
    "wb"   (wb-t/->orders raw-items)
    "ozon" (ozon-t/->orders raw-items)
    "ym"   (ym-t/->orders raw-items)))

(defn materialize-orders!
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [[from to]    (resolve-period period)
        source       (name marketplace)
        ;; Ozon uses "postings" entity type; WB/YM use "orders"
        entity-type  (if (= marketplace :ozon) :postings :orders)
        raw-items    (load-raw source entity-type from to)
        data         (transform-orders source raw-items)
        ;; WB impl filters by date range (API returns broader results)
        data         (if (= marketplace :wb)
                       (filterv #(and (<= (compare from (subs (:date %) 0 10)) 0)
                                      (>= (compare to   (subs (:date %) 0 10)) 0))
                                data)
                       data)
        ;; orders.article is NOT NULL — drop rows that came back without
        ;; one (cancelled YM orders / Ozon postings with no items). A
        ;; single such row would 23 the whole batch insert.
        data         (filterv :article data)
        rows         (mapv sync/order->row data)
        cnt          (db/insert-batch! :orders sync/orders-columns rows)]
    (println (str "Materialized orders: " cnt))
    cnt))

;; ---------------------------------------------------------------------------
;; Sales
;; ---------------------------------------------------------------------------

(defn- transform-sales [source raw-items]
  (case source
    "wb"   (wb-t/->sales raw-items)
    "ozon" (ozon-t/->sales raw-items)
    "ym"   (ym-t/->sales raw-items)))

(defn materialize-sales!
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [[from to]    (resolve-period period)
        source       (name marketplace)
        entity-type  (case marketplace
                       :ozon :postings  ;; shared with orders
                       :ym   :orders    ;; YM orders and sales use same raw data
                       :sales)
        raw-items    (load-raw source entity-type from to)
        data         (transform-sales source raw-items)
        ;; WB and Ozon may receive postings outside [from..to] (WB: weekly
        ;; report shape; Ozon: in_process_at-60d window catches cross-month
        ;; deliveries). Drop sales whose :date falls outside the window.
        data         (if (#{:wb :ozon} marketplace)
                       (filterv #(and (some? (:date %))
                                      (<= (compare from (subs (:date %) 0 10)) 0)
                                      (>= (compare to   (subs (:date %) 0 10)) 0))
                                data)
                       data)
        ;; sales.article is NOT NULL — drop articleless rows for the same
        ;; reason as materialize-orders!.
        data         (filterv :article data)
        rows         (mapv sync/sale->row data)
        cnt          (db/insert-batch! :sales sync/sales-columns rows)]
    (println (str "Materialized sales: " cnt))
    cnt))

;; ---------------------------------------------------------------------------
;; Finance
;; ---------------------------------------------------------------------------

(defn- transform-finance [source raw-items]
  (case source
    "wb"   (wb-t/->finance-report raw-items)
    "ozon" (let [sku-map (db/ozon-sku-map)]
             (ozon-t/->finance-report raw-items sku-map))
    "ym"   (ym-t/->finance-from-order-stats raw-items)))

(defn- materialize-ozon-finance-from-realization!
  "Ozon-specific path: load raw_data rows of entity_type=:realization
   (one per month, shape {:header {...} :rows [{...}]}) and flatten them
   through ozon-t/->finance-from-realization. Replaces any existing
   Ozon rows for the covered period."
  [from to]
  (let [batches      (db/get-raw-range "ozon" :realization from to)
        finance-rows (into [] (mapcat (fn [{:keys [data]}]
                                        (ozon-t/->finance-from-realization data))
                                      batches))
        _            (log-bad-finance-rows! finance-rows "ozon")
        rows         (mapv sync/finance->row finance-rows)]
    (when (seq batches)
      ;; Clear every Ozon finance row whose date range intersects any
      ;; realization month we're about to re-materialise. Uses substring
      ;; comparison to cover legacy transaction-list rows stored with
      ;; timestamp-style keys like "2026-03-31 00:00:00".
      (doseq [{:keys [date-from date-to]} batches]
        ;; date-to is the last day of the month; bumping to lexicographic
        ;; "date-to plus space" includes any "YYYY-MM-DD HH:MM:SS" row
        ;; whose date half falls inside the month.
        (db/execute! ["DELETE FROM finance WHERE marketplace = 'ozon'
                       AND substr(date_from, 1, 10) >= ?
                       AND substr(date_to,   1, 10) <= ?"
                      date-from date-to])))
    (let [cnt (db/insert-batch! :finance sync/finance-columns rows)]
      (println (str "Materialized Ozon finance from realization: " cnt))
      cnt)))

;; Forward declarations: functions defined later in the file that are
;; referenced from materialize-finance!'s dispatch branches.
(declare materialize-ozon-services!)
(declare materialize-ozon-orphan-services!)
(declare materialize-wb-ad-stats!)
(declare materialize-wb-ad-cost!)

(defn respread-ozon-finance!
  "D1 step 4: read all currently-stored Ozon finance rows that overlap
   [from..to], pass them through ozon-distribute/redistribute-realization,
   and replace the originals with their daily children where spread fired.

   Runs as the final step of the Ozon `materialize-finance!` pipeline so
   that service-cost merges (step 2) and orphan-service inserts (step 3)
   are also distributed across days, not concentrated on the single
   pre-spread row. Idempotent: rows already tagged event_date_source =
   'spread' are skipped by spreadable-row?, and pass through unchanged.

   Returns the post-spread row count for the period."
  [from to]
  (let [;; Read every Ozon row whose period intersects the requested window.
        ;; Deliberately wide (substring on date-from/date-to) so that month-
        ;; aggregate realization rows show up regardless of day alignment.
        rows         (db/query
                       ["SELECT * FROM finance
                         WHERE marketplace = 'ozon'
                           AND substr(date_from, 1, 10) <= ?
                           AND substr(date_to,   1, 10) >= ?"
                        to from])
        ;; DB uses snake_case, transform layer uses kebab — re-normalise
        ;; here so spreadable-row? sees the canonical shape.
        kebab-rows   (mapv (fn [r]
                             (-> r
                                 (assoc :event-date-source (:event-date-source r))
                                 (assoc :date-from (:date-from r))
                                 (assoc :date-to (:date-to r))
                                 (assoc :rrd-id (:rrd-id r))
                                 (assoc :marketplace (some-> (:marketplace r) keyword))
                                 (assoc :operation-subtype (:operation-subtype r))
                                 (assoc :nm-id (:nm-id r))))
                           rows)
        spread       (ozon-distribute/redistribute-realization kebab-rows)
        ;; Was anything actually spread? If output count == input count
        ;; AND every row is unchanged, we can skip the DELETE+INSERT.
        any-changed? (or (not= (count spread) (count rows))
                         (some #(= "spread" (:event-date-source %)) spread))]
    (if-not any-changed?
      0
      ;; SQLite doesn't support nested transactions and `insert-batch!`
      ;; opens its own with-transaction inside. Run DELETE then INSERT
      ;; sequentially without an outer wrapper. A process crash between
      ;; the two leaves the period empty for the next run, but raw_data
      ;; is the source of truth — re-running materialize fully recovers.
      (do
        (db/execute! ["DELETE FROM finance
                       WHERE marketplace = 'ozon'
                         AND substr(date_from, 1, 10) <= ?
                         AND substr(date_to,   1, 10) >= ?"
                      to from])
        (let [out-rows (mapv sync/finance->row spread)]
          (db/insert-batch! :finance sync/finance-columns out-rows))))))

(defn materialize-finance!
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [[from to] (resolve-period period)]
    (case marketplace
      :ozon
      ;; Five-step Ozon pipeline (spec 003 US3B + T047 + D1 + spec 004
      ;; canonical events):
      ;;   1. realization → finance rows (for-pay, retail, commission).
      ;;   2. transaction/list services[] merge → cost fields per article.
      ;;   3. orphan-service INSERT (T047): service-only rows for articles
      ;;      without a sale-row in the realization window.
      ;;   4. respread-ozon-finance! (D1): replace month-stamped rows with
      ;;      daily children. Idempotent.
      ;;   5. canonical item_events: ordered/delivered/cancelled (Phase 5)
      ;;      so Pulse and UE see one count per concept. Returns deferred
      ;;      to Phase 5c.5 (realization-based, not transactions).
      ;; Steps 2–3 silently no-op when no :transactions raw_data exists.
      (let [cnt (materialize-ozon-finance-from-realization! from to)]
        (when (seq (db/get-raw-range "ozon" :transactions from to))
          (let [{:keys [orphan-aggregates]} (materialize-ozon-services! [from to])]
            (materialize-ozon-orphan-services! [from to] orphan-aggregates)))
        (respread-ozon-finance! from to)
        ((requiring-resolve 'analitica.canonical.events.materialize/materialize-ozon-events!)
         from to)
        cnt)

      :wb
      ;; Three-step WB pipeline (spec 003 US5 T039 + spec 004 Phase 5f):
      ;;   1. finance report → finance rows (same as other marketplaces).
      ;;   2. IF ad_stats raw_data exists for the period → populate
      ;;      finance.ad_cost per B-003 proportional-to-revenue rule.
      ;;   3. canonical item_events from orders/sales raw (Phase 5f).
      ;; Steps 2-3 silently no-op when no source raw_data is present, so
      ;; this change is a pure superset of the old WB behaviour.
      (let [source    "wb"
            raw-items (load-raw source :finance from to)
            data      (transform-finance source raw-items)
            _         (log-bad-finance-rows! data source)
            rows      (mapv sync/finance->row data)
            cnt       (db/insert-batch! :finance sync/finance-columns rows)]
        (println (str "Materialized finance: " cnt))
        (when (seq (db/get-raw-range source :ad_stats from to))
          (materialize-wb-ad-stats! [from to])
          (materialize-wb-ad-cost! [from to]))
        ((requiring-resolve 'analitica.canonical.events.materialize/materialize-wb-events!)
         from to)
        cnt)

      ;; Default path (e.g. :ym): plain finance materialize + canonical
      ;; item_events (Phase 5g for YM; the dispatch is generic so other
      ;; future MPs only need a normalizer ns and a registration here).
      (let [source    (name marketplace)
            raw-items (load-raw source :finance from to)
            data      (transform-finance source raw-items)
            _         (log-bad-finance-rows! data source)
            rows      (mapv sync/finance->row data)
            cnt       (db/insert-batch! :finance sync/finance-columns rows)]
        (println (str "Materialized finance: " cnt))
        (when (= :ym marketplace)
          ((requiring-resolve 'analitica.canonical.events.materialize/materialize-ym-events!)
           from to))
        cnt))))

(defn- ym-finance-raw-periods
  "Distinct [date_from date_to] pairs of stored YM finance raw_data batches.
   Read-only — never mutates raw_data. Ordered by date_from for stable runs."
  []
  (->> (db/query ["SELECT DISTINCT date_from, date_to FROM raw_data
                   WHERE source = 'ym' AND entity_type = 'finance'
                   ORDER BY date_from"])
       (mapv (fn [{:keys [date-from date-to]}] [date-from date-to]))))

(defn rematerialize-ym-finance!
  "Historical correction (spec 012 US3 / FR-010): re-run the finance
   materialize pipeline for stored YM raw under the corrected price basis
   (gross = BUYER + subsidy, net-sales = BUYER, for-pay = BUYER − commissions).

   Thin wrapper over `materialize-finance! period :marketplace :ym` — reads the
   already-stored raw_data (source=\"ym\", entity_type=\"finance\"), applies the
   current transform, and INSERTs over the finance table. Does NOT call the YM
   API and does NOT mutate raw_data (P5). Idempotent: repeated runs of the same
   period yield identical rows (INV-7). Marketplace is always :ym — WB/Ozon
   rows are never touched (INV-5).

   Args:
     :period \"YYYY-MM\"  — re-materialize a single YM month (raw must exist).
     :all    true       — re-materialize every YM period with stored raw.

   Returns the total finance-row count materialized across periods."
  [& {:keys [period all]}]
  (let [all-periods (ym-finance-raw-periods)
        periods (cond
                  all    all-periods
                  ;; Resolve a "YYYY-MM" period against the stored raw batches
                  ;; by month prefix on date_from. This uses the exact month
                  ;; bounds already recorded in raw_data (no YearMonth parsing)
                  ;; and guarantees the period actually has raw to re-run.
                  period (filterv (fn [[from _to]]
                                    (and from (.startsWith ^String from period)))
                                  all-periods)
                  :else  (throw (ex-info "rematerialize-ym-finance! needs :period or :all"
                                         {:period period :all all})))]
    (when (empty? periods)
      (println "No stored YM finance raw to re-materialize."))
    (reduce (fn [total [from to]]
              (println (str "Re-materializing YM finance " from ".." to " …"))
              (+ total (or (materialize-finance! [from to] :marketplace :ym) 0)))
            0
            periods)))

;; ---------------------------------------------------------------------------
;; Ozon hybrid service merge (US3B / spec 003-finance-row-completeness)
;;
;; Pre-req: raw_data contains BOTH `:realization` (monthly per-article reports)
;; and `:transactions` (per-operation audit trail with services[] arrays) for
;; the same period. The realization path has already populated the finance
;; table with sale/return rows (keyed by a hashed rrd-id).
;;
;; This function reads the transaction raw, converts operations → service-rows
;; via `ozon-t/tx-op->service-rows`, pre-aggregates cost contributions per
;; (article, month, field) IN-MEMORY, then UPDATEs the existing realization
;; finance-rows with these accumulated values. The pre-aggregation guarantees
;; idempotency (running twice yields the same absolute SET value — never
;; double-adds).
;;
;; Design invariants:
;;   - UPDATE-only: never INSERT new finance rows. Orphan postings / missing
;;     target rows are skipped with a mu/log event.
;;   - B-005: `for_pay`, `retail_amount`, `mp_commission` are NOT touched
;;     — those fields remain fully owned by the realization path.
;;   - Only cost-fields can be written:
;;       :delivery-cost :acquiring-fee :acceptance :storage-fee
;;       :additional-payment :ad-cost
;;   - The merge targets the "sale" realization row for each (article, month);
;;     if a month has only returns, those get the cost too. If a month has both,
;;     the cost aggregates once on the sale row (to avoid duplicate attribution).
;; ---------------------------------------------------------------------------

(def ^:private ozon-cost-fields
  "Canonical list of FinanceRow fields that the service merge can populate.
   Kept narrow intentionally — widening this list risks violating B-005.
   Phase 4 (2026-05-05): :return-logistics + :dropoff-cost added when the
   ozon-service-mapping was split for LK Накопления row-by-row sverka."
  [:delivery-cost :return-logistics :dropoff-cost
   :acquiring-fee :acceptance :storage-fee
   :additional-payment :ad-cost])

(def ^:private ozon-cost-field->column
  {:delivery-cost      :delivery_cost
   :return-logistics   :return_logistics
   :dropoff-cost       :dropoff_cost
   :acquiring-fee      :acquiring_fee
   :acceptance         :acceptance
   :storage-fee        :storage_fee
   :additional-payment :additional_payment
   :ad-cost            :ad_cost})

(defn- build-article-lookup
  "Build a merged {sku offer_id} map from two sources:
   1. Realization raw_data rows for [from..to] — covers only items sold/returned
      in the window (~100–200 SKUs for one month).
   2. The persistent `ozon_sku_map` populated by `sync-sku-map!` — covers the
      full product catalog (all FBO/FBS/SDS SKUs across the shop).

   The catalog map is loaded as a base, then realization entries are merged on
   top (realization is authoritative for the window's actual items). Without the
   catalog fallback, service-rows for SKUs not present in the window's
   realization are dropped — e.g. return-logistics for items sold months ago,
   or cross-border logistics legs that reference warehouse SKUs."
  [from to]
  (let [catalog (try (db/ozon-sku-map) (catch Exception _ nil))
        batches (db/get-raw-range "ozon" :realization from to)
        from-realization
        (reduce
          (fn [acc {:keys [data]}]
            (reduce (fn [m rrow]
                      (let [item    (get rrow :item)
                            sku     (get item :sku)
                            article (get item :offer_id)]
                        (if (and sku article) (assoc m sku article) m)))
                    acc
                    (get data :rows [])))
          {}
          batches)]
    (merge (or catalog {}) from-realization)))

(defn- load-transactions-operations
  "Collect all operations from raw_data rows of entity_type=:transactions
   for [from..to]. Raw stored shape: {:operations [...]}.

   Dedupes by `:operation_id` — Ozon transaction chunks routinely overlap
   (e.g. a 30-day chunk and a 7-day chunk both cover the same week).
   Without dedup the same op contributes its services N times, inflating
   acquiring/delivery_cost/storage by the chunk-overlap factor."
  [from to]
  (let [batches (db/get-raw-range "ozon" :transactions from to)
        all-ops (mapcat (fn [{:keys [data]}] (get data :operations [])) batches)]
    (vec (vals (reduce (fn [acc op]
                         (let [k (get op :operation_id)]
                           (if (and k (contains? acc k)) acc (assoc acc k op))))
                       (array-map)
                       all-ops)))))

(defn- aggregate-service-contributions
  "Pre-aggregate service-rows by (article, month, field). Returns a map
   {[article month field] total-amount}. Idempotent: same input → same map."
  [service-rows]
  (reduce
    (fn [acc row]
      (let [article (:article row)
            month   (:date-from row)]
        (reduce
          (fn [a field]
            (if-let [v (get row field)]
              (update a [article month field] (fnil + 0.0) v)
              a))
          acc
          ozon-cost-fields)))
    {}
    service-rows))

(defn- pick-target-rrd-id
  "Deterministically pick the rrd_id of one finance row for (article, month)
   on which to concentrate service costs. Prefers the sale operation (when
   present); falls back to any operation. Smallest rrd_id wins — stable
   across re-runs so the UPDATE is idempotent."
  [tx article month]
  (let [row (jdbc/execute-one!
              tx
              [(str "SELECT rrd_id AS picked FROM finance"
                    " WHERE marketplace='ozon' AND article = ?"
                    "   AND substr(date_from, 1, 10) = ?"
                    " ORDER BY CASE WHEN operation='sale' THEN 0 ELSE 1 END,"
                    "          rrd_id"
                    " LIMIT 1")
               article month])]
    (when row
      ;; next.jdbc returns keys as either :finance/picked or :picked depending
      ;; on builder-fn; support both for robustness.
      (or (get row :finance/picked)
          (get row :picked)))))

(defn- update-contribution!
  "UPDATE EXACTLY ONE finance row for (article, month), setting the named
   cost field to `amount`. There may be multiple realization rows for the
   same (article, month) (e.g. several sales within the month) — to keep
   reconciliation clean and idempotent, we deterministically pick the row
   via `pick-target-rrd-id` and concentrate the full monthly cost there.

   Returns 1 when a row was updated, 0 when the target doesn't exist
   (orphan posting)."
  [tx column amount article month]
  (if-let [target-rrd (pick-target-rrd-id tx article month)]
    (let [col-name (name column)]
      (jdbc/execute-one!
        tx
        [(str "UPDATE finance SET " col-name " = ?"
              " WHERE marketplace='ozon' AND rrd_id = ?")
         (double amount) target-rrd])
      1)
    0))

(defn materialize-ozon-services!
  "Merge per-article service costs from transaction/list raw_data into the
   existing Ozon finance rows (produced earlier by the realization path).

   `period` is a [from to] vector of ISO dates, a period keyword, or a
   {:from :to} map (see `resolve-period`).

   Returns a summary map: {:updates N :orphans M :service-rows K
                           :orphan-aggregates {[article month field] amount}}.
   `:orphan-aggregates` is consumed by `materialize-ozon-orphan-services!`
   (T047) to INSERT service-only rows for articles that lack a sale row in
   the current window."
  [period]
  (let [[from to]      (resolve-period period)
        article-lookup (build-article-lookup from to)
        operations     (load-transactions-operations from to)
        service-rows   (into [] (mapcat #(ozon-t/tx-op->service-rows % article-lookup)
                                        operations))
        agg            (aggregate-service-contributions service-rows)
        orphans        (atom 0)
        updates        (atom 0)
        orphan-agg     (atom {})]
    (jdbc/with-transaction [tx (db/ds)]
      (doseq [[[article month field] amount] agg]
        (let [col (ozon-cost-field->column field)
              n   (update-contribution! tx col amount article month)]
          (if (pos? n)
            (swap! updates inc)
            (do (swap! orphans inc)
                (swap! orphan-agg assoc [article month field] amount)
                (mu/log ::orphan-posting
                        :marketplace :ozon
                        :article     article
                        :period      month
                        :field       field
                        :amount      amount))))))
    (println (str "Materialized Ozon services: " @updates " UPDATEs, "
                  @orphans " orphans, "
                  (count service-rows) " service-rows from "
                  (count operations) " operations"))
    {:updates           @updates
     :orphans           @orphans
     :service-rows      (count service-rows)
     :orphan-aggregates @orphan-agg}))

;; ---------------------------------------------------------------------------
;; Ozon orphan-service INSERT (T047 / B-009 fix)
;;
;; After `materialize-ozon-services!` runs (UPDATE-only), some service-rows
;; have no target sale/return row in the realization path — typically return
;; logistics for items sold in prior months. Those contributions were logged
;; as ::orphan-posting but their ₽ amounts never reached `finance`, producing
;; the ~20% SC-003 reconciliation gap measured in B-009.
;;
;; This function re-computes the aggregation, picks the orphan keys (those
;; for which `pick-target-rrd-id` returns nil), and INSERTs ONE service-only
;; finance row per (article, month, field) triple.
;;
;; Invariants:
;;   - `operation = "service"` (new canonical value; domain/finance.clj
;;     filters sales/returns by operation so revenue aggregations are not
;;     disturbed; cost-field aggregations naturally pick up these rows).
;;   - `for_pay = 0` — B-005 holds: SUM(for_pay on sale rows) unchanged.
;;   - `retail_amount = 0`, `retail_price = 0`, `quantity = 0`.
;;   - `rrd_id` is deterministic via `String.hashCode` so re-runs hit the
;;     same natural-key (marketplace, rrd_id) and INSERT OR REPLACE is a
;;     no-op (idempotent).
;; ---------------------------------------------------------------------------

(defn- month-bounds
  "Given `YYYY-MM-01` first-of-month, return [first-of-month last-of-month]."
  [month-first]
  (let [year  (Integer/parseInt (subs month-first 0 4))
        mon   (Integer/parseInt (subs month-first 5 7))
        ym    (java.time.YearMonth/of year mon)
        first (.atDay ym 1)
        last  (.atEndOfMonth ym)]
    [(.toString first) (.toString last)]))

(defn- orphan-rrd-id
  "Deterministic rrd_id for an orphan service-row. Same (article, month, field)
   always hashes to the same value → natural-key PK guards duplicates."
  [article month field]
  (Math/abs (long (.hashCode (str ":ozon-orphan-" article "-" month "-" (name field))))))

(defn- build-orphan-finance-row
  "Build a FinanceRow map for one orphan (article, month, field) triple."
  [article month field amount]
  (let [[date-from date-to] (month-bounds month)
        base {:marketplace  :ozon
              :rrd-id       (orphan-rrd-id article month field)
              :report-id    nil
              :date-from    date-from
              :date-to      date-to
              ;; Orphan service has no per-event date — use month-first
              ;; so it falls inside any event_date BETWEEN query covering the month.
              :event-date   date-from
              :article      article
              :nm-id        nil
              :barcode      nil
              :subject      nil
              :brand        nil
              :operation    "service"
              :doc-type     nil
              :quantity     0
              :retail-price 0.0
              :retail-amount 0.0
              :for-pay      0.0}]
    (assoc base field (double amount))))

(defn materialize-ozon-orphan-services!
  "After `materialize-ozon-services!` runs (UPDATE-only), INSERT a service-only
   finance-row for each (article, month, field) triple whose target sale-row
   didn't exist in the realization window. Closes B-009 / SC-003 gap.

   Optionally accepts `orphan-aggregates` to skip recomputation when the
   caller has the map from `materialize-ozon-services!`. Defaults to running
   the aggregation again so the function can stand alone.

   Returns {:inserted N :skipped-existing M :orphan-fields K}.
   Idempotent: re-running yields identical DB state."
  ([period]
   (materialize-ozon-orphan-services! period nil))
  ([period orphan-aggregates]
   (let [[from to]      (resolve-period period)
         aggregates     (or orphan-aggregates
                            ;; Recompute orphans from raw data
                            (let [article-lookup (build-article-lookup from to)
                                  operations     (load-transactions-operations from to)
                                  service-rows   (into [] (mapcat #(ozon-t/tx-op->service-rows % article-lookup)
                                                                  operations))
                                  agg            (aggregate-service-contributions service-rows)]
                              (jdbc/with-transaction [tx (db/ds)]
                                (reduce-kv
                                  (fn [acc [article month field] amount]
                                    (if (pick-target-rrd-id tx article month)
                                      acc
                                      (assoc acc [article month field] amount)))
                                  {}
                                  agg))))
         inserted       (atom 0)
         skipped        (atom 0)]
     (when (seq aggregates)
       (jdbc/with-transaction [tx (db/ds)]
         (doseq [[[article month field] amount] aggregates]
           (let [rrd (orphan-rrd-id article month field)
                 existing (jdbc/execute-one!
                            tx
                            ["SELECT rrd_id FROM finance
                              WHERE marketplace='ozon' AND rrd_id = ?"
                             rrd])]
             (if existing
               (swap! skipped inc)
               (let [finance-row (build-orphan-finance-row article month field amount)
                     row-vec     (sync/finance->row finance-row)
                     col-names   (clojure.string/join "," (map name sync/finance-columns))
                     ph          (clojure.string/join "," (repeat (count sync/finance-columns) "?"))]
                 (jdbc/execute-one!
                   tx
                   (into [(str "INSERT INTO finance (" col-names ") VALUES (" ph ")")]
                         row-vec))
                 (swap! inserted inc)
                 (mu/log ::orphan-service-inserted
                         :marketplace :ozon
                         :article     article
                         :period      month
                         :field       field
                         :amount      amount
                         :rrd-id      rrd)))))))
     (println (str "Materialized Ozon orphan services: " @inserted " INSERTs, "
                   @skipped " skipped (already present), "
                   (count aggregates) " orphan-fields"))
     {:inserted         @inserted
      :skipped-existing @skipped
      :orphan-fields    (count aggregates)})))

;; ---------------------------------------------------------------------------
;; Storage (paid storage)
;; ---------------------------------------------------------------------------

(defn- transform-storage [source raw-items]
  (case source
    "wb"   (wb-t/->storage-costs raw-items)
    "ozon" (ozon-t/->storage-costs raw-items)
    "ym"   []))

(defn materialize-storage!
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [[from to]  (resolve-period period)
        source     (name marketplace)
        raw-items  (load-raw source :storage from to)
        data       (transform-storage source raw-items)
        rows       (mapv sync/storage->row data)
        cnt        (db/insert-batch! :paid_storage sync/storage-columns rows)]
    (println (str "Materialized storage: " cnt))
    cnt))

;; ---------------------------------------------------------------------------
;; Stocks (full replace)
;; ---------------------------------------------------------------------------

(defn- transform-stocks [source raw-items]
  (case source
    "wb"   (wb-t/->stocks raw-items)
    "ozon" (ozon-t/->stocks raw-items)
    "ym"   (ym-t/->stocks raw-items)))

(defn materialize-stocks!
  [& {:keys [marketplace] :or {marketplace :wb}}]
  (let [source    (name marketplace)
        ;; Find latest stocks snapshot
        rows-raw  (db/raw-status)
        latest    (->> rows-raw
                       (filter #(and (= (:source %) source)
                                     (= (:entity-type %) "stocks")))
                       first)
        date      (or (:max-date latest)
                      (t/format-date (t/today)))]
    (when-let [raw-items (load-raw-exact source :stocks date)]
      (db/clear-marketplace-rows! :stocks marketplace)
      (let [data (-> (transform-stocks source raw-items)
                     (log-bad-stocks-rows! source))
            rows (mapv sync/stock->row data)
            cnt  (db/insert-batch! :stocks sync/stocks-columns rows)]
        (println (str "Materialized stocks: " cnt))
        cnt))))

;; ---------------------------------------------------------------------------
;; Product stats
;; ---------------------------------------------------------------------------

(defn- transform-product-stats [source raw-items]
  (case source
    ;; WB: raw-items are unwrapped cards, each needs ->product-stat
    "wb"   (mapv wb-t/->product-stat raw-items)
    ;; Ozon: raw data is full response, needs wrapping
    "ozon" (ozon-t/->product-stats raw-items)
    ;; YM: raw data is full response
    "ym"   (ym-t/->product-stats raw-items)))

(defn materialize-product-stats!
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [[from to] (resolve-period period)
        source    (name marketplace)
        ts        (sync/now-str)]
    ;; For WB, raw is flat list of cards; for Ozon/YM, raw is the full response
    (if (= marketplace :wb)
      (let [raw-items (load-raw source :product_stats from to)
            data      (mapv wb-t/->product-stat raw-items)
            rows      (mapv (fn [p]
                              [(:nm-id p) (:article p) from to
                               (:views p) (:add-to-cart p) (:orders p) (:orders-sum p)
                               (:buyouts p) (:buyouts-sum p) (:cancel-count p) (:cancel-sum p)
                               (name marketplace) ts])
                            data)
            cnt       (db/insert-batch! :product_stats
                                        [:nm_id :article :date_from :date_to
                                         :views :add_to_cart :orders :orders_sum
                                         :buyouts :buyouts_sum :cancel_count :cancel_sum
                                         :marketplace :synced_at]
                                        rows)]
        (println (str "Materialized product stats: " cnt))
        cnt)
      ;; Ozon/YM: stored full response, apply transform directly
      (let [raw-data  (db/get-raw source :product_stats from to)
            data      (case marketplace
                        :ozon (ozon-t/->product-stats raw-data)
                        :ym   (ym-t/->product-stats raw-data))
            rows      (mapv (fn [p]
                              [(:nm-id p) (:article p) from to
                               (:views p) (:add-to-cart p) (:orders p) (:orders-sum p)
                               (:buyouts p) (:buyouts-sum p) (:cancel-count p) (:cancel-sum p)
                               (name marketplace) ts])
                            data)
            cnt       (db/insert-batch! :product_stats
                                        [:nm_id :article :date_from :date_to
                                         :views :add_to_cart :orders :orders_sum
                                         :buyouts :buyouts_sum :cancel_count :cancel_sum
                                         :marketplace :synced_at]
                                        rows)]
        (println (str "Materialized product stats: " cnt))
        cnt))))

;; ---------------------------------------------------------------------------
;; Prices (full replace)
;; ---------------------------------------------------------------------------

(defn- transform-prices [source raw-items]
  (case source
    ;; WB: raw-items are unwrapped listGoods, each needs ->price
    "wb"   (mapv wb-t/->price raw-items)
    "ozon" (ozon-t/->prices raw-items)
    "ym"   (ym-t/->prices raw-items)))

(defn materialize-prices!
  [& {:keys [marketplace] :or {marketplace :wb}}]
  (let [source    (name marketplace)
        rows-raw  (db/raw-status)
        latest    (->> rows-raw
                       (filter #(and (= (:source %) source)
                                     (= (:entity-type %) "prices")))
                       first)
        date      (or (:max-date latest)
                      (t/format-date (t/today)))]
    (when-let [raw-items (load-raw-exact source :prices date)]
      (db/clear-marketplace-rows! :prices marketplace)
      (let [ts   (sync/now-str)
            data (transform-prices source raw-items)
            rows (->> data
                      (filter #(seq (:article %)))
                      (mapv (fn [p]
                              [(:nm-id p) (:article p) (:price p)
                               (:discount p) (:club-disc p) (name marketplace) ts])))
            cnt  (db/insert-batch! :prices
                                   [:nm_id :article :price :discount :club_discount :marketplace :synced_at]
                                   rows)]
        (println (str "Materialized prices: " cnt))
        cnt))))

;; ---------------------------------------------------------------------------
;; Regions (WB only)
;; ---------------------------------------------------------------------------

(defn materialize-regions!
  "Flatten WB region-sales raw_data into the `region_sales` table.

   Each raw_data batch wraps the rows in a `:report` array
   (`{:report [{:nmID ... :regionName ... :sa ...}, ...]}`). Earlier
   versions of this materializer iterated the outer batch object as if
   it were a row — every (nm_id, region, city) field read as nil and
   the `region_sales` table accumulated 4 empty placeholder rows. We
   now mapcat into `:report` to yield real per-sale rows."
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (when (not= marketplace :wb)
    (throw (ex-info "materialize-regions! supports only :wb marketplace"
                    {:marketplace marketplace})))
  (let [[from to]  (resolve-period period)
        ;; Each WB regions raw_data batch wraps the rows in a top-level
        ;; `:report` array. The shared `load-raw` helper does
        ;; `(mapcat :data batches)`, which on this map shape produces
        ;; MapEntries (one per batch) instead of report rows — the field
        ;; reads then all returned nil and `region_sales` accumulated
        ;; placeholder rows. Bypass the helper and unwrap `:report`
        ;; directly. Fallback to plain seq when a batch was pre-flattened.
        batches    (db/get-raw-range "wb" :regions from to)
        flat       (mapcat (fn [b]
                             (let [d (:data b)]
                               (cond
                                 (sequential? d) d
                                 (map? d)        (or (:report d) [d])
                                 :else           [])))
                           batches)
        ts         (sync/now-str)]
    (when (seq flat)
      (let [rows (mapv (fn [r]
                         [(or (:nmID r) (:nm-id r))
                          (or (:sa r) (:article r))
                          (:regionName r) (:cityName r) (:countryName r) (:foName r)
                          (:saleItemInvoiceQty r) (:saleInvoiceCostPrice r) (:saleInvoiceCostPricePerc r)
                          from to ts])
                       flat)
            cnt  (db/insert-batch! :region_sales
                                   [:nm_id :article :region :city :country :fo
                                    :qty :sum_price :sum_price_prc :date_from :date_to :synced_at]
                                   rows)]
        (println (str "Materialized regions: " cnt))
        cnt))))

;; ---------------------------------------------------------------------------
;; WB ad_stats
;;
;; Source: raw_data rows with source='wb' and entity_type='ad_stats', each
;; containing a vector of campaign-stats maps from /adv/v3/fullstats. The v3
;; shape adds an extra nesting level (`:apps[].nms[]`) and switches some keys
;; to camelCase:
;;
;;   [{:advertId cid
;;     :days [{:date "YYYY-MM-DD..."
;;             :views ... :clicks ... :sum ...
;;             :apps  [{:appType N :views ... :sum ...
;;                      :nms [{:nmId N :name "..." :views ... :sum ...}]}]}]}
;;    ...]
;;
;; Flattening rule: one `ad_stats` row per (campaign_id × day × nm). When
;; `:apps[].nms[]` is empty/absent we fall back to the day-level row with
;; `nm_id = 0` (DB sentinel — the ad_stats PK includes nm_id and SQLite
;; treats NULLs as distinct, so using 0 keeps INSERT-OR-REPLACE idempotent).
;;
;; Idempotency: DELETE all rows whose (campaign_id, date) falls in the
;; covered window BEFORE the INSERT batch. Re-materialising the same raw
;; yields identical DB state.
;; ---------------------------------------------------------------------------

(def ^:private ad-stats-columns
  [:campaign_id :date :views :clicks :ctr :cpc :spend
   :atbs :orders :cr :shks :sum_price :nm_id :synced_at])

(defn- ad-stats-row
  "Build a single ad_stats row vector matching `ad-stats-columns`."
  [campaign-id date nm-id day-or-app ts]
  [campaign-id
   date
   (get day-or-app :views)
   (get day-or-app :clicks)
   (get day-or-app :ctr)
   (get day-or-app :cpc)
   (get day-or-app :sum)
   (get day-or-app :atbs)
   (get day-or-app :orders)
   (get day-or-app :cr)
   (get day-or-app :shks)
   (get day-or-app :sum_price)
   nm-id
   ts])

(defn- flatten-ad-campaign
  "Turn one campaign stats map into a seq of ad_stats row vectors — one per
   (day, nm) pair. v3 nests per-article rows under :apps[].nms[] and uses
   :nmId (camelCase). Legacy v2 used :apps[].nm_id directly. We unwrap both
   shapes so re-materializing historical raw data still works.

   When neither path yields any per-article rows we emit a single
   (day, nm_id=0) sentinel row to keep day-level totals visible."
  [campaign ts]
  (let [cid (or (:id campaign) (:advertId campaign))]
    (mapcat
      (fn [day]
        (let [;; v3 emits :date as ISO datetime "2026-04-10T00:00:00Z"; v2
              ;; used "YYYY-MM-DD". The ad-cost allocator joins ad_stats.date
              ;; against substr(finance.date_from, 1, 10), so we have to
              ;; persist only the date prefix or the join misses every row.
              raw-date (:date day)
              date     (cond-> raw-date
                         (and (string? raw-date) (>= (count raw-date) 10))
                         (subs 0 10))
              apps (:apps day)
              ;; v3: each app has :nms — drill in. v2 fallback: app itself
              ;; carries :nm_id and the spend keys.
              nm-rows
              (mapcat (fn [app]
                        (cond
                          (seq (:nms app))
                          (mapv (fn [nm]
                                  (ad-stats-row cid date
                                                (or (:nmId nm) (:nm_id nm) 0)
                                                nm ts))
                                (:nms app))

                          (:nm_id app)
                          [(ad-stats-row cid date (:nm_id app) app ts)]

                          :else nil))
                      apps)]
          (if (seq nm-rows)
            nm-rows
            [(ad-stats-row cid date 0 day ts)])))
      (:days campaign))))

(defn materialize-wb-ad-stats!
  "Populate the `ad_stats` table from raw_data rows of
   source='wb' entity_type='ad_stats' in the given period.

   Flattens each raw campaign → one row per (campaign_id, date, nm_id).
   Idempotent: existing rows for the covered (campaign, date) pairs are
   cleared before reinsertion. Caller passes `period` as [from to] vec,
   period keyword, or {:from :to} map (see `resolve-period`).

   Returns the number of rows inserted."
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (when-not (= marketplace :wb)
    (throw (ex-info "materialize-wb-ad-stats! supports only :wb"
                    {:marketplace marketplace})))
  (let [[from to]  (resolve-period period)
        raw-items  (load-raw "wb" :ad_stats from to)
        ts         (sync/now-str)
        rows       (into [] (mapcat #(flatten-ad-campaign % ts) raw-items))]
    (when (seq rows)
      ;; Idempotency: clear existing ad_stats rows for every (campaign, date)
      ;; we're about to INSERT. PK is (campaign_id, date) so INSERT OR REPLACE
      ;; would also work — explicit DELETE makes the contract obvious and
      ;; survives a future widening of the natural key (e.g. adding nm_id).
      (let [pairs (into #{} (map (fn [r] [(nth r 0) (nth r 1)]) rows))]
        (jdbc/with-transaction [tx (db/ds)]
          (doseq [[cid date] pairs]
            (jdbc/execute! tx
              ["DELETE FROM ad_stats WHERE campaign_id = ? AND date = ?"
               cid date])))))
    (let [cnt (db/insert-batch! :ad_stats ad-stats-columns rows)]
      (println (str "Materialized WB ad_stats: " (or cnt 0) " rows"))
      (or cnt 0))))

;; ---------------------------------------------------------------------------
;; WB :ad-cost migration (spec 003 US5, T039)
;;
;; Populate `finance.ad_cost` for WB rows from the `ad_stats` table, per the
;; B-003 proportional-to-revenue rule.
;;
;; Allocation algorithm (per (campaign_id, date) group):
;;
;;   1. Separate ad_stats rows into "per-nm_id" (nm_id > 0) and "campaign-only"
;;      (nm_id = 0 sentinel) buckets.
;;   2. Per-nm_id rows attribute their spend directly to the matching nm_id's
;;      finance rows on the same date (nm_id ↔ article is 1:1 for WB per B-003
;;      evidence; spend is SUMed into finance.ad_cost for every finance row
;;      with that (nm_id, date_from)).
;;   3. Campaign-only rows (nm_id=0 sentinel → multi-article campaigns without
;;      apps breakdown) are allocated:
;;         - Primary: proportional to `finance.retail_amount` across WB rows on
;;           the same date that are NOT already covered by a per-nm_id row for
;;           this campaign-day.
;;         - Fallback (zero-revenue): equal-split across WB rows on the same
;;           date (still restricted to rows not covered by per-nm_id).
;;   4. Edge case — a campaign-only row with no candidate finance rows on that
;;      date: logged as ::wb-ad-cost-unallocated, spend stays uncredited
;;      (not distributed elsewhere) — conservative, preserves conservation
;;      property when allocation is well-defined.
;;
;; IMPORTANT semantics:
;;   - This is an absolute SET per finance row: pre-aggregate ALL contributions
;;     in-memory first, then UPDATE finance.ad_cost = computed_value (not +=).
;;     Running twice yields the same DB state (idempotent).
;;   - Resets `finance.ad_cost` to 0 for WB rows in the period BEFORE applying,
;;     so re-runs after ad_stats rows are deleted correctly clear stale values.
;;   - B-005 invariant preserved — `for_pay` is never touched.
;;   - YM and Ozon rows are NOT touched — only marketplace='wb'.
;;
;; See specs/002-calculation-audit/verdicts.md §B-003 for the full rule derivation.
;; ---------------------------------------------------------------------------

(defn- load-wb-finance-for-ad-cost
  "Load WB finance rows in [from..to] that are candidates for ad-cost
   attribution. Returns a map {(date-substring) [{:rrd-id :nm-id :retail-amount}]}."
  [tx from to]
  (let [rows (jdbc/execute!
               tx
               ["SELECT rrd_id, nm_id, substr(date_from, 1, 10) AS d, retail_amount
                 FROM finance
                 WHERE marketplace='wb'
                   AND substr(date_from, 1, 10) >= ?
                   AND substr(date_from, 1, 10) <= ?"
                from to]
               {:builder-fn rs/as-unqualified-kebab-maps})]
    (group-by :d rows)))

(defn- wb-ad-stats-by-campaign-day
  "Load ad_stats rows within [from..to], grouped by (campaign_id, date).
   Each value is {:per-nm-id {nm-id spend} :campaign-only spend-sum-of-nm0}.
   spend values are always positive WB /adv/v2/fullstats `sum`."
  [tx from to]
  (let [rows (jdbc/execute!
               tx
               ["SELECT campaign_id, date, nm_id, spend
                 FROM ad_stats
                 WHERE date >= ? AND date <= ?"
                from to]
               {:builder-fn rs/as-unqualified-kebab-maps})]
    (reduce
      (fn [acc {:keys [campaign-id date nm-id spend]}]
        (let [k [campaign-id date]
              spend (double (or spend 0.0))]
          (if (zero? (or nm-id 0))
            (update-in acc [k :campaign-only] (fnil + 0.0) spend)
            (update-in acc [k :per-nm-id nm-id] (fnil + 0.0) spend))))
      {}
      rows)))

(defn- allocate-campaign-day
  "For one (campaign_id, date) group, compute per-rrd_id ad-cost contributions.

   Returns a map {rrd-id cost}. Uses proportional-to-retail-amount over the
   finance rows for `date` that are NOT already covered by this campaign's
   per-nm-id rows. Falls back to equal-split when the uncovered subset has
   zero total retail.

   `covered-nm-ids` — set of nm_ids already attributed via per-nm-id on the
   same date (spend went straight to their rows, so they are excluded from
   the campaign-only residual)."
  [spend covered-nm-ids finance-rows-on-date]
  (let [candidates       (filterv #(not (contains? covered-nm-ids (:nm-id %)))
                                  finance-rows-on-date)]
    (cond
      ;; No candidate rows → spend stays unallocated (caller logs)
      (empty? candidates)
      {}

      :else
      (let [total-revenue (reduce + 0.0 (keep :retail-amount candidates))]
        (if (pos? total-revenue)
          ;; Revenue-proportional
          (into {}
                (map (fn [{:keys [rrd-id retail-amount]}]
                       [rrd-id (* spend (/ (double (or retail-amount 0.0))
                                           total-revenue))]))
                candidates)
          ;; Equal-split fallback (zero-revenue campaign / date)
          (let [share (/ spend (count candidates))]
            (into {}
                  (map (fn [{:keys [rrd-id]}] [rrd-id share]))
                  candidates)))))))

(defn- compute-wb-ad-cost-contributions
  "Produce {rrd-id cost} map summing contributions from all (campaign, date)
   groups. Handles per-nm-id direct attribution AND campaign-only allocation.

   `ad-stats-by-day`: map as produced by `wb-ad-stats-by-campaign-day`.
   `finance-by-date`: map from `load-wb-finance-for-ad-cost`."
  [ad-stats-by-day finance-by-date]
  (reduce-kv
    (fn [acc [_campaign-id date] {:keys [per-nm-id campaign-only]}]
      (let [rows-on-date (get finance-by-date date [])
            ;; Per-nm_id: direct attribution — spend goes to every finance row
            ;; matching (nm_id, date). For WB, nm_id ↔ article is 1:1 (B-003)
            ;; so this is typically a single row per nm_id, but if there are
            ;; multiple we SPLIT spend proportionally across them by retail.
            acc-per-nm
            (reduce-kv
              (fn [a nm-id spend]
                (let [matching (filterv #(= nm-id (:nm-id %)) rows-on-date)
                      total-r  (reduce + 0.0 (keep :retail-amount matching))]
                  (cond
                    (empty? matching) a
                    (pos? total-r)
                    (reduce (fn [b {:keys [rrd-id retail-amount]}]
                              (update b rrd-id (fnil + 0.0)
                                      (* spend
                                         (/ (double (or retail-amount 0.0))
                                            total-r))))
                            a matching)
                    :else  ;; equal-split fallback when matching rows have 0 revenue
                    (let [share (/ spend (count matching))]
                      (reduce (fn [b {:keys [rrd-id]}]
                                (update b rrd-id (fnil + 0.0) share))
                              a matching)))))
              acc
              (or per-nm-id {}))
            ;; Campaign-only: distribute residual spend across rows not
            ;; already covered by per-nm_id for this campaign-day.
            covered-nm-ids (into #{} (keys (or per-nm-id {})))
            acc-total
            (if (and campaign-only (pos? (double campaign-only)))
              (let [alloc (allocate-campaign-day campaign-only
                                                 covered-nm-ids
                                                 rows-on-date)]
                (reduce-kv (fn [m rrd-id cost]
                             (update m rrd-id (fnil + 0.0) cost))
                           acc-per-nm
                           alloc))
              acc-per-nm)]
        acc-total))
    {}
    ad-stats-by-day))

(defn materialize-wb-ad-cost!
  "Populate `finance.ad_cost` for WB rows in [from..to] from `ad_stats`.

   Reads:
     - `finance` rows (marketplace='wb', date_from in period)
     - `ad_stats` rows (date in period)

   Writes:
     - `finance.ad_cost` (absolute SET per row, WB only)

   Idempotency is absolute: the function first resets ad_cost=0 for all WB
   rows in the period, then SETs the computed contribution per rrd_id. Running
   twice yields identical state.

   B-005 preserved: only `ad_cost` is written; `for_pay` and other cost fields
   are untouched. YM and Ozon rows are never updated.

   Returns {:updates N :covered-rrd-ids K :total-spend-allocated ₽
            :unallocated-count M}.

   See specs/002-calculation-audit/verdicts.md §B-003 for the allocation rule."
  [period]
  (let [[from to] (resolve-period period)]
    (jdbc/with-transaction [tx (db/ds)]
      (let [finance-by-date (load-wb-finance-for-ad-cost tx from to)
            ad-stats-by-day (wb-ad-stats-by-campaign-day tx from to)
            contributions   (compute-wb-ad-cost-contributions
                              ad-stats-by-day finance-by-date)
            ;; Count ad_stats rows whose spend had no eligible finance row
            ;; (logged once per orphan).
            unallocated     (atom 0)]
        ;; Count unallocated: for each campaign-day with no rows in finance-by-date,
        ;; any per-nm-id or campaign-only spend is unallocated.
        (reduce-kv
          (fn [_ [campaign-id date] {:keys [per-nm-id campaign-only]}]
            (let [rows-on-date (get finance-by-date date [])]
              (when (empty? rows-on-date)
                (when (or (seq per-nm-id)
                          (and campaign-only (pos? (double campaign-only))))
                  (swap! unallocated inc)
                  (mu/log ::wb-ad-cost-unallocated
                          :campaign-id campaign-id
                          :date        date
                          :per-nm-id   per-nm-id
                          :campaign-only campaign-only))))
            nil)
          nil
          ad-stats-by-day)

        ;; Reset all WB ad_cost in the period to 0, then SET per-rrd contribution.
        (jdbc/execute! tx
          ["UPDATE finance SET ad_cost = 0
            WHERE marketplace='wb'
              AND substr(date_from, 1, 10) >= ?
              AND substr(date_from, 1, 10) <= ?"
           from to])

        (doseq [[rrd-id cost] contributions]
          (jdbc/execute! tx
            ["UPDATE finance SET ad_cost = ?
              WHERE marketplace='wb' AND rrd_id = ?"
             (double cost) rrd-id]))

        (let [total-allocated (reduce + 0.0 (vals contributions))
              ;; T041 SC-009 migration assertion log — compare new path to
              ;; legacy ad-spend-by-article. `new ≥ legacy` is the target
              ;; invariant; any positive delta is "newly captured
              ;; multi-campaign allocation" (null-nm_id rows that the
              ;; legacy INNER JOIN silently dropped).
              legacy (safe/safely
                       (->> (jdbc/execute!
                              tx
                              ["SELECT COALESCE(SUM(a.spend), 0) AS spend
                                FROM ad_stats a
                                JOIN (SELECT DISTINCT nm_id, article, marketplace FROM finance
                                      WHERE article IS NOT NULL AND article != ''
                                        AND marketplace = 'wb') f
                                  ON a.nm_id = f.nm_id
                                WHERE a.date >= ? AND a.date <= ?"
                               from to]
                              {:builder-fn rs/as-unqualified-kebab-maps})
                            first :spend double)
                       0.0
                       ::wb-ad-cost-legacy-failed)
              delta  (- total-allocated (or legacy 0.0))
              covg   (if (pos? total-allocated)
                       (* 100.0 (/ (- total-allocated delta) total-allocated))
                       0.0)]
          (println (str "Materialized WB ad_cost: " (count contributions)
                        " rows updated, " (format "%.2f" total-allocated) " ₽ allocated, "
                        @unallocated " unallocated campaign-days"))
          (println (format "Ad-spend migration: legacy=%.2f, new=%.2f, delta=%+.2f (%.1f%% legacy-match)"
                           (double (or legacy 0.0)) total-allocated delta covg))
          {:updates               (count contributions)
           :covered-rrd-ids       (count contributions)
           :total-spend-allocated total-allocated
           :legacy-ad-spend       (double (or legacy 0.0))
           :delta                 delta
           :unallocated-count     @unallocated})))))

;; ---------------------------------------------------------------------------
;; Cash flow periods (Ozon)
;; ---------------------------------------------------------------------------

(def ^:private cashflow-columns
  [:source :period_begin :period_end
   :orders_amount :returns_amount :commission_amount
   :delivery_amount :delivery_logistics
   :return_amount :return_logistics
   :storage :packaging :warehouse_movement :returns_cargo :subscription :fines :other_services
   :acquiring :corrections :compensation
   :payment :begin_balance :end_balance :invoice_transfer
   :synced_at])

(defn- cashflow-period->row [source period ts]
  [(name source)
   (:period-begin period) (:period-end period)
   (:orders-amount period) (:returns-amount period) (:commission-amount period)
   (:delivery-amount period) (:delivery-logistics period)
   (:return-amount period) (:return-logistics period)
   (:storage period) (:packaging period) (:warehouse-movement period)
   (:returns-cargo period) (:subscription period) (:fines period)
   (:other-services period)
   (:acquiring period) (:corrections period) (:compensation period)
   (:payment period) (:begin-balance period) (:end-balance period)
   (:invoice-transfer period)
   ts])

(defn materialize-cashflow!
  [period & {:keys [marketplace] :or {marketplace :ozon}}]
  (when-not (= marketplace :ozon)
    (println "materialize-cashflow! supports only :ozon")
    (throw (ex-info "materialize-cashflow! supports only :ozon" {:marketplace marketplace})))
  (let [[from to] (resolve-period period)
        source    (name marketplace)
        raw-data  (db/get-raw source :cashflow from to)]
    (if-not raw-data
      (println "No raw cash flow data found")
      (let [periods (ozon-t/->cash-flow-periods raw-data)
            ts      (sync/now-str)
            rows    (mapv #(cashflow-period->row marketplace % ts) periods)
            cnt     (db/insert-batch! :cash_flow_periods cashflow-columns rows)]
        (println (str "Materialized cash flow: " cnt " periods"))
        cnt))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn materialize!
  "Materialize analytical tables from raw_data.
   Usage:
     (materialize! :finance :period :last-30-days :marketplace :wb)
     (materialize! :all :period :last-30-days :marketplace :ozon)"
  [what & {:keys [period marketplace] :or {marketplace :wb}}]
  (println (str "\n=== Materialize: " (name what) " ==="))
  (case what
    :sales    (materialize-sales! period :marketplace marketplace)
    :orders   (materialize-orders! period :marketplace marketplace)
    :finance  (materialize-finance! period :marketplace marketplace)
    :storage  (materialize-storage! period :marketplace marketplace)
    :stocks   (materialize-stocks! :marketplace marketplace)
    :stats    (materialize-product-stats! period :marketplace marketplace)
    :prices   (materialize-prices! :marketplace marketplace)
    :regions  (materialize-regions! period :marketplace marketplace)
    :ad-stats (materialize-wb-ad-stats! period :marketplace marketplace)
    :ad_stats (materialize-wb-ad-stats! period :marketplace marketplace)
    :ad-cost  (materialize-wb-ad-cost! period)
    :ad_cost  (materialize-wb-ad-cost! period)
    :cashflow (materialize-cashflow! period :marketplace marketplace)
    :all      (let [p (or period :last-30-days)]
                (materialize-orders! p :marketplace marketplace)
                (materialize-sales! p :marketplace marketplace)
                (materialize-finance! p :marketplace marketplace)
                (materialize-storage! p :marketplace marketplace)
                (materialize-stocks! :marketplace marketplace)
                (materialize-product-stats! p :marketplace marketplace)
                (materialize-prices! :marketplace marketplace)
                (when (= marketplace :wb)
                  (materialize-regions! p :marketplace marketplace))
                (when (= marketplace :ozon)
                  (materialize-cashflow! p :marketplace marketplace))
                (println "=== Materialize complete ==="))))

(defn rebuild!
  "Clear analytical table(s) and re-materialize from raw data.
   Usage:
     (rebuild! :finance :period :last-30-days :marketplace :wb)
     (rebuild! :all :period :last-30-days :marketplace :ozon)"
  [what & {:keys [period marketplace] :or {marketplace :wb}}]
  (println (str "\n=== Rebuild: " (name what) " (clearing + materializing) ==="))
  (let [tables (case what
                 :sales    [:sales]
                 :orders   [:orders]
                 :finance  [:finance]
                 :storage  [:paid_storage]
                 :stocks   [:stocks]
                 :stats    [:product_stats]
                 :prices   [:prices]
                 :regions  [:region_sales]
                 :ad-stats [:ad_stats]
                 :ad_stats [:ad_stats]
                 :cashflow [:cash_flow_periods]
                 :all      [:sales :orders :finance :paid_storage :stocks
                            :product_stats :prices :region_sales :cash_flow_periods])]
    (doseq [table tables]
      (println (str "  Clearing " (name table) " for " (name marketplace) "..."))
      (cond
        (= table :cash_flow_periods)
        (db/execute! ["DELETE FROM cash_flow_periods WHERE source = ?" (name marketplace)])
        ;; ad_stats has no `marketplace` column (WB-only currently) —
        ;; the table gets fully cleared on rebuild.
        (= table :ad_stats)
        (db/execute! ["DELETE FROM ad_stats"])
        :else
        (db/clear-marketplace-rows! table marketplace)))
    (apply materialize! what
           (cond-> [:marketplace marketplace]
             period (into [:period period])))))

;; ---------------------------------------------------------------------------
;; RFC-13 (closed 2026-04-28): stocks_history snapshot capture
;; ---------------------------------------------------------------------------

(defn- today-iso []
  (str (java.time.LocalDate/now)))

(defn snapshot-stocks-history!
  "Copy the current `stocks` table into `stocks_history` for today's date.

   Idempotent: rows are inserted via `INSERT OR IGNORE` against the
   composite PK `(snapshot_date, marketplace, article, warehouse)`,
   so re-running on the same day is a no-op.

   Args:
     :date           override snapshot date (default: today). Useful for
                     backfilling a known-good day from raw_data, or
                     replaying tests.
     :marketplace    optional scope filter; default :all.

   Returns {:date D :inserted N :skipped M}."
  [& {:keys [date marketplace] :or {date nil marketplace :all}}]
  (let [snap-date (or date (today-iso))
        synced-at (str (java.time.Instant/now))
        rows (if (= :all marketplace)
               (db/query ["SELECT * FROM stocks"])
               (db/query ["SELECT * FROM stocks WHERE marketplace = ?"
                          (name marketplace)]))
        before-count (-> (db/query ["SELECT COUNT(*) AS n FROM stocks_history WHERE snapshot_date = ?"
                                    snap-date])
                         first :n)]
    (when (seq rows)
      (let [col-names "snapshot_date,marketplace,article,warehouse,quantity,quantity_full,in_way_to,in_way_from,nm_id,barcode,tech_size,subject,brand,synced_at"
            row-ph    "(?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            ;; SQLite param limit is 999; we have 14 cols, so chunk to ~70 rows.
            chunk-sz  70]
        (doseq [chunk (partition-all chunk-sz rows)]
          (let [sql    (str "INSERT OR IGNORE INTO stocks_history (" col-names ") VALUES "
                            (str/join "," (repeat (count chunk) row-ph)))
                params (into [sql]
                             (mapcat (fn [r]
                                       [snap-date
                                        (or (:marketplace r) "wb")
                                        (or (:article r) "")
                                        (or (:warehouse r) "")
                                        (:quantity r)
                                        (:quantity-full r)
                                        (:in-way-to r)
                                        (:in-way-from r)
                                        (:nm-id r)
                                        (:barcode r)
                                        (:tech-size r)
                                        (:subject r)
                                        (:brand r)
                                        synced-at])
                                     chunk))]
            (db/execute! params)))))
    (let [after-count (-> (db/query ["SELECT COUNT(*) AS n FROM stocks_history WHERE snapshot_date = ?"
                                     snap-date])
                          first :n)
          inserted (- (or after-count 0) (or before-count 0))]
      {:date     snap-date
       :inserted inserted
       :skipped  (- (count rows) inserted)
       :total-from-stocks (count rows)})))
