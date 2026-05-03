(ns marker.pages.pulse
  "Pulse Dashboard — Phase 5.
   Driven entirely by marker.mock (deterministic seed data).
   Real API wiring happens in Phase 8.

   Layout:
     1. Alerts strip
     2. KPI grid (8 cards × 2 rows)
     3. Plan-vs-Fact card  +  Donut  (col-8 / col-4, inside grid-12)
        The revenue line chart lives *inside* the plan-fact card.
     4. Stacked-bar orders  +  Top-movers/fallers tabs  (col-7 / col-5)
     5. Critical stocks table

   Chart.js: imported as chart.js/auto (registers all controllers).
   Each canvas ref lives in its own defui so use-effect/use-ref hooks
   are scoped correctly."
  (:require ["chart.js/auto" :refer [Chart]]
            [uix.core :refer [$ defui use-state use-effect use-ref use-memo]]
            [uix.re-frame :refer [use-subscribe]]
            [marker.state.subs     :as subs]
            [marker.ui.chrome      :refer [sparkline delta mp-badge]]
            [marker.ui.icons       :refer [icon]]
            [marker.mock           :as mock]
            [marker.util.format    :as fmt]))

;; ---------------------------------------------------------------------------
;; Chart colour constants
;; ---------------------------------------------------------------------------

(def ^:private wb-color   "#4f46e5")
(def ^:private ozon-color "#0891b2")
(def ^:private ym-color   "#ca8a04")
(def ^:private mp-colors  {:wb wb-color :ozon ozon-color :ym ym-color})

(def ^:private day-labels
  (mapv #(str (-> (inc %) str (.padStart 2 "0")) ".05") (range 30)))

(defn- css-var [name]
  (let [v (.getPropertyValue (js/getComputedStyle js/document.documentElement) name)]
    (if (seq (.trim v)) (.trim v) nil)))

;; ---------------------------------------------------------------------------
;; Alert card
;; ---------------------------------------------------------------------------

(defui ^:private alert-card [{:keys [kind title body cta]}]
  (let [icon-name (case kind "danger" :danger "warning" :warning :info)]
    ($ :div {:class (str "alert alert-" kind)}
       ($ icon {:name icon-name :class "alert-icon"})
       ($ :div {:class "alert-body"}
          ($ :div {:class "alert-title"} title)
          ($ :div body))
       ($ :button {:class "btn btn-ghost btn-sm"
                   :style {:color "inherit" :border "1px solid currentColor"}}
          cta))))

;; ---------------------------------------------------------------------------
;; KPI card
;; ---------------------------------------------------------------------------

(defui ^:private kpi-card [{:keys [label value delta-pct sub spark compare? inverted?]}]
  ($ :div {:class "kpi" :style {:position "relative"}}
     ($ :div {:class "kpi-label"} label)
     ($ :div {:class "kpi-value"} value)
     ($ :div {:class "kpi-foot"}
        (when compare?
          ($ delta {:pct delta-pct :inverted inverted?}))
        (when compare?
          ($ :span {:style {:color "var(--color-fg-muted)"}} sub))
        (when-not compare?
          ($ :span {:style {:color "var(--color-fg-muted)"}}
             (when (= label "Выручка") "за 30 дней"))))
     (when spark
       ($ :div {:style {:position "absolute" :right 14 :top 14}}
          ($ sparkline {:data spark :width 72 :height 26})))))

;; ---------------------------------------------------------------------------
;; Revenue line chart (lives inside plan-fact card)
;; ---------------------------------------------------------------------------

(defui ^:private revenue-chart [{:keys [compare? mp-filter]}]
  (let [canvas-ref (use-ref nil)]
    (use-effect
     (fn []
       (when-let [canvas @canvas-ref]
         (let [fg-muted      (or (css-var "--color-fg-muted")      "#94a3b8")
               border-subtle (or (css-var "--color-border-subtle") "#e2e8f0")
               bg-subtle     (or (css-var "--color-bg-subtle")     "#f1f5f9")
               base-font     #js{:family "Inter" :size 11}
               common-opts   #js{:responsive         true
                                 :maintainAspectRatio false
                                 :plugins #js{:legend #js{:position "bottom"
                                                          :align    "start"
                                                          :labels   #js{:boxWidth 10
                                                                        :boxHeight 10
                                                                        :padding  14
                                                                        :font     base-font
                                                                        :color    "#475569"}}
                                              :tooltip #js{:backgroundColor "#0f172a"
                                                           :titleColor      "#fff"
                                                           :bodyColor       "#cbd5e1"
                                                           :borderColor     "#334155"
                                                           :borderWidth     1
                                                           :cornerRadius    6
                                                           :padding         10
                                                           :titleFont       #js{:family "Inter" :size 11 :weight 600}
                                                           :bodyFont        base-font}}
                                 :scales #js{:x #js{:grid   #js{:display false}
                                                    :ticks  #js{:font base-font :color fg-muted :maxTicksLimit 10}
                                                    :border #js{:color border-subtle}}
                                             :y #js{:grid   #js{:color bg-subtle :drawBorder false}
                                                    :ticks  #js{:font base-font :color fg-muted
                                                                :callback (fn [v] (fmt/format-short v))}
                                                    :border #js{:display false}
                                                    :beginAtZero true}}}
               datasets      (clj->js
                              (cond-> [{:label           "Выручка"
                                        :data            mock/revenue-series
                                        :borderColor     "#4f46e5"
                                        :backgroundColor "rgba(79,70,229,0.12)"
                                        :fill            true
                                        :tension         0.3
                                        :borderWidth     2
                                        :pointRadius     0
                                        :pointHoverRadius 4}]
                                compare?
                                (conj {:label       "Пред. период"
                                       :data        (map-indexed
                                                     (fn [i v] (* v (+ 0.85 (* (mod i 5) 0.02))))
                                                     mock/revenue-series)
                                       :borderColor  "#94a3b8"
                                       :borderDash   [4 4]
                                       :borderWidth  1.5
                                       :pointRadius  0
                                       :fill         false
                                       :tension      0.3})))
               chart         (Chart. canvas
                                     #js{:type "line"
                                         :data #js{:labels   (clj->js day-labels)
                                                   :datasets datasets}
                                         :options common-opts})]
           (fn [] (.destroy chart)))))
     [compare? mp-filter])
    ($ :canvas {:ref canvas-ref})))

