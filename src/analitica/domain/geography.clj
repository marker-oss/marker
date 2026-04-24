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

(defn fetch-regions
  "Fetch region sales from DB or API."
  [period & {:keys [source] :or {source :db}}]
  (let [[from to] (resolve-dates period)]
    (case source
      :db  (db/query ["SELECT * FROM region_sales WHERE date_from >= ? AND date_to <= ?" from to])
      :api (let [mp (registry/get-marketplace :wb)]
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
