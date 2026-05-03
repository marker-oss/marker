(ns analitica.web.components.pulse.plan-fact
  "Run Rate forecast cards. One card per `target` produced by the
   orchestrator. The orchestrator pre-computes actual-mtd, last-7d and
   the configured target via domain.plan/lookup-plan. We only render."
  (:require [clojure.string :as str]
            [analitica.domain.plan :as plan]
            [analitica.web.components.pulse.shared :as shared]))

(defn- fmt-rub [v]
  (if (number? v)
    (str (str/replace (format "%,.0f" (double v)) "," " ") " ₽")
    "—"))

(defn- fmt-int [v]
  (if (number? v)
    (str/replace (format "%,d" (long v)) "," " ")
    "—"))

(defn- fmt-pct [v]
  (if (number? v) (str (format "%.1f" (double v)) "%") "—"))

(defn- formatter-for [metric]
  (case metric
    (:revenue :gross_profit :ad_spend) fmt-rub
    :orders                            fmt-int
    fmt-pct))

(def ^:private metric-labels
  {:revenue            "Выручка"
   :orders             "Заказы"
   :gross_profit       "Валовая прибыль"
   :margin_pct         "Маржа"
   :ad_spend           "Расходы на рекламу"
   :drr_pct            "ДРР"
   :profit_margin_pct  "% чистой прибыли"})

(defn- finite? [x]
  (and (number? x) (Double/isFinite (double x))))

(defn- pace-class [m]
  (cond
    (nil? m)                       "text-gray-500"
    (and (finite? m) (<= m 1.02))  "text-green-600"
    (and (finite? m) (<= m 1.10))  "text-amber-600"
    :else                          "text-red-600"))

(defn- pace-label [m]
  (cond
    (nil? m)                       "—"
    (and (finite? m) (<= m 1.02))  "✓ в плане"
    (finite? m)                    (format "нужен темп ×%.2f" (double m))
    :else                          "не успеть"))

(defn- target-card [{:keys [metric target actual-mtd last-7d
                            days-elapsed days-in-month]}]
  (let [fmt          (formatter-for metric)
        forecast     (plan/run-rate {:actual-mtd actual-mtd
                                     :days-elapsed days-elapsed
                                     :days-in-month days-in-month
                                     :last-7d-actual last-7d})
        days-rem     (max 0 (- days-in-month days-elapsed))
        m            (plan/pace-multiplier {:actual-mtd actual-mtd
                                            :forecast forecast
                                            :target target
                                            :days-remaining days-rem})
        ratio        (when (and target (pos? target))
                       (/ forecast target))]
    [:div.border.border-gray-200.rounded.p-3
     [:div.text-xs.uppercase.text-gray-500
      (get metric-labels metric (name metric))]
     [:div.flex.items-baseline.justify-between.mt-1
      [:div.text-xl.font-semibold (fmt forecast)]
      [:div.text-xs.text-gray-500
       (if ratio (format "%.0f%%" (* 100 (double ratio))) "")]]
     [:div.text-xs.text-gray-600.mt-2
      "План: " (fmt target)]
     [:div.text-xs.text-gray-600
      "Факт MTD: " (fmt actual-mtd)]
     [:div.text-xs.text-gray-600
      "Осталось: " days-rem " дн"]
     [:div.text-sm.font-medium.mt-2 {:class (pace-class m)}
      (pace-label m)]]))

(defn render
  "Render the Plan/Fact section.

   Expected data:
     :period-month  YYYY-MM string
     :days-elapsed  int
     :days-in-month int
     :targets       seq of {:metric kw  :target num
                            :actual-mtd num  :last-7d num}"
  [{:keys [days-elapsed days-in-month targets period-month] :as data}]
  (let [body (cond
               (nil? data)
                 (shared/empty-state "План-Факт")
               (empty? targets)
                 [:div.text-sm.text-gray-700
                  "📋 План на " (or period-month "месяц") " не задан. "
                  [:a.text-blue-600.hover:underline {:href "/plan"}
                   "Задать план →"]]
               :else
                 [:div.grid.grid-cols-1.sm:grid-cols-2.lg:grid-cols-3.gap-3
                  (for [t targets]
                    (target-card (assoc t
                                  :days-elapsed days-elapsed
                                  :days-in-month days-in-month)))])]
    (shared/section-card
      {:title    "План-Факт"
       :subtitle "Run Rate-прогноз и нужный темп"
       :body     body})))
