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
  "SSE-driven sync progress log with structured frontend rendering:
   stage counter, elapsed timer, color-coded lines, auto-scroll.

   Backend still streams plain text — the frontend parses well-known
   patterns ('=== Ingest: X ===', 'Ingested ... N items', 'ERROR ...')
   to render a header bar above the raw log. A full structured-events
   refactor across all ingest/materialize call sites is deferred."
  [{:keys [id stream-url height]
    :or {id "sync-log" stream-url "/api/sync/stream" height "400px"}}]
  [:div.bg-white.rounded-lg.shadow.p-6
   [:h3.text-lg.font-semibold.text-gray-900.mb-4 "Прогресс синхронизации"]
   ;; Status bar — populated by JS as SSE messages arrive
   [:div#sync-log-status.flex.flex-wrap.gap-4.items-center.text-sm.mb-2.text-gray-500
    [:span#sync-log-state.font-medium "—"]
    [:span#sync-log-stage "стадия: —"]
    [:span#sync-log-counter "—"]
    [:span#sync-log-elapsed.text-xs.font-mono "00:00"]]
   ;; Live log
   [:div.bg-gray-900.text-green-400.font-mono.text-sm.p-4.rounded.overflow-y-auto
    {:id id
     :style (str "height: " height ";")
     :hx-ext "sse"
     :sse-connect stream-url
     :sse-swap "message"
     :hx-swap "beforeend"}
    [:div.text-gray-500 "Ожидание запуска синхронизации..."]]
   [:script (str "
     (function() {
       const log     = document.getElementById('" id "');
       const elState = document.getElementById('sync-log-state');
       const elStage = document.getElementById('sync-log-stage');
       const elCount = document.getElementById('sync-log-counter');
       const elTime  = document.getElementById('sync-log-elapsed');
       if (!log) return;

       let startedAt = null;
       let timerHandle = null;
       let stages = [];          // each '=== Ingest/Materialize: NAME ===' bumps this
       let itemsInStage = 0;     // sum of N from 'Ingested … N items' since stage start

       function fmtElapsed(sec) {
         const m = Math.floor(sec / 60), s = sec % 60;
         return String(m).padStart(2,'0') + ':' + String(s).padStart(2,'0');
       }
       function tick() {
         if (!startedAt) return;
         elTime.textContent = fmtElapsed(Math.floor((Date.now() - startedAt) / 1000));
       }

       // ---- post-process appended SSE rows ----------------------------------
       // HTMX inserts each event as a text/HTML node into log. We re-style the
       // most recent text node based on regex patterns and update the header.
       const observer = new MutationObserver(muts => {
         for (const m of muts) {
           m.addedNodes.forEach(n => {
             if (n.nodeType !== Node.ELEMENT_NODE && n.nodeType !== Node.TEXT_NODE) return;
             const text = (n.textContent || '').trim();
             if (!text) return;

             if (!startedAt) {
               startedAt = Date.now();
               elState.textContent = '⏳ Идёт…';
               elState.className = 'font-medium text-yellow-700';
               timerHandle = setInterval(tick, 500);
             }

             // Color the new line in place
             if (n.nodeType === Node.ELEMENT_NODE) {
               const lc = text.toLowerCase();
               if (text.match(/^=== /))                  n.classList.add('text-cyan-300','font-semibold','mt-2','block');
               else if (lc.includes('error') || lc.includes('ошибка'))
                                                          n.classList.add('text-red-400');
               else if (text.match(/^\\s*Ingested|Materialized|^\\s*✓/))
                                                          n.classList.add('text-green-300');
               else if (text.match(/^\\s*Warning|^\\s*⚠/))
                                                          n.classList.add('text-yellow-300');
             }

             // Update header bar
             const stageMatch = text.match(/^=== (?:Ingest|Materialize): (\\S+) ===/);
             if (stageMatch) {
               stages.push(stageMatch[1]);
               itemsInStage = 0;
               elStage.textContent = 'стадия ' + stages.length + ': ' + stageMatch[1];
             }
             const itemsMatch = text.match(/(\\d+)\\s+items/);
             if (itemsMatch) {
               itemsInStage += parseInt(itemsMatch[1], 10);
               elCount.textContent = 'строк: ' + itemsInStage.toLocaleString('ru-RU');
             }
             if (text.includes('Completed') || text.includes('=== Ingest complete') ||
                 text.includes('Ingest complete')) {
               elState.textContent = '✓ Готово';
               elState.className = 'font-medium text-green-700';
               if (timerHandle) clearInterval(timerHandle);
             } else if (text.toLowerCase().includes('cancelled') || text.toLowerCase().includes('прервано')) {
               elState.textContent = '⛔ Прервано';
               elState.className = 'font-medium text-gray-600';
               if (timerHandle) clearInterval(timerHandle);
             } else if (text.toLowerCase().includes('error') && stages.length > 0) {
               elState.textContent = '✗ Ошибка';
               elState.className = 'font-medium text-red-700';
             }
           });
         }
         // Auto-scroll to bottom
         log.scrollTop = log.scrollHeight;
       });
       observer.observe(log, { childList: true, subtree: false });
     })();
   ")]])

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
    - :href - Optional URL; when provided, card is rendered as <a> link

  Example:
    (kpi-card {:title \"Выручка\" :value 1830000 :format :rub :delta 12.4})"
  [{:keys [title value format delta delta-unit delta-direction href]
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
                      :else "text-red-600")
        tag (if href :a.block :div)
        attrs (cond-> {:class "bg-white rounded-lg shadow p-4 border border-gray-100"}
                href (assoc :href href))]
    [tag attrs
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
  - kpi-schema: seq of {:key :title :format :delta-from :delta-direction [:href]}
  - totals: map {<key> <numeric>}
  - compare-totals: optional map for delta calc (prior period)

  Example:
    (kpi-row [{:key :revenue :title \"Выручка\" :format :rub}] totals prev-totals)"
  [kpi-schema totals & [compare-totals]]
  [:div.grid.grid-cols-2.md:grid-cols-4.gap-4.mb-6
   (for [{:keys [key title format delta-from delta-direction href]} kpi-schema]
     (let [value (get totals key)
           prev-value (when (and compare-totals delta-from)
                        (get compare-totals key))
           delta (when (and prev-value (number? value) (number? prev-value) (not (zero? prev-value)))
                   (* 100.0 (/ (- value prev-value) prev-value)))]
       (kpi-card {:title title :value value :format format
                  :delta delta :delta-direction delta-direction :href href})))])

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
           // Month-boundary header: render a row that shows the month at each
           // 1st-of-month column. Lets the eye locate March/April without
           // counting cells.
           const monthHeader = days.map(d => {
             const dt = new Date(d + 'T00:00:00Z');
             const isFirst = dt.getUTCDate() === 1;
             const label = isFirst
               ? String(dt.getUTCMonth() + 1).padStart(2, '0') + '.' + dt.getUTCFullYear()
               : '';
             return '<td style=\"width:8px;height:18px;font-size:9px;color:#666;padding:0;white-space:nowrap;overflow:visible\">' + label + '</td>';
           }).join('');
           rows.push('<tr><td></td><td></td>' + monthHeader + '</tr>');
           for (const mpKey of ['wb', 'ozon', 'ym']) {
             const mp = data[mpKey] || {};
             for (const typeKey of Object.keys(mp)) {
               const set = new Set(mp[typeKey].days || []);
               const label = mpNames[mpKey] + ' / ' + (typeNames[typeKey] || typeKey);
               const total = set.size;
               // Highlight empty rows so missing-ingest pipelines are obvious
               // at a glance instead of buried among the bands.
               const empty = total === 0;
               const labelCls = empty
                 ? 'pr-3 py-1 text-xs font-semibold text-red-600 whitespace-nowrap'
                 : 'pr-3 py-1 text-xs text-gray-600 whitespace-nowrap';
               const totalCls = empty
                 ? 'pr-3 text-xs font-semibold text-red-600'
                 : 'pr-3 text-xs text-gray-400';
               const cells = days.map(d => {
                 const has = set.has(d);
                 const dt  = new Date(d + 'T00:00:00Z');
                 // Faint vertical band at the 1st of each month for orientation.
                 const monthBoundary = dt.getUTCDate() === 1 ? 'border-left:1px solid #999;' : '';
                 return '<td title=\"' + label + ' · ' + d + (has ? ' ✓' : ' ✗') + '\" class=\"' +
                   (has ? 'bg-green-500' : 'bg-gray-200') + '\" style=\"width:8px;height:18px;border:0;' + monthBoundary + '\"></td>';
               }).join('');
               rows.push('<tr><td class=\"' + labelCls + '\">' + label +
                         '</td><td class=\"' + totalCls + '\">' + total + ' дн.</td>' +
                         cells + '</tr>');
             }
           }
           container.innerHTML = '<div class=\"overflow-x-auto\"><table style=\"border-collapse:separate;border-spacing:1px\">' +
                                  '<tbody>' + rows.join('') + '</tbody></table>' +
                                  '<div class=\"text-xs text-gray-500 mt-2 flex gap-3\">' +
                                  '<span><span class=\"inline-block w-3 h-3 bg-green-500 align-middle\"></span> данные есть</span>' +
                                  '<span><span class=\"inline-block w-3 h-3 bg-gray-200 align-middle\"></span> нет</span>' +
                                  '<span class=\"text-red-600\">красным — пробел в загрузке</span>' +
                                  '</div></div>';
         })
         .catch(err => { container.innerHTML = '<div class=\"text-sm text-red-600\">Ошибка: ' + err + '</div>'; });
     })();
   ")]])

;; ---------------------------------------------------------------------------
;; Cost Prices CSV Upload Component
;; ---------------------------------------------------------------------------

(defn cost-prices-upload
  "Drag-drop zone for 1C CSV with two-step preview → commit flow:

     1. drop / pick file → POSTs to /api/cost-prices/preview (no DB write)
     2. browser renders parsed rows + per-line errors in a table
     3. user clicks 'Применить' → POSTs the same file to /upload to commit

   Old single-step behaviour was opaque — users only saw a JSON dump
   after the fact, with no chance to inspect errors before committing."
  []
  [:div.bg-white.rounded-lg.shadow.p-6
   [:h3.text-lg.font-semibold.text-gray-900.mb-2 "Себестоимость из 1С (CSV)"]
   [:p.text-sm.text-gray-600.mb-4 "Загрузите units.csv — обязательные колонки: article, cost_price"]
   [:label#cost-prices-dropzone.block.border-2.border-dashed.border-gray-300.rounded-lg.p-6.text-center.cursor-pointer.hover:border-blue-500.hover:bg-blue-50
    [:input#cost-prices-file {:type "file" :accept ".csv" :class "hidden"}]
    [:div.text-gray-500 "📁 Перетащите CSV или кликните для выбора"]]
   [:div#cost-prices-summary.mt-3.text-sm]
   [:div#cost-prices-preview.mt-3]
   [:div#cost-prices-actions.mt-3.flex.gap-2]
   [:div#cost-prices-result.mt-3.text-sm]
   [:div.mt-4.text-xs.text-gray-500
    [:a {:href "/api/cost-prices/imports" :target "_blank"} "📜 История импортов (JSON)"]]
   [:script "
     (function() {
       const input    = document.getElementById('cost-prices-file');
       const dropzone = document.getElementById('cost-prices-dropzone');
       const summary  = document.getElementById('cost-prices-summary');
       const preview  = document.getElementById('cost-prices-preview');
       const actions  = document.getElementById('cost-prices-actions');
       const result   = document.getElementById('cost-prices-result');
       let stagedFile = null;

       const REASON_RU = {
         'too-few-columns':    'мало колонок',
         'missing-article':    'нет артикула',
         'missing-cost-price': 'нет цены',
         'unparseable':        'не разобрана'
       };

       function escapeHtml(s) {
         return String(s ?? '').replace(/[&<>\"']/g, c =>
           ({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;','\\'':'&#39;'}[c]));
       }

       async function preview_(file) {
         stagedFile = null;
         summary.innerHTML = '<div class=\"text-gray-500\">⏳ Парсинг ' + escapeHtml(file.name) + '…</div>';
         preview.innerHTML = '';
         actions.innerHTML = '';
         result.innerHTML = '';
         const fd = new FormData();
         fd.append('file', file);
         try {
           const r = await fetch('/api/cost-prices/preview', { method: 'POST', body: fd });
           const d = await r.json();
           if (!r.ok) {
             summary.innerHTML = '<div class=\"text-red-700\">✗ Ошибка ' + r.status + ': ' + escapeHtml(d.error || '') + '</div>';
             return;
           }

           const valid = d.valid || 0;
           const errs  = d['errors-count'] || 0;
           const skip  = d.skipped || 0;
           summary.innerHTML =
             '<div class=\"flex flex-wrap gap-3\">' +
               '<span><b>Файл:</b> ' + escapeHtml(d.filename) + '</span>' +
               '<span class=\"text-gray-500\">всего строк: ' + d['total-lines'] + '</span>' +
               '<span class=\"text-green-700\">✓ корректных: <b>' + valid + '</b></span>' +
               '<span class=\"' + (errs ? 'text-red-700' : 'text-gray-500') + '\">✗ ошибок: <b>' + errs + '</b></span>' +
               '<span class=\"text-gray-500\">служебных: ' + skip + '</span>' +
             '</div>';

           // Rows table (first 50 of cap-200 returned)
           const rows = (d.rows || []).slice(0, 50);
           const rowsHtml = rows.length === 0
             ? '<div class=\"text-gray-500 text-sm\">Нет валидных строк для предпросмотра.</div>'
             : '<div class=\"overflow-x-auto border border-gray-200 rounded\"><table class=\"min-w-full text-xs\">' +
               '<thead class=\"bg-gray-50\"><tr>' +
                 '<th class=\"px-2 py-1 text-left\">Артикул</th>' +
                 '<th class=\"px-2 py-1 text-left\">Номер</th>' +
                 '<th class=\"px-2 py-1 text-left\">Цвет</th>' +
                 '<th class=\"px-2 py-1 text-right\">Себестоимость</th>' +
                 '<th class=\"px-2 py-1 text-right\">Кол-во</th>' +
                 '<th class=\"px-2 py-1 text-left\">Номенклатура</th>' +
               '</tr></thead><tbody>' +
                 rows.map(r =>
                   '<tr class=\"border-t border-gray-100\">' +
                     '<td class=\"px-2 py-1 font-mono\">' + escapeHtml(r.article) + '</td>' +
                     '<td class=\"px-2 py-1\">' + escapeHtml(r['article-num']) + '</td>' +
                     '<td class=\"px-2 py-1\">' + escapeHtml(r.color || '—') + '</td>' +
                     '<td class=\"px-2 py-1 text-right\">' + Number(r['cost-price']).toLocaleString('ru-RU') + '</td>' +
                     '<td class=\"px-2 py-1 text-right text-gray-500\">' + (r.quantity ?? '—') + '</td>' +
                     '<td class=\"px-2 py-1 text-gray-600\">' + escapeHtml(r.nomenclature || '') + '</td>' +
                   '</tr>'
                 ).join('') +
               '</tbody></table></div>' +
               (valid > 50 ? '<div class=\"text-xs text-gray-500 mt-1\">…ещё ' + (valid - 50) + ' строк не показано в превью</div>' : '');

           // Errors list
           const errsList = d.errors || [];
           const errsHtml = errsList.length === 0 ? '' :
             '<div class=\"mt-3\"><div class=\"text-sm font-semibold text-red-700 mb-1\">Ошибки (' + errsList.length + '):</div>' +
             '<div class=\"max-h-48 overflow-auto border border-red-200 rounded\"><table class=\"min-w-full text-xs\">' +
               '<thead class=\"bg-red-50 sticky top-0\"><tr>' +
                 '<th class=\"px-2 py-1 text-left\">Стр.</th>' +
                 '<th class=\"px-2 py-1 text-left\">Причина</th>' +
                 '<th class=\"px-2 py-1 text-left\">Содержимое</th>' +
               '</tr></thead><tbody>' +
                 errsList.map(e =>
                   '<tr class=\"border-t border-red-100\">' +
                     '<td class=\"px-2 py-1 font-mono text-gray-700\">' + e.line + '</td>' +
                     '<td class=\"px-2 py-1 text-red-700\">' + escapeHtml(REASON_RU[e.reason] || e.reason) + '</td>' +
                     '<td class=\"px-2 py-1 text-gray-500 truncate max-w-md\" title=\"' + escapeHtml(e.raw) + '\">' + escapeHtml((e.raw || '').slice(0, 120)) + '</td>' +
                   '</tr>'
                 ).join('') +
               '</tbody></table></div></div>';

           preview.innerHTML = rowsHtml + errsHtml;

           // Commit button (only when there's something to commit)
           if (valid > 0) {
             stagedFile = file;
             const errsNote = errs ? ' <span class=\"text-red-600 text-xs\">(' + errs + ' ошибок будет пропущено)</span>' : '';
             actions.innerHTML =
               '<button id=\"cost-prices-commit\" class=\"px-4 py-2 bg-green-600 text-white rounded-md hover:bg-green-700 transition-colors text-sm font-medium\">Применить ' + valid + ' строк</button>' +
               '<button id=\"cost-prices-cancel\" class=\"px-4 py-2 bg-gray-200 text-gray-700 rounded-md hover:bg-gray-300 transition-colors text-sm\">Отмена</button>' +
               '<span class=\"text-xs text-gray-500 self-center\">' + errsNote + '</span>';
             document.getElementById('cost-prices-commit').addEventListener('click', commit);
             document.getElementById('cost-prices-cancel').addEventListener('click', reset);
           } else {
             actions.innerHTML = '<button id=\"cost-prices-cancel\" class=\"px-4 py-2 bg-gray-200 text-gray-700 rounded-md hover:bg-gray-300 transition-colors text-sm\">Закрыть</button>';
             document.getElementById('cost-prices-cancel').addEventListener('click', reset);
           }
         } catch (e) {
           summary.innerHTML = '<div class=\"text-red-700\">✗ ' + escapeHtml(e.message) + '</div>';
         }
       }

       async function commit() {
         if (!stagedFile) return;
         const btn = document.getElementById('cost-prices-commit');
         if (btn) { btn.disabled = true; btn.textContent = 'Загрузка…'; }
         result.innerHTML = '<div class=\"text-gray-500\">⏳ Отправка ' + escapeHtml(stagedFile.name) + ' на сервер…</div>';
         const fd = new FormData();
         fd.append('file', stagedFile);
         try {
           const r = await fetch('/api/cost-prices/upload', { method: 'POST', body: fd });
           const d = await r.json();
           if (r.ok) {
             const rows = d.loaded || d.rows || d.inserted || 0;
             const rej  = d.rejected || 0;
             result.innerHTML = '<div class=\"text-green-700\">✓ Загружено: <b>' + rows + '</b>' + (rej ? ', отклонено: ' + rej : '') + '</div>';
             actions.innerHTML = '';
             stagedFile = null;
           } else {
             result.innerHTML = '<div class=\"text-red-700\">✗ Ошибка ' + r.status + ': ' + escapeHtml(d.error || JSON.stringify(d)) + '</div>';
             if (btn) { btn.disabled = false; btn.textContent = 'Повторить'; }
           }
         } catch (e) {
           result.innerHTML = '<div class=\"text-red-700\">✗ ' + escapeHtml(e.message) + '</div>';
           if (btn) { btn.disabled = false; btn.textContent = 'Повторить'; }
         }
       }

       function reset() {
         stagedFile = null;
         input.value = '';
         summary.innerHTML = '';
         preview.innerHTML = '';
         actions.innerHTML = '';
         result.innerHTML = '';
       }

       input.addEventListener('change', e => { if (e.target.files[0]) preview_(e.target.files[0]); });
       ['dragenter','dragover'].forEach(evt => dropzone.addEventListener(evt, e => {
         e.preventDefault(); e.stopPropagation();
         dropzone.classList.add('border-blue-500', 'bg-blue-50');
       }));
       ['dragleave','drop'].forEach(evt => dropzone.addEventListener(evt, e => {
         e.preventDefault(); e.stopPropagation();
         dropzone.classList.remove('border-blue-500', 'bg-blue-50');
       }));
       dropzone.addEventListener('drop', e => {
         if (e.dataTransfer.files[0]) preview_(e.dataTransfer.files[0]);
       });
     })();
   "]])

