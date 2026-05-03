(ns marker.core
  "Marker SPA — Phase 6 entry point.
   Dispatches to all implemented pages.
   Installs global Cmd+K keyboard binding and Sheet/CmdK overlays."
  (:require [uix.core :refer [$ defui use-effect]]
            [uix.dom]
            [re-frame.core         :as rf]
            [uix.re-frame          :refer [use-subscribe]]
            [marker.router         :as router]
            [marker.api            :as api] ; registers :http-xhrio effect handler
            [marker.state.subs     :as subs]
            [marker.state.events   :as events]
            [marker.ui.chrome      :refer [sidebar topbar mp-filter period-selector
                                           sync-banner sheet cmdk]]
            [marker.ui.icons       :refer [icon]]
            [marker.ui.tweaks      :refer [tweaks-panel]]
            [marker.ui.sku-sheet   :refer [sku-sheet-content]]
            [marker.ui.error       :as err]
            [marker.pages.pulse    :as pulse]
            [marker.pages.pnl      :as pnl]
            [marker.pages.unit     :as unit]
            [marker.pages.products :as products]
            [marker.pages.reports  :as reports]
            [marker.pages.cost-prices :as cost-prices]
            [marker.pages.kit      :as kit]))

;; ---------------------------------------------------------------------------
;; Page metadata
;; ---------------------------------------------------------------------------

(def ^:private page-titles
  {:pulse        "Главная (Pulse)"
   :pnl          "P&L"
   :unit         "Юнит-экономика"
   :products     "Товары"
   :cost-prices  "Себестоимость"
   :plan         "План"
   :kit          "UI Kit"})

(def ^:private report-titles
  {:sales   "Продажи"
   :finance "Финансы"
   :ue      "Юнит-экономика"
   :abc     "ABC-анализ"
   :stock   "Остатки"
   :returns "Возвраты"
   :buyout  "Выкуп"
   :geo     "География"
   :trends  "Тренды"
   :losses  "Потери"})

(defn- page-key
  "Reduce a page value (kw or [:report :finance]) to a stable lookup key."
  [page]
  (cond
    (vector? page) (first page)
    :else page))

(defn- page-title-for [page]
  (cond
    (and (vector? page) (= :report (first page)))
    (get report-titles (second page) (name (or (second page) "")))

    :else
    (get page-titles page (name page))))

(defn- crumbs-for [page]
  (let [finance-children #{:pnl :unit}]
    (cond
      (and (vector? page) (= :report (first page)))
      ["Marker" "Отчёты" (page-title-for page)]

      (contains? finance-children page)
      ["Marker" "Финансы" (page-title-for page)]

      :else
      ["Marker" (page-title-for page)])))

;; ---------------------------------------------------------------------------
;; Placeholder card (for routes not yet implemented)
;; ---------------------------------------------------------------------------

(defui ^:private placeholder-page [{:keys [title]}]
  ($ :div {:class "page-content"}
     ($ :div {:class "card section-card"
              :style {:text-align    "center"
                      :padding       "64px 32px"
                      :color         "var(--color-fg-muted)"}}
        ($ :div {:style {:font-size "32px" :margin-bottom "12px"}} "📊")
        ($ :p {:style {:font-size   "15px"
                       :font-weight 600
                       :margin      "0 0 6px"
                       :color       "var(--color-fg-primary)"}}
           (str "Страница «" title "»"))
        ($ :p {:style {:font-size "13px" :margin 0}}
           "Будет реализована в следующей фазе."))))

;; ---------------------------------------------------------------------------
;; Root component
;; ---------------------------------------------------------------------------

(defui app-shell []
  ;; --- Subscribe to all app-db slices ---
  (let [page        (use-subscribe [::subs/page])
        collapsed   (use-subscribe [::subs/sidebar-collapsed])
        mps         (use-subscribe [::subs/mp-filter])
        period      (use-subscribe [::subs/period])
        compare?    (use-subscribe [::subs/compare])
        theme       (use-subscribe [::subs/theme])
        density     (use-subscribe [::subs/density])
        sync-st     (use-subscribe [::subs/sync-state])
        cmdk-open?  (use-subscribe [::subs/cmdk-open])
        sheet-sku   (use-subscribe [::subs/sheet-sku])]

    ;; --- Apply tweaks to documentElement ---
    (use-effect
     (fn []
       (let [ds (.-dataset js/document.documentElement)]
         (set! (.-theme ds)    theme)
         (set! (.-density ds)  density)
         (set! (.-sidebar ds)  (if collapsed "collapsed" "expanded")))
       js/undefined)
     [theme density collapsed])

    ;; --- Global Cmd+K keyboard binding (installed once; cleanup on unmount) ---
    (use-effect
     (fn []
       (let [on-key (fn [e]
                      (when (and (or (.-metaKey e) (.-ctrlKey e))
                                 (= (.-key e) "k"))
                        (.preventDefault e)
                        (rf/dispatch [::events/open-cmdk])))]
         (.addEventListener js/window "keydown" on-key)
         #(.removeEventListener js/window "keydown" on-key)))
     [])

    ($ :<>
       ($ :div {:class "app"}
          ;; Sidebar
          ($ sidebar {:active    (let [pk (page-key page)]
                                   (if (and (vector? page) (= :report (first page)))
                                     (str "report:" (name (second page)))
                                     (name pk)))
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
                        :on-sync           #(rf/dispatch [::events/sync-and-refresh])
                        :on-tweaks         #(rf/dispatch [::events/toggle-tweaks])})

             ;; Sync banner
             (when sync-st
               ($ sync-banner {:state    sync-st
                               :on-close #(rf/dispatch [::events/set-sync-state nil])}))

             ;; Page header
             ($ :div {:class "page-header"}
                ($ :div
                   ($ :h1 {:class "page-title"} (page-title-for page))
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
             (cond
               (and (vector? page) (= :report (first page)))
               ($ reports/report {:type (second page)})

               :else
               (case page
                 :pulse        ($ pulse/pulse {})
                 :pnl          ($ pnl/pnl {})
                 :unit         ($ unit/unit {})
                 :products     ($ products/products {})
                 :cost-prices  ($ cost-prices/cost-prices {})
                 :kit          ($ kit/kit {})
                 ;; Placeholder for routes not yet implemented
                 ($ placeholder-page {:title (page-title-for page)})))))

       ;; Tweaks panel (portals out of the page flow, fixed position)
       ($ tweaks-panel {})

       ;; Global SKU sheet
       ($ sheet {:open?    (boolean sheet-sku)
                 :on-close #(rf/dispatch [::events/close-sheet])}
          ($ sku-sheet-content {}))

       ;; Global Cmd+K palette
       ($ cmdk {:open?   cmdk-open?
                :on-close #(rf/dispatch [::events/close-cmdk])
                :on-nav  (fn [page-id]
                           (router/nav! page-id))}))))

;; ---------------------------------------------------------------------------
;; Mount
;; ---------------------------------------------------------------------------

(defonce root
  (uix.dom/create-root (js/document.getElementById "root")))

(defn ^:export init []
  (rf/dispatch-sync [::events/initialize-db])
  (router/init!)
  (uix.dom/render-root (err/boundary {} ($ app-shell)) root))
