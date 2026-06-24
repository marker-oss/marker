(ns marker.pages.dynamics
  "Wrapper page for the «Динамика» section.
   Tabs (all schema-driven reports today; charts come in Phase 3):
     :trends → trends report
     :sales  → sales report
     :geo    → geography report
     :buyout → buyout report"
  (:require [uix.core             :refer [$ defui]]
            [marker.ui.chrome     :refer [tabs]]
            [marker.util.nav      :as nav]
            [marker.router        :as router]
            [marker.pages.reports :as reports]))

(defui dynamics
  [{:keys [tab]}]
  (let [active (or tab :trends)]
    ($ :<> {}
       ($ tabs {:items     (nav/section-tabs :dynamics)
                :active    active
                :on-change (fn [t] (router/nav! [:dynamics t]))})
       (case active
         :trends ($ reports/report {:type :trends})
         :sales  ($ reports/report {:type :sales})
         :geo    ($ reports/report {:type :geo})
         :buyout ($ reports/report {:type :buyout})
         ($ reports/report {:type :trends})))))
