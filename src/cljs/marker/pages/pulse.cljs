(ns marker.pages.pulse
  "Pulse Dashboard — Phase 8.
   Data comes from the live /api/v1/marker/pulse-summary endpoint via
   ::events/load-pulse.  marker.mock is kept as a fallback shape reference
   but is NOT the source of truth for any rendered value.

   Loading skeleton: when ::subs/pulse-data is nil, .skel placeholders render
   in place of real content."
  (:require ["chart.js/auto" :refer [Chart]]
            [uix.core :refer [$ defui use-state use-effect use-ref use-memo]]
            [uix.re-frame :refer [use-subscribe]]
            [re-frame.core :as rf]
            [marker.state.subs     :as subs]
            [marker.state.events   :as events]
            [marker.ui.chrome      :refer [sparkline delta mp-badge kpi-card]]
            [marker.ui.icons       :refer [icon]]
            [marker.util.format    :as fmt]))

;; ---------------------------------------------------------------------------
;; Chart colour constants
;; ---------------------------------------------------------------------------

(def ^:private wb-color   "#4f46e5")
(def ^:private ozon-color "#0891b2")
(def ^:private ym-color   "#ca8a04")

(def ^:private day-labels
  (mapv #(str (-> (inc %) str (.padStart 2 "0")) ".05") (range 30)))

(defn- css-var [name]
  (let [v (.getPropertyValue (js/getComputedStyle js/document.documentElement) name)]
    (if (seq (.trim v)) (.trim v) nil)))

;; ---------------------------------------------------------------------------
;; Safe accessor helpers — guard nil / empty backend stubs
;; ---------------------------------------------------------------------------

(defn- safe-spark
  "Return spark data as-is, or an empty vec; Chart.js handles empty arrays gracefully."
  [v]
  (if (seq v) v []))

(defn- safe-num
  "Return number, or 0 if nil/NaN."
  [v]
  (if (and (some? v) (not (js/isNaN v))) v 0))

(defn- or-ndash
  "Return formatted string, or '—' when value is nil."
  [v fmt-fn]
  (if (nil? v) "—" (fmt-fn v)))

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
;; Revenue line chart
;; ---------------------------------------------------------------------------

(defui ^:private revenue-chart [{:keys [compare? rev-spark rev-prev-spark]}]
  (let [canvas-ref (use-ref nil)]
    (use-effect
     (fn []
       (when-let [canvas @canvas-ref]
         (let [fg-muted      (or (css-var "--color-fg-muted")      "#94a3b8")
               border-subtle (or (css-var "--color-border-subtle") "#e2e8f0")
               bg-subtle     (or (css-var "--color-bg-subtle")     "#f1f5f9")
               base-font     #js{:family "Inter" :size 11}
               data          (safe-spark rev-spark)
               prev-data     (safe-spark rev-prev-spark)
               labels        (if (seq data)
                               (mapv #(str (-> (inc %) str (.padStart 2 "0")) ".05")
                                     (range (count data)))
                               day-labels)
               datasets      (clj->js
                              (cond-> [{:label           "Выручка"
                                        :data            data
                                        :borderColor     "#4f46e5"
                                        :backgroundColor "rgba(79,70,229,0.12)"
                                        :fill            true
                                        :tension         0.3
                                        :borderWidth     2
                                        :pointRadius     0
                                        :pointHoverRadius 4}]
                                (and compare? (seq prev-data))
                                (conj {:label       "Пред. период"
                                       :data        prev-data
                                       :borderColor  "#94a3b8"
                                       :borderDash   [4 4]
                                       :borderWidth  1.5
                                       :pointRadius  0
                                       :fill         false
                                       :tension      0.3})))
               chart         (Chart. canvas
                                     #js{:type "line"
                                         :data #js{:labels   (clj->js labels)
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
                                                      :scales #js{:x #js{:grid   #js{:display false}
                                                                         :ticks  #js{:font base-font :color fg-muted :maxTicksLimit 10}
                                                                         :border #js{:color border-subtle}}
                                                                  :y #js{:grid   #js{:color bg-subtle :drawBorder false}
                                                                         :ticks  #js{:font base-font :color fg-muted
                                                                                     :callback (fn [v] (fmt/format-short v))}
                                                                         :border #js{:display false}
                                                                         :beginAtZero true}}}})]
           (fn [] (.destroy chart)))))
     [rev-spark rev-prev-spark compare?])
    ($ :canvas {:ref canvas-ref})))

