(ns analitica.web.components.pulse.margin-roi
  (:require [clojure.string :as str]
            [analitica.web.components.pulse.shared :as shared]))

(defn- fmt-rub [v]
  (if (number? v) (str (str/replace (format "%,.0f" (double v)) "," " ") " ₽") "—"))

(defn- fmt-pct [v]
  (if (number? v) (str (format "%.1f" (double v)) "%") "—"))

(defn- tile [label value formatter]
  [:div.border.rounded.p-3
   [:div.text-xs.uppercase.text-gray-500 label]
   [:div.text-xl.font-semibold.mt-1 (formatter value)]])

(defn render [data]
  (let [d (or data {})
        body (if (empty? d)
               (shared/empty-state "Маржинальность и ROI")
               [:div.grid.grid-cols-2.sm:grid-cols-5.gap-3
                (tile "Валовая прибыль"  (:gross-profit d)   fmt-rub)
                (tile "Маржа"            (:margin-pct d)     fmt-pct)
                (tile "ROI"              (:roi-pct d)        fmt-pct)
                (tile "Комиссия"         (:commission-pct d) fmt-pct)
                (tile "Логистика"        (:logistics-rub d)  fmt-rub)])]
    (shared/section-card
      {:title    "Маржинальность и ROI"
       :subtitle "Доход после комиссии и логистики"
       :body     body})))
