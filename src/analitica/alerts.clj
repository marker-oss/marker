(ns analitica.alerts
  "Alert detection engine — pure + impure layers.

  Pure:  (detect-alerts data-map)  — all 5 rules, cap 5, sort by severity.
  Impure: (detect-alerts! opts)    — fetches data from DB, calls pure layer."
  (:require [analitica.db :as db]
            [analitica.domain.sales    :as sales]
            [analitica.domain.finance  :as finance]
            [analitica.domain.pnl      :as pnl]
            [analitica.domain.stock    :as stock]
            [analitica.domain.buyout   :as buyout]
            [analitica.util.period     :as period]))

;; ---------------------------------------------------------------------------
;; Thresholds (hardcoded V1 — easy to override in tests or future config)
;; ---------------------------------------------------------------------------

(def thresholds
  {:out-of-stock-days          7        ; days-of-cover below this fires
   :out-of-stock-min-daily     1        ; avg-daily-sales must be at least this
   :returns-spike-buyout-max   70.0     ; buyout-rate below this fires (percent)
   :margin-drop-abs-pct        15.0     ; absolute margin drop in pct-points fires
   :top-mover-growth-pct       30.0     ; revenue growth above this fires
   :zero-sales-days            3})      ; days with 0 sales for top-SKU rule

;; ---------------------------------------------------------------------------
;; Severity ordering (for sort + UI coloring)
;; ---------------------------------------------------------------------------

(def ^:private severity-order {:red 1 :yellow 2 :green 3})

;; ---------------------------------------------------------------------------
;; Rule: OUT_OF_STOCK
;; ---------------------------------------------------------------------------

(defn- rule-out-of-stock
  "Fires for every SKU where days-of-cover < threshold AND avg-daily-sales >= 1.
   stocks-with-turnover: rows from (stock/with-turnover ...)."
  [stocks-with-turnover]
  (let [min-days  (:out-of-stock-days thresholds)
        min-daily (:out-of-stock-min-daily thresholds)]
    (->> stocks-with-turnover
         (filter (fn [s]
                   (let [doc  (or (:days-of-cover (:days-left s)) (:days-of-cover s) (:days-left s))
                         ads  (or (:avg-daily-sales s) (:daily-rate s))]
                     (and (some? doc)
                          (< doc min-days)
                          (some? ads)
                          (>= ads min-daily)))))
         (map (fn [s]
                (let [doc  (or (:days-of-cover s) (:days-left s))
                      ads  (or (:avg-daily-sales s) (:daily-rate s))
                      name (or (:name s) (:subject s) (:article s))
                      size (or (:size s) "")
                      art  (:article s)]
                  {:rule         :OUT_OF_STOCK
                   :severity     :red
                   :delta        (- doc)  ; negative = fewer days → higher urgency
                   :title        (str "Заканчивается: " name (when (seq size) (str " / " size)))
                   :body         (format "%s — остатков на %.0f дней (%.0f шт/день)"
                                         name (double doc) (double ads))
                   :action-route (str "/reports/stock?article=" art)
                   :action-label "Смотреть остатки"}))))))

;; ---------------------------------------------------------------------------
;; Rule: ZERO_SALES_TOP_SKU
;; ---------------------------------------------------------------------------

