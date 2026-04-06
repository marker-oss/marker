(ns analitica.domain.unit-economics
  (:require [analitica.domain.finance :as finance]
            [analitica.domain.cost-price :as cost-price]
            [analitica.report.table :as table]
            [analitica.report.export :as export]
            [analitica.util.math :as math]))

(defn calculate
  "Calculate unit economics from finance data.
   Full chain: revenue → -commission → -logistics → -storage → -penalties = for_pay → -cost = profit

   Fields from finance/by-article:
     :revenue      — розничная выручка (retail_amount по продажам)
     :commission   — комиссия WB
     :logistics    — логистика (доставка + возвраты)
     :storage      — хранение на складе WB
     :acceptance   — платная приёмка
     :penalties    — штрафы
     :for-pay      — к выплате от WB (revenue - все удержания)
     :total-cost   — себестоимость (из 1С, по баркоду)

   Profit = for_pay - total_cost"
  [finance-data]
  (let [by-art (finance/by-article finance-data)]
    (->> by-art
         (map (fn [{:keys [sales-qty returns-qty revenue commission logistics
                           storage acceptance penalties for-pay total-cost] :as row}]
                (let [net-qty          (max 1 (- sales-qty returns-qty))
                      total-deductions (+ (Math/abs (or commission 0))
                                         (or logistics 0)
                                         (or storage 0)
                                         (or acceptance 0)
                                         (or penalties 0))
                      profit           (- for-pay total-cost)
                      profit-per-unit  (math/round2 (math/safe-div profit net-qty))
                      margin-pct       (math/percentage profit revenue)
                      logistics-per-unit (math/round2 (math/safe-div logistics (+ sales-qty returns-qty)))
                      storage-per-unit   (math/round2 (math/safe-div storage net-qty))
                      cost-per-unit      (math/round2 (math/safe-div total-cost sales-qty))]
                  (assoc row
                         :total-deductions (math/round2 total-deductions)
                         :profit           (math/round2 profit)
                         :profit-per-unit  profit-per-unit
                         :margin-pct       margin-pct
                         :logistics-per-unit logistics-per-unit
                         :storage-per-unit   storage-per-unit
                         :cost-per-unit      cost-per-unit))))
         (sort-by :profit >))))

(defn totals [ue-data]
  (let [total-for-pay   (reduce + 0.0 (map :for-pay ue-data))
        total-cost      (reduce + 0.0 (map :total-cost ue-data))
        total-profit    (reduce + 0.0 (map :profit ue-data))
        total-logistics (reduce + 0.0 (map :logistics ue-data))
        total-storage   (reduce + 0.0 (map :storage ue-data))
        total-revenue   (reduce + 0.0 (map :revenue ue-data))
        total-commission (reduce + 0.0 (map :commission ue-data))]
    {:total-revenue    (math/round2 total-revenue)
     :total-for-pay   (math/round2 total-for-pay)
     :total-cost       (math/round2 total-cost)
     :total-profit     (math/round2 total-profit)
     :total-logistics  (math/round2 total-logistics)
     :total-storage    (math/round2 total-storage)
     :total-commission (math/round2 total-commission)
     :total-acceptance (math/round2 (reduce + 0.0 (map :acceptance ue-data)))
     :total-penalties  (math/round2 (reduce + 0.0 (map :penalties ue-data)))
     :total-deductions (math/round2 (reduce + 0.0 (map :total-deductions ue-data)))
     :margin-pct       (math/percentage total-profit total-revenue)
     :cost-loaded      (pos? total-cost)}))

(defn report
  "Print unit economics report.
   Usage:
     (report :last-30-days)
     (report {:from \"2026-03-01\" :to \"2026-03-31\"})"
  [period & {:keys [marketplace source] :or {marketplace :wb source :db}}]
  (println "\nЗагрузка данных для юнит-экономики...")
  (let [fin-data (finance/fetch-finance period :marketplace marketplace :source source)
        ue-data  (calculate fin-data)
        summary  (totals ue-data)]

    (when-not (:cost-loaded summary)
      (println "\n! Себестоимость не загружена. Загрузите:")
      (println "  (cost-price/load-from-1c)   ;; из 1С")
      (println "  (sync/sync! :1c)            ;; сохранить в БД"))

    (table/print-summary
     "ЮНИТ-ЭКОНОМИКА"
     [["Выручка (розница)"    (:total-revenue summary)]
      ["— Комиссия WB"        (:total-commission summary)]
      ["— Логистика"          (:total-logistics summary)]
      ["— Хранение"           (:total-storage summary)]
      ["— Приёмка"            (:total-acceptance summary)]
      ["— Штрафы"             (:total-penalties summary)]
      ["= К выплате от WB"    (:total-for-pay summary)]
      ["— Себестоимость"      (:total-cost summary)]
      ["= ПРИБЫЛЬ"            (:total-profit summary)]
      ["  Маржинальность"     (str (:margin-pct summary) "%")]])

    (println "\n── Топ-20 по прибыли ──")
    (table/print-table
     [[:article "Артикул"] [:sales-qty "Прод."] [:revenue "Выручка"]
      [:cost-per-unit "Себ/шт"] [:logistics-per-unit "Лог/шт"] [:storage-per-unit "Скл/шт"]
      [:for-pay "От WB"] [:profit "Прибыль"] [:profit-per-unit "На ед."] [:margin-pct "Маржа%"]]
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

;; ---------------------------------------------------------------------------
;; Export
;; ---------------------------------------------------------------------------

(def ^:private ue-export-cols
  [[:article "Article"] [:brand "Brand"] [:subject "Subject"]
   [:sales-qty "Sales qty"] [:returns-qty "Returns qty"]
   [:revenue "Revenue"] [:cost-per-unit "COGS/unit"] [:total-cost "COGS total"]
   [:commission "Commission"] [:logistics "Logistics"] [:logistics-per-unit "Logistics/unit"]
   [:storage "Storage"] [:storage-per-unit "Storage/unit"]
   [:acceptance "Acceptance"] [:penalties "Penalties"]
   [:for-pay "WB Payout"] [:profit "Profit"]
   [:profit-per-unit "Profit/unit"] [:margin-pct "Margin%"]])

(defn export-excel [period path & opts]
  (let [fin-data (apply finance/fetch-finance period opts)
        ue-data  (calculate fin-data)]
    (export/to-excel path "Юнит-экономика" ue-export-cols ue-data)))