;; ---------------------------------------------------------------------------
;; Stacked bar — orders by MP
;; ---------------------------------------------------------------------------

(defui ^:private orders-bar [{:keys [mp-filter compare?]}]
  (let [canvas-ref (use-ref nil)]
    (use-effect
     (fn []
       (when-let [canvas @canvas-ref]
         (let [fg-muted      (or (css-var "--color-fg-muted")      "#94a3b8")
               border-subtle (or (css-var "--color-border-subtle") "#e2e8f0")
               bg-subtle     (or (css-var "--color-bg-subtle")     "#f1f5f9")
               base-font     #js{:family "Inter" :size 11}
               active?       (fn [mp] (some #{mp} mp-filter))
               wb-data       (mapv #(if (active? :wb)   (* % 0.55) 0) mock/orders-series)
               oz-data       (mapv #(if (active? :ozon) (* % 0.30) 0) mock/orders-series)
               ym-data       (mapv #(if (active? :ym)   (* % 0.15) 0) mock/orders-series)
               datasets      (clj->js
                              [{:label           "WB"
                                :data            wb-data
                                :backgroundColor wb-color
                                :stack           "s"
                                :borderRadius    2
                                :barThickness    10}
                               {:label           "Ozon"
                                :data            oz-data
                                :backgroundColor ozon-color
                                :stack           "s"
                                :borderRadius    2
                                :barThickness    10}
                               {:label           "YM"
                                :data            ym-data
                                :backgroundColor ym-color
                                :stack           "s"
                                :borderRadius    2
                                :barThickness    10}])
               chart         (Chart. canvas
                                     #js{:type "bar"
                                         :data #js{:labels   (clj->js day-labels)
                                                   :datasets datasets}
                                         :options #js{:responsive         true
                                                      :maintainAspectRatio false
                                                      :plugins #js{:legend #js{:position "bottom"
                                                                               :align    "start"
                                                                               :labels   #js{:boxWidth 10 :boxHeight 10 :padding 14
                                                                                             :font     base-font :color "#475569"}}
                                                                   :tooltip #js{:backgroundColor "#0f172a"
                                                                                :titleColor      "#fff"
                                                                                :bodyColor       "#cbd5e1"
                                                                                :borderColor     "#334155"
                                                                                :borderWidth     1
                                                                                :cornerRadius    6
                                                                                :padding         10
                                                                                :titleFont       #js{:family "Inter" :size 11 :weight 600}
                                                                                :bodyFont        base-font}}
                                                      :scales #js{:x #js{:stacked true
                                                                         :grid    #js{:display false}
                                                                         :ticks   #js{:font base-font :color fg-muted :maxTicksLimit 10}
                                                                         :border  #js{:color border-subtle}}
                                                                  :y #js{:stacked     true
                                                                         :grid        #js{:color bg-subtle :drawBorder false}
                                                                         :ticks       #js{:font base-font :color fg-muted
                                                                                          :callback (fn [v] (fmt/format-short v))}
                                                                         :border      #js{:display false}
                                                                         :beginAtZero true}}}})]
           (fn [] (.destroy chart)))))
     [mp-filter compare?])
    ($ :canvas {:ref canvas-ref})))

