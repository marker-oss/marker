(ns analitica.web.pages.digest
  "Digest home page — action-first dashboard.

  Pure layer:
    sparkline             — builds SVG polyline from a numeric vector
    metric-card-with-sparkline — KPI tile with inline sparkline
    alert-card            — colored alert card
    top-movers-table / top-fallers-table — revenue movement tables
    freshness-panel       — data-freshness status by MP
    render-page           — composes all sections from a data map

  Impure layer:
    collect-page-data!    — fetches from DB
    page                  — ring handler entry point"
  (:require [clojure.string :as str]
            [hiccup.core :refer [html]]
            [jsonista.core :as json]
            [analitica.web.components :as c]
            [analitica.alerts :as alerts]
            [analitica.domain.buyout   :as buyout]
            [analitica.domain.finance  :as finance]
            [analitica.domain.plan     :as plan]
            [analitica.domain.pnl      :as pnl]
            [analitica.domain.preliminary :as prelim]
            [analitica.domain.sales    :as sales]
            [analitica.domain.stock    :as stock]
            [analitica.util.period     :as period]
            [analitica.web.components.pulse.ads-traffic     :as p-at]
            [analitica.web.components.pulse.custom          :as p-cust]
            [analitica.web.components.pulse.hypotheses      :as p-hyp]
            [analitica.web.components.pulse.margin-roi      :as p-mroi]
            [analitica.web.components.pulse.plan-fact       :as p-pf]
            [analitica.web.components.pulse.products-stock  :as p-ps]
            [analitica.web.components.pulse.profit-forecast :as p-pforecast]
            [analitica.web.components.pulse.sales-conversion :as p-sc]))

;; ---------------------------------------------------------------------------
;; Period helpers for Pulse Dashboard (calendar-month semantics)
;; ---------------------------------------------------------------------------

(defn- ->local-date [s]
  (try (java.time.LocalDate/parse s) (catch Exception _ (java.time.LocalDate/now))))

(defn- month-period
  "From a `:to` date string return calendar-month period info usable by
   Pulse plan-fact and friends. Returns
     {:period-month \"YYYY-MM\"
      :from         \"YYYY-MM-01\"
      :to           same as input
      :days-elapsed int
      :days-in-month int}"
  [to]
  (let [d           (->local-date to)
        ym          (java.time.YearMonth/from d)
        first-day   (.atDay ym 1)
        days-in-mo  (.lengthOfMonth ym)
        days-elap   (.getDayOfMonth d)]
    {:period-month  (str ym)
     :from          (str first-day)
     :to            (str d)
     :days-elapsed  days-elap
     :days-in-month days-in-mo}))

(defn- last-7d-period
  "Period covering the last 7 days ending at `to` (inclusive)."
  [to]
  (let [d  (->local-date to)
        f  (.minusDays d 6)]
    {:from (str f) :to (str d)}))

;; ---------------------------------------------------------------------------
;; Sparkline — inline SVG <polyline> scaled to 80×24 viewBox
;; ---------------------------------------------------------------------------

