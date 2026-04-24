(ns analitica.web.api.charts
  (:require [analitica.domain.sales :as sales]
            [analitica.domain.finance :as finance]
            [analitica.domain.pnl :as pnl]
            [analitica.util.time :as t]
            [analitica.util.period :as period]))

;; ---------------------------------------------------------------------------
;; Helper functions
;; ---------------------------------------------------------------------------

(defn- resolve-dates
  "Convert period to [from to] date strings."
  [period]
  (cond
    (keyword? period) (t/period period)
    (vector? period)  period
    :else             [(:from period) (:to period)]))

(defn- parse-date-str
  "Extract date string from datetime string (first 10 chars)."
  [s]
  (when s (subs s 0 10)))

;; ---------------------------------------------------------------------------
;; Chart data functions
;; ---------------------------------------------------------------------------

(defn sales-chart-data
  "Returns line chart data for sales dynamics by day.
   
   Parameters:
   - period: keyword or map with :from/:to keys
   - marketplace: optional keyword (:wb, :ozon, :ym)
   
   Returns map with:
   - :labels - array of date strings [\"2026-04-01\" ...]
   - :datasets - array of dataset objects [{:label \"WB\" :data [N ...]} ...]
   
   If marketplace is specified, returns single dataset for that marketplace.
   If marketplace is nil, returns datasets for all marketplaces (WB, Ozon, YM).
   
   Requirements: 4.4, 4.5, 11.1, 11.2, 11.4"
  [period & {:keys [marketplace]}]
  (let [[from to] (resolve-dates period)]
    (if marketplace
      ;; Single marketplace
      (let [sales-data (sales/fetch-sales {:from from :to to}
                                           :marketplace marketplace
                                           :source :db)
            by-day (sales/by-day sales-data)
            labels (mapv :group by-day)
            data (mapv :revenue by-day)]
        {:labels labels
         :datasets [{:label (name marketplace)
                     :data data}]})
      
      ;; All marketplaces
      (let [marketplaces [:wb :ozon :ym]
            ;; Load data for all marketplaces
            mp-data (into {}
                          (for [mp marketplaces]
                            [mp (sales/fetch-sales {:from from :to to}
                                                   :marketplace mp
                                                   :source :db)]))
            ;; Get all unique dates across all marketplaces
            all-dates (->> mp-data
                           vals
                           (mapcat sales/by-day)
                           (map :group)
                           distinct
                           sort
                           vec)
            ;; Build datasets for each marketplace
            datasets (vec (for [mp marketplaces]
                            (let [by-day (sales/by-day (get mp-data mp))
                                  date-map (into {} (map (juxt :group :revenue) by-day))
                                  data (mapv #(get date-map % 0.0) all-dates)]
                              {:label (name mp)
                               :data data})))]
        {:labels all-dates
         :datasets datasets}))))

(defn share-chart-data
  "Returns donut chart data for marketplace revenue shares.
   
   Parameters:
   - period: keyword or map with :from/:to keys
   
   Returns map with:
   - :labels - array of marketplace names [\"WB\" \"Ozon\" \"YM\"]
   - :data - array of revenue values [N N N]
   
   Requirements: 4.4, 4.5, 11.1, 11.2, 11.4"
  [period]
  (let [[from to] (resolve-dates period)
        marketplaces [:wb :ozon :ym]
        ;; Calculate revenue for each marketplace
        revenues (vec (for [mp marketplaces]
                        (let [finance-data (finance/fetch-finance {:from from :to to}
                                                                   :marketplace mp
                                                                   :source :db)
                              pnl-data (pnl/calculate finance-data)]
                          (or (:revenue pnl-data) 0.0))))]
    {:labels (mapv name marketplaces)
     :datasets [{:data revenues
                 :backgroundColor ["rgba(59, 130, 246, 0.8)"
                                   "rgba(239, 68, 68, 0.8)"
                                   "rgba(34, 197, 94, 0.8)"]}]}))

(defn- compute-report-chart
  "Single-period chart data (no compare). Internal helper factored out of report-chart-data."
  [report-type period marketplace]
  (let [[from to] (resolve-dates period)]
    (case report-type
      ;; Sales: line chart - daily dynamics
      :sales
      (let [sales-data (sales/fetch-sales {:from from :to to}
                                           :marketplace marketplace
                                           :source :db)
            by-day (sales/by-day sales-data)
            labels (mapv :group by-day)
            data (mapv :revenue by-day)]
        {:labels labels
         :datasets [{:label "Выручка"
                     :data data}]})
      
      ;; Finance: stacked bar chart - cost breakdown
      :finance
      (let [finance-data (finance/fetch-finance {:from from :to to}
                                                 :marketplace marketplace
                                                 :source :db)
            by-art (finance/by-article finance-data)
            ;; Take top 10 articles by revenue
            top-10 (take 10 by-art)
            labels (mapv :article top-10)]
        {:labels labels
         :datasets [{:label "Выручка" :data (mapv :revenue top-10)}
                    {:label "Комиссия" :data (mapv :wb-reward top-10)}
                    {:label "Логистика" :data (mapv :logistics top-10)}
                    {:label "Хранение" :data (mapv :storage top-10)}]})
      
      ;; UE: horizontal bar chart - top-20 products by profit
      :ue
      (let [finance-data (finance/fetch-finance {:from from :to to}
                                                 :marketplace marketplace
                                                 :source :db)
            storage-map (let [rows ((requiring-resolve 'analitica.db/storage-by-article) 
                                    from to :marketplace marketplace)]
                          (when (seq rows)
                            (into {} (map (juxt :article :storage-cost) rows))))
            ad-map (let [rows ((requiring-resolve 'analitica.db/ad-spend-by-article) from to :marketplace marketplace)]
                     (when (seq rows)
                       (into {} (map (juxt :article :ad-spend) rows))))
            ue-data ((requiring-resolve 'analitica.domain.unit-economics/calculate) 
                     finance-data
                     :storage-by-article storage-map
                     :ad-spend-by-article ad-map)
            top-20 (->> ue-data
                        (sort-by :profit >)
                        (take 20))
            labels (mapv :article top-20)
            data (mapv :profit top-20)]
        {:labels labels
         :datasets [{:label "Прибыль"
                     :data data}]})
      
      ;; PNL: waterfall chart
      :pnl
      (let [finance-data (finance/fetch-finance {:from from :to to}
                                                 :marketplace marketplace
                                                 :source :db)
            pnl-data (pnl/calculate finance-data)]
        {:labels ["Выручка" "Комиссия МП" "Логистика" "Хранение" "Себестоимость" "Реклама" "Чистая прибыль"]
         :datasets [{:label "P&L"
                     :data [(:revenue pnl-data)
                            (- (:wb-reward pnl-data))
                            (- (:logistics pnl-data))
                            (- (:storage pnl-data))
                            (- (:cogs pnl-data))
                            (- (:ad-spend pnl-data))
                            (:net-profit pnl-data)]}]})
      
      ;; ABC: pareto curve
      :abc
      (let [finance-data (finance/fetch-finance {:from from :to to}
                                                 :marketplace marketplace
                                                 :source :db)
            abc-data ((requiring-resolve 'analitica.domain.abc/analyze-by) 
                      finance-data :revenue)
            labels (mapv :article abc-data)
            cum-pct (mapv :cum-pct abc-data)
            revenue (mapv :revenue abc-data)]
        {:labels labels
         :datasets [{:label "Накопительный %"
                     :data cum-pct
                     :type "line"}
                    {:label "Выручка"
                     :data revenue
                     :type "bar"}]})
      
      ;; Stock: bar chart - stock by warehouse
      :stock
      (let [stocks ((requiring-resolve 'analitica.domain.stock/fetch-stocks) 
                    :marketplace marketplace :source :db)
            by-wh ((requiring-resolve 'analitica.domain.stock/by-warehouse) stocks)
            labels (mapv :warehouse by-wh)
            data (mapv :quantity-full by-wh)]
        {:labels labels
         :datasets [{:label "Остатки"
                     :data data}]})
      
      ;; Returns: line chart - % returns by day
      :returns
      (let [sales-data (sales/fetch-sales {:from from :to to}
                                           :marketplace marketplace
                                           :source :db)
            by-day (sales/by-day sales-data)
            labels (mapv :group by-day)
            ;; Calculate return rate per day
            data (mapv (fn [day-data]
                         (let [sales (:sales-count day-data)
                               returns (:returns-count day-data)
                               total (+ sales returns)]
                           (if (pos? total)
                             (* 100.0 (/ returns total))
                             0.0)))
                       by-day)]
        {:labels labels
         :datasets [{:label "% возвратов"
                     :data data}]})
      
      ;; Buyout: bar chart - % buyout by article
      :buyout
      (let [buyout-data ((requiring-resolve 'analitica.domain.buyout/analyze) 
                         {:from from :to to})
            ;; Take articles with at least 3 operations, sorted by buyout rate
            filtered (->> buyout-data
                          (filter #(>= (:ordered %) 3))
                          (sort-by :buyout-rate)
                          (take 20))
            labels (mapv :article filtered)
            data (mapv :buyout-rate filtered)]
        {:labels labels
         :datasets [{:label "% выкупа"
                     :data data}]})
      
      ;; Trends: grouped bar chart - WoW and MoM
      :trends
      (let [wow-data ((requiring-resolve 'analitica.domain.trends/wow))
            mom-data ((requiring-resolve 'analitica.domain.trends/mom))
            labels (mapv :metric wow-data)
            wow-values (mapv :change-pct wow-data)
            mom-values (mapv :change-pct mom-data)]
        {:labels labels
         :datasets [{:label "WoW %"
                     :data wow-values}
                    {:label "MoM %"
                     :data mom-values}]})
      
      ;; Default: empty chart
      {:labels []
       :datasets []})))

(defn report-chart-data
  "Returns Chart.js data for report visualizations.

   Parameters:
   - report-type: keyword (:sales, :finance, :ue, :pnl, :abc, :stock, :returns, :buyout, :trends)
   - period: keyword or map with :from/:to keys
   - marketplace: optional keyword (:wb, :ozon, :ym)
   - compare: optional keyword; when :prev, appends a prev-period dataset styled as a dashed gray line

   Returns map with chart data in Chart.js format, structure depends on report type:
   - sales: line chart (daily dynamics)
   - finance: stacked bar chart (cost breakdown)
   - ue: horizontal bar chart (top-20 products by profit)
   - pnl: waterfall chart
   - abc: pareto curve
   - stock: bar chart (stock by warehouse)
   - returns: line chart (% returns by day)
   - buyout: bar chart (% buyout by article)
   - trends: grouped bar chart (WoW and MoM)

   Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 11.3"
  [report-type period & {:keys [marketplace compare] :or {compare :none}}]
  (let [current (compute-report-chart report-type period marketplace)]
    (if (= compare :prev)
      (let [[from to] (resolve-dates period)
            [prev-from prev-to] (period/compare-period {:from from :to to})
            prev-period {:from prev-from :to prev-to}
            prev (compute-report-chart report-type prev-period marketplace)
            prev-datasets (:datasets prev)]
        ;; Only append if prev has at least one dataset with data
        (if (seq prev-datasets)
          (let [styled-prev (-> (first prev-datasets)
                                (assoc :label "Пред. период"
                                       :borderColor "rgba(156,163,175,0.6)"
                                       :backgroundColor "rgba(156,163,175,0.1)"
                                       :borderDash [5 5]
                                       :fill false))]
            (update current :datasets conj styled-prev))
          current))
      current)))

(defn finance-breakdown-chart-data
  "Returns stacked bar chart data for financial breakdown.
   
   Parameters:
   - marketplace: keyword (:wb, :ozon, :ym)
   - period: keyword or map with :from/:to keys
   
   Returns map with:
   - :labels - array with single label [\"Финансы\"]
   - :datasets - array of datasets for revenue, commission, logistics, storage, profit
   
   Requirements: 5.3"
  [marketplace period]
  (let [[from to] (resolve-dates period)
        finance-data (finance/fetch-finance {:from from :to to}
                                             :marketplace marketplace
                                             :source :db)
        finance-totals (finance/totals finance-data)
        pnl-data (pnl/calculate finance-data)]
    {:labels ["Финансы"]
     :datasets [{:label "Выручка" 
                 :data [(:total-revenue finance-totals)]
                 :backgroundColor "rgba(59, 130, 246, 0.8)"}
                {:label "Комиссия" 
                 :data [(- (:total-wb-reward finance-totals))]
                 :backgroundColor "rgba(239, 68, 68, 0.8)"}
                {:label "Логистика" 
                 :data [(- (:total-logistics finance-totals))]
                 :backgroundColor "rgba(249, 115, 22, 0.8)"}
                {:label "Хранение" 
                 :data [(- (:total-storage finance-totals))]
                 :backgroundColor "rgba(234, 179, 8, 0.8)"}
                {:label "Прибыль" 
                 :data [(:net-profit pnl-data)]
                 :backgroundColor "rgba(34, 197, 94, 0.8)"}]}))

(defn abc-distribution-chart-data
  "Returns bar chart data for ABC distribution.
   
   Parameters:
   - marketplace: keyword (:wb, :ozon, :ym)
   - period: keyword or map with :from/:to keys
   
   Returns map with:
   - :labels - array of ABC categories [\"A\" \"B\" \"C\"]
   - :datasets - array with two datasets: count and revenue %
   
   Requirements: 5.4"
  [marketplace period]
  (let [[from to] (resolve-dates period)
        finance-data (finance/fetch-finance {:from from :to to}
                                             :marketplace marketplace
                                             :source :db)
        abc-data ((requiring-resolve 'analitica.domain.abc/analyze-by) 
                  finance-data :revenue)
        abc-summary ((requiring-resolve 'analitica.domain.abc/summary) abc-data)
        total-revenue (reduce + 0.0 (map :revenue abc-summary))
        labels (mapv (comp name :category) abc-summary)
        counts (mapv :count abc-summary)
        revenue-pcts (mapv (fn [item]
                             (if (pos? total-revenue)
                               (* 100.0 (/ (:revenue item) total-revenue))
                               0.0))
                           abc-summary)]
    {:labels labels
     :datasets [{:label "Количество SKU" 
                 :data counts
                 :backgroundColor "rgba(59, 130, 246, 0.8)"
                 :yAxisID "y"}
                {:label "% выручки" 
                 :data revenue-pcts
                 :backgroundColor "rgba(34, 197, 94, 0.8)"
                 :yAxisID "y1"}]}))
