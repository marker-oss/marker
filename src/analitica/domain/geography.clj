(ns analitica.domain.geography
  (:require [analitica.db :as db]
            [analitica.marketplace.wb.api :as wb-api]
            [analitica.marketplace.registry :as registry]
            [analitica.report.table :as table]
            [analitica.report.export :as export]
            [analitica.util.time :as t]
            [analitica.util.math :as math]))

(defn- resolve-dates [period]
  (if (keyword? period) (t/period period) [(:from period) (:to period)]))

(defn- fetch-from-region-sales
  "Read pre-aggregated WB region_sales rows. Each row spans its reporting
   batch window, so the query is overlap-style."
  [from to]
  (db/query ["SELECT * FROM region_sales WHERE date_from <= ? AND date_to >= ?" to from]))

(defn- fetch-from-sales
  "RFC-12 (closed 2026-04-28): augment region data from per-event `sales`
   table. Aggregates `:sale` rows by region for the requested date range,
   producing rows with the same `:region`/`:qty`/`:sum-price` shape as
   `region_sales`. Covers MPs that lack a pre-aggregated regions endpoint
   — primarily YM (`stats/orders.deliveryRegion`) but also fills WB gaps
   on periods where `analytics/region-sale` was not synced. Ozon currently
   has no event-level region in `sales` table, so it contributes nothing
   here.

   Per concept-crosswalk §9.1: the `sales` table already captures region
   per-event (WB `regionName`, YM `deliveryRegion.name`); this function
   surfaces it to the Geography report without requiring a separate
   ingest pipeline."
  [from to marketplace]
  (let [base-sql ["SELECT region, COUNT(*) AS qty,
                          SUM(COALESCE(for_pay, total_price, 0)) AS sum_price,
                          ? AS date_from, ? AS date_to,
                          NULL AS city, NULL AS country, NULL AS fo
                   FROM sales
                   WHERE type = 'sale'
                     AND region IS NOT NULL AND region <> ''
                     AND date >= ? AND date <= ?"
                  from to from to]
        with-mp  (if marketplace
                   (-> base-sql
                       (update 0 #(str % " AND marketplace = ?"))
                       (conj (name marketplace)))
                   base-sql)
        final    (update with-mp 0 #(str % " GROUP BY region"))]
    (db/query final)))

(defn fetch-regions
  "Fetch region sales rows.

   Sources:
     :db          (default) — pre-aggregated WB `region_sales` table.
                  Overlap query because each row spans its batch window.
     :sales       per-event aggregation from `sales` table. Closes RFC-12 —
                  surfaces region data for MPs without a pre-aggregated
                  endpoint (YM, partial WB). Pass `:marketplace` to scope.
     :combined    UNION of `:db` and `:sales`. Same article appearing in
                  both sources is summed twice — use only when sources are
                  known not to overlap (e.g., separate marketplace scopes).
     :api         live WB `region-sale` endpoint (legacy fallback)."
  [period & {:keys [source marketplace] :or {source :db}}]
  (let [[from to] (resolve-dates period)]
    (case source
      :db       (fetch-from-region-sales from to)
      :sales    (fetch-from-sales from to marketplace)
      :combined (concat (fetch-from-region-sales from to)
                        (fetch-from-sales from to marketplace))
      :api      (let [mp (registry/get-marketplace :wb)]
                  (wb-api/region-sales mp from to)))))

(defn by-region
  "Aggregate region_sales rows by region name → {:region :qty :sum} per region.

   §Geography.1 dual-key-read: each row may carry DB-normalised keys (:region,
   :qty, :sum-price) or WB-API camelCase keys (:regionName, :saleItemInvoiceQty,
   :saleInvoiceCostPrice). The `(or ...)` fallbacks in group-by and reduce
   accept both shapes without a transformation step, so DB-sourced and
   API-sourced rows produce identical aggregation output.

   Result is sorted descending by :sum. :sum is rounded via math/round2.
   See canonical-formulas.md §Geography.1 for the full formula and
   §Geography.4 for marketplace coverage (WB only)."
  [data]
  (->> data
       (group-by (fn [r] (or (:region r) (:regionName r))))
       (map (fn [[region items]]
              {:region region
               :qty    (reduce + 0 (map #(or (:qty %) (:saleItemInvoiceQty %) 0) items))
               :sum    (math/round2 (reduce + 0.0 (map #(or (:sum-price %) (:saleInvoiceCostPrice %) 0) items)))}))
       (sort-by :sum >)))

(defn by-city
  "Aggregate by city."
  [data]
  (->> data
       (group-by (fn [r] (or (:city r) (:cityName r))))
       (map (fn [[city items]]
              {:city city
               :qty  (reduce + 0 (map #(or (:qty %) (:saleItemInvoiceQty %) 0) items))
               :sum  (math/round2 (reduce + 0.0 (map #(or (:sum-price %) (:saleInvoiceCostPrice %) 0) items)))}))
       (sort-by :sum >)))

(defn report
  [period & {:keys [source] :or {source :db}}]
  (println "\nЗагрузка географии продаж...")
  (let [data (fetch-regions period :source source)]
    (if (empty? data)
      (println "  Нет данных. Запустите: (sync/sync! :regions :last-30-days)")
      (do
        (table/print-summary "ГЕОГРАФИЯ ПРОДАЖ" [["Записей" (count data)]])

        (println "\n── Топ-15 регионов ──")
        (table/print-table
         [[:region "Регион"] [:qty "Продажи шт"] [:sum "Сумма"]]
         (take 15 (by-region data)))

        (println "\n── Топ-15 городов ──")
        (table/print-table
         [[:city "Город"] [:qty "Продажи шт"] [:sum "Сумма"]]
         (take 15 (by-city data)))))
    data))

(defn export-excel [period path & opts]
  (let [data (apply fetch-regions period opts)]
    (export/to-excel path
      [{:name "By region" :cols [[:region "Region"] [:qty "Qty"] [:sum "Sum"]] :rows (by-region data)}
       {:name "By city"   :cols [[:city "City"] [:qty "Qty"] [:sum "Sum"]]     :rows (by-city data)}])))
