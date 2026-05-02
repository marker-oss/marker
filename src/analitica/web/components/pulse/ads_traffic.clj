(ns analitica.web.components.pulse.ads-traffic
  (:require [clojure.string :as str]
            [analitica.web.components.pulse.shared :as shared]))

(defn- fmt-int [v]
  (if (number? v) (str/replace (format "%,d" (long v)) "," " ") "—"))

(defn- fmt-rub [v]
  (if (number? v) (str (format "%.2f" (double v)) " ₽") "—"))

(defn- fmt-pct [v]
  (if (number? v) (str (format "%.2f" (double v)) "%") "—"))

(defn- fmt-mult [v]
  (if (number? v) (format "%.2f" (double v)) "—"))

(defn- tile [label value formatter]
  [:div.border.rounded.p-3
   [:div.text-xs.uppercase.text-gray-500 label]
   [:div.text-lg.font-semibold.mt-1 (formatter value)]])

(defn render [data]
  (let [d (or data {})
        body (if (empty? d)
               (shared/empty-state "Реклама и трафик")
               [:div.grid.grid-cols-2.sm:grid-cols-6.gap-3
                (tile "Показы"  (:impressions d) fmt-int)
                (tile "Клики"   (:clicks d)      fmt-int)
                (tile "CTR"     (:ctr-pct d)     fmt-pct)
                (tile "CPC"     (:cpc-rub d)     fmt-rub)
                (tile "ROMI"    (:romi d)        fmt-mult)
                (tile "ДРР"     (:drr-pct d)     fmt-pct)])]
    (shared/section-card
      {:title    "Реклама и трафик"
       :subtitle "Воронка показ → клик и эффективность"
       :body     body})))
