(ns analitica.domain.buyout
  (:require [analitica.db :as db]
            [analitica.domain.sales :as sales]
            [analitica.report.table :as table]
            [analitica.report.export :as export]
            [analitica.util.time :as t]
            [analitica.util.math :as math]))

(defn analyze
  "Calculate buyout rate per article from sales data."
  [period]
  (let [data     (sales/fetch-sales period)
        by-art   (->> data
                      (group-by :article)
                      (map (fn [[art items]]
                             (let [sold (count (filter #(= :sale (:type %)) items))
                                   rets (count (filter #(= :return (:type %)) items))
                                   total (+ sold rets)]
                               {:article     art
                                :subject     (:subject (first items))
                                :ordered     total
                                :bought      sold
                                :returned    rets
                                :buyout-rate (math/percentage sold total)})))
                      (sort-by :buyout-rate))]
    by-art))

(defn report
  [period]
  (println "\nАнализ % выкупа...")
  (let [data    (analyze period)
        total-o (reduce + 0 (map :ordered data))
        total-b (reduce + 0 (map :bought data))
        total-r (reduce + 0 (map :returned data))
        low     (filter #(and (>= (:ordered %) 3) (< (or (:buyout-rate %) 100) 70)) data)]

    (table/print-summary
     "АНАЛИЗ ВЫКУПА"
     [["Всего операций"    total-o]
      ["Выкуплено"         total-b]
      ["Возвращено"        total-r]
      ["Общий % выкупа"    (str (math/percentage total-b total-o) "%")]])

    (println "\n── Низкий % выкупа (< 70%, мин. 3 операции) ──")
    (if (seq low)
      (table/print-table
       [[:article "Артикул"] [:subject "Предмет"] [:ordered "Операций"]
        [:bought "Выкуп"] [:returned "Возврат"] [:buyout-rate "% выкупа"]]
       low)
      (println "  Все артикулы с хорошим выкупом."))

    (println "\n── Топ-20 по количеству операций ──")
    (table/print-table
     [[:article "Артикул"] [:ordered "Операций"] [:bought "Выкуп"]
      [:returned "Возврат"] [:buyout-rate "% выкупа"]]
     (->> data (sort-by :ordered >) (take 20)))

    data))

(defn export-excel [period path]
  (let [data (analyze period)]
    (export/to-excel path "Buyout rate"
      [[:article "Article"] [:subject "Subject"] [:ordered "Total ops"]
       [:bought "Bought"] [:returned "Returned"] [:buyout-rate "Buyout %"]]
      data)))
