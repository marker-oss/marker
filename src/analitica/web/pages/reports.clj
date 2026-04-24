(ns analitica.web.pages.reports
  (:require [hiccup.core :refer [html]]
            [analitica.web.components :as c]
            [analitica.web.report-schemas :as rs]))

;; ---------------------------------------------------------------------------
;; Schema Column Helpers
;; ---------------------------------------------------------------------------

(defn- columns-from-schema
  "Convert schema columns to grouped Tabulator columns.
   Groups use schema :column-groups; columns retain :default-visible? filter.
   Returns a vector of {:title :columns [...]}, ordered by :column-groups insertion order.
   Falls back to flat column list if :column-groups not defined."
  [schema]
  (let [groups (:column-groups schema)
        visible-cols (filter :default-visible? (:columns schema))]
    (if (seq groups)
      (let [grouped (group-by :group visible-cols)]
        (->> (keys groups)
             (filter #(seq (grouped %)))
             (mapv (fn [g-key]
                     {:title (get-in groups [g-key :title])
                      :columns (mapv (fn [c] {:title (:title c)
                                              :field (name (:key c))
                                              :format (:format c)
                                              :canon-anchor (:canon-anchor c)
                                              :width (case (:format c)
                                                       :rub 130 :int 100 :pct 100
                                                       :text 150 :date 120 120)})
                                     (grouped g-key))}))))
      ;; flat fallback: wrap everything in a single pseudo-group
      (mapv (fn [c] {:title (:title c)
                     :field (name (:key c))
                     :format (:format c)
                     :canon-anchor (:canon-anchor c)
                     :width (case (:format c)
                              :rub 130 :int 100 :pct 100
                              :text 150 :date 120 120)})
            visible-cols))))

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
  "Render unified report page from schema.
   Tabs are driven by schema :tabs; contents wrapped in data-tab-content divs.
   When :totals provided, KPI row renders above tabs, drawer tab populated.

   Parameters:
   - report-type: keyword (:sales, :finance, :ue, :pnl, :abc, :stock, :returns, :buyout, :geo, :trends)
   - period: period string (e.g. last-week, last-30-days)
   - marketplace: optional marketplace keyword or string
   - show-no-data: optional boolean to show no-data banner
   - article: optional article string for UE filtering
   - totals: optional map of metric key -> numeric value; enables KPI row + summary drawer

   Features:
   - Period and marketplace filters with HTMX updates
   - Excel and CSV export buttons
   - Chart.js visualization (in :chart tab)
   - Tabulator interactive table (in :table tab, when rows-mode != :none)
   - Summary drawer (in :drawer tab, when totals provided)
   - No data banner when data is missing
   - KPI row above tabs (when totals provided and kpi schema defined)
   - Tab switcher driven by schema :tabs key

   Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 8.1, 9.1, 14.1"
  [report-type period marketplace & {:keys [show-no-data article totals]}]
  (let [schema (rs/get-schema report-type)
        _ (when-not schema
            (throw (ex-info "Unknown report-type" {:type report-type})))
        report-title (:title schema)
        chart-type-kw (:type (:chart schema))
        chart-type (case chart-type-kw
                     :waterfall "bar"           ;; Chart.js has no waterfall; bar placeholder
                     :horizontalBar "bar"
                     (name chart-type-kw))
        grouped-cols (columns-from-schema schema)
        kpi-schema (:kpi schema)
        tabs (or (:tabs schema) [:chart])
        active-tab (first tabs)
        tab-set (set tabs)
        marketplace-param (if (and marketplace (not= marketplace "all"))
                            (str "&marketplace=" marketplace) "")
        article-param (when (seq article)
                        (str "&article=" (java.net.URLEncoder/encode article "UTF-8")))
        api-url (str "/api/report/" (name report-type) "?period=" period
                     marketplace-param (or article-param ""))
        chart-api-url (str "/api/chart/report?type=" (name report-type)
                           "&period=" period marketplace-param)]

    [:div
     [:div.mb-6
      [:h2.text-2xl.font-bold.text-gray-900 report-title]]

     (when show-no-data (no-data-banner))

     [:div.bg-white.rounded-lg.shadow.p-4.mb-6
      [:div.flex.items-center.justify-between.flex-wrap.gap-4
       [:div.flex.items-center.gap-4.flex-wrap
        (period-filter report-type period marketplace)
        (marketplace-filter report-type period marketplace)
        (when (= report-type :ue)
          (article-filter report-type article))]
       (export-buttons report-type period marketplace)]]

     ;; KPI row (above tabs)
     (when (and kpi-schema (seq totals))
       (c/kpi-row kpi-schema totals))

     ;; Tab switcher
     (c/tab-switcher {:tabs tabs
                      :active active-tab
                      :labels {:table "Таблица"
                               :chart "График"
                               :drawer (str "Все метрики" (when (seq totals)
                                                             (str " (" (count totals) ")")))}})

     ;; Tab content containers
     [:div#report-content.bg-white.rounded-b-lg.shadow.p-6
      (when (contains? tab-set :table)
        [:div {:data-tab-content "table"
               :style (when (not= active-tab :table) "display:none;")}
         (if (not= :none (:rows-mode schema))
           (c/tabulator-table {:id (str (name report-type) "-table")
                               :api-url api-url
                               :grouped-columns grouped-cols
                               :frozen-cols 1
                               :page-size 50})
           [:div.text-gray-500.text-sm "Нет табличных данных для этого отчёта"])])

      (when (contains? tab-set :chart)
        [:div {:data-tab-content "chart"
               :style (when (not= active-tab :chart) "display:none;")}
         (c/chart-container {:id (str (name report-type) "-chart")
                             :type chart-type
                             :title (str "Визуализация: " report-title)
                             :api-url chart-api-url
                             :height 400})])

      (when (contains? tab-set :drawer)
        [:div {:data-tab-content "drawer"
               :style (when (not= active-tab :drawer) "display:none;")}
         (if (seq totals)
           (c/summary-drawer {:totals totals :title "Все метрики периода"})
           [:div.text-gray-500.text-sm "Нет данных"])])]]))
