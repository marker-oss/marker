(ns analitica.web.pages.reports
  (:require [hiccup.core :refer [html]]
            [analitica.web.components :as c]
            [analitica.web.components.stale-banner :as sb]
            [analitica.web.pages.digest :as digest]
            [analitica.web.report-schemas :as rs]
            [analitica.domain.finance :as finance]
            [analitica.domain.pnl :as pnl]
            [analitica.domain.sales :as sales]
            [analitica.util.time :as time]
            [analitica.freshness :as freshness]
            [com.brunobonacci.mulog :as μ]))

(defn- compute-by-marketplace
  "Compute per-MP {:revenue :profit :margin :sales-qty :returns-qty} for the
   given period via the canonical pnl/calculate path. Only invoked when no
   single marketplace is selected, since the per-MP breakdown is exactly
   what's interesting in the all-MP view."
  [period-arg]
  (let [[from to] (time/resolve-period period-arg)
        period    {:from from :to to}]
    (vec
     (for [mp [:wb :ozon :ym]]
       (let [fin     (try (finance/fetch-finance period :marketplace mp)
                          (catch Exception _ []))
             p       (try (pnl/calculate fin
                                         :marketplace mp
                                         :from from :to to)
                          (catch Exception _ {}))
             mp-sales (try (sales/fetch-sales period :marketplace mp)
                           (catch Exception _ []))
             s-totals (try (sales/totals mp-sales) (catch Exception _ {}))
             fin-rev  (or (:revenue p) 0.0)
             sale-rev (or (:total-revenue s-totals) (:revenue s-totals) 0.0)
             prelim?  (and (zero? fin-rev) (pos? sale-rev))
             revenue  (if prelim? sale-rev fin-rev)
             profit   (or (:net-profit p) 0.0)
             margin   (when (and (number? revenue) (pos? revenue))
                        (* 100.0 (/ profit revenue)))]
         (let [pnl-sq  (:sales-qty p)
               pnl-rq  (:returns-qty p)
               sq      (if (and (number? pnl-sq) (pos? pnl-sq))
                         pnl-sq
                         (or (:sales-count s-totals) 0))
               rq      (if (and (number? pnl-rq) (pos? pnl-rq))
                         pnl-rq
                         (or (:returns-count s-totals) 0))]
           {:marketplace mp
            :revenue     revenue
            :profit      profit
            :margin      margin
            :sales-qty   sq
            :returns-qty rq
            :preliminary? prelim?}))))))

;; ---------------------------------------------------------------------------
;; Schema Column Helpers
;; ---------------------------------------------------------------------------

(defn- schema-col->tabulator
  "Convert a single schema column map to a Tabulator column definition map."
  [c]
  (cond-> {:title (:title c)
           :field (name (:key c))
           :format (:format c)
           :canon-anchor (:canon-anchor c)
           :width (case (:format c)
                    :rub 130 :int 100 :pct 100
                    :text 150 :date 120 120)}
    (not (:default-visible? c)) (assoc :visible false)
    (:linkable? c)               (assoc :linkable? true)))

(defn- delta-triplet
  "Return the 3 extra Tabulator column definitions for a delta-supported column.
   Fields: <key>_prev, <key>_delta, <key>_delta_pct."
  [col]
  (let [base  (name (:key col))
        fmt   (:format col)
        label (:title col)]
    [{:title (str label " пред.") :field (str base "_prev")   :format fmt  :width (case fmt :rub 130 :int 100 :pct 100 80) :visible false}
     {:title (str "Δ " label)    :field (str base "_delta")   :format fmt  :width (case fmt :rub 130 :int 100 :pct 100 80)}
     {:title (str "Δ% " label)   :field (str base "_delta_pct") :format :pct :width 90}]))

(defn- expand-delta-cols
  "Given a seq of schema column maps and compare-active? flag, return a flat vector
   of Tabulator column defs. For each delta-supported column when compare is active,
   inject <col> followed immediately by its _prev/_delta/_delta_pct triplet."
  [cols compare-active?]
  (reduce (fn [acc c]
            (let [base (schema-col->tabulator c)]
              (if (and compare-active? (:delta-supported? c))
                (into acc (cons base (delta-triplet c)))
                (conj acc base))))
          []
          cols))

