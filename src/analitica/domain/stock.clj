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
    :db  (db/query ["SELECT * FROM stocks ORDER BY article"])
    :api (proto/fetch-stocks (get-mp marketplace))))

;; ---------------------------------------------------------------------------
;; Aggregation
;; ---------------------------------------------------------------------------

(defn by-article
  "Aggregate stock by article (sum across warehouses)."
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

    at-risk))

