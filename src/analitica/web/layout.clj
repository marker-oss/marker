(ns analitica.web.layout
  (:require [hiccup.page :refer [html5 include-css include-js]]
            [hiccup.core :refer [html]]
            [jsonista.core :as json]
            [analitica.web.components :as components]))

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

(defn- period-selector
  "Render the period selector: native date range inputs + preset buttons."
  []
  [:div.flex.flex-wrap.items-center.gap-2
   [:label.text-sm.font-medium.text-gray-700 "Период:"]
   [:input#period-from.px-2.py-1.5.border.border-gray-300.rounded-md.text-sm.focus:outline-none.focus:ring-2.focus:ring-blue-500
    {:type "date" :name "from"}]
   [:span.text-gray-500 "—"]
   [:input#period-to.px-2.py-1.5.border.border-gray-300.rounded-md.text-sm.focus:outline-none.focus:ring-2.focus:ring-blue-500
    {:type "date" :name "to"}]
   [:button#period-apply.px-3.py-1.5.bg-blue-600.text-white.rounded-md.text-sm.font-medium.hover:bg-blue-700
    "Применить"]
   [:div.flex.items-center.gap-1.ml-2
    [:button.px-2.py-1.text-xs.border.border-gray-300.rounded.hover:bg-gray-100
     {:type "button" :data-preset "last-week"} "Пр. неделя"]
    [:button.px-2.py-1.text-xs.border.border-gray-300.rounded.hover:bg-gray-100
     {:type "button" :data-preset "last-7-days"} "7 дней"]
    [:button.px-2.py-1.text-xs.border.border-gray-300.rounded.hover:bg-gray-100
     {:type "button" :data-preset "last-30-days"} "30 дней"]
    [:button.px-2.py-1.text-xs.border.border-gray-300.rounded.hover:bg-gray-100
     {:type "button" :data-preset "this-month"} "Месяц"]]])

(defn- header
  "Render the header with title, period selector, sync button, and last sync time."
  []
  [:header.bg-white.shadow-sm.border-b.border-gray-200.px-4.lg:px-6.py-4
   [:div.flex.flex-col.lg:flex-row.items-start.lg:items-center.justify-between.gap-4
    [:div.flex.flex-col.sm:flex-row.items-start.sm:items-center.gap-4.lg:gap-6.w-full.lg:w-auto
     [:h1.text-xl.lg:text-2xl.font-bold.text-gray-900 "Analitica"]
     (period-selector)]
    [:div.flex.flex-col.sm:flex-row.items-start.sm:items-center.gap-3.lg:gap-4.w-full.lg:w-auto
     [:button.px-4.py-2.bg-blue-600.text-white.rounded-md.hover:bg-blue-700.transition-colors.text-sm.font-medium.w-full.sm:w-auto
      {:hx-post "/api/sync/start"
       :hx-vals (json/write-value-as-string {:what "all" :period "last-30-days"})
       :hx-swap "none"
       "hx-on:htmx:responseError" "if(event.detail.xhr.status === 409) { alert('Синхронизация уже запущена. Дождитесь завершения текущей синхронизации.'); }"}
      "Sync All (WB)"]
     [:div.text-xs.lg:text-sm.text-gray-600
      [:span "Последняя синхронизация: "]
      [:span#last-sync-time.font-medium "—"]]]]])

;; ---------------------------------------------------------------------------
;; Main Layout
;; ---------------------------------------------------------------------------

(defn page
  "Main page layout with sidebar, header, and content area.
  
  Parameters:
  - title: Page title (string)
  - content: Hiccup content vector
  - options:
    - :active-route - Current route for sidebar highlighting (default: slash)
  
  Example:
    (page \"Dashboard\" [:div \"Content\"] :active-route \"/\")"
  [title content & {:keys [active-route] :or {active-route "/"}}]
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
       (header)
       [:main#main-content.bg-gray-50
        content]]
     (components/drill-panel {})]
     
     ;; Initialize period selector from localStorage
     [:script "
       (function() {
         const select = document.getElementById('period-select');
         const saved = localStorage.getItem('analitica-period');
         if (saved && select) {
           select.value = saved;
         }
         if (select) {
           select.addEventListener('change', function() {
             localStorage.setItem('analitica-period', this.value);
           });
         }
       })();
     "]]))
