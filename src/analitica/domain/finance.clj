(ns analitica.domain.finance
  (:require [analitica.marketplace.protocol :as proto]
            [analitica.marketplace.registry :as registry]
            [analitica.db :as db]
            [analitica.domain.cost-price :as cost-price]
            [analitica.report.table :as table]
            [analitica.report.export :as export]
            [analitica.util.time :as t]
            [analitica.util.math :as math]))

(defn- get-mp [marketplace]
  (registry/get-marketplace (or marketplace :wb)))

(defn- resolve-dates [period]
  (if (keyword? period) (t/period period) [(:from period) (:to period)]))

(defn- db-finance [from to]
  (db/query ["SELECT * FROM finance WHERE date_from >= ? AND date_to <= ? ORDER BY rrd_id"
             from to]))

(defn fetch-finance
  [period & {:keys [marketplace source] :or {marketplace :wb source :db}}]
  (let [[from to] (resolve-dates period)]
    (case source
      :db  (db-finance from to)
      :api (proto/fetch-finance-report (get-mp marketplace) from to))))

;; ---------------------------------------------------------------------------
;; Aggregation
;; ---------------------------------------------------------------------------

(defn- line-cost
  "Get cost price for a finance line via barcode lookup."
  [line]
  (let [barcode (or (:barcode line) (:barcode line))]
    (or (when barcode (cost-price/get-price nil barcode))
        (cost-price/get-price (:article line))
        0.0)))

(defn by-article [finance-data]
  (->> finance-data
       (group-by :article)
       (map (fn [[article lines]]
              (let [sales-lines  (filter #(= "Продажа" (:operation %)) lines)
                    return-lines (filter #(= "Возврат" (:operation %)) lines)
                    ;; Себестоимость по баркодам каждой строки продажи
                    total-cost   (reduce + 0.0
                                   (map #(* (line-cost %) (max 1 (or (:quantity %) 1)))
                                        sales-lines))]
                {:article     article
                 :brand       (or (:brand (first lines)) (:brand-name (first lines)))
                 :subject     (or (:subject (first lines)) (:subject-name (first lines)))
                 :sales-qty   (reduce + 0 (map #(or (:quantity %) 0) sales-lines))
                 :returns-qty (reduce + 0 (map #(or (:quantity %) 0) return-lines))
                 :revenue     (math/round2 (reduce + 0.0 (map #(or (:retail-amount %) 0) sales-lines)))
                 :commission  (math/round2 (reduce + 0.0 (map #(let [ra (or (:retail-amount %) 0)
                                                                      fp (or (:for-pay %) 0)]
                                                                  (- ra fp))
                                                               sales-lines)))
                 :logistics   (math/round2 (reduce + 0.0 (map #(or (:delivery-cost %) 0) lines)))
                 :penalties   (math/round2 (reduce + 0.0 (map #(or (:penalty %) 0) lines)))
                 :additional  (math/round2 (reduce + 0.0 (map #(or (:additional-payment %) 0) lines)))
                 :storage     (math/round2 (reduce + 0.0 (map #(or (:storage-fee %) 0) lines)))
                 :acceptance  (math/round2 (reduce + 0.0 (map #(or (:acceptance %) 0) lines)))
                 :for-pay     (math/round2 (reduce + 0.0 (map #(or (:for-pay %) 0) lines)))
                 :cost-price  (math/round2 (line-cost (first sales-lines)))
                 :total-cost  (math/round2 total-cost)})))
       (sort-by :for-pay >)))

(defn totals [finance-data]
  (let [articles (by-article finance-data)]
    {:total-revenue     (math/round2 (reduce + 0.0 (map :revenue articles)))
     :total-commission  (math/round2 (reduce + 0.0 (map :commission articles)))
     :total-logistics   (math/round2 (reduce + 0.0 (map :logistics articles)))
     :total-penalties   (math/round2 (reduce + 0.0 (map :penalties articles)))
     :total-storage     (math/round2 (reduce + 0.0 (map :storage articles)))
     :total-acceptance  (math/round2 (reduce + 0.0 (map :acceptance articles)))
     :total-additional  (math/round2 (reduce + 0.0 (map :additional articles)))
     :total-for-pay    (math/round2 (reduce + 0.0 (map :for-pay articles)))
     :total-sales-qty   (reduce + 0 (map :sales-qty articles))
     :total-returns-qty (reduce + 0 (map :returns-qty articles))
     :articles-count    (count articles)}))

(defn by-report-id [finance-data]
  (->> finance-data
       (group-by (fn [r] (or (:report-id r) (:report_id r))))
       (map (fn [[id lines]]
              {:report-id id
               :date-from (or (:date-from (first lines)) (:date_from (first lines)))
               :date-to   (or (:date-to (first lines)) (:date_to (first lines)))
               :lines     (count lines)
               :for-pay   (math/round2 (reduce + 0.0 (map #(or (:for-pay %) 0) lines)))}))
       (sort-by :date-from)))

;; ---------------------------------------------------------------------------
;; Report
;; ---------------------------------------------------------------------------

(defn report
  [period & {:keys [marketplace source] :or {marketplace :wb source :db}}]
  (println "\nЗагрузка финансового отчёта...")
  (let [data    (fetch-finance period :marketplace marketplace :source source)
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

;; ---------------------------------------------------------------------------
;; Export
;; ---------------------------------------------------------------------------

(def ^:private finance-export-cols
  [[:article "Артикул"] [:brand "Бренд"] [:subject "Предмет"]
   [:sales-qty "Продажи шт"] [:returns-qty "Возвраты шт"]
   [:revenue "Выручка"] [:commission "Комиссия"] [:logistics "Логистика"]
   [:storage "Хранение"] [:acceptance "Приёмка"] [:penalties "Штрафы"]
   [:additional "Доплаты"] [:for-pay "К выплате"]])

(defn export-csv [period path & opts]
  (let [data (apply fetch-finance period opts)]
    (export/to-csv path finance-export-cols (by-article data))))

(defn export-excel [period path & opts]
  (let [data (apply fetch-finance period opts)]
    (export/to-excel path
                     [{:name "По артикулам" :cols finance-export-cols :rows (by-article data)}
                      {:name "По отчётам"
                       :cols [[:report-id "ID"] [:date-from "С"] [:date-to "По"]
                              [:lines "Строк"] [:for-pay "К выплате"]]
                       :rows (by-report-id data)}])))
