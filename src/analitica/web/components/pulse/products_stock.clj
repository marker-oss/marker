(ns analitica.web.components.pulse.products-stock
  (:require [clojure.string :as str]
            [analitica.web.components.pulse.shared :as shared]))

(defn- fmt-int [v]
  (if (number? v) (str/replace (format "%,d" (long v)) "," " ") "—"))

(defn- fmt-num1 [v]
  (if (number? v) (format "%.1f" (double v)) "—"))

(defn- fmt-pct [v]
  (if (number? v) (str (format "%.1f" (double v)) "%") "—"))

(defn- localization-hint []
  [:div.bg-amber-50.border.border-amber-200.rounded.p-3.mt-3
   [:div.flex.items-start.gap-2
    [:span.text-amber-600.text-lg "🟡"]
    [:div.text-sm.text-gray-800
     [:strong "Локализация WB. "]
     "WB поднимает трафик карточкам с индексом локализации ≥70%. "
     "Проверьте индекс в личном кабинете: "
     [:em "WB → Аналитика → География."]]]])

(defn render [data]
  (let [d (or data {})
        body
        [:div
         (if (empty? d)
           (shared/empty-state "Товары и остатки")
           [:div.grid.grid-cols-3.gap-3
            [:div.border.rounded.p-3
             [:div.text-xs.uppercase.text-gray-500 "OOS товары"]
             [:div.text-xl.font-semibold.mt-1 (fmt-int (:oos-skus d))]
             [:div.text-xs.text-gray-500.mt-1 "Без остатков"]]
            [:div.border.rounded.p-3
             [:div.text-xs.uppercase.text-gray-500 "Оборачиваемость"]
             [:div.text-xl.font-semibold.mt-1
              (str (fmt-num1 (:turnover-days d)) " дн")]
             [:div.text-xs.text-gray-500.mt-1 "оборачиваемость, дней"]]
            [:div.border.rounded.p-3
             [:div.text-xs.uppercase.text-gray-500 "% возвратов"]
             [:div.text-xl.font-semibold.mt-1 (fmt-pct (:return-pct d))]]])
         (localization-hint)]]
    (shared/section-card
      {:title    "Товары и остатки"
       :subtitle "Доступность ассортимента + индикатор локализации"
       :body     body})))
