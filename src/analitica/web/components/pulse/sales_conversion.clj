(ns analitica.web.components.pulse.sales-conversion
  (:require [clojure.string :as str]
            [analitica.web.components.pulse.shared :as shared]))

(defn- fmt-rub [v]
  (if (number? v) (str (str/replace (format "%,.0f" (double v)) "," " ") " ₽") "—"))

(defn- fmt-int [v]
  (if (number? v) (str/replace (format "%,d" (long v)) "," " ") "—"))

(defn- fmt-pct [v]
  (if (number? v) (str (format "%.1f" (double v)) "%") "—"))

(defn- delta-badge
  "Render WoW delta as a coloured arrow + percentage. `inverted?` flips
   the colour mapping (true for metrics where down is good — none here)."
  [v {:keys [inverted?]}]
  (cond
    (nil? v) [:span.text-gray-400.text-xs "—"]
    :else
    (let [pos? (pos? v)
          good? (if inverted? (not pos?) pos?)
          cls (cond
                (zero? v) "text-gray-500"
                good?     "text-green-600"
                :else     "text-red-600")
          arrow (cond (zero? v) "→" pos? "↑" :else "↓")]
      [:span.text-xs {:class cls}
       arrow " " (format "%.1f" (* 100 (double v))) "%"])))

(defn- metric-tile [{:keys [label value formatter delta inverted?]}]
  [:div.border.border-gray-200.rounded.p-3
   [:div.text-xs.uppercase.text-gray-500 label]
   [:div.text-xl.font-semibold.mt-1 (formatter value)]
   [:div.mt-1 (delta-badge delta {:inverted? (boolean inverted?)})
    [:span.text-xs.text-gray-400.ml-1 "WoW"]]])

(defn render [data]
  (let [d (or data {})
        wow (:wow d)
        body (if (empty? d)
               (shared/empty-state "Продажи и конверсия")
               [:div.grid.grid-cols-2.sm:grid-cols-4.gap-3
                (metric-tile {:label "Заказы шт" :value (:orders-qty d)
                              :formatter fmt-int :delta (:orders-qty wow)})
                (metric-tile {:label "Заказы ₽" :value (:orders-rub d)
                              :formatter fmt-rub :delta (:orders-rub wow)})
                (metric-tile {:label "Средний чек" :value (:avg-check d)
                              :formatter fmt-rub :delta (:avg-check wow)})
                (metric-tile {:label "% выкупа" :value (:buyout-pct d)
                              :formatter fmt-pct :delta (:buyout-pct wow)})])]
    (shared/section-card
      {:title    "Продажи и конверсия"
       :subtitle "WoW-динамика к предыдущей неделе"
       :body     body})))
