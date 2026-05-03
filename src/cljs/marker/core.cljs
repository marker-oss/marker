(ns marker.core
  "Marker SPA — Phase 4 entry point.
   Initialises re-frame app-db (with localStorage tweak merge) and
   reitit-frontend router.  All UI state lives in app-db; local use-state
   has been removed.  DOM attribute side-effects (data-theme / data-density /
   data-sidebar) are driven by a use-effect that watches the relevant subs."
  (:require [uix.core :refer [$ defui use-effect]]
            [uix.dom]
            [re-frame.core         :as rf]
            [uix.re-frame          :refer [use-subscribe]]
            [marker.router         :as router]
            [marker.state.subs     :as subs]
            [marker.state.events   :as events]
            [marker.ui.chrome      :refer [sidebar topbar mp-filter period-selector sync-banner]]
            [marker.ui.icons       :refer [icon]]
            [marker.ui.tweaks      :refer [tweaks-panel]]
            [marker.pages.pulse    :as pulse]))

;; ---------------------------------------------------------------------------
;; Page metadata
;; ---------------------------------------------------------------------------

(def ^:private page-titles
  {:pulse     "Главная (Pulse)"
   :pnl       "P&L"
   :unit      "Юнит-экономика"
   :returns   "Возвраты"
   :products  "Товары"
   :warehouse "Склады"
   :plan      "План"
   :kit       "UI Kit"})

(defn- crumbs-for [page]
  (let [finance-children #{:pnl :unit :returns}]
    (if (contains? finance-children page)
      ["Marker" "Финансы" (get page-titles page)]
      ["Marker" (get page-titles page (name page))])))

;; ---------------------------------------------------------------------------
;; Root component
;; ---------------------------------------------------------------------------

(defui app-shell []
  ;; --- Subscribe to all app-db slices ---
  (let [page      (use-subscribe [::subs/page])
        collapsed (use-subscribe [::subs/sidebar-collapsed])
        mps       (use-subscribe [::subs/mp-filter])
        period    (use-subscribe [::subs/period])
        compare?  (use-subscribe [::subs/compare])
        theme     (use-subscribe [::subs/theme])
        density   (use-subscribe [::subs/density])
        sync-st   (use-subscribe [::subs/sync-state])]

    ;; --- Apply tweaks to documentElement (mirrors prototype useEffect) ---
    (use-effect
     (fn []
       (let [ds (.-dataset js/document.documentElement)]
         (set! (.-theme ds)    theme)
         (set! (.-density ds)  density)
         (set! (.-sidebar ds)  (if collapsed "collapsed" "expanded")))
       js/undefined)
     [theme density collapsed])

    ($ :<>
       ($ :div {:class "app"}
        ;; Sidebar — :on-nav calls router/nav! so URL is the source of truth
        ($ sidebar {:active    (name page)
                   :on-nav    router/nav!
                   :collapsed collapsed})

       ;; Main column
       ($ :div {:class "page"}
          ;; Topbar
          ($ topbar {:crumbs            (crumbs-for page)
                     :on-search         #(rf/dispatch [::events/open-cmdk])
                     :on-theme          #(rf/dispatch [::events/set-theme
                                                       (if (= theme "dark") "light" "dark")])
                     :theme             theme
                     :on-sidebar-toggle #(rf/dispatch [::events/toggle-sidebar])
                     :on-sync           #(rf/dispatch [::events/set-sync-state
                                                       {:kind     :running
                                                        :section  "WB"
                                                        :elapsed  "0s"
                                                        :progress 30}])
                     :on-tweaks         #(rf/dispatch [::events/toggle-tweaks])})

          ;; Sync banner
          (when sync-st
            ($ sync-banner {:state    sync-st
                             :on-close #(rf/dispatch [::events/set-sync-state nil])}))

          ;; Page header
          ($ :div {:class "page-header"}
             ($ :div
                ($ :h1 {:class "page-title"}
                   (get page-titles page (name page)))
                ($ :p {:class "page-subtitle"}
                   "Анализ данных по всем маркетплейсам"))
             ($ :div {:class "page-actions"}
                ($ :button {:class "btn btn-secondary"}
                   ($ icon {:name :download :size 14})
                   "Экспорт")))

          ;; Filterbar
          ($ :div {:class "filterbar"}
             ($ :div {:class "filterbar-group"}
                ($ :span {:class "filterbar-label"} "МП")
                ($ mp-filter {:value     mps
                               :on-change #(rf/dispatch [::events/set-mp-filter %])}))
             ($ :div {:class "filterbar-group"}
                ($ :span {:class "filterbar-label"} "Период")
                ($ period-selector {:value      period
                                     :on-change  #(rf/dispatch [::events/set-period %])
                                     :compare    compare?
                                     :on-compare #(rf/dispatch [::events/set-compare %])})))

          ;; Page content — dispatch by route
          (if (= page :pulse)
            ($ pulse/pulse {})
            ($ :div {:class "page-content"}
               ($ :div {:class "card section-card"
                         :style {:text-align    "center"
                                 :padding       "64px 32px"
                                 :color         "var(--color-fg-muted)"}}
                  ($ :div {:style {:font-size     "32px"
                                    :margin-bottom "12px"}}
                     "📊")
                  ($ :p {:style {:font-size   "15px"
                                  :font-weight 600
                                  :margin      "0 0 6px"
                                  :color       "var(--color-fg-primary)"}}
                     (str "Страница «" (get page-titles page (name page)) "»"))
                  ($ :p {:style {:font-size "13px" :margin 0}}
                     "Phase 6: страничный контент приходит следующим."))))))

       ;; Tweaks panel (portals out of the page flow, fixed position)
       ($ tweaks-panel {}))))

;; ---------------------------------------------------------------------------
;; Mount
;; ---------------------------------------------------------------------------

(defonce root
  (uix.dom/create-root (js/document.getElementById "root")))

(defn ^:export init []
  (rf/dispatch-sync [::events/initialize-db])
  (router/init!)
  (uix.dom/render-root ($ app-shell) root))
