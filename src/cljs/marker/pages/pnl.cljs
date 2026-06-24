(ns marker.pages.pnl
  "P&L page — Phase 8.
   Data from /api/v1/marker/pnl via ::events/load-pnl.
   Renders summary P&L table + per-SKU breakdown from API data.
   Loading skeletons when data is nil."
  (:require [uix.core :refer [$ defui use-state use-memo use-ref use-effect]]
            [uix.re-frame :refer [use-subscribe]]
            [re-frame.core :as rf]
            [marker.state.subs   :as subs]
            [marker.state.events :as events]
            [marker.ui.chrome    :refer [delta mp-badge]]
            [marker.ui.icons     :refer [icon]]
            [marker.util.format  :as fmt]))

;; ---------------------------------------------------------------------------
;; Safe helpers
;; ---------------------------------------------------------------------------

(defn- safe-num [v]
  (if (and (some? v) (not (js/isNaN v))) v 0))

;; ---------------------------------------------------------------------------
;; Skeleton helpers
;; ---------------------------------------------------------------------------

(defui ^:private skel-row []
  ($ :tr
     (for [i (range 5)]
       ($ :td {:key i}
          ($ :div {:class "skel"
                   :style {:height "14px" :border-radius "4px"}})))))

(defui ^:private pnl-skeleton []
  ($ :div {:class "page-content"}
     ($ :section {:class "card section-card"}
        ($ :table {:class "tbl"}
           ($ :tbody
              (for [i (range 11)]
                ($ skel-row {:key i})))))
     ($ :section {:class "card section-card"}
        ($ :table {:class "tbl"}
           ($ :tbody
              (for [i (range 8)]
                ($ skel-row {:key i})))))))

;; ---------------------------------------------------------------------------
;; Error banner
;; ---------------------------------------------------------------------------

(defui ^:private error-banner [{:keys [message on-retry]}]
  ($ :div {:class "alert alert-danger"
           :style {:margin-bottom "12px"}}
     ($ icon {:name :danger :class "alert-icon"})
     ($ :div {:class "alert-body"}
        ($ :div {:class "alert-title"} "Не удалось загрузить данные P&L")
        ($ :div (or message "Проверьте соединение с сервером.")))
     ($ :button {:class    "btn btn-ghost btn-sm"
                 :style    {:color "inherit" :border "1px solid currentColor"}
                 :on-click on-retry}
        "Повторить")))

;; ---------------------------------------------------------------------------
;; P&L summary table — reads from API :rows
;; ---------------------------------------------------------------------------

