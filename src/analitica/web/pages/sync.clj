(ns analitica.web.pages.sync
  (:require [hiccup.core :refer [html]]
            [analitica.web.components :as c]
            [analitica.web.api.metrics :as metrics-api]
            [jsonista.core :as json]))

;; ---------------------------------------------------------------------------
;; Sync Buttons
;; ---------------------------------------------------------------------------

(defn- sync-controls
  "Render marketplace selector, period selector and sync buttons."
  []
  [:div.bg-white.rounded-lg.shadow.p-6.mb-6
   [:h3.text-lg.font-semibold.text-gray-900.mb-4 "Управление синхронизацией"]

   ;; Selectors row
   [:div.flex.flex-wrap.items-center.gap-4.mb-4
    [:div.flex.items-center.gap-2
     [:label.text-sm.font-medium.text-gray-700 "Маркетплейс:"]
     [:select#sync-marketplace.border.border-gray-300.rounded-md.px-3.py-1.5.text-sm.focus:outline-none.focus:ring-2.focus:ring-blue-500
      [:option {:value "wb"}   "Wildberries"]
      [:option {:value "ozon"} "Ozon"]
      [:option {:value "ym"}   "Яндекс Маркет"]]]
    [:div.flex.items-center.gap-2
     [:label.text-sm.font-medium.text-gray-700 "Период:"]
     [:select#sync-period.border.border-gray-300.rounded-md.px-3.py-1.5.text-sm.focus:outline-none.focus:ring-2.focus:ring-blue-500
      [:option {:value "last-30-days"} "30 дней"]
      [:option {:value "last-7-days"}  "7 дней"]
      [:option {:value "this-month"}   "Этот месяц"]
      [:option {:value "last-week"}    "Прошлая неделя"]]]]

   ;; Buttons
   [:div.flex.flex-wrap
    (for [[label what] [["Sync All"  "all"]
                        ["Sales"     "sales"]
                        ["Orders"    "orders"]
                        ["Finance"   "finance"]
                        ["Storage"   "storage"]
                        ["Stocks"    "stocks"]
                        ["Stats"     "stats"]
                        ["Prices"    "prices"]
                        ["Regions"   "regions"]
                        ["1C"        "1c"]]]
      [:button.px-4.py-2.bg-blue-600.text-white.rounded-md.hover:bg-blue-700.transition-colors.text-sm.font-medium.mr-2.mb-2
       {:hx-post    "/api/sync/start"
        :hx-include "#sync-marketplace, #sync-period"
        :hx-vals    (json/write-value-as-string {:what what})
        :hx-swap    "none"
        "hx-on:htmx:responseError"
        "if(event.detail.xhr.status===409){alert('Синхронизация уже запущена.');}"}
       label])]])

;; ---------------------------------------------------------------------------
;; Last Sync Status Table
;; ---------------------------------------------------------------------------

