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
        avg? (= :pct fmt)
        canon (:canon-anchor c)
        title (if canon
                (str (:title c)
                     " <span title='Canon: " canon "' "
                     "style='font-size:9px;color:#9ca3af;cursor:help;'>ⓘ</span>")
                (:title c))]
    (cond-> (assoc c "headerFilter" true
                     "headerFilterPlaceholder" "Фильтр..."
                     "title" title)
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
    - :id — Table container ID (string, required)
    - :api-url — API endpoint for table data (string, required)
    - :columns — Flat column definitions (vector of maps). Mutually exclusive with :grouped-columns.
    - :grouped-columns — Nested column groups. Each: {:title :columns}. Inner columns enriched same as flat.
    - :frozen-cols — Number of columns to freeze (number, optional, default: 1; ignored for grouped)
    - :page-size — Rows per page (number, optional, default: 50)
    - :column-presets — Map of preset-key → preset-value (optional). Serialised to window['<id>_presets'].
    - :default-visible-fields — Vector of field name strings visible in the :full/:all-default-visible preset.

  Column definition map: {:title :field :format (:rub|:int|:pct|:text|:date) :width}
  Numeric formats (:rub/:int/:pct) get a bottomCalc footer row."
  [{:keys [id api-url columns grouped-columns frozen-cols page-size
           column-presets default-visible-fields on-row-click-js]
    :or {frozen-cols 1 page-size 50}}]
  (let [final-columns
        (if grouped-columns
          (mapv (fn [g] {:title (:title g)
                         :columns (mapv enrich-column (:columns g))})
                grouped-columns)
          (mapv enrich-column columns))
        freezing-enabled? (not grouped-columns)]
    [:div.bg-white.rounded-lg.shadow.p-6
     [:div {:id id}]
     [:script {:type "text/javascript"}
      (str "
        (function() {
          const columns = " (json/write-value-as-string final-columns) ";
          window['" id "_presets'] = " (json/write-value-as-string (or column-presets {})) ";
          window['" id "_defaultVisible'] = " (json/write-value-as-string (or default-visible-fields [])) ";

          " (when freezing-enabled?
              (str "for (let i = 0; i < " frozen-cols " && i < columns.length; i++) {
                      columns[i].frozen = true;
                    }")) "

          fetch('" api-url "')
            .then(res => res.json())
            .then(data => {
              const rows = Array.isArray(data) ? data : (data.rows || []);
              const t = new Tabulator('#" id "', {
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
              window['" id "_tabulator'] = t;
              " (when on-row-click-js
                  (str "t.on('rowClick', " on-row-click-js ");")) "
            })
            .catch(err => console.error('Table load error:', err));
        })();
      ")]]))

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
;; Preset Chips Component
;; ---------------------------------------------------------------------------

(defn preset-chips
  "Row of chip buttons representing column presets for a report table.

   Parameters:
   - :presets  — map {preset-key preset-value}. Values may be vectors of column keys
                 or the keyword :all-default-visible. Keys determine chip order via
                 insertion order of the map.
   - :active   — preset-key of the chip rendered with preset-chip-active class.
   - :table-id — id of the Tabulator container the preset applies to; passed to
                 window.applyPreset on click.
   - :labels   — optional map {preset-key label-string}. Missing keys fall back to name."
  [{:keys [presets active table-id labels]}]
  [:div.flex.gap-2.flex-wrap.items-center.mb-3
   [:span.text-xs.text-gray-600 "Колонки:"]
   (for [[k _] presets]
     (let [is-active? (= k active)
           cls (str "px-3 py-1 text-xs rounded-full border cursor-pointer "
                    (if is-active?
                      "preset-chip-active bg-blue-600 text-white border-blue-600"
                      "bg-white text-gray-700 border-gray-300 hover:bg-gray-50"))]
       [:button {:class cls
                 :data-preset (name k)
                 :data-table-id table-id
                 :onclick (str "window.applyPreset('" table-id "', '" (name k) "')")}
        (get labels k (name k))]))])

;; ---------------------------------------------------------------------------
;; Column Chooser Component
;; ---------------------------------------------------------------------------

(defn column-chooser
  "⚙ popover button with checkboxes to toggle column visibility.

  :columns  — seq of schema column maps (each has :key :title :default-visible?)
  :table-id — Tabulator container id that toggleColumn applies to"
  [{:keys [columns table-id]}]
  [:details.relative.inline-block.ml-2
   [:summary.inline-flex.items-center.gap-1.px-3.py-1.border.border-gray-300.rounded.text-xs.cursor-pointer.bg-white.hover:bg-gray-50
    "⚙ Колонки"]
   [:div.absolute.right-0.mt-1.w-56.bg-white.border.border-gray-200.rounded-lg.shadow-lg.p-3.z-10
    [:div.text-xs.font-semibold.text-gray-600.mb-2 "Видимые колонки"]
    (for [c columns]
      [:label.flex.items-center.gap-2.py-1.text-xs.cursor-pointer
       [:input (cond-> {:type "checkbox"
                        :data-table-id table-id
                        :data-col-key (name (:key c))
                        :onchange (str "window.toggleColumn('" table-id "', '" (name (:key c)) "', this.checked)")}
                 (:default-visible? c) (assoc :checked true))]
       [:span (:title c)]])]])

;; ---------------------------------------------------------------------------
;; Data Coverage Bar Component
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Drill-down Side Panel Component
;; ---------------------------------------------------------------------------

(defn drill-panel
  "Empty shell for per-article drill-down side panel. Content loaded by JS via fetch on row click."
  [_]
  [:div#drill-panel.fixed.top-0.right-0.h-full.w-96.bg-white.border-l.border-gray-300.shadow-2xl.transform.translate-x-full.transition-transform.duration-200.z-40.overflow-y-auto.p-4
   {:style "display:none;"}
   [:div.flex.justify-between.items-center.mb-4
    [:h3#drill-panel-title.text-lg.font-bold.text-gray-900 "—"]
    [:button.text-gray-500.hover:text-gray-700.text-xl.close.cursor-pointer
     {:onclick "window.closeDrillPanel()"}
     "×"]]
   [:div#drill-panel-content
    [:div.text-sm.text-gray-500 "Загрузка…"]]])

;; ---------------------------------------------------------------------------
;; Period Picker (global header)
;; ---------------------------------------------------------------------------

(defn- fmt-date-ru
  "\"2026-04-01\" → \"01.04.2026\". Returns nil for nil input."
  [iso]
  (when (seq iso)
    (let [[y m d] (str/split iso #"-")]
      (str d "." m "." y))))

(defn period-picker
  "Chip trigger + popover shell for the global period selector.

   :from / :to           — ISO date strings (YYYY-MM-DD)
   :compare              — :none | :prev
   :supports-compare?    — default true; when false, compare toggle is hidden

   All interactivity (calendar, preset buttons, apply, compare-toggle) is wired
   by resources/public/js/period-picker.js. This fn emits only the shell."
  [{:keys [from to compare supports-compare?]
    :or {compare :none supports-compare? true}}]
  [:div#period-picker.relative.inline-block
   ;; Chip trigger
   [:button#period-picker-trigger.flex.items-center.gap-2.bg-white.border.border-gray-300.px-3.py-1.5.rounded.text-sm.hover:bg-gray-50
    {:onclick "window.togglePeriodPicker()"}
    [:span "📅"]
    [:span.font-semibold (str (fmt-date-ru from) " — " (fmt-date-ru to))]
    (when (= compare :prev)
      [:span.text-xs.text-gray-500 "↻ vs пред."])
    [:span.text-gray-400 "▾"]]

   ;; Popover (hidden by default; JS toggles display)
   [:div#period-picker-popover.absolute.right-0.mt-1.bg-white.border.border-gray-200.rounded-lg.shadow-xl.p-4.z-50
    {:style "width: 520px; display: none;"}
    [:div {:style "display: grid; grid-template-columns: 170px 1fr; gap: 12px;"}
     ;; Preset list (5 options)
     [:div.flex.flex-col.gap-1
      [:button.preset-option.px-3.py-2.text-sm.text-left.rounded.hover:bg-blue-50.cursor-pointer
       {:data-preset "last-7-days"} "7 дней"]
      [:button.preset-option.px-3.py-2.text-sm.text-left.rounded.hover:bg-blue-50.cursor-pointer
       {:data-preset "last-30-days"} "30 дней"]
      [:button.preset-option.px-3.py-2.text-sm.text-left.rounded.hover:bg-blue-50.cursor-pointer
       {:data-preset "this-month"} "Этот месяц"]
      [:button.preset-option.px-3.py-2.text-sm.text-left.rounded.hover:bg-blue-50.cursor-pointer
       {:data-preset "prev-month"} "Пред. месяц"]
      [:button.preset-option.px-3.py-2.text-sm.text-left.rounded.hover:bg-blue-50.cursor-pointer
       {:data-preset "custom"} "Custom…"]]

     ;; Calendar container (JS renders the grid inside)
     [:div#period-picker-calendar
      [:div.text-xs.text-gray-500 "Загрузка календаря…"]]]

    (when supports-compare?
      [:div.border-t.border-gray-200.mt-3.pt-3.flex.items-center.gap-2
       [:label.text-sm {:for "compare-toggle"} "Сравнить с:"]
       [:input#compare-toggle {:type "checkbox" :checked (= compare :prev)}]
       [:select#compare-mode.text-xs.px-2.py-1.border.rounded
        [:option {:value "prev"} "Пред. периодом"]]])

    [:div.border-t.border-gray-200.mt-3.pt-3.flex.justify-between.items-center
     [:span#period-picker-summary.text-xs.text-gray-600 "—"]
     [:div.flex.gap-2
      [:button.px-3.py-1.text-xs.border.border-gray-300.rounded
       {:onclick "window.closePeriodPicker()"} "Отмена"]
      [:button#period-picker-apply.px-3.py-1.text-xs.bg-blue-600.text-white.rounded
       "Применить"]]]]])

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

;; ---------------------------------------------------------------------------
;; Sync Heatmap Component
;; ---------------------------------------------------------------------------

(defn sync-heatmap
  "Heatmap of data coverage across MP × type × day.

  :id — container div id
  :api-url — endpoint returning {mp-kw {type-kw {:days [iso-strs]}}}

  Rendered as a table: rows = (mp, type) pairs, columns = days in a
  90-day window ending today. Each cell green if the day is in :days,
  gray otherwise."
  [{:keys [id api-url]}]
  [:div.bg-white.rounded-lg.shadow.p-6
   [:h3.text-lg.font-semibold.text-gray-900.mb-4 "Покрытие данных (90 дней)"]
   [:div {:id id} [:div.text-sm.text-gray-500 "Загрузка…"]]
   [:script (str "
     (function() {
       const WINDOW_DAYS = 90;
       const container = document.getElementById('" id "');
       fetch('" api-url "')
         .then(r => r.json())
         .then(data => {
           const today = new Date();
           const days = [];
           for (let i = WINDOW_DAYS - 1; i >= 0; i--) {
             const d = new Date(today);
             d.setDate(today.getDate() - i);
             days.push(d.toISOString().slice(0, 10));
           }
           const rows = [];
           const mpNames = {wb: 'WB', ozon: 'Ozon', ym: 'YM'};
           const typeNames = {finance: 'Finance', orders: 'Orders', sales: 'Sales', storage: 'Storage', stocks: 'Stocks'};
           for (const mpKey of ['wb', 'ozon', 'ym']) {
             const mp = data[mpKey] || {};
             for (const typeKey of Object.keys(mp)) {
               const set = new Set(mp[typeKey].days || []);
               const label = mpNames[mpKey] + ' / ' + (typeNames[typeKey] || typeKey);
               const total = set.size;
               const cells = days.map(d => {
                 const has = set.has(d);
                 return '<td title=\"' + label + ' · ' + d + (has ? ' ✓' : ' ✗') + '\" class=\"' +
                   (has ? 'bg-green-500' : 'bg-gray-200') + '\" style=\"width:8px;height:18px;border:0\"></td>';
               }).join('');
               rows.push('<tr><td class=\"pr-3 py-1 text-xs text-gray-600 whitespace-nowrap\">' + label +
                         '</td><td class=\"pr-3 text-xs text-gray-400\">' + total + ' дн.</td>' +
                         cells + '</tr>');
             }
           }
           container.innerHTML = '<div class=\"overflow-x-auto\"><table style=\"border-collapse:separate;border-spacing:1px\">' +
                                  '<tbody>' + rows.join('') + '</tbody></table>' +
                                  '<div class=\"text-xs text-gray-500 mt-2 flex gap-3\">' +
                                  '<span><span class=\"inline-block w-3 h-3 bg-green-500 align-middle\"></span> данные есть</span>' +
                                  '<span><span class=\"inline-block w-3 h-3 bg-gray-200 align-middle\"></span> нет</span>' +
                                  '</div></div>';
         })
         .catch(err => { container.innerHTML = '<div class=\"text-sm text-red-600\">Ошибка: ' + err + '</div>'; });
     })();
   ")]])

;; ---------------------------------------------------------------------------
;; Cost Prices CSV Upload Component
;; ---------------------------------------------------------------------------

(defn cost-prices-upload
  "Drag-drop zone + hidden file input + progress/result area for 1C CSV upload.
   POSTs multipart to /api/cost-prices/upload, shows JSON response inline."
  []
  [:div.bg-white.rounded-lg.shadow.p-6
   [:h3.text-lg.font-semibold.text-gray-900.mb-2 "Себестоимость из 1С (CSV)"]
   [:p.text-sm.text-gray-600.mb-4 "Загрузите units.csv — обязательные колонки: article, cost_price"]
   [:label#cost-prices-dropzone.block.border-2.border-dashed.border-gray-300.rounded-lg.p-6.text-center.cursor-pointer.hover:border-blue-500.hover:bg-blue-50
    [:input#cost-prices-file {:type "file" :accept ".csv" :class "hidden"}]
    [:div.text-gray-500 "📁 Перетащите CSV или кликните для выбора"]]
   [:div#cost-prices-result.mt-3.text-sm]
   [:div.mt-4.text-xs.text-gray-500
    [:a {:href "/api/cost-prices/imports" :target "_blank"} "📜 История импортов (JSON)"]]
   [:script "
     (function() {
       const input = document.getElementById('cost-prices-file');
       const dropzone = document.getElementById('cost-prices-dropzone');
       const result = document.getElementById('cost-prices-result');

       async function upload(file) {
         result.innerHTML = '<div class=\"text-gray-500\">⏳ Загрузка ' + file.name + '…</div>';
         const fd = new FormData();
         fd.append('file', file);
         try {
           const r = await fetch('/api/cost-prices/upload', { method: 'POST', body: fd });
           const d = await r.json();
           if (r.ok) {
             const rows = d.rows || d.inserted || d.loaded || 0;
             const warns = (d.warnings && d.warnings.length) || 0;
             result.innerHTML = '<div class=\"text-green-700\">✓ Загружено строк: <b>' + rows + '</b>' +
                                (warns ? ', предупреждений: ' + warns : '') + '</div>' +
                                '<pre class=\"mt-2 text-xs bg-gray-50 border border-gray-200 rounded p-2 overflow-auto\">' +
                                JSON.stringify(d, null, 2) + '</pre>';
           } else {
             result.innerHTML = '<div class=\"text-red-700\">✗ Ошибка ' + r.status + ': ' + (d.error || JSON.stringify(d)) + '</div>';
           }
         } catch (e) {
           result.innerHTML = '<div class=\"text-red-700\">✗ ' + e.message + '</div>';
         }
       }

       input.addEventListener('change', e => { if (e.target.files[0]) upload(e.target.files[0]); });
       ['dragenter','dragover'].forEach(evt => dropzone.addEventListener(evt, e => {
         e.preventDefault(); e.stopPropagation();
         dropzone.classList.add('border-blue-500', 'bg-blue-50');
       }));
       ['dragleave','drop'].forEach(evt => dropzone.addEventListener(evt, e => {
         e.preventDefault(); e.stopPropagation();
         dropzone.classList.remove('border-blue-500', 'bg-blue-50');
       }));
       dropzone.addEventListener('drop', e => {
         if (e.dataTransfer.files[0]) upload(e.dataTransfer.files[0]);
       });
     })();
   "]])