(defui ^:private rows-table [{:keys [compare? rows]}]
  (let [revenue (safe-num (-> rows first :cur))]
    ($ :section {:class "card section-card"}
       ($ :div {:class "section-head"}
          ($ :div
             ($ :h3 {:class "section-title"} "P&L")
             ($ :div {:class "section-subtitle"}
                (if compare? "сравнение с предыдущим периодом" "без сравнения")))
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
                ($ :th {:class "num"} "Текущий период")
                (when compare? ($ :th {:class "num"} "Пред. период"))
                (when compare? ($ :th {:class "num"} "Δ ₽"))
                (when compare? ($ :th {:class "num"} "Δ %"))
                ($ :th {:class "num"} "% от выручки")))
          ($ :tbody
             (for [r rows]
               (let [cur       (safe-num (:cur r))
                     prev      (safe-num (:prev r))
                     d-abs     (- cur prev)
                     d-pct     (if (zero? prev)
                                 0
                                 (* (/ d-abs (js/Math.abs prev)) 100))
                     subtotal? (or (= (:group r) "subtotal")
                                   (= (:group r) "total"))
                     cost?     (neg? cur)
                     pct-rev   (if (zero? revenue)
                                 0
                                 (* (/ (js/Math.abs cur) revenue) 100))]
                 ($ :tr
                    {:key   (str (:key r))
                     :style (when subtotal?
                              {:background  "var(--color-bg-subtle)"
                               :font-weight 600})}
                    ($ :td {:style {:padding-left (if (and (= (:group r) "cost")
                                                           (not subtotal?))
                                                    "24px" "12px")}}
                       (when subtotal? "⏵ ")
                       (:label r))
                    ($ :td {:class "num mono"
                            :style {:color       (when cost? "var(--color-delta-negative)")
                                    :font-weight (when subtotal? 600)}}
                       (fmt/format-rub cur))
                    (when compare?
                      ($ :td {:class "num mono"
                              :style {:color "var(--color-fg-muted)"}}
                         (fmt/format-rub prev)))
                    (when compare?
                      ($ :td {:class "num mono"}
                         ($ delta {:pct d-pct :inverted cost?})))
                    (when compare?
                      ($ :td {:class "num mono"}
                         ($ :span
                            {:class (str "delta "
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
;; Per-SKU detail table — reads from API :sku-detail
;; ---------------------------------------------------------------------------

(defn- sku-visible
  "Filter skus by active MPs and search query."
  [skus mps q]
  (filterv
   (fn [s]
     (let [mp-ok  (or (empty? mps) (some (set mps) (:mp s)))
           id-str (.toLowerCase (str (:id s)))
           nm-str (.toLowerCase (str (:name s)))
           q-ok   (or (empty? q)
                      (let [ql (.toLowerCase q)]
                        (or (.includes id-str ql)
                            (.includes nm-str ql))))]
       (and mp-ok q-ok)))
   skus))

(defui ^:private sku-table [{:keys [compare? sku-rows]}]
  (let [skus           (or sku-rows [])
        mps            (use-subscribe [::subs/mp-filter])
        [selected    set-selected!]  (use-state #{})
        [q           set-q!]         (use-state "")
        all-cb-ref     (use-ref nil)
        visible        (use-memo
                        (fn [] (sku-visible skus mps q))
                        [skus mps q])
        all-selected?  (and (pos? (count visible))
                            (every? #(contains? selected (:id %)) visible))
        some-selected? (boolean (some #(contains? selected (:id %)) visible))
        toggle-all!    (fn []
                         (if all-selected?
                           (set-selected! (reduce disj selected (map :id visible)))
                           (set-selected! (reduce conj selected (map :id visible)))))
        toggle-one!    (fn [id]
                         (set-selected!
                          (if (contains? selected id)
                            (disj selected id)
                            (conj selected id))))]

    (use-effect
     (fn []
       (when @all-cb-ref
         (set! (.-indeterminate @all-cb-ref)
               (and (not all-selected?) some-selected?)))
       js/undefined)
     [all-selected? some-selected?])

    ($ :<> {}
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
            ($ :button {:class "btn btn-secondary btn-sm"} "Экспорт")
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
                   (str "показано " (count visible) " из " (count skus))))
             ($ :div {:class "row"}
                ($ :input {:class       "input"
                           :placeholder "Найти артикул…"
                           :value       q
                           :on-change   #(set-q! (.. % -target -value))
                           :style       {:width "220px"}})
                ($ :button {:class "btn btn-secondary btn-sm"}
                   ($ icon {:name :download :size 14})
                   "CSV")))
          ($ :div {:class "tbl-wrap"}
             ($ :table {:class "tbl"}
                ($ :thead
                   ($ :tr
                      ($ :th {:class "tbl-checkbox"}
                         ($ :input (assoc {:type      "checkbox"
                                           :checked   all-selected?
                                           :on-change toggle-all!}
                                          :ref all-cb-ref)))
                      ($ :th "Артикул")
                      ($ :th "МП")
                      ($ :th {:class "num"} "Выручка")
                      (when compare? ($ :th {:class "num"} "Δ %"))
                      ($ :th {:class "num"} "Себестоимость")
                      ($ :th {:class "num"} "Комиссия")
                      ($ :th {:class "num"} "Реклама")
                      ($ :th {:class "num"} "К выплате")
                      ($ :th)))
                ($ :tbody
                   (for [s visible]
                     (let [rev  (safe-num (:revenue s))
                           cogs (safe-num (:cogs s))
                           comm (safe-num (:commission s))
                           ads  (safe-num (:ads s))
                           net  (safe-num (:net s))]
                       ($ :tr
                          {:key   (:id s)
                           :class (when (contains? selected (:id s)) "selected")}
                          ($ :td {:class    "tbl-checkbox"
                                  :on-click #(.stopPropagation %)}
                             ($ :input {:type      "checkbox"
                                        :checked   (boolean (contains? selected (:id s)))
                                        :on-change #(toggle-one! (:id s))}))
                          ($ :td
                             ($ :span {:class    "tbl-link"
                                       :on-click #(rf/dispatch
                                                   [::events/open-sheet-and-load (:id s)])}
                                (:id s))
                             ($ :div {:style {:font-size  "12px"
                                              :color      "var(--color-fg-muted)"
                                              :margin-top "2px"}}
                                (:name s)))
                          ($ :td
                             (for [m (:mp s)]
                               ($ mp-badge {:key (name m) :mp m})))
                          ($ :td {:class "num mono"
                                  :style {:font-weight 600}}
                             (fmt/format-rub rev))
                          (when compare?
                            ($ :td {:class "num mono"}
                               ($ delta {:pct (:delta-pct s)})))
                          ($ :td {:class "num mono"
                                  :style {:color "var(--color-delta-negative)"}}
                             (fmt/format-rub (- cogs)))
                          ($ :td {:class "num mono"
                                  :style {:color "var(--color-delta-negative)"}}
                             (fmt/format-rub (- comm)))
                          ($ :td {:class "num mono"
                                  :style {:color "var(--color-delta-negative)"}}
                             (fmt/format-rub (- ads)))
                          ($ :td {:class "num mono"
                                  :style {:color       (if (pos? net)
                                                         "var(--color-delta-positive)"
                                                         "var(--color-delta-negative)")
                                          :font-weight 600}}
                             (fmt/format-rub net))
                          ($ :td
                             ($ :button {:class "icon-btn"}
                                ($ icon {:name :more-v :size 14})))))))
                ($ :tfoot
                   ($ :tr
                      ($ :td {:class "tbl-checkbox"})
                      ($ :td (str "Итого (" (count visible) ")"))
                      ($ :td)
                      ($ :td {:class "num mono"
                              :style {:font-weight 600}}
                         (fmt/format-rub (reduce #(+ %1 (safe-num (:revenue %2))) 0 visible)))
                      (when compare? ($ :td))
                      ($ :td) ($ :td) ($ :td)
                      ($ :td {:class "num mono"
                              :style {:font-weight 600}}
                         (fmt/format-rub (reduce #(+ %1 (safe-num (:net %2))) 0 visible)))
                      ($ :td)))))))))

;; ---------------------------------------------------------------------------
;; Page root
;; ---------------------------------------------------------------------------

(defui pnl []
  (let [compare?   (use-subscribe [::subs/compare])
        mp-filter  (use-subscribe [::subs/mp-filter])
        period     (use-subscribe [::subs/period])
        data       (use-subscribe [::subs/pnl-data])
        loading?   (use-subscribe [::subs/pnl-loading?])
        api-errors (use-subscribe [::subs/api-errors])
        error-msg  (get-in api-errors ["/api/v1/marker/pnl" :message])
        fs         {:mp-filter mp-filter :period period :compare compare?}]

    ;; Single effect on [mp-filter period compare?] handles both mount and
    ;; subsequent filter changes — a separate `[]` mount effect duplicates.
    (use-effect
     (fn []
       (rf/dispatch [::events/load-pnl
                     {:mp-filter mp-filter :period period :compare compare?}])
       js/undefined)
     [mp-filter period compare?])

    (cond
      (and loading? (nil? data))
      ($ pnl-skeleton)

      (and error-msg (nil? data))
      ($ :div {:class "page-content"}
         ($ error-banner
            {:message  error-msg
             :on-retry #(do (rf/dispatch [::events/clear-cache])
                            (rf/dispatch [::events/load-pnl fs]))}))

      :else
      (let [rows     (or (:rows data) [])
            sku-rows (or (:sku-detail data) [])]
        ($ :div {:class "page-content"}
           (when error-msg
             ($ error-banner
                {:message  error-msg
                 :on-retry #(do (rf/dispatch [::events/clear-cache])
                                (rf/dispatch [::events/load-pnl fs]))}))
           ($ rows-table {:compare? compare? :rows rows})
           ($ sku-table   {:compare? compare? :sku-rows sku-rows}))))))