(defn- last-sync-status
  "Render table showing last sync status for each data type."
  []
  [:div.bg-white.rounded-lg.shadow.p-6.mb-6
   [:h3.text-lg.font-semibold.text-gray-900.mb-4 "Статус последней синхронизации"]
   [:table.min-w-full.divide-y.divide-gray-200
    [:thead.bg-gray-50
     [:tr
      [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Тип данных"]
      [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Последняя синхронизация"]
      [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Время выполнения"]
      [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Статус"]]]
    [:tbody.bg-white.divide-y.divide-gray-200
     [:tr
      [:td.px-6.py-4.whitespace-nowrap.text-sm.font-medium.text-gray-900 "Sales"]
      [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-500 "—"]
      [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-500 "—"]
      [:td.px-6.py-4.whitespace-nowrap
       [:span.px-2.inline-flex.text-xs.leading-5.font-semibold.rounded-full.bg-gray-100.text-gray-800 "Не запущено"]]]
     [:tr
      [:td.px-6.py-4.whitespace-nowrap.text-sm.font-medium.text-gray-900 "Finance"]
      [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-500 "—"]
      [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-500 "—"]
      [:td.px-6.py-4.whitespace-nowrap
       [:span.px-2.inline-flex.text-xs.leading-5.font-semibold.rounded-full.bg-gray-100.text-gray-800 "Не запущено"]]]
     [:tr
      [:td.px-6.py-4.whitespace-nowrap.text-sm.font-medium.text-gray-900 "Stocks"]
      [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-500 "—"]
      [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-500 "—"]
      [:td.px-6.py-4.whitespace-nowrap
       [:span.px-2.inline-flex.text-xs.leading-5.font-semibold.rounded-full.bg-gray-100.text-gray-800 "Не запущено"]]]]]])

;; ---------------------------------------------------------------------------
;; Data Coverage Heatmap
;; ---------------------------------------------------------------------------

(defn render-coverage-bars
  "Render coverage bars for all data types and marketplaces."
  [coverage-data]
  (when coverage-data
    [:div
     ;; WB Coverage
     (when-let [wb-data (:wb coverage-data)]
       [:div.mb-6
        [:h4.text-md.font-semibold.text-gray-800.mb-3 "Wildberries"]
        (when-let [sales (:sales wb-data)]
          (c/data-coverage-bar {:label "Продажи"
                                :filled-days (:days sales)
                                :total-days (:total-days sales)
                                :date-from (:from sales)
                                :date-to (:to sales)}))
        (when-let [orders (:orders wb-data)]
          (c/data-coverage-bar {:label "Заказы"
                                :filled-days (:days orders)
                                :total-days (:total-days orders)
                                :date-from (:from orders)
                                :date-to (:to orders)}))
        (when-let [finance (:finance wb-data)]
          (c/data-coverage-bar {:label "Финансы"
                                :filled-days (:days finance)
                                :total-days (:total-days finance)
                                :date-from (:from finance)
                                :date-to (:to finance)}))
        (when-let [storage (:storage wb-data)]
          (c/data-coverage-bar {:label "Хранение"
                                :filled-days (:days storage)
                                :total-days (:total-days storage)
                                :date-from (:from storage)
                                :date-to (:to storage)}))
        (when-let [stocks (:stocks wb-data)]
          (c/data-coverage-bar {:label "Остатки"
                                :filled-days (:days stocks)
                                :total-days (:total-days stocks)
                                :date-from (:from stocks)
                                :date-to (:to stocks)}))])
     
     ;; Ozon Coverage
     (when-let [ozon-data (:ozon coverage-data)]
       [:div.mb-6
        [:h4.text-md.font-semibold.text-gray-800.mb-3 "Ozon"]
        (when-let [sales (:sales ozon-data)]
          (c/data-coverage-bar {:label "Продажи"
                                :filled-days (:days sales)
                                :total-days (:total-days sales)
                                :date-from (:from sales)
                                :date-to (:to sales)}))
        (when-let [orders (:orders ozon-data)]
          (c/data-coverage-bar {:label "Заказы"
                                :filled-days (:days orders)
                                :total-days (:total-days orders)
                                :date-from (:from orders)
                                :date-to (:to orders)}))
        (when-let [finance (:finance ozon-data)]
          (c/data-coverage-bar {:label "Финансы"
                                :filled-days (:days finance)
                                :total-days (:total-days finance)
                                :date-from (:from finance)
                                :date-to (:to finance)}))
        (when-let [storage (:storage ozon-data)]
          (c/data-coverage-bar {:label "Хранение"
                                :filled-days (:days storage)
                                :total-days (:total-days storage)
                                :date-from (:from storage)
                                :date-to (:to storage)}))
        (when-let [stocks (:stocks ozon-data)]
          (c/data-coverage-bar {:label "Остатки"
                                :filled-days (:days stocks)
                                :total-days (:total-days stocks)
                                :date-from (:from stocks)
                                :date-to (:to stocks)}))])
     
     ;; YM Coverage
     (when-let [ym-data (:ym coverage-data)]
       [:div.mb-6
        [:h4.text-md.font-semibold.text-gray-800.mb-3 "Yandex Market"]
        (when-let [sales (:sales ym-data)]
          (c/data-coverage-bar {:label "Продажи"
                                :filled-days (:days sales)
                                :total-days (:total-days sales)
                                :date-from (:from sales)
                                :date-to (:to sales)}))
        (when-let [orders (:orders ym-data)]
          (c/data-coverage-bar {:label "Заказы"
                                :filled-days (:days orders)
                                :total-days (:total-days orders)
                                :date-from (:from orders)
                                :date-to (:to orders)}))
        (when-let [finance (:finance ym-data)]
          (c/data-coverage-bar {:label "Финансы"
                                :filled-days (:days finance)
                                :total-days (:total-days finance)
                                :date-from (:from finance)
                                :date-to (:to finance)}))
        (when-let [storage (:storage ym-data)]
          (c/data-coverage-bar {:label "Хранение"
                                :filled-days (:days storage)
                                :total-days (:total-days storage)
                                :date-from (:from storage)
                                :date-to (:to storage)}))
        (when-let [stocks (:stocks ym-data)]
          (c/data-coverage-bar {:label "Остатки"
                                :filled-days (:days stocks)
                                :total-days (:total-days stocks)
                                :date-from (:from stocks)
                                :date-to (:to stocks)}))])
     
     ;; Non-marketplace data
     [:div.mb-6
      [:h4.text-md.font-semibold.text-gray-800.mb-3 "Общие данные"]
      (when-let [stats (:stats coverage-data)]
        (c/data-coverage-bar {:label "Статистика товаров"
                              :filled-days (:days stats)
                              :total-days (:total-days stats)
                              :date-from (:from stats)
                              :date-to (:to stats)}))
      (when-let [regions (:regions coverage-data)]
        (c/data-coverage-bar {:label "Продажи по регионам"
                              :filled-days (:days regions)
                              :total-days (:total-days regions)
                              :date-from (:from regions)
                              :date-to (:to regions)}))
      (when-let [cost-prices (:1c coverage-data)]
        (c/data-coverage-bar {:label "Себестоимость (1C)"
                              :filled-days (:days cost-prices)
                              :total-days (:total-days cost-prices)
                              :date-from (:from cost-prices)
                              :date-to (:to cost-prices)}))
      (when-let [prices (:prices coverage-data)]
        (c/data-coverage-bar {:label "Цены"
                              :filled-days (:days prices)
                              :total-days (:total-days prices)
                              :date-from (:from prices)
                              :date-to (:to prices)}))]]))

(defn- data-coverage-section
  "Render data coverage heatmap section."
  []
  [:div.bg-white.rounded-lg.shadow.p-6.mb-6
   [:h3.text-lg.font-semibold.text-gray-900.mb-4 "Покрытие данных"]
   [:div {:hx-get "/api/sync/coverage"
          :hx-trigger "load"
          :hx-swap "innerHTML"}
    [:div.text-sm.text-gray-500 "Загрузка данных покрытия..."]]])

;; ---------------------------------------------------------------------------
;; Main Sync Page
;; ---------------------------------------------------------------------------

(defn sync-page
  "Render the sync management page with buttons, progress log, coverage, and status table.

  Requirements: 6.1, 6.4, 6.5"
  []
  [:div
   ;; Sync control buttons
   (sync-controls)
   
   ;; Progress log with SSE
   (c/sync-log {:id "sync-log"
                :stream-url "/api/sync/stream"
                :height "400px"})
   
   ;; Data coverage heatmap (per-day × per-MP × per-type)
   (c/sync-heatmap {:id "sync-coverage-heatmap"
                    :api-url "/api/sync/coverage-days"})

   ;; 1C CSV cost-prices upload
   [:div.mt-6
    (c/cost-prices-upload)]

   ;; Last sync status table
   (last-sync-status)])