;; ---------------------------------------------------------------------------
;; MP share donut
;; ---------------------------------------------------------------------------

(defui ^:private mp-donut [{:keys [mp-filter]}]
  (let [canvas-ref (use-ref nil)]
    (use-effect
     (fn []
       (when-let [canvas @canvas-ref]
         (let [active?  (fn [mp] (some #{mp} mp-filter))
               wb-val   (if (active? :wb)   62 0)
               oz-val   (if (active? :ozon) 26 0)
               ym-val   (if (active? :ym)   12 0)
               base-font #js{:family "Inter" :size 11}
               chart    (Chart. canvas
                                #js{:type "doughnut"
                                    :data #js{:labels   #js["WB" "Ozon" "YM"]
                                              :datasets #js[#js{:data            #js[wb-val oz-val ym-val]
                                                                 :backgroundColor #js[wb-color ozon-color ym-color]
                                                                 :borderWidth     0
                                                                 :hoverOffset     6}]}
                                    :options #js{:responsive         true
                                                 :maintainAspectRatio false
                                                 :cutout             "70%"
                                                 :plugins #js{:legend #js{:position "bottom"
                                                                           :align    "start"
                                                                           :labels   #js{:boxWidth 10 :boxHeight 10 :padding 14
                                                                                         :font     base-font :color "#475569"}}
                                                              :tooltip #js{:backgroundColor "#0f172a"
                                                                           :titleColor      "#fff"
                                                                           :bodyColor       "#cbd5e1"
                                                                           :borderColor     "#334155"
                                                                           :borderWidth     1
                                                                           :cornerRadius    6
                                                                           :padding         10
                                                                           :titleFont       #js{:family "Inter" :size 11 :weight 600}
                                                                           :bodyFont        base-font}}
                                                 :scales #js{}}})]
           (fn [] (.destroy chart)))))
     [mp-filter])
    ($ :canvas {:ref canvas-ref})))

;; ---------------------------------------------------------------------------
;; Plan-fact card
;; ---------------------------------------------------------------------------

