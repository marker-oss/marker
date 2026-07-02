(ns analitica.alerts
  "Alert detection engine — pure layer.

  Pure:  (detect-alerts data-map)  — all 5 rules, cap 5, sort by severity.
  Impure fetch/freshness: (freshness-data) — last sync timestamps by MP."
  (:require [analitica.db :as db]))

(defn- url-encode
  "URL-encode a string component for safe use in query params.
   Articles often contain '/', spaces, and Cyrillic — these MUST be encoded
   before going into href, otherwise the route parser sees a different path."
  [s]
  (java.net.URLEncoder/encode (or s "") "UTF-8"))

;; ---------------------------------------------------------------------------
;; Thresholds (hardcoded V1 — easy to override in tests or future config)
;; ---------------------------------------------------------------------------

(def thresholds
  {:out-of-stock-days          7        ; days-of-cover below this fires
   :out-of-stock-min-daily     1        ; avg-daily-sales must be at least this
   :returns-spike-buyout-max   70.0     ; non-return-rate below this fires (percent)
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

   Accepts both shapes: production rows from `stock/with-turnover` carry
   `:days-left`/`:daily-rate`; inline test fixtures use the canonical
   `:days-of-cover`/`:avg-daily-sales`."
  [stocks-with-turnover]
  (let [min-days  (:out-of-stock-days thresholds)
        min-daily (:out-of-stock-min-daily thresholds)]
    (->> stocks-with-turnover
         (filter (fn [s]
                   (let [doc  (or (:days-of-cover s) (:days-left s))
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
                   :action-route (str "/reports/stock?article=" (url-encode art))
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
                 :action-route (str "/reports/sales?article=" (url-encode (:article t)))
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

   Revenue-collapse case (curr-revenue = 0, prev-revenue > 0): safe-margin
   would return nil and the rule used to silently skip — masking the worst
   possible scenario. We treat the effective current margin as 0 in that
   case so a healthy prev period correctly triggers the alert."
  [current-pnl prev-pnl]
  (let [curr-rev    (or (:revenue current-pnl) 0)
        prev-rev    (or (:revenue prev-pnl)    0)
        curr-margin (safe-margin current-pnl)
        prev-margin (safe-margin prev-pnl)
        effective-curr (cond
                         curr-margin                            curr-margin
                         (and (zero? curr-rev) (pos? prev-rev)) 0.0
                         :else                                  nil)
        threshold   (:margin-drop-abs-pct thresholds)]
    (when (and effective-curr prev-margin)
      (let [drop-pct (- prev-margin effective-curr)]
        (when (> drop-pct threshold)
          [{:rule         :MARGIN_DROP
            :severity     :yellow
            :delta        (- drop-pct)
            :title        (format "Маржа упала на %.0f%%" drop-pct)
            :body         (if (and (zero? curr-rev) (pos? prev-rev))
                            (format "Выручка обвалилась до 0₽ (была маржа %.1f%%)" prev-margin)
                            (format "Маржа упала на %.1f%% за период (с %.1f%% до %.1f%%)"
                                    drop-pct prev-margin effective-curr))
            :action-route "/reports/pnl"
            :action-label "Смотреть P&L"}])))))

;; ---------------------------------------------------------------------------
;; Rule: RETURNS_SPIKE
;; ---------------------------------------------------------------------------

(defn- rule-returns-spike
  "Fires when any article's non-return-rate (sales-only: sold/ops) < threshold (70%).
   Picks the article with the lowest non-return-rate (worst first).
   buyout-data: rows from (buyout/analyze period).
   Alias-aware: reads :non-return-rate with :buyout-rate fallback (deprecated)."
  [current-buyout]
  (let [max-rate (:returns-spike-buyout-max thresholds)]
    (->> current-buyout
         (filter (fn [b]
                   (let [r (or (:non-return-rate b) (:buyout-rate b))]
                     (and (some? r) (< r max-rate)))))
         ;; Sort by worst rate first for delta ordering
         (sort-by (fn [b] (or (:non-return-rate b) (:buyout-rate b))))
         (map (fn [b]
                (let [rate (double (or (:non-return-rate b) (:buyout-rate b)))]
                  {:rule         :RETURNS_SPIKE
                   :severity     :yellow
                   :delta        (- rate)  ; negative = lower rate = more urgent
                   :title        (format "Высокий процент невозвратов: %.0f%%" rate)
                   :body         (format "Доля невозвратов упала до %.0f%% (норма 80-90%%)" rate)
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
                   :action-route (str "/reports/sales?article=" (url-encode art))
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