;; ---------------------------------------------------------------------------
;; Stacked bar — orders by MP
;; ---------------------------------------------------------------------------

(defui ^:private orders-bar [{:keys [mp-filter orders-by-mp]}]
  (let [canvas-ref (use-ref nil)]
    (use-effect
     (fn []
       (when-let [canvas @canvas-ref]
         (let [fg-muted      (or (css-var "--color-fg-muted")      "#94a3b8")
               border-subtle (or (css-var "--color-border-subtle") "#e2e8f0")
               bg-subtle     (or (css-var "--color-bg-subtle")     "#f1f5f9")
               base-font     #js{:family "Inter" :size 11}
               active?       (fn [mp] (some #{mp} mp-filter))
               ;; orders-by-mp may be nil when loading or all-filtered
               wb-data   (when (active? :wb)   (safe-spark (:wb orders-by-mp)))
               oz-data   (when (active? :ozon) (safe-spark (:ozon orders-by-mp)))
               ym-data   (when (active? :ym)   (safe-spark (:ym orders-by-mp)))
               n         (max (count wb-data) (count oz-data) (count ym-data) 30)
               zero-pad  (fn [v] (or v (vec (repeat n 0))))
               labels    (mapv #(str (-> (inc %) str (.padStart 2 "0")) ".05") (range n))
               datasets  (clj->js
                          [{:label           "WB"
                            :data            (zero-pad wb-data)
                            :backgroundColor wb-color
                            :stack           "s"
                            :borderRadius    2
                            :barThickness    10}
                           {:label           "Ozon"
                            :data            (zero-pad oz-data)
                            :backgroundColor ozon-color
                            :stack           "s"
                            :borderRadius    2
                            :barThickness    10}
                           {:label           "YM"
                            :data            (zero-pad ym-data)
                            :backgroundColor ym-color
                            :stack           "s"
                            :borderRadius    2
                            :barThickness    10}])
               chart     (Chart. canvas
                                 #js{:type "bar"
                                     :data #js{:labels   (clj->js labels)
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
     [mp-filter orders-by-mp])
    ($ :canvas {:ref canvas-ref})))

;; ---------------------------------------------------------------------------
;; MP share donut
;; ---------------------------------------------------------------------------

(defui ^:private mp-donut [{:keys [mp-filter mp-share]}]
  (let [canvas-ref (use-ref nil)]
    (use-effect
     (fn []
       (when-let [canvas @canvas-ref]
         (let [active?  (fn [mp] (some #{mp} mp-filter))
               wb-val   (if (active? :wb)   (or (:wb mp-share) 0)   0)
               oz-val   (if (active? :ozon) (or (:ozon mp-share) 0) 0)
               ym-val   (if (active? :ym)   (or (:ym mp-share) 0)   0)
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
     [mp-filter mp-share])
    ($ :canvas {:ref canvas-ref})))

;; ---------------------------------------------------------------------------
;; Plan-fact card
;; ---------------------------------------------------------------------------

(defui ^:private plan-fact-card [{:keys [compare? mp-filter forecast rev-spark rev-prev-spark]}]
  (let [plan       (:month-plan  forecast)
        fact       (safe-num (:month-fact forecast))
        projection (safe-num (:projection forecast))
        ;; When no plan in DB, show projection only
        plan-pct   (if (and plan (pos? plan))
                     (js/Math.round (* 100 (/ fact plan)))
                     nil)
        proj-pct   (if (and plan (pos? plan))
                     (js/Math.round (* 100 (/ projection plan)))
                     nil)
        ;; progress class driven by pace
        prog-class (cond
                     (nil? plan-pct) "progress"
                     (< plan-pct 70)  "progress danger"
                     (< plan-pct 95)  "progress warning"
                     :else            "progress success")]
    ($ :section {:class "card section-card col-8"}
       ($ :div {:class "section-head"}
          ($ :div
             ($ :h3 {:class "section-title"} "Выручка — динамика")
             ($ :div {:class "section-subtitle"}
                (cond
                  (and plan-pct proj-pct)
                  (str "Pace: " plan-pct "% · прогноз " proj-pct "% от цели")
                  projection
                  (str "Прогноз: " (fmt/format-rub projection))
                  :else "текущий период")))
          (when (and plan-pct (< plan-pct 95))
            ($ :span {:class "badge badge-warning"} "⚠ Цель будет не достигнута")))
       ($ :div {:style {:display "flex" :align-items "baseline" :gap "16px" :margin-bottom "12px"}}
          ($ :div {:style {:font-size "32px" :font-weight 700 :letter-spacing "-.01em"}}
             (fmt/format-rub fact))
          (when plan
            ($ :div {:style {:color "var(--color-fg-muted)"}}
               (str "из " (fmt/format-rub plan) " плана")))
          ($ :div {:class "spacer"})
          (when plan-pct
            ($ :div {:style {:font-size "14px" :font-weight 600}} (str plan-pct "%"))))
       (when plan-pct
         ($ :<>
            ($ :div {:class prog-class}
               ($ :div {:style {:width (str plan-pct "%")}}))
            ($ :div {:style {:display         "flex"
                             :justify-content "space-between"
                             :margin-top      "8px"
                             :font-size       "12px"
                             :color           "var(--color-fg-muted)"}}
               ($ :span "0")
               ($ :span "50%")
               ($ :span "100% цель"))))
       ($ :div {:style {:margin-top "18px" :height "220px"}}
          ($ revenue-chart {:compare?      compare?
                            :rev-spark     rev-spark
                            :rev-prev-spark rev-prev-spark})))))

;; ---------------------------------------------------------------------------
;; MP structure card (donut + legend)
;; ---------------------------------------------------------------------------

(defui ^:private mp-structure-card [{:keys [mp-filter mp-share]}]
  (let [share (or mp-share {:wb 0 :ozon 0 :ym 0})
        total (max 1 (+ (or (:wb share) 0) (or (:ozon share) 0) (or (:ym share) 0)))
        mp-rows [{:mp :wb   :label "Wildberries" :val (or (:wb share) 0)}
                 {:mp :ozon :label "Ozon"        :val (or (:ozon share) 0)}
                 {:mp :ym   :label "YM"          :val (or (:ym share) 0)}]]
    ($ :section {:class "card section-card col-4"}
       ($ :div {:class "section-head"}
          ($ :div
             ($ :h3 {:class "section-title"} "Структура выручки")
             ($ :div {:class "section-subtitle"} "по маркетплейсам")))
       ($ :div {:style {:height "180px" :position "relative"}}
          ($ mp-donut {:mp-filter mp-filter :mp-share share}))
       ($ :div {:style {:display "flex" :flex-direction "column" :gap "6px" :margin-top "14px"}}
          (for [{:keys [mp label val]} mp-rows]
            ($ :div {:key  (name mp)
                     :style {:display     "flex"
                             :align-items "center"
                             :gap         "8px"
                             :font-size   "12px"
                             :opacity     (if (some #{mp} mp-filter) "1" "0.35")}}
               ($ mp-badge {:mp mp})
               ($ :span {:style {:flex 1}} label)
               ($ :span {:class "mono"} (str (or val 0) "%"))))))))

;; ---------------------------------------------------------------------------
;; Top movers/fallers table
;; ---------------------------------------------------------------------------

(defui ^:private top-table [{:keys [items]}]
  ($ :div {:style {:margin-top "12px"}}
     (if (empty? items)
       ($ :div {:style {:color "var(--color-fg-muted)" :font-size "13px" :padding "16px 4px"}}
          "Нет данных")
       (for [s items]
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
               ($ :div {:style {:font-size "13px" :font-weight 500}}
                  (or (:name s) (:id s))))
            ($ sparkline {:data (safe-spark (:spark s))})
            ($ :div {:style {:display        "flex"
                             :flex-direction "column"
                             :align-items    "flex-end"
                             :min-width      "90px"}}
               ($ :span {:class "mono"
                         :style {:font-size "13px" :font-weight 600}}
                  (fmt/format-rub (safe-num (:revenue s))))
               ($ delta {:pct (:delta-pct s)})))))))

;; ---------------------------------------------------------------------------
;; Tabs (movers / fallers)
;; ---------------------------------------------------------------------------

(defui ^:private movers-tabs [{:keys [movers fallers]}]
  (let [[active set-active!] (use-state "movers")]
    ($ :section {:class "card section-card col-5"}
       ($ :div {:class "tabs"}
          (for [{:keys [id label items]} [{:id "movers"  :label "Топ роста"   :items movers}
                                          {:id "fallers" :label "Топ падения" :items fallers}]]
            ($ :button {:key      id
                        :class    (str "tab" (when (= active id) " active"))
                        :on-click #(set-active! id)}
               label
               ($ :span {:class "tab-counter"}
                  (count items)))))
       ($ top-table {:items (if (= active "movers") movers fallers)}))))

;; ---------------------------------------------------------------------------
;; Critical stocks table
;; ---------------------------------------------------------------------------

(defui ^:private critical-stocks-table [{:keys [rows]}]
  ($ :section {:class "card section-card"}
     ($ :div {:class "section-head"}
        ($ :div
           ($ :h3 {:class "section-title"} "Остатки — критично")
           ($ :div {:class "section-subtitle"} "артикулы, которые кончатся за ≤ 14 дней"))
        ($ :button {:class "btn btn-secondary btn-sm"} "Все остатки →"))
     (if (empty? rows)
       ($ :div {:style {:color "var(--color-fg-muted)" :font-size "13px" :padding "16px 12px"}}
          "Критичных остатков нет")
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
               (let [status (or (:status s)
                                (cond (< (:days s 0) 4) "danger"
                                      (< (:days s 0) 8) "warning"
                                      :else             "success"))]
                 ($ :tr {:key (:id s) :style {:cursor "pointer"}}
                    ($ :td
                       ($ :span {:class "tbl-link"} (:id s))
                       " · "
                       ($ :span {:style {:color "var(--color-fg-secondary)"}} (or (:name s) (:id s))))
                    ($ :td
                       (for [m (:mp s)]
                         ($ mp-badge {:key (name m) :mp m})))
                    ($ :td {:class "num mono"} (fmt/format-int (:stock s 0)))
                    ($ :td {:class "num mono"} (or (:speed s) "—"))
                    ($ :td {:class "num mono"} (:days s 0))
                    ($ :td
                       ($ :span {:class (str "badge badge-" status)}
                          (case status "danger" "Критично" "warning" "Низкий" "OK")))))))))))

;; ---------------------------------------------------------------------------
;; KPI section — reads from API :kpis map
;; ---------------------------------------------------------------------------

(defui ^:private kpi-section [{:keys [compare? kpis]}]
  (let [k      (or kpis {})
        rev    (:revenue k)
        profit (:profit k)
        orders (:orders k)
        margin (:margin k)
        check  (:avg-check k)
        buyout (:buyout k)
        roas   (:roas k)
        drr    (:drr k)
        cards  [{:label     "Выручка"
                 :value     (fmt/format-rub (safe-num (:value rev)))
                 :delta     (:delta-pct rev)
                 :spark     (safe-spark (:spark rev))
                 :sub       "WoW"}
                {:label     "Чистая прибыль"
                 :value     (fmt/format-rub (safe-num (:value profit)))
                 :delta     (:delta-pct profit)
                 :spark     (safe-spark (:spark profit))
                 :sub       "WoW"}
                {:label     "Заказы"
                 :value     (str (fmt/format-int (safe-num (:value orders))) " шт")
                 :delta     (:delta-pct orders)
                 :spark     (safe-spark (:spark orders))
                 :sub       "WoW"}
                {:label     "Маржа"
                 :value     (fmt/format-pct (safe-num (:value margin)))
                 :delta     (:delta-pct margin)
                 :sub       "WoW"}
                {:label     "Средний чек"
                 :value     (fmt/format-rub (safe-num (:value check)))
                 :delta     (:delta-pct check)
                 :sub       "WoW"}
                {:label     "Выкуп"
                 :value     (fmt/format-pct (safe-num (:value buyout)))
                 :delta     (:delta-pct buyout)
                 :sub       "WoW"}
                {:label     "ROAS"
                 :value     (or-ndash (:value roas) fmt/format-mul)
                 :delta     (:delta-pct roas)
                 :sub       "WoW"}
                {:label     "ДРР"
                 :value     (or-ndash (:value drr) fmt/format-pct)
                 :delta     (:delta-pct drr)
                 :sub       "WoW"
                 :inverted? true}]]
    ($ :section {:class "card section-card"}
       ($ :div {:class "section-head"}
          ($ :div
             ($ :h3 {:class "section-title"} "Ключевые метрики")
             (when compare?
               ($ :div {:class "section-subtitle"} "vs предыдущий период")))
          ($ :div {:class "row"}
             ($ :span {:class "badge badge-success"}
                ($ :span {:class "dot-status green"})
                " Данные загружены")))
       ($ :div {:class "kpi-grid"}
          (for [{:keys [label value delta sub spark inverted?]} cards]
            ($ kpi-card {:key       label
                         :label     label
                         :value     value
                         :delta-pct delta
                         :sub       sub
                         :spark     spark
                         :compare?  compare?
                         :inverted? (boolean inverted?)}))))))

;; ---------------------------------------------------------------------------
;; Loading skeleton helpers
;; ---------------------------------------------------------------------------

(defui ^:private skel-row []
  ($ :div {:class "skel" :style {:height "16px" :border-radius "4px" :margin-bottom "8px"}}))

(defui ^:private skel-card [{:keys [height]}]
  ($ :div {:class "skel card section-card"
           :style {:height (str (or height 80) "px")
                   :border-radius "var(--radius-lg)"}}))

(defui ^:private pulse-skeleton []
  ($ :div {:class "page-content"}
     ;; Alert skeletons
     ($ :div {:style {:display "flex" :flex-direction "column" :gap "10px"}}
        ($ skel-card {:height 56})
        ($ skel-card {:height 56})
        ($ skel-card {:height 56}))
     ;; KPI grid skeleton
     ($ :section {:class "card section-card"}
        ($ :div {:class "kpi-grid"}
           (for [i (range 8)]
             ($ :div {:key i :class "kpi"}
                ($ :div {:class "skel" :style {:height "12px" :width "60%" :margin-bottom "8px" :border-radius "4px"}})
                ($ :div {:class "skel" :style {:height "26px" :width "80%" :border-radius "4px"}})))))
     ;; Charts skeleton
     ($ :div {:class "grid-12"}
        ($ skel-card {:height 320})
        ($ skel-card {:height 320}))
     ;; Table skeleton
     ($ :section {:class "card section-card"}
        (for [i (range 5)]
          ($ skel-row {:key i})))))

;; ---------------------------------------------------------------------------
;; Error banner
;; ---------------------------------------------------------------------------

(defui ^:private error-banner [{:keys [message on-retry]}]
  ($ :div {:class "alert alert-danger"
           :style {:margin-bottom "12px"}}
     ($ icon {:name :danger :class "alert-icon"})
     ($ :div {:class "alert-body"}
        ($ :div {:class "alert-title"} "Не удалось загрузить данные")
        ($ :div (or message "Проверьте соединение с сервером.")))
     ($ :button {:class    "btn btn-ghost btn-sm"
                 :style    {:color "inherit" :border "1px solid currentColor"}
                 :on-click on-retry}
        "Повторить")))

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
  (let [mp-filter  (use-subscribe [::subs/mp-filter])
        period     (use-subscribe [::subs/period])
        compare?   (use-subscribe [::subs/compare])
        _density   (use-subscribe [::subs/density])
        data       (use-subscribe [::subs/pulse-data])
        loading?   (use-subscribe [::subs/pulse-loading?])
        api-errors (use-subscribe [::subs/api-errors])
        error-msg  (get-in api-errors ["/api/v1/marker/pulse-summary" :message])

        ;; Filter state map for dispatch
        fs {:mp-filter mp-filter :period period :compare compare?}]

    ;; On mount AND on filter/period/compare change — re-dispatch the load.
    ;; A single effect with deps fires on first render too, so a separate
    ;; `[]` mount effect would only cause a duplicate concurrent request.
    (use-effect
     (fn []
       (rf/dispatch [::events/load-pulse
                     {:mp-filter mp-filter :period period :compare compare?}])
       js/undefined)
     [mp-filter period compare?])

    (cond
      ;; No MP selected
      (empty? mp-filter)
      ($ :div {:class "page-content"} ($ empty-state))

      ;; Loading skeleton (first load only — data is nil)
      (and loading? (nil? data))
      ($ pulse-skeleton)

      ;; Error state with no previously-loaded data
      (and error-msg (nil? data))
      ($ :div {:class "page-content"}
         ($ error-banner {:message error-msg
                          :on-retry #(do (rf/dispatch [::events/clear-cache])
                                         (rf/dispatch [::events/load-pulse fs]))}))

      :else
      (let [alerts         (or (:alerts data) [])
            kpis           (:kpis data)
            forecast       (or (:forecast data) {})
            charts         (or (:charts data) {})
            movers         (or (:top-movers data) [])
            fallers        (or (:top-fallers data) [])
            critical       (or (:critical-stocks data) [])
            rev-spark      (safe-spark (:revenue-30d charts))
            rev-prev-spark (safe-spark (:revenue-prev-30d charts))
            orders-by-mp   (or (:orders-by-mp charts) {})]
        ($ :div {:class "page-content"}

           ;; Error banner overlay (data available but stale load failed)
           (when error-msg
             ($ error-banner {:message error-msg
                              :on-retry #(do (rf/dispatch [::events/clear-cache])
                                              (rf/dispatch [::events/load-pulse fs]))}))

           ;; Alerts
           (when (seq alerts)
             ($ :div {:style {:display "flex" :flex-direction "column" :gap "10px"}}
                (for [a alerts]
                  ($ alert-card {:key   (:title a)
                                 :kind  (:kind a)
                                 :title (:title a)
                                 :body  (:body a)
                                 :cta   (or (:cta a) "Подробнее")}))))

           ;; KPI grid
           ($ kpi-section {:compare? compare? :kpis kpis})

           ;; Plan-fact + Donut
           ($ :div {:class "grid-12"}
              ($ plan-fact-card {:compare?       compare?
                                 :mp-filter      mp-filter
                                 :forecast       forecast
                                 :rev-spark      rev-spark
                                 :rev-prev-spark rev-prev-spark})
              ($ mp-structure-card {:mp-filter mp-filter
                                    :mp-share  (:mp-share charts)}))

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
                    ($ orders-bar {:mp-filter    mp-filter
                                   :orders-by-mp orders-by-mp})))
              ($ movers-tabs {:movers movers :fallers fallers}))

           ;; Critical stocks
           ($ critical-stocks-table {:rows critical}))))))