(defui ^:private plan-fact-card [{:keys [compare? mp-filter]}]
  (let [plan        (:month-plan  mock/forecast)
        fact        (:month-fact  mock/forecast)
        projection  (:projection  mock/forecast)
        plan-pct    (js/Math.round (* 100 (/ fact plan)))
        proj-pct    (js/Math.round (* 100 (/ projection plan)))]
    ($ :section {:class "card section-card col-8"}
       ($ :div {:class "section-head"}
          ($ :div
             ($ :h3 {:class "section-title"} "План — факт. Прибыль за май 2026")
             ($ :div {:class "section-subtitle"}
                (str "Pace: " plan-pct "% · прогноз достижения " proj-pct "% от цели")))
          ($ :span {:class "badge badge-warning"} "⚠ Цель будет не достигнута"))
       ($ :div {:style {:display "flex" :align-items "baseline" :gap "16px" :margin-bottom "12px"}}
          ($ :div {:style {:font-size "32px" :font-weight 700 :letter-spacing "-.01em"}}
             (fmt/format-rub fact))
          ($ :div {:style {:color "var(--color-fg-muted)"}}
             (str "из " (fmt/format-rub plan) " плана"))
          ($ :div {:class "spacer"})
          ($ :div {:style {:font-size "14px" :font-weight 600}} (str plan-pct "%")))
       ($ :div {:class "progress warning"}
          ($ :div {:style {:width (str plan-pct "%")}}))
       ($ :div {:style {:display         "flex"
                        :justify-content "space-between"
                        :margin-top      "8px"
                        :font-size       "12px"
                        :color           "var(--color-fg-muted)"}}
          ($ :span "0")
          ($ :span "50%")
          ($ :span "100% цель"))
       ($ :div {:style {:margin-top "18px" :height "220px"}}
          ($ revenue-chart {:compare? compare? :mp-filter mp-filter})))))

;; ---------------------------------------------------------------------------
;; MP structure card (donut + legend)
;; ---------------------------------------------------------------------------

