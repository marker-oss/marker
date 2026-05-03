(ns marker.pages.products
  "Products catalog page — Phase 8.
   Data from /api/v1/marker/sku-list via ::events/load-sku-list.
   Grid/list toggle + SKU drill-down sheet."
  (:require [uix.core :refer [$ defui use-state use-memo use-effect]]
            [uix.re-frame :refer [use-subscribe]]
            [re-frame.core :as rf]
            [marker.state.subs   :as subs]
            [marker.state.events :as events]
            [marker.ui.chrome    :refer [delta mp-badge sparkline]]
            [marker.ui.icons     :refer [icon]]
            [marker.util.format  :as fmt]))

(defn- safe-num [v] (if (and (some? v) (not (js/isNaN v))) v 0))

;; ---------------------------------------------------------------------------
;; Loading skeletons
;; ---------------------------------------------------------------------------

(defui ^:private skel-card []
  ($ :div {:class "skel"
           :style {:border-radius "var(--radius-lg)"
                   :height        "180px"}}))

(defui ^:private products-skeleton []
  ($ :div {:class "page-content"}
     ($ :section {:class "card section-card"}
        ($ :div {:style {:display               "grid"
                         :grid-template-columns "repeat(auto-fill, minmax(280px, 1fr))"
                         :gap                   "14px"}}
           (for [i (range 8)]
             ($ skel-card {:key i}))))))

;; ---------------------------------------------------------------------------
;; Error banner
;; ---------------------------------------------------------------------------

(defui ^:private error-banner [{:keys [message on-retry]}]
  ($ :div {:class "alert alert-danger" :style {:margin-bottom "12px"}}
     ($ icon {:name :danger :class "alert-icon"})
     ($ :div {:class "alert-body"}
        ($ :div {:class "alert-title"} "Не удалось загрузить товары")
        ($ :div (or message "Проверьте соединение с сервером.")))
     ($ :button {:class "btn btn-ghost btn-sm"
                 :style {:color "inherit" :border "1px solid currentColor"}
                 :on-click on-retry}
        "Повторить")))

;; ---------------------------------------------------------------------------
;; Grid card
;; ---------------------------------------------------------------------------

(defui ^:private sku-card [{:keys [sku]}]
  ($ :button
     {:class    "sku-card"
      :on-click #(rf/dispatch [::events/open-sheet-and-load (:id sku)])
      :style    {:background    "var(--color-bg-surface)"
                 :border        "1px solid var(--color-border-subtle)"
                 :border-radius "var(--radius-lg)"
                 :padding       "14px"
                 :text-align    "left"
                 :display       "flex"
                 :flex-direction "column"
                 :gap           "8px"
                 :cursor        "pointer"
                 :transition    "border-color 100ms, box-shadow 100ms"}}
     ($ :div {:style {:aspect-ratio   "16/10"
                      :background     "repeating-linear-gradient(135deg, var(--color-bg-subtle), var(--color-bg-subtle) 8px, var(--color-bg-muted) 8px, var(--color-bg-muted) 16px)"
                      :border-radius  "6px"
                      :display        "grid"
                      :place-items    "center"
                      :color          "var(--color-fg-muted)"
                      :font-family    "var(--font-mono)"
                      :font-size      "11px"}}
        (:id sku))
     ($ :div {:class "row"}
        ($ :span {:class "mono"
                  :style {:font-size "11px" :color "var(--color-fg-muted)"}}
           (:id sku))
        (for [m (:mp sku)]
          ($ mp-badge {:key (name m) :mp m})))
     ($ :div {:style {:font-size "13px" :font-weight 500}}
        (or (:name sku) (:id sku)))
     ($ :div {:class "row" :style {:margin-top "auto"}}
        ($ :span {:class "mono" :style {:font-weight 600}}
           (fmt/format-rub (safe-num (:revenue sku))))
        ($ delta {:pct (:delta-pct sku)})
        ($ :div {:class "spacer"})
        ;; spark may be [] from API — sparkline handles empty gracefully
        (when (seq (:spark sku))
          ($ sparkline {:data (:spark sku) :width 60 :height 20})))))

;; ---------------------------------------------------------------------------
;; List row (table)
;; ---------------------------------------------------------------------------