(defn sparkline
  "Build an inline SVG sparkline from a vector of numeric values.
  Scales values into an 80×24 viewBox with 2px top/bottom padding.
  Returns a hiccup SVG vector. Safe for empty or single-value input."
  [data]
  (let [w 80 h 24 pad 2
        points (vec (remove nil? (map #(when (number? %) %) data)))]
    [:svg {:viewBox (str "0 0 " w " " h)
           :width   w
           :height  h
           :class   "inline-block align-middle"}
     (if (< (count points) 2)
       ;; Degenerate case: render a flat midline
       [:polyline {:points         (str "0," (/ h 2) " " w "," (/ h 2))
                   :fill           "none"
                   :stroke         "currentColor"
                   :stroke-width   1.5}]
       (let [mn  (apply min points)
             mx  (apply max points)
             rng (- mx mn)
             n   (count points)
             ;; Scale x: evenly spaced from 0 to w
             x-of (fn [i] (* i (/ w (dec n))))
             ;; Scale y: inverted (SVG top=0, we want high value at top)
             y-of (fn [v] (if (zero? rng)
                            (/ h 2)
                            (+ pad (* (- h (* 2 pad))
                                      (/ (- mx v) rng)))))
             pts  (str/join " "
                            (map-indexed (fn [i v]
                                           (format "%.1f,%.1f" (double (x-of i)) (double (y-of v))))
                                         points))]
         [:polyline {:points       pts
                     :fill         "none"
                     :stroke       "currentColor"
                     :stroke-width 1.5}]))]))

;; ---------------------------------------------------------------------------
;; KPI tile with sparkline
;; ---------------------------------------------------------------------------

(defn- format-kpi-value [value fmt]
  (cond
    (nil? value) "—"
    (= fmt :rub) (str (str/replace (format "%,.0f" (double value)) "," " ") " ₽")
    (= fmt :pct) (str (format "%.1f" (double value)) "%")
    (= fmt :int) (str/replace (format "%,d" (long value)) "," " ")
    :else         (str value)))

(defn metric-card-with-sparkline
  "KPI tile with optional inline sparkline.

  Options:
    :label          — display label (string)
    :value          — numeric value
    :delta          — WoW delta (number, percent-points)
    :fmt            — :rub | :pct | :int (default :rub)
    :sparkline-data — vector of numbers for sparkline (optional)"
  [{:keys [label value delta fmt sparkline-data]
    :or   {fmt :rub}}]
  (let [is-positive?  (and delta (pos? delta))
        is-negative?  (and delta (neg? delta))
        arrow         (cond is-positive? "↑" is-negative? "↓" :else "")
        ;; For :drr / inverted metrics we'd want green=down, but keeping
        ;; neutral here — caller passes pre-signed delta
        color-class   (cond
                        (nil? delta)    "text-gray-400"
                        is-positive?    "text-green-600"
                        is-negative?    "text-red-600"
                        :else           "text-gray-400")]
    [:div.bg-white.rounded-lg.shadow.p-4.border.border-gray-100
     [:div.text-xs.font-medium.text-gray-500.uppercase.tracking-wide.mb-1 label]
     [:div.flex.items-end.justify-between
      [:div.text-2xl.font-bold.text-gray-900 (format-kpi-value value fmt)]
      (when (seq sparkline-data)
        [:div {:class (str "text-gray-400 " color-class)}
         (sparkline sparkline-data)])]
     (when delta
       [:div {:class (str "text-xs mt-1 " color-class)}
        arrow " " (clojure.core/format "%+.1f%%" (double delta)) " vs пред."])]))

;; ---------------------------------------------------------------------------
;; Alert card component
;; ---------------------------------------------------------------------------

(def ^:private severity->border
  {:red    "border-red-500"
   :yellow "border-amber-400"
   :green  "border-green-500"})

(def ^:private severity->bg
  {:red    "bg-red-50"
   :yellow "bg-amber-50"
   :green  "bg-green-50"})

(def ^:private severity->icon
  {:red    "🔴"
   :yellow "🟡"
   :green  "🟢"})

(defn alert-card
  "Render a single alert as a colored card with action button.
  Alert map keys: :rule :severity :title :body :action-route :action-label"
  [{:keys [severity title body action-route action-label]}]
  (let [border (get severity->border severity "border-gray-300")
        bg     (get severity->bg severity "bg-gray-50")
        icon   (get severity->icon severity "ℹ")]
    [:div {:class (str "rounded-lg border-l-4 p-4 " border " " bg " flex items-start justify-between gap-4")}
     [:div.flex-1
      [:div.flex.items-center.gap-2.mb-1
       [:span icon]
       [:span.font-semibold.text-gray-900.text-sm title]]
      [:p.text-xs.text-gray-600 body]]
     (when (and action-route action-label)
       [:a {:href  action-route
            :class "flex-shrink-0 text-xs px-3 py-1.5 bg-white border border-gray-300 rounded hover:bg-gray-50 text-gray-700 whitespace-nowrap"}
        action-label])]))

;; ---------------------------------------------------------------------------
;; Top-movers and top-fallers tables
;; ---------------------------------------------------------------------------

(defn- mover-row [{:keys [article name revenue prev-revenue delta-pct nm-id nm_id]}]
  (let [art-name (or name article)
        delta    (or delta-pct 0.0)
        sign     (if (neg? delta) "" "+")
        nm       (str (or nm-id nm_id ""))]
    [:tr.border-b.border-gray-100.hover:bg-gray-50
     [:td.px-3.py-2.text-sm.font-medium.text-gray-800
      [:button.sku-link.text-blue-600.hover:underline
       {:data-sku article :data-nm-id nm}
       art-name]]
     [:td.px-3.py-2.text-sm.text-right.text-gray-600
      (when revenue (str/replace (format "%,.0f" (double revenue)) "," " "))]
     [:td.px-3.py-2.text-sm.text-right.font-semibold
      {:class (if (neg? delta) "text-red-600" "text-green-600")}
      (str sign (format "%.1f" (double delta)) "%")]]))

(defn top-movers-table
  "Renders top-N positive movers (caller passes pre-filtered positive deltas;
  this fn applies the >5% threshold and limits to 10 rows)."
  [movers]
  (let [rows (->> movers
                  (filter #(> (or (:delta-pct %) 0) 5))
                  (sort-by :delta-pct >)
                  (take 10))]
    [:div.overflow-x-auto
     (if (empty? rows)
       [:p.text-sm.text-gray-400.py-4 "Нет данных для периода"]
       [:table.w-full.text-xs
        [:thead
         [:tr.bg-gray-50
          [:th.px-3.py-2.text-left.text-gray-500.font-medium "Артикул"]
          [:th.px-3.py-2.text-right.text-gray-500.font-medium "Выручка"]
          [:th.px-3.py-2.text-right.text-gray-500.font-medium "Рост"]]]
        [:tbody (map mover-row rows)]])]))

(defn top-fallers-table
  "Render top-fallers table: articles sorted by delta_pct ASC, |delta| > 5%."
  [fallers]
  (let [rows (->> fallers
                  (filter #(< (or (:delta-pct %) 0) -5))
                  (sort-by :delta-pct)
                  (take 10))]
    [:div.overflow-x-auto
     (if (empty? rows)
       [:p.text-sm.text-gray-400.py-4 "Нет данных для периода"]
       [:table.w-full.text-xs
        [:thead
         [:tr.bg-gray-50
          [:th.px-3.py-2.text-left.text-gray-500.font-medium "Артикул"]
          [:th.px-3.py-2.text-right.text-gray-500.font-medium "Выручка"]
          [:th.px-3.py-2.text-right.text-gray-500.font-medium "Падение"]]]
        [:tbody (map mover-row rows)]])]))

;; ---------------------------------------------------------------------------
;; Per-marketplace comparison table
;; ---------------------------------------------------------------------------

(defn- mp-display [mp]
  (case mp :wb "Wildberries" :ozon "Ozon" :ym "Yandex.Market" (name mp)))

(defn- fmt-rub
  [v]
  (if (number? v)
    (str (format "%,.0f" (double v)) " ₽")
    "—"))

(defn- fmt-pct-cell
  [v]
  (if (number? v)
    (str (format "%.1f" (double v)) "%")
    "—"))

(defn marketplace-comparison-table
  "Per-marketplace breakdown row — WB / Ozon / Я.Маркет side-by-side.
  by-marketplace is a vector of {:marketplace :revenue :profit :margin
  :sales-qty :returns-qty} (one row per MP)."
  [by-marketplace]
  [:section.bg-white.rounded-lg.shadow.p-4.mb-6
   [:h3.text-base.font-semibold.text-gray-800.mb-3
    "🏪 По маркетплейсам"]
   (if (empty? by-marketplace)
     [:div.text-sm.text-gray-500 "Нет данных"]
     [:div.overflow-x-auto
      [:table.min-w-full.text-sm
       [:thead
        [:tr.bg-gray-50.text-left.text-xs.uppercase.text-gray-500
         [:th.px-3.py-2 "Маркетплейс"]
         [:th.px-3.py-2.text-right "Выручка"]
         [:th.px-3.py-2.text-right "Прибыль"]
         [:th.px-3.py-2.text-right "Маржа"]
         [:th.px-3.py-2.text-right "Продажи (шт)"]
         [:th.px-3.py-2.text-right "Возвраты (шт)"]]]
       [:tbody.divide-y.divide-gray-100
        (for [m by-marketplace]
          [:tr
           [:td.px-3.py-2.font-medium (mp-display (:marketplace m))]
           [:td.px-3.py-2.text-right
            (fmt-rub (:revenue m))
            (when (:preliminary? m)
              [:span.ml-1.text-xs.text-amber-600
               {:title (if (= :ozon (:marketplace m))
                         "Реализация Ozon ещё не опубликована — показана выручка по cash-flow (orders − returns). Совпадёт с finance.for_pay net когда отчёт придёт."
                         "Финансовый отчёт ещё не опубликован — показана gross-выручка по продажам.")}
               "(предв.)"])]
           [:td.px-3.py-2.text-right
            {:class (cond (and (number? (:profit m)) (neg? (:profit m))) "text-red-600"
                          (and (number? (:profit m)) (pos? (:profit m))) "text-green-600")}
            (fmt-rub (:profit m))]
           [:td.px-3.py-2.text-right (fmt-pct-cell (:margin m))]
           [:td.px-3.py-2.text-right (or (:sales-qty m) "—")]
           [:td.px-3.py-2.text-right (or (:returns-qty m) "—")]])]]])])

;; ---------------------------------------------------------------------------
;; Data-freshness panel
;; ---------------------------------------------------------------------------

(defn- fmt-ru
  "\"2026-04-25T18:30:00\" → \"25.04 18:30\"."
  [iso]
  (when (seq (str iso))
    (let [s (str iso)
          d (subs s 0 10)
          t (when (> (count s) 10) (subs s 11 16))]
      (let [[y m day] (str/split d #"-")]
        (str day "." m (when t (str " " t)))))))

(defn- freshness-age-days
  "Days since iso-datetime string; nil if nil input."
  [iso]
  (when (seq (str iso))
    (try
      (let [dt   (.toLocalDate (java.time.LocalDateTime/parse (str iso)))
            today (java.time.LocalDate/now)]
        (.until dt today java.time.temporal.ChronoUnit/DAYS))
      (catch Exception _ nil))))

(defn- freshness-status-cell
  "Return a hiccup span with color/text based on last-sync age."
  [mp-key iso max-lag-days]
  (let [age (freshness-age-days iso)]
    (cond
      (nil? iso)
      [:span.text-red-500 "нет данных"]

      (nil? age)
      [:span.text-red-500 "нет данных"]

      (> age max-lag-days)
      [:span.text-amber-600
       (str "отстаёт " age " дн. (ожидаемо до " max-lag-days " дн.)")]

      :else
      [:span.text-green-600
       (str "✅ ОК (" (fmt-ru iso) ")")])))

(defn freshness-panel
  "Data freshness panel: for each MP show last synced_at and status.
  freshness-map: {:wb iso :ozon iso :ym iso} — iso may be nil."
  [freshness-map]
  [:div.grid.grid-cols-1.md:grid-cols-3.gap-4
   ;; WB Finance: weekly delay — warn if >6 days
   [:div.bg-white.rounded-lg.border.border-gray-200.p-4
    [:div.flex.items-center.gap-2.mb-1
     [:span.text-sm.font-semibold.text-gray-700 "WB"]
     [:span.text-xs.text-gray-400 "финансы раз в неделю"]]
    [:div.text-sm (freshness-status-cell :wb (:wb freshness-map) 6)]]
   ;; Ozon realization: monthly cadence — warn if >30 days
   [:div.bg-white.rounded-lg.border.border-gray-200.p-4
    [:div.flex.items-center.gap-2.mb-1
     [:span.text-sm.font-semibold.text-gray-700 "Ozon"]
     [:span.text-xs.text-gray-400 "реализация раз в месяц"]]
    [:div.text-sm (freshness-status-cell :ozon (:ozon freshness-map) 30)]]
   ;; YM: standard daily — warn if >2 days
   [:div.bg-white.rounded-lg.border.border-gray-200.p-4
    [:div.flex.items-center.gap-2.mb-1
     [:span.text-sm.font-semibold.text-gray-700 "Яндекс.Маркет"]
     [:span.text-xs.text-gray-400 "ежедневно"]]
    [:div.text-sm (freshness-status-cell :ym (:ym freshness-map) 2)]]])

;; ---------------------------------------------------------------------------
;; Pure page renderer
;; ---------------------------------------------------------------------------

(defn- revenue-chart
  "Inline Chart.js line chart for daily revenue. Data is baked into the JS
   init via JSON literal — no separate API round-trip. Falls back to an
   empty-state message when there is no data."
  [daily-revenue]
  (let [rows   (->> (or daily-revenue [])
                    (sort-by :group)
                    (filter #(seq (:group %))))
        labels (mapv :group rows)
        values (mapv #(or (:revenue %) 0.0) rows)]
    [:section.bg-white.rounded-lg.shadow.p-4.mb-6
     [:h3.text-base.font-semibold.text-gray-800.mb-3
      "📊 Динамика выручки за период"]
     (if (empty? rows)
       [:div.text-sm.text-gray-500 "Нет данных для графика"]
       [:div {:style "height: 240px;"}
        [:canvas#digest-revenue-chart]
        [:script {:type "text/javascript"}
         (str "(function(){"
              " var ctx=document.getElementById('digest-revenue-chart').getContext('2d');"
              " new Chart(ctx,{type:'line',"
              " data:{labels:" (json/write-value-as-string labels) ","
              " datasets:[{label:'Выручка',"
              " data:" (json/write-value-as-string values) ","
              " borderColor:'rgb(59,130,246)',"
              " backgroundColor:'rgba(59,130,246,0.1)',"
              " tension:0.25,fill:true,pointRadius:2}]},"
              " options:{responsive:true,maintainAspectRatio:false,"
              " plugins:{legend:{display:false}}}});"
              "})();")]])]))

(defn- fmt-date-header
  "\"2026-04-25\" → \"25.04.2026\"."
  [iso]
  (when (seq iso)
    (let [[y m d] (str/split iso #"-")]
      (str d "." m "." y))))

(defn render-page
  "Pure renderer — takes a data map and returns hiccup.
  No DB calls here; all DB fetching done by collect-page-data! below.

  Data map keys:
    :kpi           — map with :revenue :net-profit :margin :drr + deltas + sparklines
    :alerts        — vector of alert maps
    :movers        — vector of mover maps {:article :name :revenue :delta-pct}
    :fallers       — vector of faller maps
    :freshness     — {:wb :ozon :ym} with iso-datetime or nil
    :from / :to    — ISO date strings
    :daily-revenue — vector of {:day :revenue} for 30-day bar chart (unused in SVG path)"
  [{:keys [kpi alerts movers fallers freshness from to daily-revenue by-marketplace
           pulse period-month days-elapsed days-in-month]}]
  (let [today-str (fmt-date-header (or to (str (java.time.LocalDate/now))))
        n-alerts  (count alerts)]
    [:div.digest-page.max-w-7xl.mx-auto.px-4.py-6
     ;; Header
     [:div.flex.items-center.justify-between.mb-6
      [:h1.text-2xl.font-bold.text-gray-900
       (str "Сегодня · " today-str)]
      [:div.text-sm.text-gray-500
       (str (fmt-date-header from) " — " today-str)]]

     ;; ---- Pulse Dashboard 8 sections ---------------------------------
     ;; See docs/superpowers/specs/2026-05-02-pulse-dashboard-design.md
     (when pulse
       (let [d-elap (or days-elapsed 0)
             d-mo   (or days-in-month 31)]
         [:section#digest-pulse.mb-6
          (p-hyp/render        (:hypotheses pulse))
          (p-pf/render         (assoc (:plan-fact pulse)
                                :days-elapsed  d-elap
                                :days-in-month d-mo))
          (p-sc/render         (:sales-conversion pulse))
          (p-pforecast/render  (assoc (:profit-forecast pulse)
                                :days-elapsed  d-elap
                                :days-in-month d-mo))
          (p-mroi/render       (:margin-roi pulse))
          (p-ps/render         (:products-stock pulse))
          (p-at/render         (:ads-traffic pulse))
          (p-cust/render       (:custom pulse))]))

     ;; Row 1: KPI tiles
     [:div.grid.grid-cols-1.md:grid-cols-4.gap-4.mb-6
      (metric-card-with-sparkline
       {:label          "Выручка"
        :value          (:revenue kpi)
        :delta          (some-> (:revenue-delta kpi) (* 100))
        :fmt            :rub
        :sparkline-data (:revenue-sparkline kpi)})
      (metric-card-with-sparkline
       {:label          "Прибыль"
        :value          (:net-profit kpi)
        :delta          (some-> (:net-profit-delta kpi) (* 100))
        :fmt            :rub
        :sparkline-data (:profit-sparkline kpi)})
      (metric-card-with-sparkline
       {:label          "Маржа"
        :value          (:margin kpi)
        :delta          (some-> (:margin-delta kpi) (* 100))
        :fmt            :pct
        :sparkline-data (:margin-sparkline kpi)})
      (metric-card-with-sparkline
       {:label          "ДРР"
        :value          (:drr kpi)
        :delta          (some-> (:drr-delta kpi) (* 100))
        :fmt            :pct
        :sparkline-data (:drr-sparkline kpi)})]

     ;; Row 2: Alert cards
     [:section#digest-alerts.mb-6
      [:h2.text-lg.font-semibold.text-gray-800.mb-3
       (str "⚠ Что нужно увидеть сегодня"
            (when (pos? n-alerts) (str " (" n-alerts ")")))]
      (if (empty? alerts)
        [:div.bg-green-50.border.border-green-200.rounded-lg.p-4.text-sm.text-green-700
         "✅ Всё в порядке — нет срочных предупреждений"]
        [:div.flex.flex-col.gap-3
         (map alert-card alerts)])]

     ;; Row 2.5: Per-marketplace comparison
     (marketplace-comparison-table (or by-marketplace []))

     ;; Row 3: Revenue chart (period-over-period dynamics)
     (revenue-chart daily-revenue)

     ;; Row 4: Top-movers / Top-fallers (2-col grid)
     [:div#digest-movers.grid.grid-cols-1.md:grid-cols-2.gap-6.mb-6
      [:div.bg-white.rounded-lg.shadow.p-4
       [:h3.text-base.font-semibold.text-gray-800.mb-3 "🏆 Топ продаж"]
       (top-movers-table movers)]
      [:div.bg-white.rounded-lg.shadow.p-4
       [:h3.text-base.font-semibold.text-gray-800.mb-3 "📉 Падает"]
       (top-fallers-table fallers)]]

     ;; Row 4: Data freshness
     [:section.mb-4
      [:h3.text-base.font-semibold.text-gray-800.mb-3 "📅 Свежесть данных"]
      (freshness-panel (or freshness {}))]]))

;; ---------------------------------------------------------------------------
;; Data collection (impure — hits DB)
;; ---------------------------------------------------------------------------

(defn- build-sparkline-series
  "Build a daily revenue sparkline from (sales/by-day ...) output.
  Returns a vector of revenue values ordered by day."
  [by-day-data]
  (->> by-day-data
       (sort-by :group)
       (mapv #(or (:revenue %) 0.0))))

(defn- compute-movers-fallers
  "Compare current vs prev sales-by-article, return mover/faller rows."
  [curr-by-art prev-by-art]
  (let [prev-map (into {} (map (juxt (fn [r] (or (:group r) (:article r))) :revenue)
                               prev-by-art))]
    (->> curr-by-art
         (map (fn [curr]
                (let [art      (or (:group curr) (:article curr))
                      curr-rev (or (:revenue curr) 0.0)
                      prev-rev (or (get prev-map art) 0.0)
                      delta    (when (pos? prev-rev)
                                 (* 100.0 (/ (- curr-rev prev-rev) prev-rev)))]
                  {:article      art
                   :name         (or (:subject curr) art)
                   :revenue      curr-rev
                   :prev-revenue prev-rev
                   :delta-pct    (or delta 0.0)})))
         (remove #(< (Math/abs (or (:delta-pct %) 0.0)) 5)))))

(defn collect-page-data!
  "Fetch all data needed by render-page. Returns a data map.
  :from / :to — ISO date strings; nil defaults to last-30-days.
  :marketplace — keyword or nil (nil = all)."
  [& {:keys [from to marketplace]}]
  (let [state      (if (and from to)
                     {:from from :to to}
                     (period/default-state))
        curr-from  (:from state)
        curr-to    (:to state)
        curr-period {:from curr-from :to curr-to}
        [prev-from prev-to] (period/compare-period curr-period)
        prev-period {:from prev-from :to prev-to}
        days        (period/days-between curr-from curr-to)
        ;; Sales data — always pass :marketplace per memory note
        curr-sales  (try (sales/fetch-sales curr-period :marketplace marketplace)
                         (catch Exception _ []))
        prev-sales  (try (sales/fetch-sales prev-period :marketplace marketplace)
                         (catch Exception _ []))
        curr-by-art (sales/by-article curr-sales)
        prev-by-art (sales/by-article prev-sales)
        curr-by-day (sales/by-day curr-sales)
        ;; Finance data — always pass :marketplace
        curr-finance (try (finance/fetch-finance curr-period :marketplace marketplace)
                          (catch Exception _ []))
        prev-finance (try (finance/fetch-finance prev-period :marketplace marketplace)
                          (catch Exception _ []))
        curr-pnl    (try (pnl/calculate curr-finance
                                        :marketplace marketplace
                                        :from curr-from :to curr-to)
                         (catch Exception _ {:revenue 0 :net-profit 0 :ad-spend 0}))
        prev-pnl    (try (pnl/calculate prev-finance
                                        :marketplace marketplace
                                        :from prev-from :to prev-to)
                         (catch Exception _ {:revenue 0 :net-profit 0 :ad-spend 0}))
        ;; KPI deltas
        rev-curr    (or (:revenue curr-pnl) 0.0)
        rev-prev    (or (:revenue prev-pnl) 0.0)
        np-curr     (or (:net-profit curr-pnl) 0.0)
        np-prev     (or (:net-profit prev-pnl) 0.0)
        ;; Margin = net-profit / revenue * 100
        margin-curr (if (pos? rev-curr) (* 100.0 (/ np-curr rev-curr)) 0.0)
        margin-prev (if (pos? rev-prev) (* 100.0 (/ np-prev rev-prev)) 0.0)
        ;; DRR = ad-spend / revenue * 100
        ad-curr     (or (:ad-spend curr-pnl) 0.0)
        ad-prev     (or (:ad-spend prev-pnl) 0.0)
        drr-curr    (if (pos? rev-curr) (* 100.0 (/ ad-curr rev-curr)) 0.0)
        drr-prev    (if (pos? rev-prev) (* 100.0 (/ ad-prev rev-prev)) 0.0)
        safe-delta  (fn [curr prev]
                      (when (pos? prev)
                        (/ (- curr prev) prev)))
        ;; Sparkline series from daily sales
        sparkline-series (build-sparkline-series curr-by-day)
        ;; Buyout data for alerts
        buyout-data (try (buyout/analyze curr-period)
                         (catch Exception _ []))
        ;; Top 10 by revenue + last-3-days sales for ZERO_SALES_TOP_SKU alert
        ;; sales/by-article emits {:group <article> ...} (not :article); the
        ;; alerts rule expects :article, so we copy it across.
        top-10 (->> curr-by-art
                    (sort-by :revenue >)
                    (take 10)
                    (map-indexed (fn [i r]
                                   (assoc r
                                          :rank    (inc i)
                                          :article (or (:article r) (:group r))))))
        three-days-ago (-> (java.time.LocalDate/parse curr-to)
                           (.minusDays 2)
                           str)
        last-3-sales (->> curr-sales
                          (filter (fn [s]
                                    (let [d (or (:date s) (:event-date s) "")]
                                      (and (seq d)
                                           (>= (compare (subs d 0 10) three-days-ago) 0))))))
        ;; Stocks for OUT_OF_STOCK alerts
        stocks-raw   (try (stock/fetch-stocks :marketplace (or marketplace :wb))
                          (catch Exception _ []))
        stocks-by-art (stock/by-article stocks-raw)
        stocks-turn   (try (stock/with-turnover stocks-by-art curr-sales days)
                           (catch Exception _ []))
        ;; Alerts
        alert-data  {:stocks-with-turnover     stocks-turn
                     :current-sales-by-article curr-by-art
                     :prev-sales-by-article    prev-by-art
                     :current-pnl              curr-pnl
                     :prev-pnl                 prev-pnl
                     :current-buyout           buyout-data
                     :sales-last-3-days        last-3-sales
                     :top-10-by-revenue        top-10}
        detected    (try (alerts/detect-alerts alert-data)
                         (catch Exception _ []))
        ;; Movers / fallers
        all-movers   (compute-movers-fallers curr-by-art prev-by-art)
        movers       (filter #(pos? (:delta-pct %)) all-movers)
        fallers      (filter #(neg? (:delta-pct %)) all-movers)
        freshness    (try (alerts/freshness-data) (catch Exception _ {}))
        ;; Per-marketplace breakdown — fetch finance per MP and roll up via PnL.
        ;; Reuses the canonical pnl/calculate path so numbers align with the
        ;; full P&L report for each MP. When the finance/realization report
        ;; for the period hasn't been published yet (Ozon realization is
        ;; monthly, current month always lags), revenue from PnL is 0 — fall
        ;; back to a preliminary source:
        ;;   - Ozon → cash_flow_periods.orders+returns (matches finance net
        ;;     exactly when realization arrives — verified Feb/Mar 2026).
        ;;     See analitica.domain.preliminary.
        ;;   - Other MPs → sales-side gross from /v3/posting/list etc.
        ;;     (kept as fallback; WB/YM realization is generally realtime).
        by-marketplace
        (vec
         (for [mp [:wb :ozon :ym]]
           (let [mp-fin    (try (finance/fetch-finance curr-period :marketplace mp)
                                (catch Exception _ []))
                 mp-pnl    (try (pnl/calculate mp-fin
                                               :marketplace mp
                                               :from curr-from :to curr-to)
                                (catch Exception _ {}))
                 ;; Ozon: try cash-flow overlay first (numerically closer to
                 ;; what realization will publish than sales table).
                 mp-pnl    (if (= mp :ozon)
                             (prelim/maybe-overlay-preliminary
                               mp-pnl
                               {:period curr-period :marketplace :ozon})
                             mp-pnl)
                 mp-sales  (try (sales/fetch-sales curr-period :marketplace mp)
                                (catch Exception _ []))
                 sales-totals (try (sales/totals mp-sales) (catch Exception _ {}))
                 fin-rev   (or (:revenue mp-pnl) 0.0)
                 sales-rev (or (:total-revenue sales-totals)
                               (:revenue sales-totals)
                               0.0)
                 ;; If still 0 after preliminary overlay (non-Ozon, or no
                 ;; cash-flow data), fall through to sales-side gross.
                 sales-prelim? (and (zero? fin-rev) (pos? sales-rev))
                 revenue   (cond
                             (:preliminary? mp-pnl) (:revenue mp-pnl)
                             sales-prelim?          sales-rev
                             :else                  fin-rev)
                 prelim?   (or (:preliminary? mp-pnl) sales-prelim?)
                 profit    (or (:net-profit mp-pnl) 0.0)
                 margin    (when (and (number? revenue) (pos? revenue))
                             (* 100.0 (/ profit revenue)))
                 ;; PnL :sales-qty / :returns-qty come from finance — when the
                 ;; report isn't published (Ozon current month) they're 0 not
                 ;; nil, so an `or` chain wouldn't fall through. Use explicit
                 ;; positive-check to fall back to sales-table counts.
                 pnl-sq    (:sales-qty mp-pnl)
                 pnl-rq    (:returns-qty mp-pnl)
                 sales-qty (if (and (number? pnl-sq) (pos? pnl-sq))
                             pnl-sq
                             (or (:sales-count sales-totals) 0))
                 ret-qty   (if (and (number? pnl-rq) (pos? pnl-rq))
                             pnl-rq
                             (or (:returns-count sales-totals) 0))]
             {:marketplace mp
              :revenue     revenue
              :profit      profit
              :margin      margin
              :sales-qty   sales-qty
              :returns-qty ret-qty
              :preliminary? prelim?})))
        ;; ---- Pulse Dashboard data ----------------------------------------
        ;; Plan-Fact uses CALENDAR-MONTH semantics, not the 30-day window
        ;; the rest of the page uses. Re-fetch PnL for current month + last 7 days.
        m-info     (month-period curr-to)
        m-period   {:from (:from m-info) :to (:to m-info)}
        l7         (last-7d-period curr-to)
        m-finance  (try (finance/fetch-finance m-period :marketplace marketplace)
                        (catch Exception _ []))
        l7-finance (try (finance/fetch-finance l7 :marketplace marketplace)
                        (catch Exception _ []))
        m-pnl      (try (pnl/calculate m-finance
                                       :marketplace marketplace
                                       :from (:from m-period) :to (:to m-period))
                        (catch Exception _ {}))
        l7-pnl     (try (pnl/calculate l7-finance
                                       :marketplace marketplace
                                       :from (:from l7) :to (:to l7))
                        (catch Exception _ {}))
        ;; Plans for the current calendar month
        plan-rows  (try (plan/fetch-plans (:period-month m-info))
                        (catch Exception _ []))
        plan-mp    (or marketplace :all)
        metric-actuals
          {:revenue       {:mtd  (or (:revenue m-pnl)    0.0)
                           :l7d  (or (:revenue l7-pnl)   0.0)}
           :gross_profit  {:mtd  (or (:gross-profit m-pnl)
                                     (:net-profit  m-pnl) 0.0)
                           :l7d  (or (:gross-profit l7-pnl)
                                     (:net-profit  l7-pnl) 0.0)}
           :ad_spend      {:mtd  (or (:ad-spend m-pnl)   0.0)
                           :l7d  (or (:ad-spend l7-pnl)  0.0)}}
        plan-targets
          (vec
            (for [metric [:revenue :gross_profit :ad_spend]
                  :let [t (plan/lookup-plan plan-rows
                            {:period-month (:period-month m-info)
                             :marketplace  plan-mp
                             :metric       metric})
                        a (get metric-actuals metric)]
                  :when t]
              {:metric     metric
               :target     t
               :actual-mtd (:mtd a)
               :last-7d    (:l7d a)}))
        sales-totals-curr
          (try (sales/totals curr-sales) (catch Exception _ {}))
        ;; buyout/analyze returns a per-article seq; aggregate to a period-wide
        ;; rate = Σ bought / Σ (bought+returned). Falls back to nil when no rows.
        buyout-pct-curr
          (let [rows  (or buyout-data [])
                bs    (reduce + 0 (keep :bought rows))
                tot   (reduce + 0 (keep :total-ops rows))]
            (when (pos? tot) (* 100.0 (/ bs (double tot)))))
        pulse-data
          {:hypotheses       nil
           :plan-fact        {:period-month (:period-month m-info)
                              :targets      plan-targets}
           :sales-conversion {:orders-qty   (or (:sales-count sales-totals-curr) 0)
                              :orders-rub   (or (:total-revenue sales-totals-curr) 0.0)
                              ;; sales/totals exposes :avg-price (per-unit average);
                              ;; the section labels it as "Средний чек" — same number
                              ;; for one-item-per-sale data, close enough for v1.
                              :avg-check    (or (:avg-price sales-totals-curr) 0.0)
                              :buyout-pct   buyout-pct-curr
                              :wow {}}
           :profit-forecast  {:gross-profit-mtd      (or (:gross-profit m-pnl)
                                                         (:net-profit   m-pnl) 0.0)
                              :gross-profit-target   (plan/lookup-plan plan-rows
                                                       {:period-month (:period-month m-info)
                                                        :marketplace  plan-mp
                                                        :metric       :gross_profit})
                              :last-7d-gross-profit  (or (:gross-profit l7-pnl)
                                                         (:net-profit   l7-pnl) 0.0)
                              :ad-budget-remaining   (when-let [t (plan/lookup-plan plan-rows
                                                                    {:period-month (:period-month m-info)
                                                                     :marketplace  plan-mp
                                                                     :metric       :ad_spend})]
                                                       (max 0 (- t (or (:ad-spend m-pnl) 0.0))))
                              :romi-on-remaining     nil}
           :margin-roi       {:gross-profit   np-curr
                              :margin-pct     margin-curr
                              :roi-pct        nil
                              :commission-pct nil
                              :logistics-rub  nil}
           :products-stock   {:oos-skus      (count (filter #(zero? (or (:stock %) 0)) stocks-by-art))
                              :turnover-days nil
                              :return-pct    nil}
           :ads-traffic      {:impressions nil
                              :clicks      nil
                              :ctr-pct     nil
                              :cpc-rub     nil
                              :romi        nil
                              :drr-pct     drr-curr}
           :custom           nil}]
    {:kpi     {:revenue          rev-curr
               :net-profit       np-curr
               :margin           margin-curr
               :drr              drr-curr
               :revenue-delta    (safe-delta rev-curr rev-prev)
               :net-profit-delta (safe-delta np-curr np-prev)
               :margin-delta     (safe-delta margin-curr margin-prev)
               :drr-delta        (safe-delta drr-curr drr-prev)
               :revenue-sparkline  sparkline-series
               ;; V1: profit/margin/DRR sparklines reuse the revenue series as a placeholder.
               ;; A proper per-KPI daily series would require additional aggregation queries
               ;; that aren't worth the latency for V1. Tracked as known limitation.
               :profit-sparkline   sparkline-series
               :margin-sparkline   []
               :drr-sparkline      []}
     :alerts          detected
     :movers          (vec movers)
     :fallers         (vec fallers)
     :freshness       freshness
     :from            curr-from
     :to              curr-to
     :daily-revenue   curr-by-day
     :by-marketplace  by-marketplace
     :period-month   (:period-month m-info)
     :days-elapsed   (:days-elapsed m-info)
     :days-in-month  (:days-in-month m-info)
     :pulse          pulse-data}))

;; ---------------------------------------------------------------------------
;; Ring page handler
;; ---------------------------------------------------------------------------

(defn page
  "Ring handler for GET /. Resolves period from params, collects data, renders page."
  [{:keys [from to marketplace]}]
  (let [mp   (when (and marketplace (not= marketplace "all"))
               (keyword marketplace))
        data (collect-page-data! :from from :to to :marketplace mp)]
    (render-page data)))
