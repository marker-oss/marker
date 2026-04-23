(ns analitica.web.api.export
  (:require [clojure.java.io :as io]
            [analitica.web.api.report :as report-api]
            [analitica.report.export :as export]
            [analitica.util.time :as t]))

;; ---------------------------------------------------------------------------
;; Helper functions
;; ---------------------------------------------------------------------------

(defn- resolve-dates
  "Convert period to [from to] date strings."
  [period]
  (cond
    (vector? period) period
    (keyword? period) (t/period period)
    (map? period) [(:from period) (:to period)]
    :else (throw (ex-info "Invalid period format" {:period period}))))

(defn- format-period-str
  "Format period for filename."
  [period]
  (let [[from to] (resolve-dates period)]
    (str from "_" to)))

(defn- get-report-columns
  "Get column definitions for each report type."
  [report-type]
  (case report-type
    :sales [[:group "Дата"] [:sales-count "Продажи"] [:returns-count "Возвраты"] 
            [:revenue "Выручка"] [:avg-price "Ср. чек"]]
    :finance [[:article "Артикул"] [:sales-qty "Продано"] [:revenue "Выручка"] 
              [:wb-reward "Вознаграждение"] [:logistics "Логистика"] 
              [:storage "Хранение"] [:for-pay "К выплате"] [:total-cost "Затраты"]]
    :ue [[:article "Артикул"] [:brand "Бренд"] [:subject "Категория"] 
         [:sales-qty "Продано"] [:buyout-rate "% выкупа"] [:revenue "Выручка"] 
         [:revenue-per-unit "Выручка/ед"] [:cost-per-unit "Затраты/ед"] 
         [:profit-per-unit "Прибыль/ед"] [:margin-pct "Маржа%"]]
    :pnl [[:revenue "Выручка"] [:for-pay "К выплате"] [:cogs "Себестоимость"] 
          [:gross-profit "Валовая прибыль"] [:net-profit "Чистая прибыль"] 
          [:margin-net "Маржа%"]]
    :abc [[:article "Артикул"] [:abc-category "ABC"] [:cum-pct "Накопл. %"] 
          [:revenue "Выручка"] [:for-pay "К выплате"] [:sales-qty "Продано"]]
    :stock [[:article "Артикул"] [:quantity "Остаток"] [:quantity-full "Полный остаток"] 
            [:in-way-to "В пути к клиенту"] [:in-way-from "В пути от клиента"] 
            [:warehouses "Склады"]]
    :returns [[:article "Артикул"] [:sold "Продано"] [:returned "Возвращено"] 
              [:total "Всего"] [:return-rate "% возврата"]]
    :buyout [[:article "Артикул"] [:ordered "Заказано"] [:bought "Выкуплено"] 
             [:returned "Возвращено"] [:buyout-rate "% выкупа"]]
    :geo [[:region "Регион"] [:qty "Количество"] [:sum "Сумма"]]
    :trends [[:metric "Метрика"] [:current "Текущий"] [:previous "Предыдущий"] 
             [:change "Изменение"] [:change-pct "Изменение%"]]
    ;; Default columns
    []))

;; ---------------------------------------------------------------------------
;; Export function
;; ---------------------------------------------------------------------------

(defn export-report
  "Generate Excel or CSV file for download.
   
   Parameters:
   - report-type: keyword (:sales, :finance, :ue, etc.)
   - period: vector [from to], keyword, or map with :from/:to keys
   - marketplace: optional keyword (:wb, :ozon, :ym)
   - format: :excel or :csv
   
   Returns Ring response with Content-Disposition: attachment.
   
   Requirements: 9.2, 9.3, 9.4, 9.5"
  [report-type period marketplace format]
  (try
    ;; Convert period to format expected by report-data
    (let [period-for-report (if (vector? period)
                              {:from (first period) :to (second period)}
                              period)
          data (report-api/report-data report-type period-for-report :marketplace marketplace)
          period-str (format-period-str period)
          mp-str (if marketplace (name marketplace) "all")
          filename (str (name report-type) "-" period-str "-" mp-str 
                       (if (= format :excel) ".xlsx" ".csv"))
          temp-file (io/file (System/getProperty "java.io.tmpdir") filename)
          temp-path (.getAbsolutePath temp-file)
          cols (get-report-columns report-type)]
      
      ;; Generate file
      (if (= format :excel)
        (export/to-excel temp-path (name report-type) cols data)
        (export/to-csv temp-path cols data))
      
      ;; Return file as download
      {:status 200
       :headers {"Content-Type" (if (= format :excel)
                                   "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                   "text/csv; charset=utf-8")
                 "Content-Disposition" (str "attachment; filename=\"" filename "\"")}
       :body (io/input-stream temp-file)})
    
    (catch Exception e
      {:status 500
       :headers {"Content-Type" "application/json; charset=utf-8"}
       :body {:error (str "Export failed: " (.getMessage e))}})))
