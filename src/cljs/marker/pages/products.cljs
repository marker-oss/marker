(ns marker.pages.products
  "Wrapper page for the «Товары» section.
   Tabs:
     :skus        → SKU-список (marker.pages.products.skus)
     :stocks      → Склады (Phase 2 — placeholder until shipped)
     :abc         → schema-driven ABC report
     :cost-prices → marker.pages.cost-prices (upload + table)
     :storage     → Платное хранение (Phase 4 placeholder)"
  (:require [uix.core                  :refer [$ defui]]
            [marker.ui.chrome          :refer [tabs]]
            [marker.util.nav           :as nav]
            [marker.router             :as router]
            [marker.pages.products.skus :as skus]
            [marker.pages.cost-prices  :as cost-prices]
            [marker.pages.reports      :as reports]))

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

(defui products
  [{:keys [tab]}]
  (let [active (or tab :skus)]
    ($ :<>
       ($ tabs {:items     (nav/section-tabs :products)
                :active    active
                :on-change (fn [t] (router/nav! [:products t]))})
       (case active
         :skus        ($ skus/skus {})
         :stocks      ($ placeholder-tab {:title "Склады"})
         :abc         ($ reports/report {:type :abc})
         :cost-prices ($ cost-prices/cost-prices {})
         :storage     ($ placeholder-tab {:title "Хранение"})
         ($ skus/skus {})))))
