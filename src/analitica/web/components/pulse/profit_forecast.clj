(ns analitica.web.components.pulse.profit-forecast
  (:require [clojure.string :as str]
            [analitica.domain.plan :as plan]
            [analitica.web.components.pulse.shared :as shared]))

(defn- fmt-rub [v]
  (if (number? v) (str (str/replace (format "%,.0f" (double v)) "," " ") " ₽") "—"))

(defn- fmt-mult [v]
  (if (number? v) (format "%.2f" (double v)) "—"))

(defn render [data]
  (let [{:keys [gross-profit-mtd last-7d-gross-profit
                gross-profit-target days-elapsed days-in-month
                ad-budget-remaining romi-on-remaining]} data
        forecast (when (and (number? gross-profit-mtd) days-elapsed days-in-month)
                   (plan/run-rate
                     {:actual-mtd      gross-profit-mtd
                      :days-elapsed    days-elapsed
                      :days-in-month   days-in-month
                      :last-7d-actual  (or last-7d-gross-profit 0.0)}))
        body (if-not forecast
               (shared/empty-state "Прогноз прибыли")
               [:div.grid.grid-cols-2.sm:grid-cols-4.gap-3
                [:div.border.rounded.p-3
                 [:div.text-xs.uppercase.text-gray-500 "Прогноз прибыли"]
                 [:div.text-xl.font-semibold.mt-1 (fmt-rub forecast)]
                 [:div.text-xs.text-gray-500.mt-1
                  "План: " (fmt-rub gross-profit-target)]]
                [:div.border.rounded.p-3
                 [:div.text-xs.uppercase.text-gray-500 "Ad-бюджет остаток"]
                 [:div.text-xl.font-semibold.mt-1 (fmt-rub ad-budget-remaining)]]
                [:div.border.rounded.p-3
                 [:div.text-xs.uppercase.text-gray-500 "ROMI на остатке"]
                 [:div.text-xl.font-semibold.mt-1 (fmt-mult romi-on-remaining)]
                 [:div.text-xs.text-gray-500.mt-1
                  "ожидаемая прибыль / расходы"]]
                [:div.border.rounded.p-3
                 [:div.text-xs.uppercase.text-gray-500 "Ожидаемая доп. прибыль"]
                 [:div.text-xl.font-semibold.mt-1
                  (fmt-rub (when (and (number? ad-budget-remaining)
                                      (number? romi-on-remaining))
                             (- (* ad-budget-remaining romi-on-remaining)
                                ad-budget-remaining)))]]])]
    (shared/section-card
      {:title    "Прогноз прибыли"
       :subtitle "Run Rate gross profit + ROMI на оставшийся ad-бюджет"
       :body     body})))
