(ns analitica.domain.returns
  (:require [analitica.domain.sales :as sales]
            [analitica.report.table :as table]
            [analitica.util.math :as math]))

(defn- parse-date-str [s]
  (when s (subs s 0 10)))

(defn by-article
  "Return rate analysis by article."
  [sales-data]
  (->> sales-data
       (group-by :article)
       (map (fn [[article items]]
              (let [sold     (count (filter #(= :sale (:type %)) items))
                    returned (count (filter #(= :return (:type %)) items))
                    total    (+ sold returned)]
                {:article     article
                 :subject     (:subject (first items))
                 :sold        sold
                 :returned    returned
                 :total       total
                 :return-rate (math/percentage returned total)})))
       (sort-by :return-rate >)))

(defn by-day
  "Return dynamics by day."
  [sales-data]
  (let [returns (filter #(= :return (:type %)) sales-data)]
    (->> returns
         (group-by #(parse-date-str (:date %)))
         (map (fn [[day items]]
                {:date     day
                 :returns  (count items)}))
         (sort-by :date))))

(defn totals [sales-data]
  (let [sold     (count (filter #(= :sale (:type %)) sales-data))
        returned (count (filter #(= :return (:type %)) sales-data))]
    {:sold        sold
     :returned    returned
     :return-rate (math/percentage returned (+ sold returned))}))

(defn report
  "Print returns analysis report.
   Usage:
     (report :last-30-days)
     (report {:from \"2026-03-01\" :to \"2026-03-31\"})"
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (println "\nЗагрузка данных для анализа возвратов...")
  (let [data    (sales/fetch-sales period :marketplace marketplace)
        summary (totals data)]

    (table/print-summary
     "АНАЛИЗ ВОЗВРАТОВ"
     [["Продажи"     (:sold summary)]
      ["Возвраты"    (:returned summary)]
      ["% возвратов" (str (:return-rate summary) "%")]])

    (println "\n── Динамика возвратов ──")
    (table/print-table
     [[:date "Дата"] [:returns "Возвраты"]]
     (by-day data))

    (println "\n── Топ-20 по % возвратов (мин. 2 операции) ──")
    (table/print-table
     [[:article "Артикул"] [:subject "Предмет"] [:sold "Продано"]
      [:returned "Возвращено"] [:return-rate "% возврата"]]
     (->> (by-article data)
          (filter #(>= (:total %) 2))
          (take 20)))

    summary))
