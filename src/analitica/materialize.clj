(ns analitica.materialize
  "Materialize layer: read raw data from raw_data table, transform, write to analytical tables.
   Works entirely offline — no API calls needed."
  (:require [analitica.db :as db]
            [analitica.domain.finance-row :as frow]
            [analitica.sync :as sync]
            [analitica.marketplace.wb.transform :as wb-t]
            [analitica.marketplace.ozon.transform :as ozon-t]
            [analitica.marketplace.ym.transform :as ym-t]
            [analitica.util.time :as t]))

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

(defn materialize-finance!
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [[from to] (resolve-period period)]
    (case marketplace
      :ozon (materialize-ozon-finance-from-realization! from to)
      (let [source    (name marketplace)
            raw-items (load-raw source :finance from to)
            data      (transform-finance source raw-items)
            _         (log-bad-finance-rows! data source)
            rows      (mapv sync/finance->row data)
            cnt       (db/insert-batch! :finance sync/finance-columns rows)]
        (println (str "Materialized finance: " cnt))
        cnt))))

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
