(ns analitica.domain.sales
  (:require [analitica.marketplace.protocol :as proto]
            [analitica.marketplace.registry :as registry]
            [analitica.report.table :as table]
            [analitica.util.time :as t]
            [analitica.util.math :as math]))

;; ---------------------------------------------------------------------------
;; Data fetching
;; ---------------------------------------------------------------------------

(defn- get-mp [marketplace]
  (registry/get-marketplace (or marketplace :wb)))

(defn fetch-sales
  "Fetch sales for a period. Period can be:
   - keyword like :last-30-days, :last-7-days, :this-month
   - map {:from \"2026-03-01\" :to \"2026-03-31\"}"
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [mp   (get-mp marketplace)
        [from to] (if (keyword? period)
                    (t/period period)
                    [(:from period) (:to period)])]
    (proto/fetch-sales mp from to)))

(defn fetch-orders
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [mp   (get-mp marketplace)
        [from to] (if (keyword? period)
                    (t/period period)
                    [(:from period) (:to period)])]
    (proto/fetch-orders mp from to)))

;; ---------------------------------------------------------------------------
;; Aggregation helpers
;; ---------------------------------------------------------------------------

(defn- parse-date-str [s]
  (when s (subs s 0 10)))

(defn- group-and-sum [data group-fn]
  (->> data
       (group-by group-fn)
       (map (fn [[k items]]
              (let [sales   (filter #(= :sale (:type %)) items)
                    returns (filter #(= :return (:type %)) items)]
                {:group         k
                 :sales-count   (count sales)
                 :returns-count (count returns)
                 :revenue       (reduce + 0.0 (map #(or (:for-pay %) 0) sales))
                 :avg-price     (math/round2
                                 (math/safe-div
                                  (reduce + 0.0 (map #(or (:finished-price %) 0) sales))
                                  (count sales)))})))
       (sort-by :revenue >)))

;; ---------------------------------------------------------------------------
;; Reports
;; ---------------------------------------------------------------------------

(defn by-day
  "Aggregate sales by day."
  [sales-data]
  (group-and-sum sales-data #(parse-date-str (:date %))))

(defn by-article
  "Aggregate sales by supplier article."
  [sales-data]
  (group-and-sum sales-data :article))

(defn by-category
  "Aggregate sales by category."
  [sales-data]
  (group-and-sum sales-data :subject))

(defn by-brand
  "Aggregate sales by brand."
  [sales-data]
  (group-and-sum sales-data :brand))

(defn by-warehouse
  "Aggregate sales by warehouse."
  [sales-data]
  (group-and-sum sales-data :warehouse))

(defn by-region
  "Aggregate sales by region."
  [sales-data]
  (group-and-sum sales-data :region))

(defn totals
  "Calculate total summary from sales data."
  [sales-data]
  (let [sales   (filter #(= :sale (:type %)) sales-data)
        returns (filter #(= :return (:type %)) sales-data)]
    {:total-sales    (count sales)
     :total-returns  (count returns)
     :total-revenue  (math/round2 (reduce + 0.0 (map #(or (:for-pay %) 0) sales)))
     :avg-price      (math/round2 (math/safe-div
                                   (reduce + 0.0 (map #(or (:finished-price %) 0) sales))
                                   (count sales)))
     :return-rate    (math/percentage (count returns) (+ (count sales) (count returns)))
     :unique-skus    (count (distinct (map :article sales-data)))}))

;; ---------------------------------------------------------------------------
;; Dashboard (main entry point)
;; ---------------------------------------------------------------------------

(defn dashboard
  "Print sales dashboard.
   Usage:
     (dashboard :last-7-days)
     (dashboard :last-30-days)
     (dashboard {:from \"2026-03-01\" :to \"2026-03-31\"})
     (dashboard :last-30-days :marketplace :wb)"
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (println "\nЗагрузка данных продаж...")
  (let [data    (fetch-sales period :marketplace marketplace)
        summary (totals data)]

    (table/print-summary
     (str "ПРОДАЖИ — " (if (keyword? period) (name period) (str (:from period) " — " (:to period))))
     [["Продажи"         (:total-sales summary)]
      ["Возвраты"        (:total-returns summary)]
      ["Выручка (к оплате)" (:total-revenue summary)]
      ["Средний чек"     (:avg-price summary)]
      ["% возвратов"     (str (:return-rate summary) "%")]
      ["Уникальных SKU"  (:unique-skus summary)]])

    (println "\n── По дням ──")
    (table/print-table
     [[:group "Дата"] [:sales-count "Продажи"] [:returns-count "Возвраты"]
      [:revenue "Выручка"] [:avg-price "Ср. чек"]]
     (by-day data))

    (println "\n── Топ-10 артикулов ──")
    (table/print-table
     [[:group "Артикул"] [:sales-count "Продажи"] [:returns-count "Возвраты"]
      [:revenue "Выручка"] [:avg-price "Ср. чек"]]
     (take 10 (by-article data)))

    (println "\n── По категориям ──")
    (table/print-table
     [[:group "Категория"] [:sales-count "Продажи"] [:returns-count "Возвраты"]
      [:revenue "Выручка"] [:avg-price "Ср. чек"]]
     (by-category data))

    (println "\n── По складам ──")
    (table/print-table
     [[:group "Склад"] [:sales-count "Продажи"] [:returns-count "Возвраты"]
      [:revenue "Выручка"]]
     (by-warehouse data))

    summary))
