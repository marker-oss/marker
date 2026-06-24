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
            [marker.pages.finance  :as finance]
            [marker.pages.products :as products]
            [marker.pages.dynamics :as dynamics]
            [marker.pages.sync     :as sync-page]
            [marker.pages.settings :as settings-page]
            [marker.pages.feedback :as feedback]))

;; ---------------------------------------------------------------------------
;; Page metadata
;; ---------------------------------------------------------------------------

(def ^:private page-titles
  "Top-level section titles."
  {:pulse    "Главная"
   :finance  "Финансы"
   :products "Товары"
   :dynamics "Динамика"
   :sync     "Синхронизация"
   :settings "Настройки"})

(def ^:private tab-titles
  "Per-section tab titles, keyed by [:section :tab]."
  {[:finance :pnl]        "P&L"
   [:finance :unit-calc]  "Юнит-эк (калькулятор)"
   [:finance :unit-table] "Юнит-эк (таблица)"
   [:finance :returns]    "Возвраты"
   [:finance :losses]     "Потери"
   [:finance :finance]    "Финансовый отчёт"
   [:finance :plan-fact]  "План/Факт"
   [:products :skus]        "SKU-список"
   [:products :stocks]      "Склады"
   [:products :abc]         "ABC"
   [:products :cost-prices] "Себестоимость"
   [:products :storage]     "Хранение"
   [:dynamics :trends] "Тренды"
   [:dynamics :sales]  "Продажи"
   [:dynamics :geo]    "География"
   [:dynamics :buyout] "Выкуп"})

(defn- page-section
  "Return the section keyword for any page shape."
  [page]
  (cond
    (vector? page) (first page)
    :else          page))

(defn- page-title-for [page]
  (cond
    (and (vector? page) (= 2 (count page)))
    (or (get tab-titles page)
        (get page-titles (first page))
        (name (first page)))

    (keyword? page)
    (get page-titles page (name page))

    :else "Marker"))

(defn- crumbs-for [page]
  (cond
    (and (vector? page) (= 2 (count page)))
    ["Marker"
     (or (get page-titles (first page)) (name (first page)))
     (or (get tab-titles page) (name (second page)))]

    :else
    ["Marker" (page-title-for page)]))

(def ^:private mp-display-names
  {:wb   "Wildberries"
   :ozon "Ozon"
   :ym   "Yandex Market"})

(defn- mp-subtitle
  "Render MP filter as a human-readable phrase. Used by the Pulse
   subtitle to make «который маркетплейс смотрим» обвязочным."
  [mp-filter]
  (cond
    (or (nil? mp-filter)
        (and (coll? mp-filter) (>= (count mp-filter) 3)))
    "по всем маркетплейсам"

    (and (coll? mp-filter) (= 1 (count mp-filter)))
    (str "— " (get mp-display-names (first mp-filter) (name (first mp-filter))))

    (keyword? mp-filter)
    (str "— " (get mp-display-names mp-filter (name mp-filter)))

    :else
    "по всем маркетплейсам"))

(defn- page-subtitle-for [page mp-filter]
  (case (page-section page)
    :finance  "Финансовая отчётность и юнит-экономика"
    :products "Товарный каталог, остатки и себестоимость"
    :dynamics "Динамика продаж, география и тренды"
    :pulse    (str "Анализ данных " (mp-subtitle mp-filter))
    :sync     "Синхронизация с маркетплейсами"
    (str "Анализ данных " (mp-subtitle mp-filter))))

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

    ($ :<> {}
       ($ :div {:class "app"}
          ;; Sidebar
          ($ sidebar {:active    (cond
                                   (and (vector? page) (= 2 (count page)))
                                   (str (name (first page)) "/" (name (second page)))

                                   (keyword? page)
                                   (name page)

                                   :else "pulse")
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
                   ($ :p {:class "page-subtitle"} (page-subtitle-for page mps)))
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

             ;; Page content — dispatch by section, pass tab to wrappers.
             (let [section (page-section page)
                   tab     (when (vector? page) (second page))]
               (case section
                 :pulse    ($ pulse/pulse    {})
                 :sync     ($ sync-page/sync-page {})
                 :settings ($ settings-page/settings {})
                 :finance  ($ finance/finance   {:tab tab})
                 :products ($ products/products {:tab tab})
                 :dynamics ($ dynamics/dynamics {:tab tab})
                 ($ pulse/pulse {})))))

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
                           (router/nav! page-id))})

       ;; Floating feedback widget (visible on every page)
       ($ feedback/widget {}))))

;; ---------------------------------------------------------------------------
;; Mount
;; ---------------------------------------------------------------------------

(defonce root
  (uix.dom/create-root (js/document.getElementById "root")))

(defn ^:export init []
  (rf/dispatch-sync [::events/initialize-db])
  (router/init!)
  (uix.dom/render-root (err/boundary {} ($ app-shell)) root))
