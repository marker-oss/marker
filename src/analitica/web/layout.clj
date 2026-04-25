(ns analitica.web.layout
  (:require [hiccup.page :refer [html5 include-css include-js]]
            [hiccup.core :refer [html]]
            [analitica.web.components :as components]
            [analitica.util.period :as period]))

;; ---------------------------------------------------------------------------
;; CDN Resources
;; ---------------------------------------------------------------------------

(def cdn-resources
  {:tailwind "https://cdn.tailwindcss.com"
   :htmx "https://unpkg.com/htmx.org@1.9.10"
   :htmx-sse "https://unpkg.com/htmx.org@1.9.10/dist/ext/sse.js"
   :chartjs "https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js"
   :tabulator-css "https://unpkg.com/tabulator-tables@5.5.2/dist/css/tabulator.min.css"
   :tabulator-js "https://unpkg.com/tabulator-tables@5.5.2/dist/js/tabulator.min.js"})

;; ---------------------------------------------------------------------------
;; Navigation Items
;; ---------------------------------------------------------------------------

(def nav-items
  [{:label "Дашборд" :route "/" :children
    [{:label "Все" :route "/"}
     {:label "WB" :route "/wb"}
     {:label "Ozon" :route "/ozon"}
     {:label "YM" :route "/ym"}]}
   {:label "Отчёты" :route "/reports" :children
    [{:label "Продажи" :route "/reports/sales"}
     {:label "Финансы" :route "/reports/finance"}
     {:label "Юнит-экономика" :route "/reports/ue"}
     {:label "P&L" :route "/reports/pnl"}
     {:label "ABC-анализ" :route "/reports/abc"}
     {:label "Остатки" :route "/reports/stock"}
     {:label "Возвраты" :route "/reports/returns"}
     {:label "Выкуп" :route "/reports/buyout"}
     {:label "География" :route "/reports/geo"}
     {:label "Тренды" :route "/reports/trends"}]}
   {:label "Синхронизация" :route "/sync"}])

;; ---------------------------------------------------------------------------
;; Components
;; ---------------------------------------------------------------------------

(defn- nav-item
  "Render a single navigation item with optional children."
  [item active-route]
  (let [{:keys [label route children]} item
        is-active? (or (= route active-route)
                       (some #(= (:route %) active-route) children))
        base-classes "block px-4 py-2 text-sm rounded-md transition-colors"
        active-classes "bg-blue-600 text-white"
        inactive-classes "text-gray-300 hover:bg-gray-700 hover:text-white"]
    [:div.mb-1
     [:a {:href route
          :class (str base-classes " " (if is-active? active-classes inactive-classes))}
      label]
     (when children
       [:div.ml-4.mt-1
        (for [child children]
          (let [child-active? (= (:route child) active-route)
                child-classes (str "block px-3 py-1.5 text-xs rounded-md transition-colors "
                                   (if child-active?
                                     "bg-blue-500 text-white"
                                     "text-gray-400 hover:bg-gray-700 hover:text-white"))]
            [:a {:href (:route child)
                 :class child-classes}
             (:label child)]))])]))

(defn- sidebar
  "Render the sidebar with navigation."
  [active-route]
  [:aside.w-64.bg-gray-800.text-white.flex-shrink-0.overflow-y-auto.hidden.lg:block
   [:div.p-4
    [:h2.text-xl.font-bold.mb-6 "Analitica"]
    [:nav
     (for [item nav-items]
       (nav-item item active-route))]]])

(defn- header
  "Render the header: title, global period picker, sync button, last-sync label.

   The period-picker chip is server-rendered with `default-state` (last-30-days).
   On DOMContentLoaded, period-picker.js reads URL params → localStorage →
   default and hydrates the chip text, so any user-chosen period survives the
   reload cycle.

   :hide-period?      — when true, omit the period-picker entirely (snapshot reports)
   :supports-compare? — passed through to period-picker; when false, compare toggle hidden"
  [& {:keys [hide-period? supports-compare?]
      :or {hide-period? false supports-compare? true}}]
  (let [initial (period/default-state)]
    [:header.bg-white.shadow-sm.border-b.border-gray-200.px-4.lg:px-6.py-4
     [:div.flex.flex-col.lg:flex-row.items-start.lg:items-center.justify-between.gap-4
      [:div.flex.flex-col.sm:flex-row.items-start.sm:items-center.gap-4.lg:gap-6.w-full.lg:w-auto
       [:h1.text-xl.lg:text-2xl.font-bold.text-gray-900 "Analitica"]
       (when-not hide-period?
         (components/period-picker {:from              (:from initial)
                                    :to                (:to initial)
                                    :compare           :none
                                    :supports-compare? supports-compare?}))]
      ;; Sync controls live on /sync; header only shows the last-sync label
      ;; so users see freshness on every page without a duplicated trigger.
      [:div.flex.flex-col.sm:flex-row.items-start.sm:items-center.gap-3.lg:gap-4.w-full.lg:w-auto
       [:div.text-xs.lg:text-sm.text-gray-600
        [:span "Последняя синхронизация: "]
        [:span#last-sync-time.font-medium "—"]]]]]))

;; ---------------------------------------------------------------------------
;; Main Layout
;; ---------------------------------------------------------------------------

(defn page
  "Main page layout with sidebar, header, and content area.

  Parameters:
  - title: Page title (string)
  - content: Hiccup content vector
  - options:
    - :active-route      - Current route for sidebar highlighting (default: \"/\")
    - :hide-period?      - When true, omit period-picker from header (default: false)
    - :supports-compare? - When false, compare toggle hidden in period-picker (default: true)

  Example:
    (page \"Dashboard\" [:div \"Content\"] :active-route \"/\")"
  [title content & {:keys [active-route hide-period? supports-compare?]
                    :or {active-route "/" hide-period? false supports-compare? true}}]
  (html5
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title (str title " - Analitica")]
     [:link {:rel "icon" :type "image/svg+xml" :href "data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><text y='.9em' font-size='90'>📊</text></svg>"}]
     
     ;; CDN Resources with cache control
     [:script {:src (:tailwind cdn-resources)}]
     [:script {:src (:htmx cdn-resources)}]
     [:script {:src (:htmx-sse cdn-resources)}]
     [:script {:src (:chartjs cdn-resources)}]
     [:link {:rel "stylesheet" :href (:tabulator-css cdn-resources)}]
     [:script {:src (:tabulator-js cdn-resources)}]
     [:script {:src "/js/table-columns.js"}]
     [:script {:src "/js/drill-panel.js"}]
     [:script {:src "/js/period-picker.js"}]

     ;; Custom styles
     [:style "
       body { margin: 0; padding: 0; }
       #app { display: flex; flex-direction: column; height: 100vh; }
       @media (min-width: 1024px) {
         #app { flex-direction: row; }
       }
       #main-container { display: flex; flex-direction: column; flex: 1; overflow: hidden; }
       #main-content { flex: 1; overflow-y: auto; padding: 1rem; }
       @media (min-width: 768px) {
         #main-content { padding: 1.5rem; }
       }
       .htmx-indicator { display: none; }
       .htmx-request .htmx-indicator { display: inline-block; }
       .htmx-request.htmx-indicator { display: inline-block; }
     "]]
    
    [:body
     [:div#app
      (sidebar active-route)
      [:div#main-container
       (header :hide-period? hide-period? :supports-compare? supports-compare?)
       [:main#main-content.bg-gray-50
        content]]
     (components/drill-panel {})]]))

