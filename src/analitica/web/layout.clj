(ns analitica.web.layout
  (:require [clojure.string :as str]
            [hiccup.page :refer [html5 include-css include-js]]
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
  [{:label "Главная" :icon "🏠" :route "/"}
   {:label "Финансы" :icon "💰" :route "/reports/pnl"
    :children [{:label "P&L"              :route "/reports/pnl"}
               {:label "Юнит-экономика"  :route "/reports/ue"}
               {:label "Финансы (детали)" :route "/reports/finance"}
               {:label "Возвраты"         :route "/reports/returns"}]}
   {:label "Товары" :icon "📦" :route "/reports/sales"
    :children [{:label "Продажи"   :route "/reports/sales"}
               {:label "ABC-анализ" :route "/reports/abc"}
               {:label "Тренды"    :route "/reports/trends"}
               {:label "Выкуп"     :route "/reports/buyout"}
               {:label "География" :route "/reports/geo"}]}
   {:label "Склады" :icon "🏬" :route "/reports/stock"
    :children [{:label "Остатки" :route "/reports/stock"}]}
   {:label "Управление" :icon "⚙" :route "/sync"
    :children [{:label "Синхронизация" :route "/sync"}]}])

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- group-active?
  "True when active-route is within a group: either the group's own route
   or any of its children's routes."
  [item active-route]
  (let [{:keys [route children]} item]
    (or (= route active-route)
        (and (seq children)
             (some #(or (= (:route %) active-route)
                        (and (seq active-route)
                             (str/starts-with? active-route (:route %))))
                   children)))))

;; ---------------------------------------------------------------------------
;; Components
;; ---------------------------------------------------------------------------

(defn- nav-item
  "Render a navigation item.
   - Items with :children render as a collapsible <details> group.
     The group is open by default when any child matches active-route.
   - Items without :children render as a plain link (e.g. Главная)."
  [item active-route]
  (let [{:keys [label icon route children]} item
        display-label (if icon (str icon " " label) label)
        active? (group-active? item active-route)]
    (if children
      ;; Collapsible group via HTML5 <details>/<summary>
      [:details.mb-1 (when active? {:open true})
       [:summary.flex.items-center.px-4.py-2.text-sm.rounded-md.cursor-pointer.transition-colors.select-none.list-none
        {:class (if active?
                  "bg-gray-700 text-white font-semibold"
                  "text-gray-300 hover:bg-gray-700 hover:text-white")}
        display-label]
       [:div.ml-4.mt-1
        (for [child children]
          (let [child-active? (= (:route child) active-route)
                child-classes (str "block px-3 py-1.5 text-xs rounded-md transition-colors "
                                   (if child-active?
                                     "bg-blue-500 text-white"
                                     "text-gray-400 hover:bg-gray-700 hover:text-white"))]
            [:a {:href (:route child)
                 :class child-classes}
             (:label child)]))]]
      ;; Plain leaf link (Главная — no sub-items)
      (let [base-classes "block px-4 py-2 text-sm rounded-md transition-colors"
            active-classes "bg-blue-600 text-white"
            inactive-classes "text-gray-300 hover:bg-gray-700 hover:text-white"]
        [:div.mb-1
         [:a {:href route
              :class (str base-classes " " (if active? active-classes inactive-classes))}
          display-label]]))))

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
     [:script {:src "/js/sku-sheet.js"}]
     [:script {:src "/js/cmdk.js"}]

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
     (components/drill-panel {})
     ;; Command-palette dialog — opened by Cmd+K / Ctrl+K via cmdk.js
     [:dialog#cmdk-palette.cmdk-palette
      [:div.cmdk-content
       [:div.cmdk-search
        [:input.w-full.text-lg.px-4.py-3.border-b.outline-none
         {:type "text" :placeholder "Поиск SKU, отчётов, страниц..."}]]
       [:ul.cmdk-results.max-h-96.overflow-y-auto]
       [:div.cmdk-footer.text-xs.text-gray-500.px-3.py-2.border-t
        "↑↓ навигация · ⏎ открыть · ESC закрыть"]]]

     ;; SKU drill-down dialog — populated by sku-sheet.js + /api/sku/:id
     [:dialog#sku-sheet.sku-sheet
      [:div.sku-sheet-content "Загрузка…"]
      [:button.sku-sheet-close {:onclick "this.closest('dialog').close()"} "×"]]
     [:style "
       dialog.sku-sheet {
         position: fixed;
         right: 0; top: 0;
         margin: 0;
         width: 480px;
         max-width: 100vw;
         height: 100vh;
         max-height: 100vh;
         border: none;
         border-left: 1px solid #e5e7eb;
         box-shadow: -4px 0 24px rgba(0,0,0,0.12);
         padding: 0;
         overflow: hidden;
         display: flex;
         flex-direction: column;
       }
       dialog.sku-sheet::backdrop {
         background: rgba(0,0,0,0.25);
       }
       dialog.sku-sheet[open] {
         display: flex;
       }
       .sku-sheet-content {
         flex: 1;
         overflow-y: auto;
         padding: 1.5rem;
         font-family: inherit;
       }
       .sku-sheet-close {
         position: absolute;
         top: 0.75rem;
         right: 0.75rem;
         background: none;
         border: 1px solid #d1d5db;
         border-radius: 50%;
         width: 2rem;
         height: 2rem;
         font-size: 1rem;
         line-height: 1;
         cursor: pointer;
         color: #6b7280;
         display: flex;
         align-items: center;
         justify-content: center;
         z-index: 10;
       }
       .sku-sheet-close:hover { background: #f3f4f6; color: #111; }
       .sku-sheet-loading, .sku-sheet-error {
         display: flex;
         align-items: center;
         justify-content: center;
         height: 100%;
         color: #9ca3af;
         font-size: 0.9rem;
       }
       .sku-link {
         background: none;
         border: none;
         padding: 0;
         cursor: pointer;
       }
       /* Command palette */
       dialog.cmdk-palette {
         position: fixed;
         top: 20vh;
         left: 50%;
         transform: translateX(-50%);
         margin: 0;
         width: 90vw;
         max-width: 600px;
         border: none;
         border-radius: 0.75rem;
         box-shadow: 0 8px 40px rgba(0,0,0,0.20);
         padding: 0;
         overflow: hidden;
         background: #fff;
         z-index: 9000;
       }
       dialog.cmdk-palette::backdrop {
         background: rgba(0,0,0,0.30);
       }
       dialog.cmdk-palette[open] { display: flex; flex-direction: column; }
       .cmdk-content { display: flex; flex-direction: column; }
       .cmdk-search input { font-family: inherit; }
       .cmdk-results { list-style: none; margin: 0; padding: 0.25rem 0; }
       .cmdk-results li { list-style: none; }
       .cmdk-footer { background: #f9fafb; border-top: 1px solid #e5e7eb; }
     "]]]))

