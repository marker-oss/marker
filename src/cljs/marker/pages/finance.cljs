(ns marker.pages.finance
  "Wrapper page for the «Финансы» section.
   Renders a tabs strip and dispatches to the active sub-component:
     :pnl        → marker.pages.pnl
     :unit-calc  → marker.pages.unit (what-if calculator)
     :unit-table → schema-driven UE report (was report:ue)
     :returns    → schema-driven returns report
     :losses     → schema-driven losses report
     :finance    → schema-driven финансовый отчёт
     :plan-fact  → marker.pages.plan-fact (per-SKU План/Факт stub)"
  (:require [uix.core             :refer [$ defui]]
            [marker.ui.chrome     :refer [tabs]]
            [marker.util.nav      :as nav]
            [marker.router        :as router]
            [marker.pages.pnl     :as pnl]
            [marker.pages.unit    :as unit]
            [marker.pages.reports :as reports]
            [marker.pages.plan-fact :as plan-fact]
            [marker.pages.reconciliation :as reconciliation]))

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
         :reconciliation ($ reconciliation/reconciliation {})
         :plan-fact  ($ plan-fact/plan-fact {})
         ($ pnl/pnl {})))))
