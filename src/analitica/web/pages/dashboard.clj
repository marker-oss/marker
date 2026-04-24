(ns analitica.web.pages.dashboard
  (:require [hiccup.core :refer [html]]
            [analitica.web.components :as c]))

;; ---------------------------------------------------------------------------
;; Helper Functions
;; ---------------------------------------------------------------------------

(defn- format-number
  "Format number with thousand separators."
  [n]
  (when n
    (let [s (str (long n))
          parts (reverse (partition-all 3 (reverse s)))]
      (clojure.string/join " " (map #(apply str %) parts)))))

(defn- format-currency
  "Format number as currency with ₽ symbol."
  [n]
  (when n
    (str (format-number n) " ₽")))

(defn- format-percent
  "Format number as percentage."
  [n]
  (when n
    (str (format "%.1f" n) "%")))

;; ---------------------------------------------------------------------------
;; Marketplace Comparison Table
;; ---------------------------------------------------------------------------

(defn marketplace-comparison-table
  "Render marketplace comparison table.
   
   Parameters:
   - by-marketplace: vector of maps with :marketplace, :revenue, :orders, :profit, :margin, :return-rate
   
   Requirements: 4.3"
  [by-marketplace]
  [:div.bg-white.rounded-lg.shadow.p-6
   [:h3.text-lg.font-semibold.text-gray-900.mb-4 "Сравнение маркетплейсов"]
   [:table.min-w-full.divide-y.divide-gray-200
    [:thead.bg-gray-50
     [:tr
      [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Маркетплейс"]
      [:th.px-6.py-3.text-right.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Выручка"]
      [:th.px-6.py-3.text-right.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Заказы"]
      [:th.px-6.py-3.text-right.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Прибыль"]
      [:th.px-6.py-3.text-right.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Маржа"]
      [:th.px-6.py-3.text-right.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Возвраты"]]]
    [:tbody.bg-white.divide-y.divide-gray-200
     (for [mp by-marketplace]
       [:tr
        [:td.px-6.py-4.whitespace-nowrap.text-sm.font-medium.text-gray-900
         (case (:marketplace mp)
           :wb "Wildberries"
           :ozon "Ozon"
           :ym "Yandex.Market"
           (name (:marketplace mp)))]
        [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-900.text-right
         (format-currency (:revenue mp))]
        [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-900.text-right
         (format-number (:orders mp))]
        [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-900.text-right
         (format-currency (:profit mp))]
        [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-900.text-right
         (format-percent (:margin mp))]
        [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-900.text-right
         (format-percent (:return-rate mp))]])]]])

;; ---------------------------------------------------------------------------
;; Top Products Table
;; ---------------------------------------------------------------------------

(defn top-products-table
  "Render top-10 products table with mini-bars.
   
   Parameters:
   - top-products: vector of maps with :article, :revenue, :orders
   
   Requirements: 5.2"
  [top-products]
  (let [max-revenue (when (seq top-products)
                      (apply max (map :revenue top-products)))]
    [:div.bg-white.rounded-lg.shadow.p-6
     [:h3.text-lg.font-semibold.text-gray-900.mb-4 "Топ-10 товаров"]
     [:table.min-w-full.divide-y.divide-gray-200
      [:thead.bg-gray-50
       [:tr
        [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Артикул"]
        [:th.px-6.py-3.text-right.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Выручка"]
        [:th.px-6.py-3.text-right.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Заказы"]
        [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider ""]]]
      [:tbody.bg-white.divide-y.divide-gray-200
       (for [product top-products]
         (let [bar-width (if (and max-revenue (pos? max-revenue))
                           (* 100.0 (/ (:revenue product) max-revenue))
                           0)]
           [:tr
            [:td.px-6.py-4.whitespace-nowrap.text-sm.font-medium.text-gray-900
             (:article product)]
            [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-900.text-right
             (format-currency (:revenue product))]
            [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-900.text-right
             (format-number (:orders product))]
            [:td.px-6.py-4.whitespace-nowrap
             [:div.w-full.bg-gray-200.rounded-full.h-2
              [:div.bg-blue-500.h-2.rounded-full
               {:style (str "width: " bar-width "%")}]]]]))]]]))

;; ---------------------------------------------------------------------------
;; Top Returns Table
;; ---------------------------------------------------------------------------

(defn top-returns-table
  "Render top returns table.
   
   Parameters:
   - top-returns: vector of maps with :article, :return-rate, :returned, :sold
   
   Requirements: 5.5"
  [top-returns]
  [:div.bg-white.rounded-lg.shadow.p-6
   [:h3.text-lg.font-semibold.text-gray-900.mb-4 "Топ возвратов"]
   [:table.min-w-full.divide-y.divide-gray-200
    [:thead.bg-gray-50
     [:tr
      [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Артикул"]
      [:th.px-6.py-3.text-right.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Возвращено"]
      [:th.px-6.py-3.text-right.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Продано"]
      [:th.px-6.py-3.text-right.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "% возврата"]]]
    [:tbody.bg-white.divide-y.divide-gray-200
     (for [item top-returns]
       [:tr
        [:td.px-6.py-4.whitespace-nowrap.text-sm.font-medium.text-gray-900
         (:article item)]
        [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-900.text-right
         (format-number (:returned item))]
        [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-900.text-right
         (format-number (:sold item))]
        [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-900.text-right
         (format-percent (:return-rate item))]])]]])

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
;; Summary Dashboard
;; ---------------------------------------------------------------------------

(defn summary-dashboard
  "Render summary dashboard page with metrics, charts, and marketplace comparison.
   
   Parameters:
   - period: period parameter (keyword or map)
   - metrics: optional metrics data (if provided, checks for no data)
   
   Displays:
   - 4 metric cards (Revenue, Orders, Profit, Return Rate) with WoW deltas
   - Marketplace comparison table
   - Sales dynamics chart (line chart by day)
   - Marketplace share chart (donut chart)
   - HTMX polling for auto-refresh every 5 minutes
   - No data banner when data is missing
   
   Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 14.1"
  [period & {:keys [metrics]}]
  (let [period-param (cond
                       (keyword? period) (name period)
                       (vector? period)  (str (first period) "," (second period))
                       :else             (str (:from period) "," (:to period)))
        has-no-data? (and metrics
                          (zero? (:revenue metrics))
                          (zero? (:orders metrics)))]
    [:div
     ;; No data banner (show if metrics indicate no data)
     (when has-no-data?
       (no-data-banner))

     ;; Metrics cards with HTMX polling
     [:div#metrics-container
      {:hx-get (str "/api/metrics?period=" period-param)
       :hx-trigger "load, every 5m"
       :hx-swap "innerHTML"}
      
      ;; Initial metrics cards (will be replaced by HTMX)
      [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-4.gap-6.mb-6
       (c/metric-card {:title "Выручка"
                       :value "—"
                       :unit "₽"
                       :delta nil
                       :delta-label "WoW"})
       (c/metric-card {:title "Заказы"
                       :value "—"
                       :unit ""
                       :delta nil
                       :delta-label "WoW"})
       (c/metric-card {:title "Прибыль"
                       :value "—"
                       :unit "₽"
                       :delta nil
                       :delta-label "WoW"})
       (c/metric-card {:title "Процент возвратов"
                       :value "—"
                       :unit "%"
                       :delta nil
                       :delta-label "WoW"})]
      ;; Marketplace comparison table (placeholder, replaced by HTMX)
      [:div.mb-6
       (marketplace-comparison-table [])]]

     ;; Charts
     [:div.grid.grid-cols-1.lg:grid-cols-2.gap-6
      ;; Sales dynamics chart
      (c/chart-container {:id "sales-chart"
                          :type "line"
                          :title "Динамика продаж"
                          :api-url (str "/api/chart/sales?period=" period-param)
                          :height 300})
      
      ;; Marketplace share chart
      (c/chart-container {:id "share-chart"
                          :type "doughnut"
                          :title "Доли маркетплейсов"
                          :api-url (str "/api/chart/share?period=" period-param)
                          :height 300})]

     ;; Losses quick-link card (WB only)
     [:div.mt-6
      [:a.block.bg-red-50.border.border-red-200.rounded-lg.p-4.hover:bg-red-100.transition-colors
       {:href (str "/reports/losses?period=" period-param)}
       [:div.flex.items-center.justify-between
        [:div
         [:div.text-sm.font-medium.text-red-700.mb-1 "Убытки (WB)"]
         [:div.text-xs.text-red-600 "SKU теряющих деньги — мёртвый сток, склад ест маржу, прогноз убытка"]]
        [:div.text-3xl "💀"]]]]]))

;; ---------------------------------------------------------------------------
;; Marketplace Dashboard
;; ---------------------------------------------------------------------------

(defn marketplace-dashboard
  "Render individual marketplace dashboard page.
   
   Parameters:
   - marketplace: keyword (:wb, :ozon, :ym)
   - period: period parameter (keyword or map)
   - metrics: optional metrics data (if provided, checks for no data)
   
   Displays:
   - 4 metric cards for the marketplace (Revenue, Orders, Profit, Return Rate) with WoW deltas
   - Top-10 products table with mini-bars
   - Stacked bar chart for financial breakdown (revenue, commission, logistics, storage, profit)
   - Bar chart for ABC distribution (count and revenue % for A, B, C)
   - Top returns table
   - No data banner when data is missing
   
   Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 14.1"
  [marketplace period & {:keys [metrics]}]
  (let [period-param (cond
                       (keyword? period) (name period)
                       (vector? period)  (str (first period) "," (second period))
                       :else             (str (:from period) "," (:to period)))
        mp-name (case marketplace
                  :wb "Wildberries"
                  :ozon "Ozon"
                  :ym "Yandex.Market"
                  (name marketplace))
        has-no-data? (and metrics 
                          (zero? (:revenue metrics))
                          (zero? (:orders metrics)))]
    [:div
     ;; No data banner (show if metrics indicate no data)
     (when has-no-data?
       (no-data-banner))
     
     ;; Metrics cards with HTMX polling
     [:div#metrics-container
      {:hx-get (str "/api/metrics/" (name marketplace) "?period=" period-param)
       :hx-trigger "load, every 5m"
       :hx-swap "innerHTML"}
      
      ;; Initial metrics cards (will be replaced by HTMX)
      [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-4.gap-6.mb-6
       (c/metric-card {:title "Выручка"
                       :value "—"
                       :unit "₽"
                       :delta nil
                       :delta-label "WoW"})
       (c/metric-card {:title "Заказы"
                       :value "—"
                       :unit ""
                       :delta nil
                       :delta-label "WoW"})
       (c/metric-card {:title "Прибыль"
                       :value "—"
                       :unit "₽"
                       :delta nil
                       :delta-label "WoW"})
       (c/metric-card {:title "Процент возвратов"
                       :value "—"
                       :unit "%"
                       :delta nil
                       :delta-label "WoW"})]]
     
     ;; Top-10 products table
     [:div.mb-6
      (top-products-table [])]
     
     ;; Charts
     [:div.grid.grid-cols-1.lg:grid-cols-2.gap-6.mb-6
      ;; Financial breakdown chart (stacked bar)
      (c/chart-container {:id "finance-chart"
                          :type "bar"
                          :title "Финансовая разбивка"
                          :api-url (str "/api/chart/finance-breakdown?marketplace=" (name marketplace) "&period=" period-param)
                          :height 300})
      
      ;; ABC distribution chart
      (c/chart-container {:id "abc-chart"
                          :type "bar"
                          :title "ABC-распределение"
                          :api-url (str "/api/chart/abc-distribution?marketplace=" (name marketplace) "&period=" period-param)
                          :height 300})]
     
     ;; Top returns table
     [:div
      (top-returns-table [])]]))

