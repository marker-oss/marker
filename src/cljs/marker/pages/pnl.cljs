(ns marker.pages.pnl
  "P&L page — summary table + per-SKU detail with bulk action bar.
   Phase 6. All data from marker.mock; real wiring Phase 8."
  (:require [uix.core :refer [$ defui use-state use-memo use-ref use-effect]]
            [uix.re-frame :refer [use-subscribe]]
            [re-frame.core :as rf]
            [marker.state.subs   :as subs]
            [marker.state.events :as events]
            [marker.ui.chrome    :refer [delta mp-badge]]
            [marker.ui.icons     :refer [icon]]
            [marker.mock         :as mock]
            [marker.util.format  :as fmt]))

;; ---------------------------------------------------------------------------
;; P&L summary table
;; ---------------------------------------------------------------------------

(defui ^:private pnl-summary [{:keys [compare?]}]
  (let [revenue (-> mock/pnl-rows first :cur)]
    ($ :section {:class "card section-card"}
       ($ :div {:class "section-head"}
          ($ :div
             ($ :h3 {:class "section-title"} "P&L · Май 2026")
             ($ :div {:class "section-subtitle"}
                (if compare? "сравнение с апрелем 2026" "без сравнения")))
          ($ :div {:class "row"}
             ($ :button {:class "btn btn-secondary btn-sm"}
                ($ icon {:name :download :size 14})
                "Export")
             ($ :button {:class "icon-btn"}
                ($ icon {:name :more-h}))))
       ($ :table {:class "tbl"}
          ($ :thead
             ($ :tr
                ($ :th "Статья")
                ($ :th {:class "num"} "Май 2026")
                (when compare? ($ :th {:class "num"} "Апр 2026"))
                (when compare? ($ :th {:class "num"} "Δ ₽"))
                (when compare? ($ :th {:class "num"} "Δ %"))
                ($ :th {:class "num"} "% от выручки")))
          ($ :tbody
             (for [r mock/pnl-rows]
               (let [d-abs     (- (:cur r) (:prev r))
                     d-pct     (if (zero? (:prev r))
                                 0
                                 (* (/ d-abs (js/Math.abs (:prev r))) 100))
                     subtotal? (or (= (:group r) "subtotal")
                                   (= (:group r) "total"))
                     cost?     (neg? (:cur r))
                     pct-rev   (if (zero? revenue)
                                 0
                                 (* (/ (js/Math.abs (:cur r)) revenue) 100))]
                 ($ :tr {:key      (:key r)
                         :style    (when subtotal?
                                     {:background  "var(--color-bg-subtle)"
                                      :font-weight 600})}
                    ($ :td {:style {:padding-left (if (and (= (:group r) "cost")
                                                           (not subtotal?))
                                                    "24px" "12px")
                                    :color        (when (:muted r)
                                                    "var(--color-fg-muted)")}}
                       (when subtotal? "⏵ ")
                       (:label r))
                    ($ :td {:class "num mono"
                            :style {:color       (when cost? "var(--color-delta-negative)")
                                    :font-weight (when subtotal? 600)}}
                       (fmt/format-rub (:cur r)))
                    (when compare?
                      ($ :td {:class "num mono"
                              :style {:color "var(--color-fg-muted)"}}
                         (fmt/format-rub (:prev r))))
                    (when compare?
                      ($ :td {:class "num mono"}
                         ($ delta {:pct d-pct :inverted cost?})))
                    (when compare?
                      ($ :td {:class "num mono"}
                         ($ :span {:class (str "delta "
                                               (if (pos? d-abs)
                                                 (if cost? "down" "up")
                                                 (if cost? "up" "down")))}
                            (str (when (pos? d-abs) "+")
                                 (-> (fmt/format-rub d-abs)
                                     (.replace "₽" "")
                                     .trim)
                                 " ₽"))))
                    ($ :td {:class "num mono"
                            :style {:color "var(--color-fg-muted)"}}
                       (fmt/format-pct pct-rev))))))))))

