(ns marker.pages.products.stocks
  "«Склады» tab inside Товары — Phase 2 of the SPA UI restructure.

   Surfaces backend data that was previously hidden:
     • totals strip (articles / warehouses / stock units / in transit)
     • by-warehouse table (where the stock physically sits)
     • by-article table with days-of-cover badge (drilldown into sku-sheet
       which loads per-article history + per-warehouse breakdown)."
  (:require [uix.core :refer [$ defui use-effect]]
            [uix.re-frame :refer [use-subscribe]]
            [re-frame.core :as rf]
            [marker.state.events :as events]
            [marker.state.subs   :as subs]
            [marker.ui.chrome    :refer [kpi-card]]
            [marker.util.format  :as fmt]))

;; ---------------------------------------------------------------------------
;; Pure helpers (testable)
;; ---------------------------------------------------------------------------

(defn days->status
  "Mirror backend `days->status`. Both sides classify the same way so the
   UI can apply badge colors without an extra round-trip."
  [d]
  (cond
    (nil? d) "ok"
    (< d 7)  "danger"
    (< d 14) "warning"
    :else    "success"))

(defn sort-warehouses
  "Sort by quantity-full descending. Caller passes by-warehouse rows."
  [rows]
  (vec (sort-by (fn [r] (- (or (:quantity-full r) 0))) rows)))

(def ^:private status->ru
  {"danger"  "OOS-риск"
   "warning" "Скоро OOS"
   "success" "OK"
   "ok"      "—"})

;; ---------------------------------------------------------------------------
;; Sub-components
;; ---------------------------------------------------------------------------

(defui ^:private kpi-strip [{:keys [totals]}]
  ($ :div {:class "kpi-row" :style {:display "grid"
                                     :grid-template-columns "repeat(5, 1fr)"
                                     :gap "12px"
                                     :margin-bottom "16px"}}
     ($ kpi-card {:label "Артикулов" :value (fmt/format-int (or (:articles totals) 0))})
     ($ kpi-card {:label "Складов"   :value (fmt/format-int (or (:warehouses totals) 0))})
     ($ kpi-card {:label "Шт. доступно"
                  :value (fmt/format-int (or (:quantity totals) 0))})
     ($ kpi-card {:label "В пути К клиенту"
                  :value (fmt/format-int (or (:in-way-to totals) 0))})
     ($ kpi-card {:label "В пути ОТ клиента"
                  :value (fmt/format-int (or (:in-way-from totals) 0))})))

(defui ^:private warehouse-table [{:keys [rows]}]
  (if (empty? rows)
    ($ :div {:class "empty-state"
             :style {:padding "32px" :text-align "center"
                     :color "var(--color-fg-muted)"}}
       "Нет данных по складам")
    ($ :table {:class "tbl"}
       ($ :thead
          ($ :tr
             ($ :th "Склад")
             ($ :th {:class "num"} "Артикулов")
             ($ :th {:class "num"} "Шт. доступно")
             ($ :th {:class "num"} "Полный остаток")
             ($ :th {:class "num"} "В пути К")
             ($ :th {:class "num"} "В пути ОТ")))
       ($ :tbody
          (for [r (sort-warehouses rows)]
            ($ :tr {:key (or (:warehouse r) "—")}
               ($ :td (or (:warehouse r) "—"))
               ($ :td {:class "num mono"} (fmt/format-int (or (:articles r) 0)))
               ($ :td {:class "num mono"} (fmt/format-int (or (:quantity r) 0)))
               ($ :td {:class "num mono"} (fmt/format-int (or (:quantity-full r) 0)))
               ($ :td {:class "num mono"} (fmt/format-int (or (:in-way-to r) 0)))
               ($ :td {:class "num mono"} (fmt/format-int (or (:in-way-from r) 0)))))))))

