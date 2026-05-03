(ns marker.pages.products
  "Products catalog page — grid/list toggle + SKU drill-down sheet.
   Phase 6. All data from marker.mock."
  (:require [uix.core :refer [$ defui use-state use-memo]]
            [uix.re-frame :refer [use-subscribe]]
            [re-frame.core :as rf]
            [marker.state.subs   :as subs]
            [marker.state.events :as events]
            [marker.ui.chrome    :refer [delta mp-badge sparkline]]
            [marker.ui.icons     :refer [icon]]
            [marker.mock         :as mock]
            [marker.util.format  :as fmt]))

;; ---------------------------------------------------------------------------
;; Grid card
;; ---------------------------------------------------------------------------

(defui ^:private sku-card [{:keys [sku]}]
  ($ :button
     {:class    "sku-card"
      :on-click #(rf/dispatch [::events/open-sheet (:id sku)])
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
     ;; Thumbnail placeholder
     ($ :div {:style {:aspect-ratio   "16/10"
                      :background     "repeating-linear-gradient(135deg, var(--color-bg-subtle), var(--color-bg-subtle) 8px, var(--color-bg-muted) 8px, var(--color-bg-muted) 16px)"
                      :border-radius  "6px"
                      :display        "grid"
                      :place-items    "center"
                      :color          "var(--color-fg-muted)"
                      :font-family    "var(--font-mono)"
                      :font-size      "11px"}}
        (:id sku))
     ;; Id + badges row
     ($ :div {:class "row"}
        ($ :span {:class "mono"
                  :style {:font-size "11px" :color "var(--color-fg-muted)"}}
           (:id sku))
        (for [m (:mp sku)]
          ($ mp-badge {:key (name m) :mp m})))
     ;; Name
     ($ :div {:style {:font-size "13px" :font-weight 500}}
        (:name sku))
     ;; Revenue + delta + sparkline
     ($ :div {:class "row" :style {:margin-top "auto"}}
        ($ :span {:class "mono" :style {:font-weight 600}}
           (fmt/format-rub (:revenue sku)))
        ($ delta {:pct (:delta-pct sku)})
        ($ :div {:class "spacer"})
        ($ sparkline {:data (:spark sku) :width 60 :height 20}))))

;; ---------------------------------------------------------------------------
;; List row (table)
;; ---------------------------------------------------------------------------

(defui ^:private sku-list [{:keys [visible]}]
  (let [[sort-key  set-sort-key!]  (use-state :revenue)
        [sort-dir  set-sort-dir!]  (use-state :desc)
        sorted (use-memo
                (fn []
                  (let [cmp (if (= sort-dir :desc) > <)]
                    (sort-by sort-key cmp visible)))
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
             ($ :th {:class    "tbl-sortable"
                     :on-click #(sort-click! :id)}
                "Артикул " (sort-icon :id))
             ($ :th "МП")
             ($ :th {:class    "num tbl-sortable"
                     :on-click #(sort-click! :orders)}
                "Заказы " (sort-icon :orders))
             ($ :th {:class    "num tbl-sortable"
                     :on-click #(sort-click! :revenue)}
                "Выручка " (sort-icon :revenue))
             ($ :th {:class    "num tbl-sortable"
                     :on-click #(sort-click! :margin)}
                "Маржа " (sort-icon :margin))
             ($ :th {:class    "num tbl-sortable"
                     :on-click #(sort-click! :stock)}
                "Остаток " (sort-icon :stock))
             ($ :th)))
       ($ :tbody
          (for [s sorted]
            ($ :tr {:key      (:id s)
                    :style    {:cursor "pointer"}
                    :on-click #(rf/dispatch [::events/open-sheet (:id s)])}
               ($ :td
                  ($ :span {:class "tbl-link"} (:id s))
                  " · "
                  (:name s))
               ($ :td
                  (for [m (:mp s)]
                    ($ mp-badge {:key (name m) :mp m})))
               ($ :td {:class "num mono"} (fmt/format-int (:orders s)))
               ($ :td {:class "num mono"} (fmt/format-rub (:revenue s)))
               ($ :td {:class "num mono"} (fmt/format-pct (* (:margin s) 100)))
               ($ :td {:class "num mono"} (fmt/format-int (:stock s)))
               ($ :td
                  ($ sparkline {:data (:spark s) :width 60 :height 20}))))))))

;; ---------------------------------------------------------------------------
;; Page root
;; ---------------------------------------------------------------------------

(defui products []
  (let [mps     (use-subscribe [::subs/mp-filter])
        [view set-view!] (use-state :grid)

        visible (use-memo
                 (fn []
                   (filterv (fn [s]
                               (or (empty? mps)
                                   (some (set mps) (:mp s))))
                             mock/skus))
                 [mps])]

    ($ :div {:class "page-content"}
       ($ :section {:class "card section-card"}
          ;; Section header
          ($ :div {:class "section-head"}
             ($ :div
                ($ :h3 {:class "section-title"} "Каталог товаров")
                ($ :div {:class "section-subtitle"}
                   (let [n (count visible)]
                     (str n " "
                          (cond (= n 1) "артикул"
                                (<= 2 n 4) "артикула"
                                :else "артикулов")))))
             ($ :div {:class "row"}
                ;; View toggle
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

          ;; Grid or list
          (if (= view :grid)
            ($ :div {:style {:display               "grid"
                             :grid-template-columns "repeat(auto-fill, minmax(280px, 1fr))"
                             :gap                   "14px"}}
               (for [s visible]
                 ($ sku-card {:key (:id s) :sku s})))
            ($ sku-list {:visible visible}))))))