(defn- rule-zero-sales-top-sku
  "Fires when a top-10-by-revenue article had 0 sales in the last 3 days.
   top-10: [{:article :rank :name :revenue}]
   sales-last-3-days: [{:article ...}]"
  [top-10-by-revenue sales-last-3-days]
  (let [articles-with-sales (into #{} (map :article sales-last-3-days))]
    (->> top-10-by-revenue
         (remove (fn [t] (contains? articles-with-sales (:article t))))
         (map (fn [t]
                {:rule         :ZERO_SALES_TOP_SKU
                 :severity     :red
                 :delta        (- (:rank t))  ; rank 1 = most urgent
                 :title        (str "0 продаж: " (or (:name t) (:article t)))
                 :body         (format "%s — 0 продаж за 3 дня (топ-%d по выручке)"
                                       (or (:name t) (:article t)) (int (:rank t)))
                 :action-route (str "/reports/sales?article=" (:article t))
                 :action-label "Смотреть продажи"})))))

;; ---------------------------------------------------------------------------
;; Rule: MARGIN_DROP
;; ---------------------------------------------------------------------------

(defn- safe-margin
  "Compute net-profit / revenue as a percentage; returns nil when revenue=0."
  [pnl-map]
  (let [rev (or (:revenue pnl-map) 0)
        np  (or (:net-profit pnl-map) 0)]
    (when (pos? rev)
      (* 100.0 (/ np rev)))))

(defn- rule-margin-drop
  "Fires when absolute margin drop from prev-pnl to current-pnl exceeds threshold.
   Both pnl maps must have :revenue and :net-profit."
  [current-pnl prev-pnl]
  (let [curr-margin (safe-margin current-pnl)
        prev-margin (safe-margin prev-pnl)
        threshold   (:margin-drop-abs-pct thresholds)]
    (when (and curr-margin prev-margin)
      (let [drop-pct (- prev-margin curr-margin)]
        (when (> drop-pct threshold)
          [{:rule         :MARGIN_DROP
            :severity     :yellow
            :delta        (- drop-pct)  ; negative = bigger drop = more urgent
            :title        (format "Маржа упала на %.0f%%" drop-pct)
            :body         (format "Маржа упала на %.1f%% за период (с %.1f%% до %.1f%%)"
                                  drop-pct prev-margin curr-margin)
            :action-route "/reports/pnl"
            :action-label "Смотреть P&L"}])))))

;; ---------------------------------------------------------------------------
;; Rule: RETURNS_SPIKE
;; ---------------------------------------------------------------------------

(defn- rule-returns-spike
  "Fires when any article's buyout-rate < threshold (70%).
   Picks the article with the lowest buyout-rate (worst first).
   buyout-data: rows from (buyout/analyze period)"
  [current-buyout]
  (let [max-rate (:returns-spike-buyout-max thresholds)]
    (->> current-buyout
         (filter (fn [b]
                   (let [r (:buyout-rate b)]
                     (and (some? r) (< r max-rate)))))
         ;; Sort by worst rate first for delta ordering
         (sort-by :buyout-rate)
         (map (fn [b]
                (let [rate (double (:buyout-rate b))]
                  {:rule         :RETURNS_SPIKE
                   :severity     :yellow
                   :delta        (- rate)  ; negative = lower rate = more urgent
                   :title        (format "Низкий выкуп: %.0f%%" rate)
                   :body         (format "Выкуп упал до %.0f%% (норма 80-90%%)" rate)
                   :action-route "/reports/buyout"
                   :action-label "Смотреть выкуп"}))))))

;; ---------------------------------------------------------------------------
;; Rule: TOP_MOVER
;; ---------------------------------------------------------------------------

(defn- rule-top-mover
  "Fires when an article's revenue in current period grew > 30% vs prev.
   current-sales-by-article: rows from (sales/by-article ...)
   prev-sales-by-article: rows from (sales/by-article ...) for compare period"
  [current-sales-by-article prev-sales-by-article]
  (let [threshold (:top-mover-growth-pct thresholds)
        prev-by-art (into {} (map (juxt :group :revenue) prev-sales-by-article))
        prev-by-art2 (into {} (map (juxt :article :revenue) prev-sales-by-article))]
    (->> current-sales-by-article
         (filter (fn [curr]
                   (let [art      (or (:group curr) (:article curr))
                         curr-rev (or (:revenue curr) 0.0)
                         prev-rev (or (get prev-by-art art)
                                      (get prev-by-art2 art)
                                      0.0)]
                     (and (pos? prev-rev)
                          (> (* 100.0 (/ (- curr-rev prev-rev) prev-rev)) threshold)))))
         (map (fn [curr]
                (let [art       (or (:group curr) (:article curr))
                      curr-rev  (or (:revenue curr) 0.0)
                      prev-rev  (or (get prev-by-art art) (get prev-by-art2 art) 0.0)
                      growth    (* 100.0 (/ (- curr-rev prev-rev) prev-rev))
                      name      (or (:subject curr) art)]
                  {:rule         :TOP_MOVER
                   :severity     :green
                   :delta        growth
                   :title        (format "Рост продаж: %s (+%.0f%%)" name growth)
                   :body         (format "%s — выручка +%.0f%% за период" name growth)
                   :action-route (str "/reports/sales?article=" art)
                   :action-label "Смотреть продажи"}))))))

;; ---------------------------------------------------------------------------
;; Pure aggregator
;; ---------------------------------------------------------------------------

(defn detect-alerts
  "Pure alert detector. Takes a pre-fetched data map and runs all 5 rules.

  Input keys:
    :stocks-with-turnover       — from (stock/with-turnover ...)
    :current-sales-by-article   — from (sales/by-article current-sales)
    :prev-sales-by-article      — from (sales/by-article prev-sales)
    :current-pnl                — from (pnl/calculate ...)
    :prev-pnl                   — from (pnl/calculate ...) for prev period
    :current-buyout             — from (buyout/analyze ...)
    :sales-last-3-days          — sales rows from last 3 days
    :top-10-by-revenue          — top 10 articles by revenue with :rank

  Returns up to 5 alerts sorted by severity (red→yellow→green) then |delta| DESC."
  [{:keys [stocks-with-turnover
           current-sales-by-article prev-sales-by-article
           current-pnl prev-pnl
           current-buyout
           sales-last-3-days top-10-by-revenue]}]
  (let [all-alerts (concat
                    (rule-out-of-stock (or stocks-with-turnover []))
                    (rule-zero-sales-top-sku (or top-10-by-revenue [])
                                             (or sales-last-3-days []))
                    (or (rule-margin-drop current-pnl prev-pnl) [])
                    (rule-returns-spike (or current-buyout []))
                    (rule-top-mover (or current-sales-by-article [])
                                    (or prev-sales-by-article [])))]
    (->> all-alerts
         (sort-by (fn [a]
                    [(get severity-order (:severity a) 99)
                     (- (Math/abs (double (or (:delta a) 0))))]))
         (take 5)
         vec)))

;; ---------------------------------------------------------------------------
;; Impure wrapper — fetches data from DB, calls pure layer
;; ---------------------------------------------------------------------------

(defn- last-synced-at
  "Query sync_tasks for MAX(finished_at) per marketplace status='ok'.
   Returns a map {mp-kw iso-string-or-nil}."
  []
  (try
    (let [rows (db/query
                ["SELECT marketplace, MAX(finished_at) AS last_sync
                  FROM sync_tasks
                  WHERE status = 'ok'
                  GROUP BY marketplace"])]
      (into {} (map (fn [r] [(keyword (:marketplace r)) (:last-sync r)]) rows)))
    (catch Exception _ {})))

(defn freshness-data
  "Return a map of {:wb iso-or-nil :ozon iso-or-nil :ym iso-or-nil}."
  []
  (merge {:wb nil :ozon nil :ym nil}
         (last-synced-at)))

(defn detect-alerts!
  "Impure: fetches data from DB for the given period, then calls pure detect-alerts.

  Options:
    :from        ISO date string (default: 30 days ago)
    :to          ISO date string (default: today)
    :marketplace keyword :wb|:ozon|:ym|nil (nil = all)"
  [& {:keys [from to marketplace]}]
  (let [state      (if (and from to)
                     {:from from :to to}
                     (period/default-state))
        curr-from  (:from state)
        curr-to    (:to state)
        curr-period {:from curr-from :to curr-to}
        ;; Compare period: same length immediately preceding
        [prev-from prev-to] (period/compare-period curr-period)
        prev-period {:from prev-from :to prev-to}
        ;; Days in period for turnover calculation
        days       (period/days-between curr-from curr-to)
        ;; Fetch data — all with explicit :marketplace kwarg per memory note
        curr-sales  (try (sales/fetch-sales curr-period :marketplace marketplace)
                         (catch Exception _ []))
        prev-sales  (try (sales/fetch-sales prev-period :marketplace marketplace)
                         (catch Exception _ []))
        curr-stocks (try (stock/fetch-stocks :marketplace (or marketplace :wb))
                         (catch Exception _ []))
        stocks-by-art (stock/by-article curr-stocks)
        stocks-turn   (try (stock/with-turnover stocks-by-art curr-sales days)
                           (catch Exception _ []))
        curr-finance  (try (finance/fetch-finance curr-period :marketplace marketplace)
                           (catch Exception _ []))
        prev-finance  (try (finance/fetch-finance prev-period :marketplace marketplace)
                           (catch Exception _ []))
        curr-pnl    (try (pnl/calculate curr-finance :marketplace marketplace)
                         (catch Exception _ {:revenue 0 :net-profit 0}))
        prev-pnl    (try (pnl/calculate prev-finance :marketplace marketplace)
                         (catch Exception _ {:revenue 0 :net-profit 0}))
        buyout-data (try (buyout/analyze curr-period)
                         (catch Exception _ []))
        ;; Sales by article for movers
        curr-by-art (sales/by-article curr-sales)
        prev-by-art (sales/by-article prev-sales)
        ;; Top 10 by revenue from current sales
        top-10 (->> curr-by-art
                    (sort-by :revenue >)
                    (take 10)
                    (map-indexed (fn [i r] (assoc r :rank (inc i)))))
        ;; Sales last 3 days (filter from curr-sales by date)
        three-days-ago (-> (java.time.LocalDate/parse curr-to)
                           (.minusDays 2)
                           str)
        last-3-sales (->> curr-sales
                          (filter (fn [s]
                                    (let [d (or (:date s) (:event-date s) "")]
                                      (and (seq d) (>= (compare (subs d 0 10) three-days-ago) 0))))))]
    (detect-alerts
     {:stocks-with-turnover       stocks-turn
      :current-sales-by-article   curr-by-art
      :prev-sales-by-article      prev-by-art
      :current-pnl                curr-pnl
      :prev-pnl                   prev-pnl
      :current-buyout             buyout-data
      :sales-last-3-days          last-3-sales
      :top-10-by-revenue          top-10})))