(defn- columns-from-schema
  "Convert schema columns to grouped Tabulator columns.
   Includes ALL columns — default-visible? false ones get :visible false in Tabulator
   so they can be toggled via preset/chooser without reloading the page.
   Returns a vector of {:title :columns [...]}, ordered by :column-groups insertion order.
   Falls back to flat column list if :column-groups not defined.

   When compare-active? is true, delta-supported columns get an injected triplet of
   _prev / _delta / _delta_pct sub-columns immediately after the primary column."
  [schema & {:keys [compare-active?]}]
  (let [groups (:column-groups schema)
        all-cols (:columns schema)]
    (if (seq groups)
      (let [grouped (group-by :group all-cols)]
        (->> (keys groups)
             (filter #(seq (grouped %)))
             (mapv (fn [g-key]
                     {:title (get-in groups [g-key :title])
                      :columns (expand-delta-cols (grouped g-key) compare-active?)}))))
      ;; flat fallback
      (expand-delta-cols all-cols compare-active?))))

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

(defn- marketplace-filter
  "Render marketplace filter dropdown with HTMX update."
  [report-type period current-marketplace]
  [:div.flex.items-center.gap-2
   [:label.text-sm.font-medium.text-gray-700 {:for "marketplace-filter"} "Маркетплейс:"]
   [:select#marketplace-filter.px-3.py-2.border.border-gray-300.rounded-md.text-sm.focus:outline-none.focus:ring-2.focus:ring-blue-500
    {:name "marketplace"
     :hx-get (str "/reports/" (name report-type))
     :hx-trigger "change"
     :hx-target "#report-body"
     :hx-swap "outerHTML"
     :hx-select "#report-body"
     :hx-include "#article-filter"}
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
     :hx-target "#report-body"
     :hx-swap "outerHTML"
     :hx-select "#report-body"
     :hx-include "#marketplace-filter"}]])

