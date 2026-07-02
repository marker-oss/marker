(ns marker.pages.reconciliation
  "Сверка P&L vs выплаты (FR-P4.6).
   Data from /api/v1/marker/reconciliation via ::events/load-reconciliation.

   HONESTY NOTE: the backend currently derives :payout-total from the same
   canonical source as :pnl-total (a same-source proxy), so every :delta is
   0.0.  The view therefore carries an explicit caption: the сверка becomes
   meaningful only once real payout/выплаты data is loaded.  We do NOT imply
   the zeros mean «идеально сошлось»."
  (:require [uix.core :refer [$ defui use-effect]]
            [uix.re-frame :refer [use-subscribe]]
            [re-frame.core :as rf]
            [marker.state.subs   :as subs]
            [marker.state.events :as events]
            [marker.ui.icons     :refer [icon]]
            [marker.util.format  :as fmt]))

(defn- safe-num [v] (if (and (some? v) (not (js/isNaN v))) v 0))

;; ---------------------------------------------------------------------------
;; Loading / error
;; ---------------------------------------------------------------------------

(defui ^:private skeleton []
  ($ :div {:class "page-content"}
     ($ :section {:class "card section-card"}
        (for [i (range 6)]
          ($ :div {:key i :class "skel"
                   :style {:height "16px" :border-radius "4px" :margin-bottom "8px"}})))))

(defui ^:private error-banner [{:keys [message on-retry]}]
  ($ :div {:class "alert alert-danger" :style {:margin-bottom "12px"}}
     ($ icon {:name :danger :class "alert-icon"})
     ($ :div {:class "alert-body"}
        ($ :div {:class "alert-title"} "Не удалось загрузить сверку")
        ($ :div (or message "Проверьте соединение с сервером.")))
     ($ :button {:class "btn btn-ghost btn-sm"
                 :style {:color "inherit" :border "1px solid currentColor"}
                 :on-click on-retry}
        "Повторить")))

;; ---------------------------------------------------------------------------
;; Page root
;; ---------------------------------------------------------------------------

(defui reconciliation []
  (let [mps        (use-subscribe [::subs/mp-filter])
        period     (use-subscribe [::subs/period])
        compare?   (use-subscribe [::subs/compare])
        data       (use-subscribe [::subs/reconciliation-data])
        loading?   (use-subscribe [::subs/reconciliation-loading?])
        api-errors (use-subscribe [::subs/api-errors])
        error-msg  (get-in api-errors ["/api/v1/marker/reconciliation" :message])
        fs         {:mp-filter mps :period period :compare compare?}]

    (use-effect
     (fn []
       (rf/dispatch [::events/load-reconciliation
                     {:mp-filter mps :period period :compare compare?}])
       js/undefined)
     [mps period compare?])

    (cond
      (and loading? (nil? data))
      ($ skeleton)

      (and error-msg (nil? data))
      ($ :div {:class "page-content"}
         ($ error-banner {:message  error-msg
                          :on-retry #(do (rf/dispatch [::events/clear-cache])
                                          (rf/dispatch [::events/load-reconciliation fs]))}))

      :else
      (let [pnl-total    (safe-num (:pnl-total data))
            payout-total (safe-num (:payout-total data))
            delta-total  (safe-num (:delta data))
            per-article  (or (:per-article data) [])
            ;; Largest absolute deltas first — the rows that need attention.
            sorted       (sort-by #(- (js/Math.abs (safe-num (:delta %)))) per-article)]
        ($ :div {:class "page-content"}
           (when error-msg
             ($ error-banner {:message  error-msg
                              :on-retry #(do (rf/dispatch [::events/clear-cache])
                                              (rf/dispatch [::events/load-reconciliation fs]))}))

           ($ :section {:class "card section-card"}
              ($ :div {:class "section-head"}
                 ($ :div
                    ($ :h3 {:class "section-title"} "Сверка: P&L vs выплаты")
                    ($ :div {:class "section-subtitle"}
                       "сопоставление расчётной прибыли с фактическими выплатами по артикулам")))

              ;; Honesty caption — the backend payout is currently a same-source
              ;; proxy, so deltas are 0.0. Do NOT read зеро as «сошлось».
              ($ :div {:class "alert alert-info" :style {:margin-bottom "12px"}}
                 ($ icon {:name :info :class "alert-icon"})
                 ($ :div {:class "alert-body"}
                    ($ :div {:class "alert-title"} "Предварительная сверка")
                    ($ :div
                       "Выплаты пока берутся из того же источника, что и P&L, поэтому "
                       "расхождения равны нулю. Сверка станет содержательной после "
                       "загрузки данных о фактических выплатах маркетплейса.")))

              ;; Totals strip
              ($ :div {:style {:display "flex" :flex-wrap "wrap" :gap "28px"
                               :padding "6px 2px 14px"}}
                 ($ :div
                    ($ :div {:class "section-subtitle" :style {:font-size "12px"}} "P&L (расчёт)")
                    ($ :div {:style {:font-size "24px" :font-weight 700}}
                       (fmt/format-rub pnl-total)))
                 ($ :div
                    ($ :div {:class "section-subtitle" :style {:font-size "12px"}} "Выплаты")
                    ($ :div {:style {:font-size "24px" :font-weight 700}}
                       (fmt/format-rub payout-total)))
                 ($ :div
                    ($ :div {:class "section-subtitle" :style {:font-size "12px"}} "Расхождение")
                    ($ :div {:style {:font-size   "24px"
                                     :font-weight 700
                                     :color       (cond
                                                    (zero? delta-total) "var(--color-fg-muted)"
                                                    (neg? delta-total)  "var(--color-delta-negative)"
                                                    :else               "var(--color-delta-positive)")}}
                       (fmt/format-rub delta-total))))

              (if (empty? per-article)
                ($ :div {:style {:color "var(--color-fg-muted)" :font-size "13px"
                                 :padding "16px 2px"}}
                   "Нет данных по артикулам за выбранный период.")
                ($ :div {:class "tbl-wrap"}
                   ($ :table {:class "tbl"}
                      ($ :thead
                         ($ :tr
                            ($ :th "Артикул")
                            ($ :th {:class "num"} "P&L")
                            ($ :th {:class "num"} "Выплата")
                            ($ :th {:class "num"} "Расхождение")))
                      ($ :tbody
                         (for [r sorted]
                           (let [d (safe-num (:delta r))]
                             ($ :tr {:key (str (:article r))}
                                ($ :td
                                   ($ :span {:class "tbl-link"} (str (:article r))))
                                ($ :td {:class "num mono"} (fmt/format-rub (safe-num (:pnl r))))
                                ($ :td {:class "num mono"} (fmt/format-rub (safe-num (:payout r))))
                                ($ :td {:class "num mono"
                                        :style (cond
                                                 (zero? d) {:color "var(--color-fg-muted)"}
                                                 (neg? d)  {:color "var(--color-delta-negative)" :font-weight 600}
                                                 :else     {:color "var(--color-delta-positive)" :font-weight 600})}
                                   (fmt/format-rub d)))))))))))))))