(defui ^:private mp-structure-card [{:keys [mp-filter]}]
  (let [mp-rows [{:mp :wb   :label "Wildberries" :val 62 :sum 5220400}
                 {:mp :ozon :label "Ozon"        :val 26 :sum 2189200}
                 {:mp :ym   :label "YM"          :val 12 :sum 1010400}]]
    ($ :section {:class "card section-card col-4"}
       ($ :div {:class "section-head"}
          ($ :div
             ($ :h3 {:class "section-title"} "Структура выручки")
             ($ :div {:class "section-subtitle"} "по маркетплейсам")))
       ($ :div {:style {:height "180px" :position "relative"}}
          ($ mp-donut {:mp-filter mp-filter}))
       ($ :div {:style {:display "flex" :flex-direction "column" :gap "6px" :margin-top "14px"}}
          (for [{:keys [mp label val sum]} mp-rows]
            ($ :div {:key  (name mp)
                     :style {:display     "flex"
                             :align-items "center"
                             :gap         "8px"
                             :font-size   "12px"
                             :opacity     (if (some #{mp} mp-filter) "1" "0.35")}}
               ($ mp-badge {:mp mp})
               ($ :span {:style {:flex 1}} label)
               ($ :span {:class "mono"} (fmt/format-rub sum))
               ($ :span {:class "mono"
                         :style {:color "var(--color-fg-muted)" :min-width "36px" :text-align "right"}}
                  (str val "%"))))))))

;; ---------------------------------------------------------------------------
;; Top movers/fallers tab content
;; ---------------------------------------------------------------------------

(defui ^:private top-table [{:keys [skus]}]
  ($ :div {:style {:margin-top "12px"}}
     (for [s skus]
       ($ :div {:key   (:id s)
                :style {:display        "flex"
                        :align-items    "center"
                        :gap            "10px"
                        :padding        "10px 4px"
                        :border-bottom  "1px solid var(--color-border-subtle)"
                        :cursor         "pointer"}}
          ($ :div {:style {:display        "flex"
                           :flex-direction "column"
                           :gap            "2px"
                           :min-width      0
                           :flex           1}}
             ($ :div {:class "row"}
                ($ :span {:class "mono"
                          :style {:font-size "12px" :color "var(--color-fg-muted)"}}
                   (:id s))
                (for [m (:mp s)]
                  ($ mp-badge {:key (name m) :mp m})))
             ($ :div {:style {:font-size "13px" :font-weight 500}} (:name s)))
          ($ sparkline {:data (:spark s)})
          ($ :div {:style {:display        "flex"
                           :flex-direction "column"
                           :align-items    "flex-end"
                           :min-width      "90px"}}
             ($ :span {:class "mono"
                       :style {:font-size "13px" :font-weight 600}}
                (fmt/format-rub (:revenue s)))
             ($ delta {:pct (:delta-pct s)}))))))

;; ---------------------------------------------------------------------------
;; Tabs (movers / fallers)
;; ---------------------------------------------------------------------------

(defui ^:private movers-tabs [{:keys [mp-filter]}]
  (let [[active set-active!] (use-state "movers")
        filtered-skus        (use-memo
                              (fn []
                                {:movers (filterv #(some (set mp-filter) (:mp %)) mock/top-movers)
                                 :fallers (filterv #(some (set mp-filter) (:mp %)) mock/top-fallers)})
                              [mp-filter])
        tab-skus             (get filtered-skus (keyword active))]
    ($ :section {:class "card section-card col-5"}
       ($ :div {:class "tabs"}
          (for [{:keys [id label]} [{:id "movers" :label "Топ роста"} {:id "fallers" :label "Топ падения"}]]
            ($ :button {:key      id
                        :class    (str "tab" (when (= active id) " active"))
                        :on-click #(set-active! id)}
               label
               ($ :span {:class "tab-counter"} 5))))
       ($ top-table {:skus tab-skus}))))

;; ---------------------------------------------------------------------------
;; Critical stocks table
;; ---------------------------------------------------------------------------

(defui ^:private critical-stocks [{:keys [mp-filter]}]
  (let [rows (use-memo
              (fn []
                (->> mock/skus
                     (filterv #(some (set mp-filter) (:mp %)))
                     (sort-by :stock)
                     (take 7)))
              [mp-filter])]
    ($ :section {:class "card section-card"}
       ($ :div {:class "section-head"}
          ($ :div
             ($ :h3 {:class "section-title"} "Остатки — критично")
             ($ :div {:class "section-subtitle"} "артикулы, которые кончатся за ≤ 14 дней"))
          ($ :button {:class "btn btn-secondary btn-sm"} "Все остатки →"))
       ($ :table {:class "tbl"}
          ($ :thead
             ($ :tr
                ($ :th "Артикул")
                ($ :th "МП")
                ($ :th {:class "num"} "Остаток, шт")
                ($ :th {:class "num"} "Скорость / день")
                ($ :th {:class "num"} "Дней до 0")
                ($ :th "Статус")))
          ($ :tbody
             (for [s rows]
               (let [speed  (max 2 (js/Math.round (/ (:orders s) 30)))
                     days   (js/Math.round (/ (:stock s) speed))
                     status (cond (< days 4) "danger"
                                  (< days 8) "warning"
                                  :else      "success")]
                 ($ :tr {:key (:id s) :style {:cursor "pointer"}}
                    ($ :td
                       ($ :span {:class "tbl-link"} (:id s))
                       " · "
                       ($ :span {:style {:color "var(--color-fg-secondary)"}} (:name s)))
                    ($ :td
                       (for [m (:mp s)]
                         ($ mp-badge {:key (name m) :mp m})))
                    ($ :td {:class "num mono"} (fmt/format-int (:stock s)))
                    ($ :td {:class "num mono"} speed)
                    ($ :td {:class "num mono"} days)
                    ($ :td
                       ($ :span {:class (str "badge badge-" status)}
                          (case status "danger" "Критично" "warning" "Низкий" "OK")))))))))))

;; ---------------------------------------------------------------------------
;; KPI section
;; ---------------------------------------------------------------------------

(defui ^:private kpi-section [{:keys [compare?]}]
  (let [totals (use-memo
                (fn []
                  (let [sum #(reduce + %)]
                    {:revenue (sum mock/revenue-series)
                     :profit  (sum mock/profit-series)
                     :orders  (js/Math.round (sum mock/orders-series))
                     :ads     (sum mock/ads-series)}))
                [])
        kpis   [{:label    "Выручка"
                 :value    (fmt/format-rub (:revenue totals))
                 :delta    12.4
                 :spark    mock/revenue-series
                 :sub      "WoW"}
                {:label    "Чистая прибыль"
                 :value    (fmt/format-rub (:profit totals))
                 :delta    8.2
                 :spark    mock/profit-series
                 :sub      "WoW"}
                {:label    "Заказы"
                 :value    (str (fmt/format-int (:orders totals)) " шт")
                 :delta    5.8
                 :spark    mock/orders-series
                 :sub      "WoW"}
                {:label    "Маржа"
                 :value    (fmt/format-pct 34.2)
                 :delta    -2.1
                 :spark    mock/profit-series
                 :sub      "WoW"}
                {:label    "Средний чек"
                 :value    (fmt/format-rub 2840)
                 :delta    1.4
                 :sub      "WoW"}
                {:label    "Выкуп"
                 :value    (fmt/format-pct 78.4)
                 :delta    0.8
                 :sub      "WoW"}
                {:label    "ROAS"
                 :value    (fmt/format-mul 3.4)
                 :delta    -0.6
                 :sub      "WoW"}
                {:label    "ДРР"
                 :value    (fmt/format-pct 11.2)
                 :delta    1.2
                 :sub      "WoW"
                 :inverted? true}]]
    ($ :section {:class "card section-card"}
       ($ :div {:class "section-head"}
          ($ :div
             ($ :h3 {:class "section-title"} "Ключевые метрики")
             (when compare?
               ($ :div {:class "section-subtitle"} "vs предыдущий период (24.04 — 23.05)")))
          ($ :div {:class "row"}
             ($ :span {:class "badge badge-success"}
                ($ :span {:class "dot-status green"})
                " Данные на 03.05.2026 11:24")))
       ($ :div {:class "kpi-grid"}
          (for [{:keys [label value delta sub spark inverted?]} kpis]
            ($ kpi-card {:key       label
                         :label     label
                         :value     value
                         :delta-pct delta
                         :sub       sub
                         :spark     spark
                         :compare?  compare?
                         :inverted? (boolean inverted?)}))))))

;; ---------------------------------------------------------------------------
;; Empty state
;; ---------------------------------------------------------------------------

(defui ^:private empty-state []
  ($ :div {:style {:display         "flex"
                   :justify-content "center"
                   :align-items     "center"
                   :padding         "80px 32px"
                   :color           "var(--color-fg-muted)"
                   :font-size       "14px"
                   :text-align      "center"}}
     "Нет выбранных маркетплейсов — включите хотя бы один в фильтре"))

;; ---------------------------------------------------------------------------
;; Top-level Pulse page
;; ---------------------------------------------------------------------------

(defui pulse []
  (let [mp-filter (use-subscribe [::subs/mp-filter])
        compare?  (use-subscribe [::subs/compare])
        _density  (use-subscribe [::subs/density])]   ; consumed so density changes re-render
    ($ :div {:class "page-content"}
       ;; Alerts
       ($ :div {:style {:display "flex" :flex-direction "column" :gap "10px"}}
          (for [a mock/alerts]
            ($ alert-card {:key   (:title a)
                           :kind  (:kind a)
                           :title (:title a)
                           :body  (:body a)
                           :cta   (:cta a)})))

       (if (empty? mp-filter)
         ($ empty-state)
         ($ :<>
            ;; KPI grid
            ($ kpi-section {:compare? compare?})

            ;; Plan-fact + Donut
            ($ :div {:class "grid-12"}
               ($ plan-fact-card {:compare? compare? :mp-filter mp-filter})
               ($ mp-structure-card {:mp-filter mp-filter}))

            ;; Bar chart + Tabs
            ($ :div {:class "grid-12"}
               ($ :section {:class "card section-card col-7"}
                  ($ :div {:class "section-head"}
                     ($ :div
                        ($ :h3 {:class "section-title"} "Заказы по дням")
                        ($ :div {:class "section-subtitle"} "stack по маркетплейсам"))
                     ($ :div {:class "row"}
                        ($ :button {:class "icon-btn" :title "Развернуть"}
                           ($ icon {:name :expand}))
                        ($ :button {:class "icon-btn"}
                           ($ icon {:name :more-h}))))
                  ($ :div {:style {:height "240px"}}
                     ($ orders-bar {:mp-filter mp-filter :compare? compare?})))
               ($ movers-tabs {:mp-filter mp-filter}))

            ;; Critical stocks
            ($ critical-stocks {:mp-filter mp-filter}))))))
