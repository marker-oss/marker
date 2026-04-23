(ns analitica.web.pages.reports
  (:require [hiccup.core :refer [html]]
            [analitica.web.components :as c]))

;; ---------------------------------------------------------------------------
;; No Data Banner
;; ---------------------------------------------------------------------------

(defn- no-data-banner
  "Render 'No data' banner with link to sync page.
   
   Requirements: 14.1"
  []
  [:div.bg-yellow-50.border-l-4.border-yellow-400.p-4.mb-6
   [:div.flex
    [:div.flex-shrink-0
     [:svg.h-5.w-5.text-yellow-400 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor"}
      [:path {:fill-rule "evenodd" :d "M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" :clip-rule "evenodd"}]]]
    [:div.ml-3
     [:p.text-sm.text-yellow-700
      "Нет данных за выбранный период. "
      [:a.font-medium.underline.text-yellow-700.hover:text-yellow-600 {:href "/sync"} 
       "Запустите синхронизацию"]
      " для загрузки данных."]]]])

;; ---------------------------------------------------------------------------
;; Report Metadata
;; ---------------------------------------------------------------------------

(def report-configs
  "Configuration for all 10 report types with titles, columns, and chart types."
  {:sales {:title "Продажи"
           :chart-type "line"
           :columns [{:title "Дата" :field "group" :width 120}
                     {:title "Продажи" :field "sales-count" :width 100}
                     {:title "Возвраты" :field "returns-count" :width 100}
                     {:title "Выручка" :field "revenue" :width 150}
                     {:title "Средняя цена" :field "avg-price" :width 120}]}
   
   :finance {:title "Финансы"
             :chart-type "bar"
             :columns [{:title "Артикул" :field "article" :width 150}
                       {:title "Продажи" :field "sales-qty" :width 100}
                       {:title "Выручка" :field "revenue" :width 150}
                       {:title "Вознаграждение WB" :field "wb-reward" :width 150}
                       {:title "Логистика" :field "logistics" :width 120}
                       {:title "Хранение" :field "storage" :width 120}
                       {:title "К оплате" :field "for-pay" :width 150}
                       {:title "Общие затраты" :field "total-cost" :width 150}]}
   
   :ue {:title "Юнит-экономика"
        :chart-type "horizontalBar"
        :columns [{:title "Артикул" :field "article" :width 150}
                  {:title "Бренд" :field "brand" :width 120}
                  {:title "Категория" :field "subject" :width 150}
                  {:title "Продажи" :field "sales-qty" :width 100}
                  {:title "% выкупа" :field "buyout-rate" :width 100}
                  {:title "Выручка" :field "revenue" :width 150}
                  {:title "Выручка/ед" :field "revenue-per-unit" :width 120}
                  {:title "Себестоимость/ед" :field "cost-per-unit" :width 150}
                  {:title "Прибыль/ед" :field "profit-per-unit" :width 120}
                  {:title "Маржа %" :field "margin-pct" :width 100}]}
   
   :pnl {:title "P&L"
         :chart-type "waterfall"
         :columns [{:title "Метрика" :field "metric" :width 200}
                   {:title "Значение" :field "value" :width 150}]}
   
   :abc {:title "ABC-анализ"
         :chart-type "line"
         :columns [{:title "Артикул" :field "article" :width 150}
                   {:title "Категория" :field "abc-category" :width 100}
                   {:title "Накопленный %" :field "cum-pct" :width 120}
                   {:title "Выручка" :field "revenue" :width 150}
                   {:title "К оплате" :field "for-pay" :width 150}
                   {:title "Продажи" :field "sales-qty" :width 100}]}
   
   :stock {:title "Остатки"
           :chart-type "bar"
           :columns [{:title "Артикул" :field "article" :width 150}
                     {:title "Количество" :field "quantity" :width 100}
                     {:title "Полное кол-во" :field "quantity-full" :width 120}
                     {:title "В пути к клиенту" :field "in-way-to" :width 130}
                     {:title "В пути от клиента" :field "in-way-from" :width 150}
                     {:title "Склады" :field "warehouses" :width 200}]}
   
   :returns {:title "Возвраты"
             :chart-type "line"
             :columns [{:title "Артикул" :field "article" :width 150}
                       {:title "Продано" :field "sold" :width 100}
                       {:title "Возвращено" :field "returned" :width 100}
                       {:title "Всего" :field "total" :width 100}
                       {:title "% возврата" :field "return-rate" :width 120}]}
   
   :buyout {:title "Выкуп"
            :chart-type "bar"
            :columns [{:title "Артикул" :field "article" :width 150}
                      {:title "Заказано" :field "ordered" :width 100}
                      {:title "Выкуплено" :field "bought" :width 100}
                      {:title "Возвращено" :field "returned" :width 100}
                      {:title "% выкупа" :field "buyout-rate" :width 120}]}
   
   :geo {:title "География"
         :chart-type "bar"
         :columns [{:title "Регион" :field "region" :width 200}
                   {:title "Количество" :field "qty" :width 100}
                   {:title "Сумма" :field "sum" :width 150}]}
   
   :trends {:title "Тренды"
            :chart-type "bar"
            :columns [{:title "Метрика" :field "metric" :width 200}
                      {:title "Текущий" :field "current" :width 120}
                      {:title "Предыдущий" :field "previous" :width 120}
                      {:title "Изменение" :field "change" :width 120}
                      {:title "Изменение %" :field "change-pct" :width 120}]}})

