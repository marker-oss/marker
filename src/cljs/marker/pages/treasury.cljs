(ns marker.pages.treasury
  "Wrapper page for the «Казначейство» section (STUB).
   Tabs:
     :cashflow    → ДДС (движение денежных средств) — later phase
     :registry    → Реестр — later phase
     :obligations → Обязательства — later phase

   This is scaffolding only: it routes, renders the 3-tab strip and
   switches placeholder panels. Real content is filled by a later
   phase. The active tab arrives as the :tab prop from marker.core
   (sectioned-route dispatch); ::subs/active-tab is the app-db source
   of truth for the same value."
  (:require [uix.core          :refer [$ defui]]
            [uix.re-frame      :refer [use-subscribe]]
            [marker.ui.chrome  :refer [tabs]]
            [marker.util.nav   :as nav]
            [marker.router     :as router]
            [marker.state.subs :as subs]))

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
           "Скоро."))))

(defui treasury
  [{:keys [tab]}]
  (let [active-sub (use-subscribe [::subs/active-tab])
        active     (or tab active-sub :cashflow)]
    ($ :<> {}
       ($ tabs {:items     (nav/section-tabs :treasury)
                :active    active
                :on-change (fn [t] (router/nav! [:treasury t]))})
       (case active
         :cashflow    ($ placeholder-tab {:title "ДДС — скоро"})
         :registry    ($ placeholder-tab {:title "Реестр — скоро"})
         :obligations ($ placeholder-tab {:title "Обязательства — скоро"})
         ($ placeholder-tab {:title "ДДС — скоро"})))))
