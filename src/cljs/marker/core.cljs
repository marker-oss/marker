(ns marker.core
  "Marker SPA — Phase 3 entry point.
   Mounts the full app shell: Sidebar + Topbar + filterbar + placeholder content.
   Phase 4 will add re-frame app-db + reitit routing."
  (:require [uix.core :refer [$ defui use-state]]
            [uix.dom]
            [marker.ui.chrome :refer [sidebar topbar mp-filter period-selector sync-banner]]
            [marker.ui.icons  :refer [icon]]))

;; Nav-id → human title map for breadcrumbs
(def ^:private page-titles
  {"pulse"    "Главная (Pulse)"
   "pnl"      "P&L"
   "unit"     "Юнит-экономика"
   "returns"  "Возвраты"
   "products" "Товары"
   "warehouse""Склады"
   "plan"     "План"
   "kit"      "UI Kit"})

(defn- crumbs-for [page-id]
  (let [finance-children #{"pnl" "unit" "returns"}]
    (if (contains? finance-children page-id)
      ["Marker" "Финансы" (get page-titles page-id)]
      ["Marker" (get page-titles page-id page-id)])))

(defui app-shell []
  (let [[page        set-page!]        (use-state "pulse")
        [collapsed?  set-collapsed!]   (use-state false)
        [mps         set-mps!]         (use-state [:wb :ozon :ym])
        [period      set-period!]      (use-state "Последние 30 дней")
        [compare?    set-compare!]     (use-state false)
        [theme       set-theme!]       (use-state "light")
        [sync-state  set-sync-state!]  (use-state nil)]
    ($ :div {:data-theme   theme
             :data-sidebar (when collapsed? "collapsed")}
       ($ :div {:class "app"}
          ;; Sidebar
          ($ sidebar {:active     page
                      :on-nav     set-page!
                      :collapsed  collapsed?})
          ;; Main column
          ($ :div {:class "page"}
             ;; Topbar
             ($ topbar {:crumbs             (crumbs-for page)
                        :on-search          #(js/console.log "search")
                        :on-theme           #(set-theme! (if (= theme "dark") "light" "dark"))
                        :theme              theme
                        :on-sidebar-toggle  #(set-collapsed! (not collapsed?))
                        :on-sync            #(set-sync-state!
                                              {:kind     :running
                                               :section  "WB"
                                               :elapsed  "0s"
                                               :progress 30})})
             ;; Sync banner (shown when sync-state is non-nil)
             (when sync-state
               ($ sync-banner {:state    sync-state
                                :on-close #(set-sync-state! nil)}))
             ;; Page header
             ($ :div {:class "page-header"}
                ($ :div
                   ($ :h1 {:class "page-title"}
                      (get page-titles page page))
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
                                  :on-change set-mps!}))
                ($ :div {:class "filterbar-group"}
                   ($ :span {:class "filterbar-label"} "Период")
                   ($ period-selector {:value      period
                                        :on-change  set-period!
                                        :compare    compare?
                                        :on-compare set-compare!})))
             ;; Content placeholder
             ($ :div {:class "page-content"}
                ($ :div {:class "card section-card"
                          :style {:text-align  "center"
                                  :padding     "64px 32px"
                                  :color       "var(--color-fg-muted)"}}
                   ($ :div {:style {:font-size   "32px"
                                    :margin-bottom "12px"}}
                      "📊")
                   ($ :p {:style {:font-size  "15px"
                                  :font-weight 600
                                  :margin     "0 0 6px"
                                  :color      "var(--color-fg-primary)"}}
                      (str "Страница «" (get page-titles page page) "»"))
                   ($ :p {:style {:font-size "13px" :margin 0}}
                      "Phase 4: routing comes next."))))))))

(defonce root
  (uix.dom/create-root (js/document.getElementById "root")))

(defn ^:export init []
  (uix.dom/render-root ($ app-shell) root))
