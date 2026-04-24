(ns analitica.web.components
  (:require [clojure.string :as str]
            [hiccup.core :refer [html]]
            [jsonista.core :as json]))

;; ---------------------------------------------------------------------------
;; Metric Card Component
;; ---------------------------------------------------------------------------

(defn metric-card
  "Render a metric card with value and WoW delta.
  
  Parameters:
  - opts: Map with keys:
    - :title - Metric title (string)
    - :value - Metric value (number or string)
    - :unit - Unit suffix (string, optional)
    - :delta - WoW delta value (number, optional)
    - :delta-label - Delta label (string, optional, default: WoW)
  
  Example:
    (metric-card {:title \"Выручка\"
                  :value 1250000
                  :unit \"₽\"
                  :delta 12.5
                  :delta-label \"WoW\"})"
  [{:keys [title value unit delta delta-label]
    :or {delta-label "WoW"}}]
  [:div.bg-white.rounded-lg.shadow.p-6
   [:div.text-sm.font-medium.text-gray-600.mb-2 title]
   [:div.flex.items-baseline.justify-between
    [:div.text-3xl.font-bold.text-gray-900
     (str value (when unit (str " " unit)))]
    (when delta
      (let [is-positive? (>= delta 0)
            color-class (if is-positive? "text-green-600" "text-red-600")
            arrow (if is-positive? "↑" "↓")]
        [:div {:class (str "text-sm font-medium " color-class)}
         [:span arrow " " (Math/abs delta) "% " delta-label]]))]])

;; ---------------------------------------------------------------------------
;; Chart Container Component
;; ---------------------------------------------------------------------------

(defn chart-container
  "Render a container for Chart.js visualization.
  
  Parameters:
  - opts: Map with keys:
    - :id - Canvas element ID (string, required)
    - :type - Chart type (string, e.g. line, bar, doughnut)
    - :title - Chart title (string, optional)
    - :api-url - API endpoint for chart data (string, required)
    - :height - Canvas height in pixels (number, optional, default: 300)
  
  Example:
    (chart-container {:id \"sales-chart\"
                      :type \"line\"
                      :title \"Динамика продаж\"
                      :api-url \"/api/chart/sales\"})"
  [{:keys [id type title api-url height]
    :or {height 300}}]
  [:div.bg-white.rounded-lg.shadow.p-6
   (when title
     [:h3.text-lg.font-semibold.text-gray-900.mb-4 title])
   [:div {:style (str "height: " height "px;")}
    [:canvas {:id id}]]
   [:script {:type "text/javascript"}
    (str "
      (function() {
        fetch('" api-url "')
          .then(res => res.json())
          .then(data => {
            const ctx = document.getElementById('" id "').getContext('2d');
            new Chart(ctx, {
              type: '" type "',
              data: data,
              options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                  legend: {
                    display: true,
                    position: 'bottom'
                  }
                }
              }
            });
          })
          .catch(err => console.error('Chart load error:', err));
      })();
    ")]])

;; ---------------------------------------------------------------------------
;; Tabulator Table Component
;; ---------------------------------------------------------------------------

(defn- enrich-column [c]
  (let [fmt (:format c)
        sum? (contains? #{:rub :int} fmt)
        avg? (= :pct fmt)]
    (cond-> (assoc c "headerFilter" true
                     "headerFilterPlaceholder" "Фильтр...")
      sum? (assoc "bottomCalc" "sum"
                  "bottomCalcFormatter" "money"
                  "bottomCalcFormatterParams" {"thousand" " " "precision" 0})
      avg? (assoc "bottomCalc" "avg"
                  "bottomCalcFormatter" "money"
                  "bottomCalcFormatterParams" {"precision" 1 "symbol" "%" "symbolAfter" "p"}))))

(defn tabulator-table
  "Render a container for Tabulator interactive table.

  Parameters:
  - opts: Map with keys:
    - :id - Table container ID (string, required)
    - :api-url - API endpoint for table data (string, required)
    - :columns - Column definitions (vector of maps, required)
    - :frozen-cols - Number of columns to freeze (number, optional, default: 1)
    - :page-size - Rows per page (number, optional, default: 50)

  Column definition map:
    {:title \"Артикул\" :field \"article\" :width 150 :format :rub}

  Numeric formats (:rub, :int, :pct) get a bottomCalc sum footer row.

  Example:
    (tabulator-table {:id \"sales-table\"
                      :api-url \"/api/report/sales\"
                      :columns [{:title \"Артикул\" :field \"article\"}
                                {:title \"Выручка\" :field \"revenue\" :format :rub}]
                      :frozen-cols 1})"
  [{:keys [id api-url columns frozen-cols page-size]
    :or {frozen-cols 1 page-size 50}}]
  [:div.bg-white.rounded-lg.shadow.p-6
   [:div {:id id}]
   [:script {:type "text/javascript"}
    (str "
      (function() {
        const columns = " (json/write-value-as-string (mapv enrich-column columns)) ";

        // Freeze first N columns
        for (let i = 0; i < " frozen-cols " && i < columns.length; i++) {
          columns[i].frozen = true;
        }

        fetch('" api-url "')
          .then(res => res.json())
          .then(data => {
            const rows = Array.isArray(data) ? data : (data.rows || []);
            new Tabulator('#" id "', {
              data: rows,
              columns: columns,
              layout: 'fitDataStretch',
              pagination: true,
              paginationSize: " page-size ",
              paginationSizeSelector: [25, 50, 100, 200],
              movableColumns: true,
              resizableColumns: true,
              headerFilterLiveFilterDelay: 600,
              placeholder: 'Нет данных',
              columnCalcs: 'bottom',
              langs: {
                'ru': {
                  'pagination': {
                    'first': 'Первая',
                    'first_title': 'Первая страница',
                    'last': 'Последняя',
                    'last_title': 'Последняя страница',
                    'prev': 'Пред',
                    'prev_title': 'Предыдущая страница',
                    'next': 'След',
                    'next_title': 'Следующая страница',
                    'page_size': 'Размер страницы'
                  }
                }
              },
              locale: 'ru'
            });
          })
          .catch(err => console.error('Table load error:', err));
      })();
    ")]])

;; ---------------------------------------------------------------------------
;; Period Selector Component
;; ---------------------------------------------------------------------------

(defn period-selector
  "Render a period selector dropdown with HTMX integration.
  
  Parameters:
  - opts: Map with keys:
    - :current-period - Currently selected period (string, optional)
    - :target - HTMX target selector (string, optional, default: main-content)
  
  Example:
    (period-selector {:current-period \"last-week\"
                      :target \"#dashboard-content\"})"
  [{:keys [current-period target]
    :or {target "#main-content"}}]
  [:div.flex.items-center.gap-2
   [:label.text-sm.font-medium.text-gray-700 {:for "period-select"} "Период:"]
   [:select#period-select.px-3.py-1.5.border.border-gray-300.rounded-md.text-sm.focus:outline-none.focus:ring-2.focus:ring-blue-500
    {:name "period"
     :hx-get ""
     :hx-trigger "change"
     :hx-target target
     :hx-swap "innerHTML"}
    [:option {:value "last-week" :selected (= current-period "last-week")} 
     "Прошлая неделя"]
    [:option {:value "last-7-days" :selected (= current-period "last-7-days")} 
     "Последние 7 дней"]
    [:option {:value "last-30-days" :selected (= current-period "last-30-days")} 
     "Последние 30 дней"]
    [:option {:value "this-month" :selected (= current-period "this-month")} 
     "Этот месяц"]
    [:option {:value "custom" :selected (= current-period "custom")} 
     "Произвольный диапазон"]]])

;; ---------------------------------------------------------------------------
;; Sync Log Component
;; ---------------------------------------------------------------------------

(defn sync-log
  "Render a container for SSE-based sync progress log.
  
  Parameters:
  - opts: Map with keys:
    - :id - Container ID (string, optional, default: sync-log)
    - :stream-url - SSE stream endpoint (string, optional, default: /api/sync/stream)
    - :height - Container height (string, optional, default: 400px)
  
  Example:
    (sync-log {:id \"sync-progress\"
               :stream-url \"/api/sync/stream\"
               :height \"500px\"})"
  [{:keys [id stream-url height]
    :or {id "sync-log" stream-url "/api/sync/stream" height "400px"}}]
  [:div.bg-white.rounded-lg.shadow.p-6
   [:h3.text-lg.font-semibold.text-gray-900.mb-4 "Прогресс синхронизации"]
   [:div.bg-gray-900.text-green-400.font-mono.text-sm.p-4.rounded.overflow-y-auto
    {:id id
     :style (str "height: " height ";")
     :hx-ext "sse"
     :sse-connect stream-url
     :sse-swap "message"
     :hx-swap "beforeend"}
    [:div.text-gray-500 "Ожидание запуска синхронизации..."]]])

;; ---------------------------------------------------------------------------
;; KPI Card Components
;; ---------------------------------------------------------------------------

(defn- format-value [value fmt]
  (cond
    (nil? value) "—"
    (not (number? value)) (str value)
    (= fmt :rub) (str (str/replace (format "%,.0f" (double value)) "," " ") " ₽")
    (= fmt :pct) (str (format "%.1f" (double value)) "%")
    (= fmt :int) (str/replace (format "%,d" (long value)) "," " ")
    :else (str value)))

(defn kpi-card
  "KPI card with title, value and optional delta.

  Parameters:
  - opts: Map with keys:
    - :title - Metric title (string)
    - :value - Metric value (number)
    - :format - Display format: :rub | :pct | :int (default: :rub)
    - :delta - Delta vs prior period (number, optional)
    - :delta-unit - Delta unit suffix (string, optional, default: %)
    - :delta-direction - :normal (green=up) | :inverted (green=down, e.g. ДРР)

  Example:
    (kpi-card {:title \"Выручка\" :value 1830000 :format :rub :delta 12.4})"
  [{:keys [title value format delta delta-unit delta-direction]
    :or {format :rub delta-direction :normal}}]
  (let [fmt format   ; rename to avoid shadowing clojure.core/format below
        is-positive? (and delta (pos? delta))
        is-negative? (and delta (neg? delta))
        arrow (cond is-positive? "↑" is-negative? "↓" :else "")
        is-good? (case delta-direction
                   :inverted is-negative?
                   is-positive?)
        color-class (cond
                      (nil? delta) ""
                      is-good? "text-green-600"
                      :else "text-red-600")]
    [:div.bg-white.rounded-lg.shadow.p-4.border.border-gray-100
     [:div.text-xs.font-medium.text-gray-500.uppercase.tracking-wide title]
     [:div.text-2xl.font-bold.text-gray-900.mt-1 (format-value value fmt)]
     (when delta
       [:div {:class (str "text-xs mt-1 " color-class)}
        arrow " " (clojure.core/format "%+.1f" (double delta)) (or delta-unit "%") " vs пред."])]))

(defn- guess-format [k]
  (cond
    (str/ends-with? (name k) "-pct") :pct
    (str/ends-with? (name k) "-rate") :pct
    (#{:total-revenue :total-profit :total-for-pay :total-cost :total-ad-spend
       :total-wb-costs :total-logistics :total-storage :avg-check
       :profit-per-sale :net-profit :gross-profit} k) :rub
    :else :int))

(defn summary-drawer
  "Collapsible drawer listing every totals entry in a 3-column grid."
  [{:keys [totals title] :or {title "Все метрики"}}]
  (let [n (count totals)]
    [:details.bg-purple-50.border.border-purple-200.rounded-lg.mt-4
     [:summary.cursor-pointer.px-4.py-3.text-sm.font-semibold.text-purple-900
      (str "▾ " title " (" n ")")]
     [:div.px-4.pb-4.pt-2
      [:div.grid.grid-cols-2.md:grid-cols-3.gap-x-6.gap-y-1
       (for [[k v] (sort-by key totals)]
         [:div.flex.justify-between.text-xs.py-1.border-b.border-purple-100
          [:span.text-gray-600.font-mono (name k)]
          [:span.font-semibold.text-gray-900 (format-value v (guess-format k))]])]]]))

(defn kpi-row
  "Render row of KPI cards from schema :kpi and totals map.

  Parameters:
  - kpi-schema: seq of {:key :title :format :delta-from :delta-direction}
  - totals: map {<key> <numeric>}
  - compare-totals: optional map for delta calc (prior period)

  Example:
    (kpi-row [{:key :revenue :title \"Выручка\" :format :rub}] totals prev-totals)"
  [kpi-schema totals & [compare-totals]]
  [:div.grid.grid-cols-2.md:grid-cols-4.gap-4.mb-6
   (for [{:keys [key title format delta-from delta-direction]} kpi-schema]
     (let [value (get totals key)
           prev-value (when (and compare-totals delta-from)
                        (get compare-totals key))
           delta (when (and prev-value (number? value) (number? prev-value) (not (zero? prev-value)))
                   (* 100.0 (/ (- value prev-value) prev-value)))]
       (kpi-card {:title title :value value :format format
                  :delta delta :delta-direction delta-direction})))])

;; ---------------------------------------------------------------------------
;; Tab Switcher Component
;; ---------------------------------------------------------------------------

(defn tab-switcher
  "Tab bar. Activation via JS toggling data-tab-content divs.

  :tabs — vector of keywords identifying tabs
  :active — keyword of default-active tab
  :labels — map kw→label string
  :target-prefix — optional prefix for data-tab-content attr (default 'tab-content-')

  Emits a nav with buttons and a <script> that defines window.switchTab(prefix, tab)."
  [{:keys [tabs active labels target-prefix]
    :or {target-prefix "tab-content-"}}]
  [:div.border-b.border-gray-200.mb-0
   [:nav.-mb-px.flex.space-x-0
    (for [t tabs]
      (let [is-active? (= t active)
            classes (if is-active?
                      "tab-active border-blue-500 text-blue-600 font-semibold"
                      "border-transparent text-gray-500 hover:text-gray-700")]
        [:button.px-4.py-2.text-sm.border-b-2
         {:class classes
          :data-tab (name t)
          :onclick (str "window.switchTab('" target-prefix "', '" (name t) "')")}
         (get labels t (name t))]))]
   [:script "
     window.switchTab = function(prefix, tab) {
       document.querySelectorAll('[data-tab-content]').forEach(el => {
         el.style.display = el.dataset.tabContent === tab ? '' : 'none';
       });
       document.querySelectorAll('[data-tab]').forEach(el => {
         if (el.dataset.tab === tab) {
           el.classList.add('tab-active', 'border-blue-500', 'text-blue-600', 'font-semibold');
           el.classList.remove('border-transparent', 'text-gray-500');
         } else {
           el.classList.remove('tab-active', 'border-blue-500', 'text-blue-600', 'font-semibold');
           el.classList.add('border-transparent', 'text-gray-500');
         }
       });
     };
   "]])

;; ---------------------------------------------------------------------------
;; Data Coverage Bar Component
;; ---------------------------------------------------------------------------

(defn data-coverage-bar
  "Render a data coverage heatmap bar showing filled vs empty days.
  
  Parameters:
  - opts: Map with keys:
    - :label - Coverage label (string, required, e.g. WB Продажи)
    - :filled-days - Number of days with data (number, required)
    - :total-days - Total days in period (number, required)
    - :date-from - Start date (string, optional)
    - :date-to - End date (string, optional)
  
  Example:
    (data-coverage-bar {:label \"WB Продажи\"
                        :filled-days 25
                        :total-days 30
                        :date-from \"2026-04-01\"
                        :date-to \"2026-04-30\"})"
  [{:keys [label filled-days total-days date-from date-to]}]
  (let [coverage-pct (if (pos? total-days)
                       (* 100.0 (/ filled-days total-days))
                       0)
        color-class (cond
                      (>= coverage-pct 90) "bg-green-500"
                      (>= coverage-pct 70) "bg-yellow-500"
                      (>= coverage-pct 50) "bg-orange-500"
                      :else "bg-red-500")]
    [:div.mb-4
     [:div.flex.items-center.justify-between.mb-2
      [:div.text-sm.font-medium.text-gray-700 label]
      [:div.text-xs.text-gray-500
       (str filled-days "/" total-days " дней")
       (when (and date-from date-to)
         (str " (" date-from " — " date-to ")"))]]
     [:div.w-full.bg-gray-200.rounded-full.h-4.overflow-hidden
      [:div {:class (str "h-full transition-all duration-300 " color-class)
             :style (str "width: " coverage-pct "%")}]]
     [:div.text-xs.text-gray-600.mt-1
      (str (format "%.1f" coverage-pct) "% покрытие")]]))

