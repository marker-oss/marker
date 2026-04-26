(ns analitica.web.api.metrics
  (:require [analitica.db :as db]
            [analitica.domain.sales :as sales]
            [analitica.domain.finance :as finance]
            [analitica.domain.pnl :as pnl]
            [analitica.domain.abc :as abc]
            [analitica.domain.returns :as returns]
            [analitica.util.time :as t]
            [analitica.util.math :as math]
            [jsonista.core :as json]
            [com.brunobonacci.mulog :as μ]))

;; ---------------------------------------------------------------------------
;; Data Coverage
;; ---------------------------------------------------------------------------

(defn- parse-flexible-date
  "Parse a date string as LocalDate. Tries ISO (YYYY-MM-DD) first, then
   Russian DD-MM-YYYY, then DD.MM.YYYY. The first 10 chars determine format
   unless timestamp has space/T separator. Returns nil for unparseable input."
  [s]
  (when (string? s)
    (let [prefix (if (> (count s) 10) (subs s 0 10) s)]
      (try
        (java.time.LocalDate/parse prefix)
        (catch java.time.format.DateTimeParseException _
          (try
            (java.time.LocalDate/parse prefix
              (java.time.format.DateTimeFormatter/ofPattern "dd-MM-yyyy"))
            (catch java.time.format.DateTimeParseException _
              (try
                (java.time.LocalDate/parse prefix
                  (java.time.format.DateTimeFormatter/ofPattern "dd.MM.yyyy"))
                (catch java.time.format.DateTimeParseException _ nil)))))))))

(defn- date-range-result
  "Build coverage map from a DB result row. Calculates :total-days from :from/:to.
   Normalizes :from/:to to ISO (YYYY-MM-DD) strings regardless of source format."
  [result]
  (when (and result (:min-date result) (:max-date result))
    (when-let [from-date (parse-flexible-date (:min-date result))]
      (when-let [to-date (parse-flexible-date (:max-date result))]
        (let [total-days (inc (.until from-date to-date java.time.temporal.ChronoUnit/DAYS))]
          {:from       (str from-date)
           :to         (str to-date)
           :days       (:days result)
           :total-days total-days})))))

(defn- query-date-range
  "Query min and max date for a table and marketplace.
   Returns map with :from, :to, :days or nil if no data."
  [table date-col marketplace]
  (let [mp-clause (when marketplace " AND marketplace = ?")
        params (cond-> [] marketplace (conj (name marketplace)))
        sql (str "SELECT MIN(" date-col ") as min_date, 
                         MAX(" date-col ") as max_date,
                         COUNT(DISTINCT " date-col ") as days
                  FROM " (name table)
                  " WHERE " date-col " IS NOT NULL" mp-clause)
        result (first (db/query (into [sql] params)))]
    (date-range-result result)))

