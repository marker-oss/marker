(ns marker.pages.finance
  "Wrapper page for the «Финансы» section.
   Renders a tabs strip and dispatches to the active sub-component:
     :pnl        → marker.pages.pnl
     :unit-calc  → marker.pages.unit (what-if calculator)
     :unit-table → schema-driven UE report (was report:ue)
     :returns    → schema-driven returns report
     :losses     → schema-driven losses report
     :finance    → schema-driven финансовый отчёт
     :plan-fact  → placeholder (Phase 5)"
  (:require [uix.core             :refer [$ defui]]
            [marker.ui.chrome     :refer [tabs]]
            [marker.util.nav      :as nav]
            [marker.router        :as router]
            [marker.pages.pnl     :as pnl]
            [marker.pages.unit    :as unit]
            [marker.pages.reports :as reports]))

(defui ^:private placeholder-tab [{:keys [title]}]
  ($ :div {:class "page-content"}
     ($ :div {:class "card section-card"
              :style {:text-align "center" :padding "48px 24px"
                      :color "var(--color-fg-muted)"}}
        ($ :p {:style {:font-size   "14px" :font-weight 600
                       :margin      "0 0 6px"
                       :color       "var(--color-fg-primary)"}}
           (str "«" title "»"))
        ($ :p {:style {:font-size "12px" :margin 0}}
           "Будет реализована в следующей фазе."))))

(defui finance
  [{:keys [tab]}]
  (let [active (or tab :pnl)]
    ($ :<> {}
       ($ tabs {:items     (nav/section-tabs :finance)
                :active    active
                :on-change (fn [t] (router/nav! [:finance t]))})
       (case active
         :pnl        ($ pnl/pnl  {})
         :unit-calc  ($ unit/unit {})
         :unit-table ($ reports/report {:type :ue})
         :returns    ($ reports/report {:type :returns})
         :losses     ($ reports/report {:type :losses})
         :finance    ($ reports/report {:type :finance})
         :plan-fact  ($ placeholder-tab {:title "План/Факт"})
         ($ pnl/pnl {})))))
