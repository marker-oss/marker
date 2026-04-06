(ns analitica.domain.finance
  (:require [analitica.marketplace.protocol :as proto]
            [analitica.marketplace.registry :as registry]
            [analitica.report.table :as table]
            [analitica.util.time :as t]
            [analitica.util.math :as math]))

(defn- get-mp [marketplace]
  (registry/get-marketplace (or marketplace :wb)))

(defn fetch-finance
  "Fetch financial report for period."
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [mp      (get-mp marketplace)
        [from to] (if (keyword? period)
                    (t/period period)
                    [(:from period) (:to period)])]
    (proto/fetch-finance-report mp from to)))

;; ---------------------------------------------------------------------------
;; Aggregation
;; ---------------------------------------------------------------------------

(defn by-article
  "Group financial report by article with totals."
  [finance-data]
  (->> finance-data
       (group-by :article)
       (map (fn [[article lines]]
              (let [sales-lines   (filter #(= "Продажа" (:operation %)) lines)
                    return-lines  (filter #(= "Возврат" (:operation %)) lines)
                    delivery-lines (filter #(some-> (:operation %) (.contains "Логистика")) lines)]
                {:article        article
                 :brand          (:brand (first lines))
                 :subject        (:subject (first lines))
                 :sales-qty      (reduce + 0 (map #(or (:quantity %) 0) sales-lines))
                 :returns-qty    (reduce + 0 (map #(or (:quantity %) 0) return-lines))
                 :revenue        (math/round2 (reduce + 0.0 (map #(or (:retail-amount %) 0) sales-lines)))
                 :commission     (math/round2 (reduce + 0.0 (map #(let [ra (or (:retail-amount %) 0)
                                                                        fp (or (:for-pay %) 0)]
                                                                    (- ra fp))
                                                                 sales-lines)))
                 :logistics      (math/round2 (reduce + 0.0 (map #(or (:delivery-cost %) 0) lines)))
                 :penalties      (math/round2 (reduce + 0.0 (map #(or (:penalty %) 0) lines)))
                 :additional     (math/round2 (reduce + 0.0 (map #(or (:additional-payment %) 0) lines)))
                 :storage        (math/round2 (reduce + 0.0 (map #(or (:storage-fee %) 0) lines)))
                 :acceptance     (math/round2 (reduce + 0.0 (map #(or (:acceptance %) 0) lines)))
                 :for-pay        (math/round2 (reduce + 0.0 (map #(or (:for-pay %) 0) lines)))})))
       (sort-by :for-pay >)))

(defn totals
  "Compute totals from finance data."
  [finance-data]
  (let [articles (by-article finance-data)]
    {:total-revenue    (math/round2 (reduce + 0.0 (map :revenue articles)))
     :total-commission (math/round2 (reduce + 0.0 (map :commission articles)))
     :total-logistics  (math/round2 (reduce + 0.0 (map :logistics articles)))
     :total-penalties  (math/round2 (reduce + 0.0 (map :penalties articles)))
     :total-storage    (math/round2 (reduce + 0.0 (map :storage articles)))
     :total-acceptance (math/round2 (reduce + 0.0 (map :acceptance articles)))
     :total-additional (math/round2 (reduce + 0.0 (map :additional articles)))
     :total-for-pay   (math/round2 (reduce + 0.0 (map :for-pay articles)))
     :total-sales-qty  (reduce + 0 (map :sales-qty articles))
     :total-returns-qty (reduce + 0 (map :returns-qty articles))
     :articles-count   (count articles)}))

(defn by-report-id
  "Group by weekly report ID."
  [finance-data]
  (->> finance-data
       (group-by :report-id)
       (map (fn [[id lines]]
              {:report-id  id
               :date-from  (:date-from (first lines))
               :date-to    (:date-to (first lines))
               :lines      (count lines)
               :for-pay    (math/round2 (reduce + 0.0 (map #(or (:for-pay %) 0) lines)))}))
       (sort-by :date-from)))

;; ---------------------------------------------------------------------------
;; Report (main entry point)
;; ---------------------------------------------------------------------------

(defn report
  "Print weekly financial report.
   Usage:
     (report {:from \"2026-03-24\" :to \"2026-03-30\"})
     (report :last-7-days)"
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (println "\nЗагрузка финансового отчёта...")
  (let [data    (fetch-finance period :marketplace marketplace)
        summary (totals data)]

    (table/print-summary
     "ФИНАНСОВЫЙ ОТЧЁТ"
     [["Продажи (шт)"     (:total-sales-qty summary)]
      ["Возвраты (шт)"    (:total-returns-qty summary)]
      ["Выручка"          (:total-revenue summary)]
      ["Комиссия WB"      (:total-commission summary)]
      ["Логистика"        (:total-logistics summary)]
      ["Хранение"         (:total-storage summary)]
      ["Приёмка"          (:total-acceptance summary)]
      ["Штрафы"           (:total-penalties summary)]
      ["Доплаты"          (:total-additional summary)]
      ["К выплате"        (:total-for-pay summary)]
      ["Артикулов"        (:articles-count summary)]])

    (println "\n── Детализация по артикулам ──")
    (table/print-table
     [[:article "Артикул"] [:sales-qty "Прод."] [:returns-qty "Возвр."]
      [:revenue "Выручка"] [:commission "Комиссия"] [:logistics "Логистика"]
      [:storage "Хранение"] [:for-pay "К выплате"]]
     (by-article data))

    (let [reports (by-report-id data)]
      (when (> (count reports) 1)
        (println "\n── По еженедельным отчётам ──")
        (table/print-table
         [[:report-id "ID отчёта"] [:date-from "С"] [:date-to "По"]
          [:lines "Строк"] [:for-pay "К выплате"]]
         reports)))

    summary))