(defn- query-stats-date-range
  "Query date range for product_stats table (uses date_from column).
   Returns map with :from, :to, :days or nil if no data."
  []
  (let [result (first (db/query ["SELECT MIN(date_from) as min_date,
                                         MAX(date_to) as max_date,
                                         COUNT(DISTINCT date_from) as days
                                  FROM product_stats
                                  WHERE date_from IS NOT NULL"]))]
    (date-range-result result)))

(defn- query-region-sales-date-range
  "Query date range for region_sales table (uses date_from column).
   Returns map with :from, :to, :days or nil if no data."
  []
  (let [result (first (db/query ["SELECT MIN(date_from) as min_date,
                                         MAX(date_to) as max_date,
                                         COUNT(DISTINCT date_from) as days
                                  FROM region_sales
                                  WHERE date_from IS NOT NULL"]))]
    (date-range-result result)))

(defn- query-cost-prices-date-range
  "Query date range for cost_prices table (uses updated_at column).
   Returns map with :from, :to, :days or nil if no data."
  []
  (let [result (first (db/query ["SELECT MIN(updated_at) as min_date,
                                         MAX(updated_at) as max_date,
                                         COUNT(DISTINCT updated_at) as days
                                  FROM cost_prices
                                  WHERE updated_at IS NOT NULL"]))]
    (date-range-result result)))

(defn- query-prices-date-range
  "Query date range for prices table (uses synced_at column).
   Returns map with :from, :to, :days or nil if no data."
  []
  (let [result (first (db/query ["SELECT MIN(synced_at) as min_date,
                                         MAX(synced_at) as max_date,
                                         COUNT(DISTINCT synced_at) as days
                                  FROM prices
                                  WHERE synced_at IS NOT NULL"]))]
    (date-range-result result)))

(defn- query-cashflow-storage-date-range
  "Storage coverage for Ozon comes from cash_flow_periods (weekly), not
   paid_storage (which is WB-only). :days is the count of distinct periods."
  [marketplace]
  (let [result (first (db/query
                        [(str "SELECT MIN(period_begin) as min_date,
                                      MAX(period_end)   as max_date,
                                      COUNT(DISTINCT period_begin) as days
                               FROM cash_flow_periods
                               WHERE source = ? AND storage IS NOT NULL")
                         (name marketplace)]))]
    (date-range-result result)))

(defn sync-coverage
  "Query database for date range coverage of each data type and marketplace.
   
   Returns JSON with structure:
   {:wb {:sales {:from \"2026-04-01\" :to \"2026-04-30\" :days 30}
         :orders {...}
         :finance {...}
         :storage {...}
         :stocks {...}}
    :ozon {...}
    :ym {...}
    :stats {...}    ;; No marketplace (product_stats)
    :regions {...}  ;; No marketplace (region_sales)
    :1c {...}       ;; No marketplace (cost_prices)
    :prices {...}}  ;; No marketplace (prices)
   
   Requirements: 6.4"
  []
  (let [marketplaces [:wb :ozon :ym]

        ;; Storage source differs per MP. WB exposes per-day per-article storage
        ;; via /api/v1/paid_storage. Ozon publishes weekly storage as part of
        ;; cash-flow-statement, materialized into cash_flow_periods. YM runs FBS-
        ;; only — no marketplace storage cost concept — so we surface a sentinel
        ;; rather than a misleading nil.
        storage-for (fn [mp]
                      (case mp
                        :wb   (query-date-range :paid_storage "date" mp)
                        :ozon (query-cashflow-storage-date-range mp)
                        :ym   {:na true :reason "FBS-only — no marketplace storage costs"}))

        ;; Query marketplace-specific data types
        coverage-by-mp (into {}
                             (for [mp marketplaces]
                               [mp {:sales (query-date-range :sales "date" mp)
                                    :orders (query-date-range :orders "date" mp)
                                    :finance (query-date-range :finance "date_from" mp)
                                    :storage (storage-for mp)
                                    :stocks (query-date-range :stocks "synced_at" mp)}]))
        
        ;; Query non-marketplace data types
        stats-coverage (query-stats-date-range)
        regions-coverage (query-region-sales-date-range)
        cost-prices-coverage (query-cost-prices-date-range)
        prices-coverage (query-prices-date-range)]
    
    (merge coverage-by-mp
           {:stats stats-coverage
            :regions regions-coverage
            :1c cost-prices-coverage
            :prices prices-coverage})))

;; ---------------------------------------------------------------------------
;; Summary Metrics
;; ---------------------------------------------------------------------------

(defn- compute-metrics
  "Compute metrics with WoW delta for a given period and optional marketplace.

   Algorithm:
   1. Calculate period length in days
   2. Calculate previous period of same length
   3. Load sales and finance data for both periods
   4. Calculate P&L for both periods
   5. Calculate WoW deltas

   Returns map with keys:
   - :revenue, :orders, :profit, :return-rate
   - :revenue-wow, :orders-wow, :profit-wow, :return-rate-wow
   - :by-marketplace (vector of maps with per-marketplace metrics)

   Requirements: 4.1, 4.2, 10.1, 10.2, 10.3, 10.4, 10.5

   This is a *dashboard aggregator*, not a UE duplication. It
   intentionally computes scalars + WoW deltas (not the full UE.8 totals
   map) because the dashboard UI consumes a flat shape and needs
   period-over-period context. Each scalar IS sourced from the
   canonical domain: :revenue from finance/totals (§Finance),
   :profit from pnl/calculate (§P&L), :return-rate from qty ratios.
   So there's no formula drift — only shape difference.

   See docs/canonical-formulas.md §Unit Economics UE.9 for comparable
   UE summary metrics; dashboards use this function instead to preserve
   the flat+wow shape."
  [period & {:keys [marketplace]}]
  (let [[from to] (t/resolve-period period)
        from-date (t/parse-date from)
        to-date (t/parse-date to)
        days (inc (t/days-between from-date to-date))
        
        ;; Calculate previous period of same length
        prev-to-date (.minusDays from-date 1)
        prev-from-date (.minusDays prev-to-date (dec days))
        prev-from (t/format-date prev-from-date)
        prev-to (t/format-date prev-to-date)
        
        ;; Load finance data (single source of truth for all financial metrics)
        current-finance (finance/fetch-finance {:from from :to to}
                                                :marketplace marketplace
                                                :source :db)
        prev-finance (finance/fetch-finance {:from prev-from :to prev-to}
                                             :marketplace marketplace
                                             :source :db)

        ;; Calculate current metrics from finance
        current-totals (finance/totals current-finance)
        current-pnl (pnl/calculate current-finance :marketplace marketplace)
        current-sales-qty (:total-sales-qty current-totals)
        current-returns-qty (:total-returns-qty current-totals)
        current-return-rate (math/percentage current-returns-qty
                                             (+ current-sales-qty current-returns-qty))

        ;; Calculate previous metrics from finance
        prev-totals (finance/totals prev-finance)
        prev-pnl (pnl/calculate prev-finance :marketplace marketplace)
        prev-sales-qty (:total-sales-qty prev-totals)
        prev-returns-qty (:total-returns-qty prev-totals)
        prev-return-rate (math/percentage prev-returns-qty
                                          (+ prev-sales-qty prev-returns-qty))

        ;; Calculate WoW deltas
        revenue-wow (math/pct-delta (:total-revenue current-totals)
                                    (:total-revenue prev-totals))
        orders-wow (math/pct-delta current-sales-qty prev-sales-qty)
        profit-wow (math/pct-delta (:net-profit current-pnl)
                                   (:net-profit prev-pnl))
        return-rate-wow (math/pct-delta (or current-return-rate 0)
                                        (or prev-return-rate 0))

        ;; Calculate by-marketplace breakdown (only if no marketplace filter)
        by-marketplace (when-not marketplace
                         (for [mp [:wb :ozon :ym]]
                           (let [mp-finance (finance/fetch-finance {:from from :to to}
                                                                    :marketplace mp
                                                                    :source :db)
                                 mp-totals (finance/totals mp-finance)
                                 mp-pnl (pnl/calculate mp-finance :marketplace mp)
                                 mp-sq (:total-sales-qty mp-totals)
                                 mp-rq (:total-returns-qty mp-totals)]
                             {:marketplace mp
                              :revenue (or (:total-revenue mp-totals) 0.0)
                              :orders (or mp-sq 0)
                              :profit (or (:net-profit mp-pnl) 0.0)
                              :margin (or (:margin-net mp-pnl) 0.0)
                              :return-rate (or (math/percentage mp-rq (+ mp-sq mp-rq)) 0.0)})))]

    {:revenue (or (:total-revenue current-totals) 0.0)
     :orders (or current-sales-qty 0)
     :profit (or (:net-profit current-pnl) 0.0)
     :return-rate (or current-return-rate 0.0)
     :revenue-wow revenue-wow
     :orders-wow orders-wow
     :profit-wow profit-wow
     :return-rate-wow return-rate-wow
     :by-marketplace by-marketplace}))

(defn summary-metrics
  "Calculate summary metrics for dashboard with WoW deltas.
   
   Parameters:
   - period: keyword or map with :from/:to keys
   - marketplace: optional keyword (:wb, :ozon, :ym)
   
   Returns JSON with fields:
   - revenue, orders, profit, return-rate
   - revenue-wow, orders-wow, profit-wow, return-rate-wow
   - by-marketplace (array of marketplace metrics, only if no marketplace filter)
   
   Requirements: 4.1, 4.2, 10.1, 10.2, 10.3, 10.4, 10.5, 14.5"
  [period & {:keys [marketplace]}]
  (try
    (compute-metrics period :marketplace marketplace)
    (catch Exception e
      ;; Log error with mulog (Requirement 14.5)
      (μ/log ::metrics-error
             :function :summary-metrics
             :period period
             :marketplace marketplace
             :error-message (.getMessage e)
             :error-type (type e))
      
      ;; Return zero values on error (Requirements: 10.4, 10.5, 14.1)
      {:revenue 0.0
       :orders 0
       :profit 0.0
       :return-rate 0.0
       :revenue-wow 0.0
       :orders-wow 0.0
       :profit-wow 0.0
       :return-rate-wow 0.0
       :by-marketplace []})))

;; ---------------------------------------------------------------------------
;; Marketplace-specific Metrics
;; ---------------------------------------------------------------------------

(defn marketplace-metrics
  "Calculate marketplace-specific metrics for individual marketplace dashboards.
   
   Parameters:
   - marketplace: keyword (:wb, :ozon, :ym)
   - period: keyword or map with :from/:to keys
   
   Returns JSON with fields:
   - revenue, orders, profit, return-rate (same as summary-metrics)
   - revenue-wow, orders-wow, profit-wow, return-rate-wow
   - top-products: top-10 products by revenue with article, revenue, orders
   - finance-breakdown: financial breakdown with commission, logistics, storage, profit
   - abc-summary: ABC distribution with count and revenue-pct for A, B, C categories
   - top-returns: top returns by article with return-rate
   
   Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 10.2, 14.5"
  [marketplace period]
  (try
    (let [[from to] (t/resolve-period period)

          ;; Load data for the marketplace
          sales-data (sales/fetch-sales {:from from :to to} 
                                         :marketplace marketplace 
                                         :source :db)
          finance-data (finance/fetch-finance {:from from :to to} 
                                               :marketplace marketplace 
                                               :source :db)
          
          ;; Calculate base metrics (reuse compute-metrics)
          base-metrics (compute-metrics period :marketplace marketplace)
          
          ;; Top-10 products by revenue
          by-article (finance/by-article finance-data)
          top-products (->> by-article
                            (sort-by :revenue >)
                            (take 10)
                            (mapv (fn [item]
                                    {:article (:article item)
                                     :revenue (:revenue item)
                                     :orders (:sales-qty item)})))
          
          ;; Financial breakdown
          finance-totals (finance/totals finance-data)
          pnl-data (pnl/calculate finance-data :marketplace marketplace)
          finance-breakdown {:commission (:total-wb-reward finance-totals)
                             :logistics (:total-logistics finance-totals)
                             :storage (:total-storage finance-totals)
                             :profit (:net-profit pnl-data)}
          
          ;; ABC distribution
          abc-data (abc/analyze-by finance-data :revenue)
          abc-summary-data (abc/summary abc-data)
          total-revenue (reduce + 0.0 (map :revenue abc-summary-data))
          abc-summary (mapv (fn [item]
                              {:category (:category item)
                               :count (:count item)
                               :revenue-pct (if (pos? total-revenue)
                                              (math/round2 (* 100.0 (/ (:revenue item) total-revenue)))
                                              0.0)})
                            abc-summary-data)
          
          ;; Top returns
          returns-data (returns/by-article sales-data)
          top-returns (->> returns-data
                           (filter #(>= (:total %) 2))  ;; Min 2 operations
                           (sort-by :return-rate >)
                           (take 10)
                           (mapv (fn [item]
                                   {:article (:article item)
                                    :return-rate (:return-rate item)
                                    :returned (:returned item)
                                    :sold (:sold item)})))]
      
      (merge base-metrics
             {:top-products top-products
              :finance-breakdown finance-breakdown
              :abc-summary abc-summary
              :top-returns top-returns}))
    
    (catch Exception e
      ;; Log error with mulog (Requirement 14.5)
      (μ/log ::metrics-error
             :function :marketplace-metrics
             :marketplace marketplace
             :period period
             :error-message (.getMessage e)
             :error-type (type e))
      
      ;; Return zero values on error
      {:revenue 0.0
       :orders 0
       :profit 0.0
       :return-rate 0.0
       :revenue-wow 0.0
       :orders-wow 0.0
       :profit-wow 0.0
       :return-rate-wow 0.0
       :top-products []
       :finance-breakdown {:commission 0.0 :logistics 0.0 :storage 0.0 :profit 0.0}
       :abc-summary []
       :top-returns []})))
