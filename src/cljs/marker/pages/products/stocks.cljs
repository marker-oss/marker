(ns marker.pages.products.stocks
  "«Склады» tab inside Товары — Phase 2 of the SPA UI restructure,
   extended by 016-US2 (capitalization / GMROI / days-of-cover).

   Surfaces backend data that was previously hidden:
     • totals strip (articles / warehouses / stock units / in transit)
     • by-warehouse table (where the stock physically sits)
     • by-article table with:
         – capitalization ₽ by-cost and by-price columns (016-US2 T-FE)
         – GMROI multiplier column (× suffix)
         – days-of-cover BADGE coloured by threshold (danger <7д /
           warning <14д / success ≥14д)
       and a totals row (Σ cap-by-cost / cap-by-price / stock-qty) plus an
       N/A-cost-count note. SKU with no cost basis renders «—», never 0.
     • drilldown into sku-sheet (per-article history + per-warehouse)."
  (:require [uix.core :refer [$ defui use-effect]]
            [uix.re-frame :refer [use-subscribe]]
            [re-frame.core :as rf]
            [marker.state.events :as events]
            [marker.state.subs   :as subs]
            [marker.ui.chrome    :refer [kpi-card]]
            [marker.ui.metric-hint :refer [metric-hint]]
            [marker.util.format  :as fmt]))

;; ---------------------------------------------------------------------------
;; Pure helpers (testable)
;; ---------------------------------------------------------------------------

(defn days->status
  "Mirror backend `days->status`. Both sides classify the same way so the
   UI can apply badge colors without an extra round-trip.

     nil  → \"ok\"    (unknown coverage, no badge colour)
     <7   → \"danger\"
     <14  → \"warning\"
     ≥14  → \"success\""
  [d]
  (cond
    (nil? d) "ok"
    (< d 7)  "danger"
    (< d 14) "warning"
    :else    "success"))

(defn status->badge-class
  "Map a coverage status → tokens.css badge modifier class. Unknown («ok»)
   coverage gets the neutral badge so it reads as «no signal», not success."
  [status]
  (case status
    "danger"  "badge-danger"
    "warning" "badge-warning"
    "success" "badge-success"
    "badge-neutral"))

(defn row-days
  "Days-of-cover for an article row. 016-US2 renamed the field to
   `:days-of-cover`; older stocks-overview payloads used `:days`. Read the
   new key first, fall back to the legacy one. Returns nil when neither is
   present (→ «—» + neutral badge)."
  [r]
  (let [d (:days-of-cover r)]
    (if (some? d) d (:days r))))

(defn row-display
  "Formatted display strings for an article row — the single code path
   shared by the component and the tests (016-US2).

   nil capitalization / GMROI (SKU without a cost basis, FR-013) renders
   «—», NEVER 0: zero would claim «worthless stock» when the truth is
   «cost price unknown»."
  [r]
  (let [d (row-days r)]
    {:cap-by-cost  (fmt/format-rub (:cap-by-cost r))
     :cap-by-price (fmt/format-rub (:cap-by-price r))
     :gmroi        (fmt/format-mul (:gmroi r))
     :days         d
     :days-label   (fmt/format-days d)
     :badge-class  (str "badge " (status->badge-class (days->status d)))}))

(defn totals-display
  "Formatted totals-row strings. `:stock-qty-total` (016-US2) is the Σ of
   quantity-full incl. in-transit, so it belongs under the «Полный» column
   (falls back to the legacy `:quantity-full` total); the available-qty
   column takes the pre-existing `:quantity` total. `:na-note` is nil when
   every SKU has a cost basis."
  [totals]
  (let [na (:na-cost-count totals)]
    {:qty       (fmt/format-int (:quantity totals))
     :qty-full  (fmt/format-int (or (:stock-qty-total totals)
                                    (:quantity-full totals)))
     :cap-cost  (fmt/format-rub (:cap-by-cost-total totals))
     :cap-price (fmt/format-rub (:cap-by-price-total totals))
     :na-note   (when (and na (pos? na))
                  (str "без себест.: " (fmt/format-int na) " арт."))}))

