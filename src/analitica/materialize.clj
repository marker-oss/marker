(ns analitica.materialize
  "Materialize layer: read raw data from raw_data table, transform, write to analytical tables.
   Works entirely offline — no API calls needed."
  (:require [analitica.db :as db]
            [analitica.domain.finance-row :as frow]
            [analitica.sync :as sync]
            [analitica.marketplace.wb.transform :as wb-t]
            [analitica.marketplace.ozon.transform :as ozon-t]
            [analitica.marketplace.ym.transform :as ym-t]
            [analitica.util.time :as t]
            [com.brunobonacci.mulog :as mu]
            [next.jdbc :as jdbc]))

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
        ;; WB impl filters by date range
        data         (if (= marketplace :wb)
                       (filterv #(and (<= (compare from (subs (:date %) 0 10)) 0)
                                      (>= (compare to   (subs (:date %) 0 10)) 0))
                                data)
                       data)
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

;; Forward declaration: materialize-ozon-services! is defined below but
;; is referenced from materialize-finance!'s :ozon branch.
(declare materialize-ozon-services!)

(defn materialize-finance!
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [[from to] (resolve-period period)]
    (case marketplace
      :ozon
      ;; Two-step Ozon pipeline (spec 003 US3B):
      ;;   1. realization → finance rows (for-pay, retail, commission).
      ;;   2. transaction/list services[] merge → cost fields per article.
      ;; Step 2 is optional and silently no-ops when no :transactions raw_data
      ;; has been ingested for the period.
      (let [cnt (materialize-ozon-finance-from-realization! from to)]
        (when (seq (db/get-raw-range "ozon" :transactions from to))
          (materialize-ozon-services! [from to]))
        cnt)
      (let [source    (name marketplace)
            raw-items (load-raw source :finance from to)
            data      (transform-finance source raw-items)
            _         (log-bad-finance-rows! data source)
            rows      (mapv sync/finance->row data)
            cnt       (db/insert-batch! :finance sync/finance-columns rows)]
        (println (str "Materialized finance: " cnt))
        cnt))))

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
;;   - B-005: `for_pay`, `retail_amount`, `wb_commission` are NOT touched
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
   Kept narrow intentionally — widening this list risks violating B-005."
  [:delivery-cost :acquiring-fee :acceptance :storage-fee
   :additional-payment :ad-cost])

(def ^:private ozon-cost-field->column
  {:delivery-cost      :delivery_cost
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
   for [from..to]. Raw stored shape: {:operations [...]}."
  [from to]
  (let [batches (db/get-raw-range "ozon" :transactions from to)]
    (into [] (mapcat (fn [{:keys [data]}]
                       (get data :operations []))
                     batches))))

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

   Returns a summary map: {:updates N :orphans M :service-rows K}."
  [period]
  (let [[from to]      (resolve-period period)
        article-lookup (build-article-lookup from to)
        operations     (load-transactions-operations from to)
        service-rows   (into [] (mapcat #(ozon-t/tx-op->service-rows % article-lookup)
                                        operations))
        agg            (aggregate-service-contributions service-rows)
        orphans        (atom 0)
        updates        (atom 0)]
    (jdbc/with-transaction [tx (db/ds)]
      (doseq [[[article month field] amount] agg]
        (let [col (ozon-cost-field->column field)
              n   (update-contribution! tx col amount article month)]
          (if (pos? n)
            (swap! updates inc)
            (do (swap! orphans inc)
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
    {:updates      @updates
     :orphans      @orphans
     :service-rows (count service-rows)}))

;; ---------------------------------------------------------------------------
;; Storage (paid storage)
;; ---------------------------------------------------------------------------

(defn- transform-storage [source raw-items]
  (case source
    ;; WB storage transform was trimmed from the branch sync; stub to empty.
    "wb"   (do (println "  WB storage transform not available on this branch; skipping.")
               (vec raw-items)
               [])
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
      (let [data (transform-stocks source raw-items)
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
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (when (not= marketplace :wb)
    (throw (ex-info "materialize-regions! supports only :wb marketplace"
                    {:marketplace marketplace})))
  (let [[from to]  (resolve-period period)
        raw-items  (load-raw "wb" :regions from to)
        ts         (sync/now-str)]
    (when (seq raw-items)
      (let [rows (mapv (fn [r]
                         [(or (:nmID r) (:nm-id r))
                          (or (:sa r) (:article r))
                          (:regionName r) (:cityName r) (:countryName r) (:foName r)
                          (:saleItemInvoiceQty r) (:saleInvoiceCostPrice r) (:saleInvoiceCostPricePerc r)
                          from to ts])
                       raw-items)
            cnt  (db/insert-batch! :region_sales
                                   [:nm_id :article :region :city :country :fo
                                    :qty :sum_price :sum_price_prc :date_from :date_to :synced_at]
                                   rows)]
        (println (str "Materialized regions: " cnt))
        cnt))))

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
                 :cashflow [:cash_flow_periods]
                 :all      [:sales :orders :finance :paid_storage :stocks
                            :product_stats :prices :region_sales :cash_flow_periods])]
    (doseq [table tables]
      (println (str "  Clearing " (name table) " for " (name marketplace) "..."))
      (if (= table :cash_flow_periods)
        (db/execute! ["DELETE FROM cash_flow_periods WHERE source = ?" (name marketplace)])
        (db/clear-marketplace-rows! table marketplace)))
    (apply materialize! what
           (cond-> [:marketplace marketplace]
             period (into [:period period])))))