(defn- period->url-frag
  "Convert a period value (map, keyword, or legacy string) to a query-string fragment
   WITHOUT leading '&' or '?'. Examples:
     {:from \"2026-03-01\" :to \"2026-04-01\"} → \"from=2026-03-01&to=2026-04-01\"
     :last-30-days                             → \"period=last-30-days\"
     \"last-7-days\"                           → \"period=last-7-days\""
  [period]
  (cond
    (map? period)     (str "from=" (:from period) "&to=" (:to period))
    (keyword? period) (str "period=" (name period))
    (string? period)  (str "period=" period)
    :else             "period=last-30-days"))

(defn- export-buttons
  "Render Excel and CSV export buttons."
  [report-type period marketplace]
  (let [period-frag (period->url-frag period)
        base-url (str "/api/export/" (name report-type) "?" period-frag)
        marketplace-str (when marketplace
                          (if (keyword? marketplace) (name marketplace) (str marketplace)))
        marketplace-param (if (and marketplace-str (not= marketplace-str "all"))
                            (str "&marketplace=" marketplace-str)
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
  [report-type period marketplace & {:keys [show-no-data article totals compare]}]
  (let [schema (rs/get-schema report-type)
        _ (when-not schema
            (throw (ex-info "Unknown report-type" {:type report-type})))
        report-title (:title schema)
        chart-type-kw (:type (:chart schema))
        chart-type (case chart-type-kw
                     :waterfall "bar"           ;; Chart.js has no waterfall; bar placeholder
                     :horizontalBar "bar"
                     (name chart-type-kw))
        ;; compare is nil when no compare period is active (report-data omits :compare for :none)
        compare-active? (some? compare)
        grouped-cols (columns-from-schema schema :compare-active? compare-active?)
        kpi-schema (:kpi schema)
        compare-totals (when compare (:totals compare))
        tabs (or (:tabs schema) [:chart])
        active-tab (first tabs)
        tab-set (set tabs)
        ;; marketplace can arrive as a keyword (`:wb`) or string (`"wb"`); when
        ;; it is a keyword, plain `str` would emit `:wb` and the API rejects it.
        marketplace-str (when marketplace
                          (cond
                            (keyword? marketplace) (name marketplace)
                            :else                  (str marketplace)))
        marketplace-param (if (and marketplace-str (not= marketplace-str "all"))
                            (str "&marketplace=" marketplace-str) "")
        article-param (when (seq article)
                        (str "&article=" (java.net.URLEncoder/encode article "UTF-8")))
        period-frag (period->url-frag period)
        api-url (str "/api/report/" (name report-type) "?" period-frag
                     marketplace-param (or article-param "")
                     (when compare "&compare=prev"))
        chart-api-url (str "/api/chart/report?type=" (name report-type)
                           "&" period-frag marketplace-param
                           (when compare "&compare=prev"))
        ;; Stale-data banner — query DB for freshness; nil on any error (non-blocking)
        mp-kw  (when (and marketplace (not= marketplace "all"))
                 (keyword marketplace))
        period-str (cond
                     (map? period)     (str (:from period) "_" (:to period))
                     (keyword? period) (name period)
                     :else             (str period))
        stale  (try
                 (freshness/stale-info {:report report-type :marketplace mp-kw})
                 (catch Exception e
                   (μ/log ::freshness-check-failed :error (.getMessage e) :report report-type)
                   nil))]

    [:div
     [:div.mb-6
      [:h2.text-2xl.font-bold.text-gray-900 report-title]]

     (sb/stale-banner stale {:report report-type :period period-str})

     (when show-no-data (no-data-banner))

     ;; What-if calculator — only on UE report.
     (when (= report-type :ue)
       [:div#what-if-card.bg-white.rounded-lg.shadow.p-4.mb-4
        [:h3.text-lg.font-semibold.text-gray-900.mb-3 "Калькулятор Что-если"]
        [:p.text-xs.text-gray-500.mb-4
         "Введите параметры юнита и увидите чистую прибыль и ROMI в реальном времени."]
        [:div.grid.grid-cols-1.md:grid-cols-2.gap-4
         (for [[k label min max step default unit]
               [["price"          "Цена"            0     5000  10    1500  "₽"]
                ["buyoutPct"      "% выкупа"        0.5   1.0   0.01  0.92  ""]
                ["commissionPct"  "Комиссия"        0     0.5   0.005 0.17  ""]
                ["logisticsRub"   "Логистика/шт"    0     500   5     80    "₽"]
                ["cogs"           "Себестоимость"   0     3000  10    500   "₽"]
                ["cpcRub"         "CPC"             0     500   1     30    "₽"]
                ["cr"             "Конверсия CR"    0.001 0.5   0.001 0.05  ""]]]
           [:div.flex.flex-col
            [:div.flex.items-center.justify-between.mb-1
             [:label.text-sm.text-gray-700 (str label (when (seq unit) (str ", " unit)))]
             [:span.text-sm.font-mono.text-gray-900
              {:data-whatif-label k} (str default)]]
            [:input.w-full
             {:type        "range"
              :data-whatif-input k
              :min         min :max max :step step
              :value       default}]])]
        [:div.mt-4.pt-3.border-t.grid.grid-cols-2.gap-4
         [:div [:span.text-xs.text-gray-500 "Чистая прибыль ₽/шт: "]
          [:span.text-xl.font-semibold.text-gray-900
           {:data-whatif-output "net"} "—"]]
         [:div [:span.text-xs.text-gray-500 "ROMI: "]
          [:span.text-xl.font-semibold.text-gray-900
           {:data-whatif-output "romi"} "—"]]]])

     [:div.bg-white.rounded-lg.shadow.p-4.mb-6
      [:div.flex.items-center.justify-between.flex-wrap.gap-4
       [:div.flex.items-center.gap-4.flex-wrap
        (marketplace-filter report-type period marketplace)
        (when (= report-type :ue)
          (article-filter report-type article))]
       (export-buttons report-type period marketplace)]]

     ;; #report-body wraps everything that depends on the marketplace/article
     ;; filter so HTMX can swap the whole thing in one go. KPI row, MP table,
     ;; tab switcher and tab bodies all need to refresh together — targeting
     ;; just the inner #report-content left them out of sync.
     [:div#report-body
      ;; KPI row (above tabs) — pass compare-totals for period-over-period deltas
      (when (and kpi-schema (seq totals))
        (c/kpi-row kpi-schema totals compare-totals))

      ;; Per-marketplace breakdown — shown on P&L when no single MP is selected.
      ;; The whole point of P&L is comparing all 3 in the all-MP view.
      (when (and (= report-type :pnl)
                 (or (nil? marketplace) (= marketplace "all") (= marketplace :all)))
        (try
          (digest/marketplace-comparison-table (compute-by-marketplace period))
          (catch Exception e
            (μ/log ::pnl-mp-breakdown-failed :error (.getMessage e))
            nil)))

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
            (let [table-id (str (name report-type) "-table")
                  presets (:column-presets schema)
                  preset-labels {:basic "Базовый" :full "Полный"
                                 :per-unit "Per-unit" :percentages "Проценты"}
                  default-visible (->> (:columns schema)
                                       (filter :default-visible?)
                                       (mapv #(name (:key %))))]
              [:div
               [:div.flex.items-center.gap-2.mb-2
                (when (seq presets)
                  (c/preset-chips {:presets presets
                                   :active :basic
                                   :table-id table-id
                                   :labels preset-labels}))
                (c/column-chooser {:columns (:columns schema) :table-id table-id})]
               (c/tabulator-table {:id table-id
                                   :api-url api-url
                                   :grouped-columns grouped-cols
                                   :frozen-cols 1
                                   :page-size 50
                                   :column-presets presets
                                   :default-visible-fields default-visible
                                   :on-row-click-js
                                   (when (#{:ue :finance} report-type)
                                     ;; If click was on a SKU link button, sku-sheet.js handles it
                                     ;; via document-level capture and we must NOT also open the
                                     ;; legacy right-side drill-panel.
                                     (str "function(e, row) {\n"
                                          "  if (e && e.target && e.target.closest && e.target.closest('.sku-link')) return;\n"
                                          "  const d = row.getData();\n"
                                          "  if (d.article) { window.openDrillPanel('"
                                          (name report-type) "', d.article, '"
                                          period-frag "', '" (or (when marketplace
                                                                   (if (keyword? marketplace) (name marketplace) (str marketplace)))
                                                                 "all") "'); }\n"
                                          "}"))})])
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
            [:div.text-gray-500.text-sm "Нет данных"])])]]]))
