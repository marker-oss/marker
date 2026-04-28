(ns analitica.domain.stock
  (:require [analitica.marketplace.protocol :as proto]
            [analitica.marketplace.registry :as registry]
            [analitica.db :as db]
            [analitica.domain.sales :as sales]
            [analitica.report.table :as table]
            [analitica.report.export :as export]
            [analitica.util.time :as t]
            [analitica.util.math :as math]))

(defn- get-mp [marketplace]
  (registry/get-marketplace (or marketplace :wb)))

(defn fetch-stocks
  [& {:keys [marketplace source] :or {marketplace :wb source :db}}]
  (case source
    :db  (if marketplace
           (db/query ["SELECT * FROM stocks WHERE marketplace = ? ORDER BY article"
                      (name marketplace)])
           (db/query ["SELECT * FROM stocks ORDER BY article"]))
    :api (proto/fetch-stocks (get-mp marketplace))))

;; ---------------------------------------------------------------------------
;; Aggregation
;; ---------------------------------------------------------------------------

(defn by-article
  "Aggregate stock by article (sum across warehouses). §Stock.1
   NOTE: output keys :in-way-to / :in-way-from are renamed from
   source keys :in-way-to-client / :in-way-from-client (§Stock.8.1)."
  [stocks]
  (->> stocks
       (group-by :article)
       (map (fn [[article items]]
              {:article       article
               :subject       (:subject (first items))
               :brand         (:brand (first items))
               :quantity      (reduce + 0 (map #(or (:quantity %) 0) items))
               :quantity-full (reduce + 0 (map #(or (:quantity-full %) 0) items))
               :in-way-to     (reduce + 0 (map #(or (:in-way-to-client %) 0) items))
               :in-way-from   (reduce + 0 (map #(or (:in-way-from-client %) 0) items))
               :warehouses    (count (distinct (map :warehouse items)))}))
       (sort-by :quantity-full >)))

(defn by-warehouse
  "Aggregate stock by warehouse."
  [stocks]
  (->> stocks
       (group-by :warehouse)
       (map (fn [[wh items]]
              {:warehouse     wh
               :articles      (count (distinct (map :article items)))
               :quantity      (reduce + 0 (map #(or (:quantity %) 0) items))
               :quantity-full (reduce + 0 (map #(or (:quantity-full %) 0) items))}))
       (sort-by :quantity-full >)))

(defn totals [stocks]
  {:total-quantity   (reduce + 0 (map #(or (:quantity %) 0) stocks))
   :total-full       (reduce + 0 (map #(or (:quantity-full %) 0) stocks))
   :total-to-client  (reduce + 0 (map #(or (:in-way-to-client %) 0) stocks))
   :total-from-client (reduce + 0 (map #(or (:in-way-from-client %) 0) stocks))
   :unique-articles  (count (distinct (map :article stocks)))
   :warehouses       (count (distinct (map :warehouse stocks)))})

;; ---------------------------------------------------------------------------
;; Turnover (requires sales data)
;; ---------------------------------------------------------------------------

(defn with-turnover
  "Enrich stock-by-article with turnover data from sales.
   sales-data: result of (sales/fetch-sales period)
   days: number of days in the sales period."
  [stock-by-article sales-data days]
  (let [sales-by-art (->> sales-data
                          (filter #(= :sale (:type %)))
                          (group-by :article)
                          (map (fn [[art items]] [art (count items)]))
                          (into {}))]
    (->> stock-by-article
         (map (fn [s]
                (let [sold       (get sales-by-art (:article s) 0)
                      daily-rate (math/safe-div sold days)
                      qty        (:quantity-full s)
                      days-left  (if (pos? daily-rate)
                                   (math/round2 (/ qty daily-rate))
                                   nil)]
                  (assoc s
                         :sold-period sold
                         :daily-rate  (math/round2 daily-rate)
                         :days-left   days-left))))
         (sort-by :days-left (fn [a b]
                               (cond
                                 (nil? a) 1
                                 (nil? b) -1
                                 :else (compare a b)))))))

;; ---------------------------------------------------------------------------
;; Reports
;; ---------------------------------------------------------------------------

(defn overview
  "Print stock overview."
  [& {:keys [marketplace] :or {marketplace :wb}}]
  (println "\nЗагрузка остатков...")
  (let [stocks  (fetch-stocks :marketplace marketplace)
        summary (totals stocks)]

    (table/print-summary
     "ОСТАТКИ НА СКЛАДАХ"
     [["На складе (доступно)" (:total-quantity summary)]
      ["Всего (с учётом в пути)" (:total-full summary)]
      ["В пути к клиенту"     (:total-to-client summary)]
      ["В пути от клиента"    (:total-from-client summary)]
      ["Уникальных артикулов" (:unique-articles summary)]
      ["Складов"              (:warehouses summary)]])

    (println "\n── По складам ──")
    (table/print-table
     [[:warehouse "Склад"] [:articles "Артикулов"] [:quantity "Доступно"]
      [:quantity-full "Всего"]]
     (by-warehouse stocks))

    (println "\n── Топ-20 артикулов ──")
    (table/print-table
     [[:article "Артикул"] [:subject "Предмет"] [:quantity "Доступно"]
      [:quantity-full "Всего"] [:in-way-to "К клиенту"] [:in-way-from "От клиента"]]
     (take 20 (by-article stocks)))

    summary))

(defn risk
  "Show items at risk of running out within `days-threshold` days.
   Uses last 30 days of sales to calculate velocity."
  [days-threshold & {:keys [marketplace] :or {marketplace :wb}}]
  (println "\nЗагрузка остатков и продаж...")
  (let [stocks     (fetch-stocks :marketplace marketplace)
        sales-data (sales/fetch-sales :last-30-days :marketplace marketplace)
        enriched   (with-turnover (by-article stocks) sales-data 30)
        at-risk    (filter #(and (:days-left %)
                                 (<= (:days-left %) days-threshold)
                                 (pos? (:quantity-full %)))
                           enriched)]

    (table/print-summary
     (str "РИСК ДЕФИЦИТА (< " days-threshold " дней)")
     [["Товаров под угрозой" (count at-risk)]])

    (when (seq at-risk)
      (table/print-table
       [[:article "Артикул"] [:quantity-full "Остаток"] [:sold-period "Продано/30д"]
        [:daily-rate "В день"] [:days-left "Дней осталось"]]
       at-risk))

    at-risk))

;; ---------------------------------------------------------------------------
;; Export
;; ---------------------------------------------------------------------------

(defn export-excel [path & opts]
  (let [stocks (apply fetch-stocks opts)]
    (export/to-excel path
                     [{:name "По артикулам"
                       :cols [[:article "Артикул"] [:subject "Предмет"] [:quantity "Доступно"]
                              [:quantity-full "Всего"] [:in-way-to "К клиенту"]
                              [:in-way-from "От клиента"] [:warehouses "Складов"]]
                       :rows (by-article stocks)}
                      {:name "По складам"
                       :cols [[:warehouse "Склад"] [:articles "Артикулов"]
                              [:quantity "Доступно"] [:quantity-full "Всего"]]
                       :rows (by-warehouse stocks)}])))

;; ---------------------------------------------------------------------------
;; RFC-13 (closed 2026-04-28): stocks_history queries
;;
;; Pure functions over rows from `stocks_history`. Snapshots are written
;; daily by `analitica.materialize/snapshot-stocks-history!`. No backfill
;; possible — history starts from the day RFC-13 went live.
;; ---------------------------------------------------------------------------

(defn fetch-history
  "Read `stocks_history` rows in `[from to]` window, optionally scoped
   to a marketplace and/or specific article. Returns rows in
   chronological order grouped by (article, warehouse).

   Args:
     from           ISO date (YYYY-MM-DD)
     to             ISO date (YYYY-MM-DD)
     :marketplace   keyword filter (optional, default :all)
     :article       single-article filter (optional)"
  [from to & {:keys [marketplace article]}]
  (let [base-sql ["SELECT * FROM stocks_history
                   WHERE snapshot_date >= ? AND snapshot_date <= ?"
                  from to]
        with-mp  (if (and marketplace (not= :all marketplace))
                   (-> base-sql
                       (update 0 #(str % " AND marketplace = ?"))
                       (conj (name marketplace)))
                   base-sql)
        with-art (if article
                   (-> with-mp
                       (update 0 #(str % " AND article = ?"))
                       (conj article))
                   with-mp)
        final    (update with-art 0
                         #(str % " ORDER BY article, warehouse, snapshot_date"))]
    (db/query final)))

(defn velocity
  "Per-day average sales velocity for an article over a window of
   `stocks_history` rows. Computed as
       (qty[start] - qty[end]) / days_in_window
   (positive value = stock declining, i.e. units shipped per day).

   Returns nil when:
     - fewer than 2 snapshots in the window
     - the window has zero days

   Per-warehouse velocities sum to the per-article velocity provided
   that the article uses a stable set of warehouses across the window
   — caller can pass already-aggregated rows."
  [history-rows]
  (when (>= (count history-rows) 2)
    (let [sorted (sort-by :snapshot-date history-rows)
          first* (first sorted)
          last*  (last sorted)
          days   (try
                   (.between java.time.temporal.ChronoUnit/DAYS
                             (java.time.LocalDate/parse (:snapshot-date first*))
                             (java.time.LocalDate/parse (:snapshot-date last*)))
                   (catch Throwable _ 0))]
      (when (pos? days)
        (let [delta (- (or (:quantity first*) 0) (or (:quantity last*) 0))]
          (double (/ delta days)))))))

(defn days-of-supply
  "Estimate how many days the current stock will last given the velocity
   computed from `history-rows`. Returns:
     - positive number: days remaining at current velocity
     - 0:               stock already at zero
     - :infinite:       velocity is zero or negative (stock not declining)

   Useful for the Stock report's restock-soon column."
  [history-rows]
  (let [v (velocity history-rows)]
    (cond
      (nil? v)        nil
      (<= v 0)        :infinite
      :else
      (let [latest (->> history-rows (sort-by :snapshot-date) last)
            qty   (or (:quantity latest) 0)]
        (if (zero? qty) 0 (double (/ qty v)))))))

(defn stock-trend
  "Two-point trend snapshot: oldest vs newest in the window.
   Returns {:from-date :to-date :from-qty :to-qty :delta :pct-change}."
  [history-rows]
  (when (>= (count history-rows) 1)
    (let [sorted (sort-by :snapshot-date history-rows)
          first* (first sorted)
          last*  (last sorted)
          q1     (or (:quantity first*) 0)
          q2     (or (:quantity last*) 0)
          delta  (- q2 q1)]
      {:from-date  (:snapshot-date first*)
       :to-date    (:snapshot-date last*)
       :from-qty   q1
       :to-qty     q2
       :delta      delta
       :pct-change (when (pos? q1) (* 100.0 (/ delta (double q1))))})))

