(ns analitica.web.components.pulse.custom
  (:require [analitica.web.components.pulse.shared :as shared]))

(defn render
  "Stub. User-defined metric block — placeholder for next iteration."
  [_data]
  (shared/section-card
    {:title    "Кастомная метрика"
     :subtitle "Своя формула на ваших данных"
     :body     (shared/coming-soon "Кастомная метрика")}))