;; ---------------------------------------------------------------------------
;; Filter Components
;; ---------------------------------------------------------------------------

(defn- period-filter
  "Render period filter dropdown with HTMX update."
  [report-type current-period marketplace]
  (let [marketplace-param (if marketplace (str "&marketplace=" (name marketplace)) "")]
    [:div.flex.items-center.gap-2
     [:label.text-sm.font-medium.text-gray-700 {:for "period-filter"} "Период:"]
     [:select#period-filter.px-3.py-2.border.border-gray-300.rounded-md.text-sm.focus:outline-none.focus:ring-2.focus:ring-blue-500
      {:name "period"
       :hx-get (str "/reports/" (name report-type))
       :hx-trigger "change"
       :hx-target "#report-content"
       :hx-swap "outerHTML"
       :hx-select "#report-content"
       :hx-include "#marketplace-filter, #article-filter"}
      [:option {:value "last-week" :selected (= current-period "last-week")}
       "Прошлая неделя"]
      [:option {:value "last-7-days" :selected (= current-period "last-7-days")}
       "Последние 7 дней"]
      [:option {:value "last-30-days" :selected (= current-period "last-30-days")}
       "Последние 30 дней"]
      [:option {:value "this-month" :selected (= current-period "this-month")}
       "Этот месяц"]]]))

(defn- marketplace-filter
  "Render marketplace filter dropdown with HTMX update."
  [report-type period current-marketplace]
  [:div.flex.items-center.gap-2
   [:label.text-sm.font-medium.text-gray-700 {:for "marketplace-filter"} "Маркетплейс:"]
   [:select#marketplace-filter.px-3.py-2.border.border-gray-300.rounded-md.text-sm.focus:outline-none.focus:ring-2.focus:ring-blue-500
    {:name "marketplace"
     :hx-get (str "/reports/" (name report-type))
     :hx-trigger "change"
     :hx-target "#report-content"
     :hx-swap "outerHTML"
     :hx-select "#report-content"
     :hx-include "#period-filter, #article-filter"}
    [:option {:value "all" :selected (or (nil? current-marketplace) (= current-marketplace "all"))}
     "Все"]
    [:option {:value "wb" :selected (= current-marketplace "wb")}
     "Wildberries"]
    [:option {:value "ozon" :selected (= current-marketplace "ozon")}
     "Ozon"]
    [:option {:value "ym" :selected (= current-marketplace "ym")}
     "Yandex.Market"]]])