(defui ^:private article-table [{:keys [rows on-pick]}]
  (if (empty? rows)
    ($ :div {:class "empty-state"
             :style {:padding "32px" :text-align "center"
                     :color "var(--color-fg-muted)"}}
       "Нет артикулов с остатком")
    ($ :table {:class "tbl"}
       ($ :thead
          ($ :tr
             ($ :th "Артикул")
             ($ :th "Название")
             ($ :th {:class "num"} "Шт.")
             ($ :th {:class "num"} "Полный")
             ($ :th {:class "num"} "Складов")
             ($ :th {:class "num"} "Дней до OOS")
             ($ :th "Статус")))
       ($ :tbody
          (for [r rows]
            ($ :tr {:key      (:article r)
                    :style    {:cursor "pointer"}
                    :on-click (fn [] (when on-pick (on-pick (:article r))))}
               ($ :td ($ :span {:class "tbl-link mono"} (:article r)))
               ($ :td (or (:subject r) ""))
               ($ :td {:class "num mono"} (fmt/format-int (or (:quantity r) 0)))
               ($ :td {:class "num mono"} (fmt/format-int (or (:quantity-full r) 0)))
               ($ :td {:class "num mono"} (or (:warehouses r) 0))
               ($ :td {:class "num mono"}
                  (if (some? (:days r))
                    (str (:days r))
                    "—"))
               ($ :td
                  ($ :span {:class (str "badge badge-"
                                        (case (:status r)
                                          "ok"      "neutral"
                                          ("danger" "warning" "success")
                                          (:status r)
                                          "neutral"))}
                     (get status->ru (:status r) "—")))))))))

;; ---------------------------------------------------------------------------
;; Page root
;; ---------------------------------------------------------------------------

(defui stocks []
  (let [data       (use-subscribe [::subs/stocks-overview])
        loading?   (use-subscribe [::subs/stocks-overview-loading?])
        mp-filter  (use-subscribe [::subs/mp-filter])
        api-errors (use-subscribe [::subs/api-errors])
        error-msg  (get-in api-errors ["/api/v1/marker/stocks/overview" :message])]

    ;; Refetch when MP filter changes (and on mount).
    (use-effect
     (fn []
       (rf/dispatch [::events/load-stocks-overview])
       js/undefined)
     [mp-filter])

    (cond
      (and loading? (nil? data))
      ($ :div {:class "page-content"}
         ($ :div {:class "skel" :style {:height "120px" :margin-bottom "12px"}})
         ($ :div {:class "skel" :style {:height "200px" :margin-bottom "12px"}})
         ($ :div {:class "skel" :style {:height "300px"}}))

      (and error-msg (nil? data))
      ($ :div {:class "page-content"}
         ($ :div {:class "alert alert-danger"
                  :style {:padding "12px"}}
            ($ :strong "Не удалось загрузить остатки. ")
            ($ :span error-msg)
            ($ :button {:class "btn btn-ghost btn-sm"
                        :style {:margin-left "auto"}
                        :on-click #(rf/dispatch [::events/load-stocks-overview])}
               "Повторить")))

      (some? data)
      ($ :div {:class "page-content"}
         ($ kpi-strip {:totals (:totals data)})

         ($ :section {:class "card section-card" :style {:margin-bottom "16px"}}
            ($ :div {:class "section-head"}
               ($ :div
                  ($ :h3 {:class "section-title"} "По складам")
                  ($ :div {:class "section-subtitle"}
                     (let [n (count (:by-warehouse data))]
                       (str n " " (fmt/plural-ru n "склад" "склада" "складов"))))))
            ($ warehouse-table {:rows (:by-warehouse data)}))

         ($ :section {:class "card section-card"}
            ($ :div {:class "section-head"}
               ($ :div
                  ($ :h3 {:class "section-title"} "По артикулам")
                  ($ :div {:class "section-subtitle"}
                     (let [n (count (:by-article data))]
                       (str n " " (fmt/plural-ru n "артикул" "артикула" "артикулов"))))))
            ($ article-table
               {:rows    (:by-article data)
                :on-pick (fn [art]
                           (rf/dispatch [::events/open-sheet-and-load art])
                           (rf/dispatch [::events/load-stock-article art]))})))

      :else
      ($ :div {:class "page-content"}))))
