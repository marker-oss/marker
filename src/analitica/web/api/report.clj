(ns analitica.web.api.report
  (:require [analitica.db :as db]
            [analitica.domain.sales :as sales]
            [analitica.domain.finance :as finance]
            [analitica.domain.unit-economics :as ue]
            [analitica.domain.pnl :as pnl]
            [analitica.domain.abc :as abc]
            [analitica.domain.stock :as stock]
            [analitica.domain.returns :as returns]
            [analitica.domain.buyout :as buyout]
            [analitica.domain.geography :as geography]
            [analitica.domain.trends :as trends]
            [analitica.util.time :as t]))

;; ---------------------------------------------------------------------------
;; Helper functions
;; ---------------------------------------------------------------------------

(defn- resolve-dates
  "Convert period to [from to] date strings."
  [period]
  (if (keyword? period)
    (t/period period)
    [(:from period) (:to period)]))

;; ---------------------------------------------------------------------------
;; Report data functions
;; ---------------------------------------------------------------------------

(defn report-data
  "Returns JSON array of report data for Tabulator tables.
   
   Parameters:
   - report-type: keyword (:sales, :finance, :ue, :pnl, :abc, :stock, :returns, :buyout, :geo, :trends)
   - period: keyword or map with :from/:to keys
   - marketplace: optional keyword (:wb, :ozon, :ym)
   - trend-type: optional keyword for trends report (:wow, :mom, :daily)
   
   Returns vector of maps with kebab-case keys.
   
   Report type mappings:
   - sales: sales/fetch-sales + sales/by-day
   - finance: finance/by-article
   - ue: unit-economics/calculate (requires storage-by-article and ad-spend-by-article from DB)
   - pnl: pnl/calculate (returns single row)
   - abc: abc/analyze-by with criterion :revenue
   - stock: stock/by-article + optionally stock/with-turnover
   - returns: returns/by-article
   - buyout: buyout/analyze
   - geo: geography/by-region
   - trends: trends/wow, trends/mom, or trends/daily based on trend-type parameter
   
   Requirements: 7.2, 13.1-13.12"
  [report-type period & {:keys [marketplace trend-type article]}]
  (try
    (case report-type
      ;; Sales report
      :sales
      (let [sales-data (sales/fetch-sales period
                                          :marketplace marketplace
                                          :source :db)]
        (sales/by-day sales-data))

      ;; Finance report
      :finance
      (let [finance-data (finance/fetch-finance period
                                                :marketplace marketplace
                                                :source :db)]
        (finance/by-article finance-data))

      ;; Unit Economics report
      :ue
      (let [[from to] (resolve-dates period)
            finance-data (cond->> (finance/fetch-finance period
                                                         :marketplace marketplace
                                                         :source :db)
                           (seq article) (filter #(= article (:article %))))
            ;; Load storage costs by article
            storage-map (let [rows (db/storage-by-article from to :marketplace marketplace)]
                          (when (seq rows)
                            (into {} (map (juxt :article :storage-cost) rows))))
            ;; Load ad spend by article
            ad-map (let [rows (db/ad-spend-by-article from to :marketplace marketplace)]
                     (when (seq rows)
                       (into {} (map (juxt :article :ad-spend) rows))))]
        (ue/calculate finance-data
                      :storage-by-article storage-map
                      :ad-spend-by-article ad-map))
      
      ;; P&L report (single row)
      :pnl
      (let [finance-data (finance/fetch-finance period 
                                                :marketplace marketplace 
                                                :source :db)
            pnl-data (pnl/calculate finance-data)]
        ;; Return as single-element vector for consistency
        [pnl-data])
      
      ;; ABC analysis
      :abc
      (let [finance-data (finance/fetch-finance period 
                                                :marketplace marketplace 
                                                :source :db)]
        (abc/analyze-by finance-data :revenue))
      
      ;; Stock report
      :stock
      (let [stocks (stock/fetch-stocks :marketplace marketplace :source :db)
            by-art (stock/by-article stocks)]
        (vec by-art))
      
      ;; Returns report
      :returns
      (let [sales-data (sales/fetch-sales period 
                                          :marketplace marketplace 
                                          :source :db)]
        (returns/by-article sales-data))
      
      ;; Buyout analysis
      :buyout
      (buyout/analyze period)
      
      ;; Geography report
      :geo
      (let [region-data (geography/fetch-regions period :source :db)]
        (geography/by-region region-data))
      
      ;; Trends report
      :trends
      (case (or trend-type :wow)
        :wow (trends/wow)
        :mom (trends/mom)
        :daily (trends/daily period)
        ;; Default to wow
        (trends/wow))
      
      ;; Unknown report type
      [])
    
    (catch Exception e
      ;; Return empty vector on error
      [])))
