(ns analitica.domain.unit-economics
  (:require [analitica.domain.finance :as finance]
            [analitica.domain.cost-price :as cost-price]
            [analitica.report.table :as table]
            [analitica.util.math :as math]))

(defn calculate
  "Calculate unit economics from finance data and cost prices.
   Returns per-article breakdown."
  [finance-data]
  (let [by-art (finance/by-article finance-data)]
    (->> by-art
         (map (fn [{:keys [article sales-qty returns-qty revenue commission
                           logistics storage penalties acceptance for-pay] :as row}]
                (let [cost       (or (cost-price/get-price article) 0.0)
                      total-cost (* cost sales-qty)
                      net-qty    (max 1 (- sales-qty returns-qty))
                      profit     (- for-pay total-cost)
                      profit-per-unit (math/round2 (math/safe-div profit net-qty))
                      margin-pct (math/percentage profit (+ for-pay total-cost))]
                  (assoc row
                         :cost-price    cost
                         :total-cost    (math/round2 total-cost)
                         :profit        (math/round2 profit)
                         :profit-per-unit profit-per-unit
                         :margin-pct    margin-pct))))
         (sort-by :profit >))))

(defn totals [ue-data]
  (let [total-revenue  (reduce + 0.0 (map :revenue ue-data))
        total-for-pay  (reduce + 0.0 (map :for-pay ue-data))
        total-cost     (reduce + 0.0 (map :total-cost ue-data))
        total-profit   (reduce + 0.0 (map :profit ue-data))
        total-logistics (reduce + 0.0 (map :logistics ue-data))
        total-storage  (reduce + 0.0 (map :storage ue-data))
        total-commission (reduce + 0.0 (map :commission ue-data))]
    {:total-revenue    (math/round2 total-revenue)
     :total-for-pay   (math/round2 total-for-pay)
     :total-cost       (math/round2 total-cost)
     :total-profit     (math/round2 total-profit)
     :total-logistics  (math/round2 total-logistics)
     :total-storage    (math/round2 total-storage)
     :total-commission (math/round2 total-commission)
     :margin-pct       (math/percentage total-profit (+ total-for-pay total-cost))
     :cost-loaded      (pos? total-cost)}))

(defn report
  "Print unit economics report.
   Usage:
     (report {:from \"2026-03-01\" :to \"2026-03-31\"})
     (report :last-30-days)"
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (println "\nЗагрузка данных для юнит-экономики...")
  (let [fin-data (finance/fetch-finance period :marketplace marketplace)
        ue-data  (calculate fin-data)
        summary  (totals ue-data)]

    (when-not (:cost-loaded summary)
      (println "\n⚠ Себестоимость не загружена. Загрузите через:")
      (println "  (require '[analitica.domain.cost-price :as cp])")
      (println "  (cp/load-from-csv \"data/cost-prices.csv\")")
      (println "  или (cp/set-price! \"article\" 500.0)"))

    (table/print-summary
     "ЮНИТ-ЭКОНОМИКА"
     [["Выручка"           (:total-revenue summary)]
      ["К выплате от WB"   (:total-for-pay summary)]
      ["Себестоимость"     (:total-cost summary)]
      ["Комиссия WB"       (:total-commission summary)]
      ["Логистика"         (:total-logistics summary)]
      ["Хранение"          (:total-storage summary)]
      ["Прибыль"           (:total-profit summary)]
      ["Маржинальность"    (str (:margin-pct summary) "%")]])

    (println "\n── По артикулам (топ-20 по прибыли) ──")
    (table/print-table
     [[:article "Артикул"] [:sales-qty "Прод."] [:for-pay "От WB"]
      [:total-cost "Себест."] [:logistics "Логист."] [:storage "Хранение"]
      [:profit "Прибыль"] [:profit-per-unit "На ед."] [:margin-pct "Маржа%"]]
     (take 20 ue-data))

    (println "\n── Убыточные артикулы ──")
    (let [losers (filter #(neg? (:profit %)) ue-data)]
      (if (seq losers)
        (table/print-table
         [[:article "Артикул"] [:sales-qty "Прод."] [:for-pay "От WB"]
          [:total-cost "Себест."] [:profit "Убыток"] [:margin-pct "Маржа%"]]
         losers)
        (println "  Убыточных артикулов нет.")))

    summary))