(defn sort-warehouses
  "Sort by quantity-full descending. Caller passes by-warehouse rows."
  [rows]
  (vec (sort-by (fn [r] (- (or (:quantity-full r) 0))) rows)))

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

(defui ^:private days-badge
  "Days-of-cover badge coloured by threshold. nil coverage → «—» in a neutral
   badge (unknown, not «healthy»)."
  [{:keys [days]}]
  ($ :span {:class (str "badge " (status->badge-class (days->status days)))}
     (fmt/format-days days)))

(defui ^:private article-table [{:keys [rows totals on-pick]}]
  (if (empty? rows)
    ($ :div {:class "empty-state"
             :style {:padding "32px" :text-align "center"
                     :color "var(--color-fg-muted)"}}
       "Нет артикулов с остатком")
    ($ :div {:class "tbl-wrap"}
       ($ :table {:class "tbl"}
          ($ :thead
             ($ :tr
                ($ :th "Артикул")
                ($ :th "Название")
                ($ :th {:class "num"} "Шт.")
                ($ :th {:class "num"} "Полный")
                ($ :th {:class "num"}
                   ($ :span {:style {:display "inline-flex" :align-items "center"}}
                      "Капит. (себест.)"
                      ($ metric-hint {:hint "Капитализация склада по себестоимости: Σ (себестоимость × полный остаток). Артикулы без себестоимости не учитываются."})))
                ($ :th {:class "num"}
                   ($ :span {:style {:display "inline-flex" :align-items "center"}}
                      "Капит. (цена)"
                      ($ metric-hint {:hint "Капитализация склада по цене продажи: Σ (средняя цена × полный остаток)."})))
                ($ :th {:class "num"}
                   ($ :span {:style {:display "inline-flex" :align-items "center"}}
                      "GMROI"
                      ($ metric-hint {:hint "Gross Margin Return On Inventory Investment: валовая прибыль / средний запас (по себестоимости). Больше 1× — запас окупается."})))
                ($ :th {:class "num"}
                   ($ :span {:style {:display "inline-flex" :align-items "center"}}
                      "Дней до OOS"
                      ($ metric-hint {:hint "Оборачиваемость: на сколько дней хватит текущего полного остатка при средней скорости продаж. Красный < 7 дн., жёлтый < 14 дн."})))))
          ($ :tbody
             (for [r rows]
               (let [disp (row-display r)]
                 ($ :tr {:key      (:article r)
                         :style    {:cursor "pointer"}
                         :on-click (fn [] (when on-pick (on-pick (:article r))))}
                    ($ :td ($ :span {:class "tbl-link mono"} (:article r)))
                    ($ :td (or (:subject r) ""))
                    ($ :td {:class "num mono"} (fmt/format-int (or (:quantity r) 0)))
                    ($ :td {:class "num mono"} (fmt/format-int (or (:quantity-full r) 0)))
                    ;; Capitalization / GMROI — nil (no cost basis) → «—», never 0.
                    ($ :td {:class "num mono"} (:cap-by-cost disp))
                    ($ :td {:class "num mono"} (:cap-by-price disp))
                    ($ :td {:class "num mono"} (:gmroi disp))
                    ($ :td {:class "num"}
                       ($ days-badge {:days (:days disp)}))))))
          ;; Totals row: Σ cap-by-cost / cap-by-price / stock-qty from backend
          ;; totals; N/A-cost note where SKUs lack a cost basis.
          (when totals
            (let [t (totals-display totals)]
              ($ :tfoot
                 ($ :tr
                    ($ :td "Итого")
                    ($ :td
                       (when (:na-note t)
                         ($ :span {:class "muted" :style {:font-size "12px"}}
                            (:na-note t))))
                    ($ :td {:class "num mono"} (:qty t))
                    ($ :td {:class "num mono"} (:qty-full t))
                    ($ :td {:class "num mono"} (:cap-cost t))
                    ($ :td {:class "num mono"} (:cap-price t))
                    ($ :td)
                    ($ :td)))))))))

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
                :totals  (:totals data)
                :on-pick (fn [art]
                           (rf/dispatch [::events/open-sheet-and-load art])
                           (rf/dispatch [::events/load-stock-article art]))})))

      :else
      ($ :div {:class "page-content"}))))