(defui ^:private sku-list [{:keys [visible]}]
  (let [[sort-key  set-sort-key!]  (use-state :revenue)
        [sort-dir  set-sort-dir!]  (use-state :desc)
        sorted (use-memo
                (fn []
                  (let [cmp (if (= sort-dir :desc) > <)]
                    (sort-by #(safe-num (get % sort-key)) cmp visible)))
                [visible sort-key sort-dir])
        sort-click! (fn [k]
                      (if (= sort-key k)
                        (set-sort-dir! (if (= sort-dir :asc) :desc :asc))
                        (do (set-sort-key! k)
                            (set-sort-dir! :desc))))
        sort-icon (fn [k]
                    (when (= sort-key k)
                      ($ icon {:name (if (= sort-dir :asc) :arrow-up :arrow-down)
                               :size 12})))]
    ($ :table {:class "tbl"}
       ($ :thead
          ($ :tr
             ($ :th {:class "tbl-sortable" :on-click #(sort-click! :id)}
                "Артикул " (sort-icon :id))
             ($ :th "МП")
             ($ :th {:class "num tbl-sortable" :on-click #(sort-click! :orders)}
                "Заказы " (sort-icon :orders))
             ($ :th {:class "num tbl-sortable" :on-click #(sort-click! :revenue)}
                "Выручка " (sort-icon :revenue))
             ($ :th {:class "num tbl-sortable" :on-click #(sort-click! :margin)}
                "Маржа " (sort-icon :margin))
             ($ :th {:class "num tbl-sortable" :on-click #(sort-click! :stock)}
                "Остаток " (sort-icon :stock))
             ($ :th)))
       ($ :tbody
          (for [s sorted]
            ($ :tr {:key      (:id s)
                    :style    {:cursor "pointer"}
                    :on-click #(rf/dispatch [::events/open-sheet-and-load (:id s)])}
               ($ :td
                  ($ :span {:class "tbl-link"} (:id s))
                  " · "
                  (or (:name s) ""))
               ($ :td
                  (for [m (:mp s)]
                    ($ mp-badge {:key (name m) :mp m})))
               ($ :td {:class "num mono"} (fmt/format-int (safe-num (:orders s))))
               ($ :td {:class "num mono"} (fmt/format-rub (safe-num (:revenue s))))
               ($ :td {:class "num mono"} (fmt/format-pct (safe-num (:margin s))))
               ($ :td {:class "num mono"} (fmt/format-int (safe-num (:stock s))))
               ($ :td
                  (when (seq (:spark s))
                    ($ sparkline {:data (:spark s) :width 60 :height 20})))))))))

;; ---------------------------------------------------------------------------
;; Page root
;; ---------------------------------------------------------------------------

(defui products []
  (let [mps         (use-subscribe [::subs/mp-filter])
        period      (use-subscribe [::subs/period])
        compare?    (use-subscribe [::subs/compare])
        skus-raw    (use-subscribe [::subs/sku-list-data])
        loading?    (use-subscribe [::subs/sku-list-loading?])
        api-errors  (use-subscribe [::subs/api-errors])
        error-msg   (get-in api-errors ["/api/v1/marker/sku-list" :message])
        [view set-view!] (use-state :grid)
        fs          {:mp-filter mps :period period :compare compare?}]

    (use-effect
     (fn []
       (rf/dispatch [::events/load-sku-list
                     {:mp-filter mps :period period :compare compare?}])
       js/undefined)
     [])

    (use-effect
     (fn []
       (rf/dispatch [::events/load-sku-list
                     {:mp-filter mps :period period :compare compare?}])
       js/undefined)
     [mps period compare?])

    (cond
      (and loading? (nil? skus-raw))
      ($ products-skeleton)

      (and error-msg (nil? skus-raw))
      ($ :div {:class "page-content"}
         ($ error-banner {:message  error-msg
                          :on-retry #(do (rf/dispatch [::events/clear-cache])
                                          (rf/dispatch [::events/load-sku-list fs]))}))

      :else
      (let [skus    (or skus-raw [])
            visible (filterv (fn [s]
                               (or (empty? mps)
                                   (some (set mps) (:mp s))))
                             skus)]

        ($ :div {:class "page-content"}
           (when error-msg
             ($ error-banner {:message  error-msg
                              :on-retry #(do (rf/dispatch [::events/clear-cache])
                                              (rf/dispatch [::events/load-sku-list fs]))}))
           ($ :section {:class "card section-card"}
              ($ :div {:class "section-head"}
                 ($ :div
                    ($ :h3 {:class "section-title"} "Каталог товаров")
                    ($ :div {:class "section-subtitle"}
                       (let [n (count visible)]
                         (str n " " (fmt/plural-ru n "артикул" "артикула" "артикулов")))))
                 ($ :div {:class "row"}
                    ($ :div {:style {:display       "flex"
                                     :border        "1px solid var(--color-border-subtle)"
                                     :border-radius "6px"
                                     :padding       "2px"}}
                       ($ :button {:class    (str "btn btn-sm " (if (= view :grid) "btn-secondary" "btn-ghost"))
                                   :on-click #(set-view! :grid)
                                   :style    {:height "26px"}}
                          ($ icon {:name :layout :size 14})
                          "Карточки")
                       ($ :button {:class    (str "btn btn-sm " (if (= view :list) "btn-secondary" "btn-ghost"))
                                   :on-click #(set-view! :list)
                                   :style    {:height "26px"}}
                          ($ icon {:name :layers :size 14})
                          "Список"))
                    ($ :button {:class "btn btn-secondary btn-sm"}
                       ($ icon {:name :plus :size 14})
                       "Артикул")))

              (if (= view :grid)
                ($ :div {:style {:display               "grid"
                                 :grid-template-columns "repeat(auto-fill, minmax(280px, 1fr))"
                                 :gap                   "14px"}}
                   (for [s visible]
                     ($ sku-card {:key (:id s) :sku s})))
                ($ sku-list {:visible visible}))))))))