;; ---------------------------------------------------------------------------
;; Per-SKU detail with bulk action bar
;; ---------------------------------------------------------------------------

;; TODO Phase 8: cost columns currently overlap — :margin in mock-data
;; encodes total cost ratio (incl. commission/ads/returns), so showing
;; commission/ads as separate columns counts them twice. When real
;; per-SKU cost lines come from the backend, decompose into mutually
;; exclusive cost lines so revenue − Σ(cost-cols) == net.
(defui ^:private sku-table [{:keys [compare?]}]
  (let [mps          (use-subscribe [::subs/mp-filter])
        [selected set-selected!] (use-state #{})
        [q        set-q!]        (use-state "")

        ;; indeterminate checkbox ref
        all-cb-ref (use-ref nil)

        visible (use-memo
                 (fn []
                   (filterv (fn [s]
                               (and (or (empty? mps)
                                        (some (set mps) (:mp s)))
                                    (or (empty? q)
                                        (let [ql (.toLowerCase q)]
                                          (or (.includes (.toLowerCase (:id s)) ql)
                                              (.includes (.toLowerCase (:name s)) ql))))))
                             mock/skus))
                 [mps q])

        all-selected? (and (pos? (count visible))
                           (every? #(contains? selected (:id %)) visible))
        some-selected? (some #(contains? selected (:id %)) visible)

        toggle-all!  (fn []
                       (if all-selected?
                         (set-selected! (reduce disj selected (map :id visible)))
                         (set-selected! (reduce conj selected (map :id visible)))))
        toggle-one!  (fn [id]
                       (set-selected! (if (contains? selected id)
                                        (disj selected id)
                                        (conj selected id))))]

    ;; Drive indeterminate state on the header checkbox
    (use-effect
     (fn []
       (when @all-cb-ref
         (set! (.-indeterminate @all-cb-ref)
               (and (not all-selected?) some-selected?)))
       js/undefined)
     [all-selected? some-selected?])

    ($ :<>
       ;; Bulk action bar
       (when (pos? (count selected))
         ($ :div {:class "bulk-bar"}
            ($ :strong
               (str "Выбрано " (count selected) " "
                    (fmt/plural-ru (count selected) "строка" "строки" "строк")))
            ($ :button {:class    "btn-link"
                        :style    {:color "var(--color-fg-muted)"}
                        :on-click #(set-selected! #{})}
               "Снять")
            ($ :div {:class "spacer"})
            ;; TODO(Phase-N): wire bulk actions — rate change, pause, export
            ($ :button {:class "btn btn-secondary btn-sm"} "Изменить ставки")
            ($ :button {:class "btn btn-secondary btn-sm"} "Поставить на паузу")
            ($ :button {:class "btn btn-primary btn-sm"} "Экспорт")
            ($ :button {:class    "icon-btn"
                        :style    {:color "var(--color-fg-muted)"}
                        :on-click #(set-selected! #{})}
               ($ icon {:name :x :size 14}))))

       ;; SKU table card
       ($ :section {:class "card section-card"}
          ($ :div {:class "section-head"}
             ($ :div
                ($ :h3 {:class "section-title"} "Прибыль по артикулам")
                ($ :div {:class "section-subtitle"}
                   (str "показано " (count visible) " из " (count mock/skus))))
             ($ :div {:class "row"}
                ($ :input {:class       "input"
                            :placeholder "Найти артикул…"
                            :value       q
                            :on-change   #(set-q! (.. % -target -value))
                            :style       {:width "220px"}})
                ($ :button {:class "btn btn-secondary btn-sm"}
                   "Колонки "
                   ($ icon {:name :chev-down :size 12}))
                ($ :button {:class "btn btn-secondary btn-sm"}
                   ($ icon {:name :download :size 14})
                   "CSV")))
          ($ :div {:class "tbl-wrap"}
             ($ :table {:class "tbl"}
                ($ :thead
                   ($ :tr
                      ($ :th {:class "tbl-checkbox"}
                         ($ :input {:type      "checkbox"
                                    :ref       all-cb-ref
                                    :checked   all-selected?
                                    :on-change toggle-all!}))
                      ($ :th "Артикул")
                      ($ :th "МП")
                      ($ :th {:class "num"} "Выручка")
                      (when compare? ($ :th {:class "num"} "Δ %"))
                      ($ :th {:class "num"} "Себестоимость")
                      ($ :th {:class "num"} "Комиссия МП")
                      ($ :th {:class "num"} "Реклама")
                      ($ :th {:class "num"} "Чистая прибыль")
                      ($ :th)))
                ($ :tbody
                   (for [s visible]
                     ($ :tr {:key   (:id s)
                             :class (when (contains? selected (:id s)) "selected")}
                        ($ :td {:class    "tbl-checkbox"
                                :on-click #(.stopPropagation %)}
                           ($ :input {:type      "checkbox"
                                      :checked   (boolean (contains? selected (:id s)))
                                      :on-change #(toggle-one! (:id s))}))
                        ($ :td
                           ($ :span {:class    "tbl-link"
                                     :on-click #(rf/dispatch [::events/open-sheet (:id s)])}
                              (:id s))
                           ($ :div {:style {:font-size   "12px"
                                            :color       "var(--color-fg-muted)"
                                            :margin-top  "2px"}}
                              (:name s)))
                        ($ :td
                           (for [m (:mp s)]
                             ($ mp-badge {:key (name m) :mp m})))
                        ($ :td {:class "num mono"
                                :style {:font-weight 600}}
                           (fmt/format-rub (:revenue s)))
                        (when compare?
                          ($ :td {:class "num mono"}
                             ($ delta {:pct (:delta-pct s)})))
                        ($ :td {:class "num mono"
                                :style {:color "var(--color-delta-negative)"}}
                           (fmt/format-rub (- (* (:revenue s) (- 1 (:margin s))))))
                        ($ :td {:class "num mono"
                                :style {:color "var(--color-delta-negative)"}}
                           (fmt/format-rub (- (* (:revenue s) 0.18))))
                        ($ :td {:class "num mono"
                                :style {:color "var(--color-delta-negative)"}}
                           (fmt/format-rub (- (:ads-cost s))))
                        ($ :td {:class "num mono"
                                :style {:color (if (pos? (- (* (:revenue s) (:margin s)) (:ads-cost s)))
                                                 "var(--color-delta-positive)"
                                                 "var(--color-delta-negative)")
                                        :font-weight 600}}
                           (fmt/format-rub (- (* (:revenue s) (:margin s)) (:ads-cost s))))
                        ($ :td
                           ($ :button {:class "icon-btn"}
                              ($ icon {:name :more-v :size 14}))))))
                ($ :tfoot
                   ($ :tr
                      ($ :td {:class "tbl-checkbox"})
                      ($ :td (str "Итого (" (count visible) ")"))
                      ($ :td)
                      ($ :td {:class "num mono" :style {:font-weight 600}}
                         (fmt/format-rub (reduce #(+ %1 (:revenue %2)) 0 visible)))
                      (when compare? ($ :td))
                      ($ :td) ($ :td) ($ :td)
                      ($ :td {:class "num mono" :style {:font-weight 600}}
                         (fmt/format-rub (reduce #(+ %1 (- (* (:revenue %2) (:margin %2)) (:ads-cost %2))) 0 visible)))
                      ($ :td)))))))))

;; ---------------------------------------------------------------------------
;; Page root
;; ---------------------------------------------------------------------------

(defui pnl []
  (let [compare? (use-subscribe [::subs/compare])]
    ($ :div {:class "page-content"}
       ($ pnl-summary {:compare? compare?})
       ($ sku-table   {:compare? compare?}))))
