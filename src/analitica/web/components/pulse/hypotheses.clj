(ns analitica.web.components.pulse.hypotheses
  (:require [analitica.web.components.pulse.shared :as shared]))

(defn render
  "Stub. Accepts (and ignores) a data map; returns a hiccup section."
  [_data]
  (shared/section-card
    {:title    "Гипотезы"
     :subtitle "What-if сценарии и трекинг"
     :body     (shared/coming-soon "Гипотезы")}))