(defn- article-filter
  "Article text input for filtering UE report to a single article."
  [report-type current-article]
  [:div.flex.items-center.gap-2
   [:label.text-sm.font-medium.text-gray-700 {:for "article-filter"} "Артикул:"]
   [:input#article-filter.px-3.py-2.border.border-gray-300.rounded-md.text-sm.focus:outline-none.focus:ring-2.focus:ring-blue-500
    {:type "text"
     :name "article"
     :value (or current-article "")
     :placeholder "Все артикулы"
     :hx-get (str "/reports/" (name report-type))
     :hx-trigger "input changed delay:400ms"
     :hx-target "#report-content"
     :hx-swap "outerHTML"
     :hx-select "#report-content"
     :hx-include "#period-filter, #marketplace-filter"}]])

(defn- export-buttons
  "Render Excel and CSV export buttons."
  [report-type period marketplace]
  (let [base-url (str "/api/export/" (name report-type) "?period=" period)
        marketplace-param (if (and marketplace (not= marketplace "all"))
                            (str "&marketplace=" marketplace)
                            "")]
    [:div.flex.items-center.gap-2
     [:a.px-4.py-2.bg-green-600.text-white.rounded-md.hover:bg-green-700.transition-colors.text-sm.font-medium
      {:href (str base-url marketplace-param "&format=excel")
       :download true}
      "📊 Excel"]
     [:a.px-4.py-2.bg-blue-600.text-white.rounded-md.hover:bg-blue-700.transition-colors.text-sm.font-medium
      {:href (str base-url marketplace-param "&format=csv")
       :download true}
      "📄 CSV"]]))

;; ---------------------------------------------------------------------------
;; Report Page
;; ---------------------------------------------------------------------------

(defn report-page
  "Render unified report page template for all 10 report types.
   
   Parameters:
   - report-type: keyword (:sales, :finance, :ue, :pnl, :abc, :stock, :returns, :buyout, :geo, :trends)
   - period: period string (e.g. last-week, last-30-days)
   - marketplace: optional marketplace keyword or string
   - show-no-data: optional boolean to show no-data banner
   
   Features:
   - Period and marketplace filters with HTMX updates
   - Excel and CSV export buttons
   - Chart.js visualization
   - Tabulator interactive table with:
     - Sorting
     - Column filters
     - Global search
     - Pagination (50 rows)
     - Frozen first column
   - No data banner when data is missing
   
   Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 8.1, 9.1, 14.1"
  [report-type period marketplace & {:keys [show-no-data article]}]
  (let [config (get report-configs report-type)
        report-title (:title config)
        chart-type (:chart-type config)
        columns (:columns config)
        marketplace-param (if (and marketplace (not= marketplace "all"))
                            (str "&marketplace=" marketplace)
                            "")
        article-param (when (seq article) (str "&article=" (java.net.URLEncoder/encode article "UTF-8")))
        api-url (str "/api/report/" (name report-type) "?period=" period marketplace-param (or article-param ""))
        chart-api-url (str "/api/chart/report?type=" (name report-type) "&period=" period marketplace-param)]

    [:div
     ;; Page header with title
     [:div.mb-6
      [:h2.text-2xl.font-bold.text-gray-900 report-title]]

     ;; No data banner (show if requested)
     (when show-no-data
       (no-data-banner))

     ;; Filters and export buttons
     [:div.bg-white.rounded-lg.shadow.p-4.mb-6
      [:div.flex.items-center.justify-between.flex-wrap.gap-4
       ;; Left side: filters
       [:div.flex.items-center.gap-4.flex-wrap
        (period-filter report-type period marketplace)
        (marketplace-filter report-type period marketplace)
        (when (= report-type :ue)
          (article-filter report-type article))]

       ;; Right side: export buttons
       (export-buttons report-type period marketplace)]]
     
     ;; Report content container (for HTMX updates)
     [:div#report-content
      ;; Chart visualization
      [:div.mb-6
       (c/chart-container {:id (str (name report-type) "-chart")
                           :type chart-type
                           :title (str "Визуализация: " report-title)
                           :api-url chart-api-url
                           :height 400})]
      
      ;; Tabulator table
      (c/tabulator-table {:id (str (name report-type) "-table")
                          :api-url api-url
                          :columns columns
                          :frozen-cols 1
                          :page-size 50})]]))
