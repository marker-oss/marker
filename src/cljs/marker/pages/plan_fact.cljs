(ns marker.pages.plan-fact
  "Per-SKU «План/Факт» surface (STUB).
   Rendered by the Финансы section's :plan-fact tab
   (see marker.pages.finance). Real plan-vs-fact tables and the
   per-SKU drill-down are filled by a later phase."
  (:require [uix.core :refer [$ defui]]))

(defui plan-fact
  [_]
  ($ :div {:class "page-content"}
     ($ :div {:class "card section-card"
              :style {:text-align "center" :padding "48px 24px"
                      :color "var(--color-fg-muted)"}}
        ($ :p {:style {:font-size   "14px" :font-weight 600
                       :margin      "0 0 6px"
                       :color       "var(--color-fg-primary)"}}
           "«План/Факт»")
        ($ :p {:style {:font-size "12px" :margin 0}}
           "Скоро."))))
