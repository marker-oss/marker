(ns marker.pages.sync.schedule
  "Schedule editor and pure schedule helpers, extracted from marker.pages.sync.

   Pure helpers (parse-schedule-payload, schedule-form->body,
   validate-schedule-form) are public so they can be tested independently.
   The schedule-editor component is also public; sync.cljs renders it by
   requiring this namespace.

   load-schedule! and save-schedule! remain in sync.cljs because they are
   closures over that page's state setters and the private fetch/post helpers."
  (:require [uix.core :refer [$ defui use-state use-effect use-ref]]
            [marker.ui.icons :refer [icon]]))

;; ---------------------------------------------------------------------------
;; Valid-value sets (pure, used in validate-schedule-form)
;; ---------------------------------------------------------------------------

(def valid-what-values
  #{"sales" "orders" "finance" "storage" "stocks" "stats" "prices" "regions" "cashflow" "all"})

(def valid-mp-values    #{"all" "wb" "ozon" "ym"})
(def valid-period-values #{"last-week" "last-7-days" "last-30-days" "this-month"})

(def what-labels
  {"all"      "Все"
   "sales"    "Продажи"
   "orders"   "Заказы"
   "finance"  "Финансы"
   "storage"  "Хранение"
   "stocks"   "Остатки"
   "stats"    "Статистика товара"
   "prices"   "Цены"
   "regions"  "География"
   "cashflow" "Cash-flow"})

(def mp-labels
  {"all"  "Все"
   "wb"   "WB"
   "ozon" "Ozon"
   "ym"   "YM"})

(def period-labels
  {"last-week"    "Прошлая неделя"
   "last-7-days"  "Последние 7 дней"
   "last-30-days" "Последние 30 дней"
   "this-month"   "Этот месяц"})

;; ---------------------------------------------------------------------------
;; Pure helpers (testable without a DOM)
;; ---------------------------------------------------------------------------

(defn parse-schedule-payload
  "Normalise a GET /api/sync/schedule response body.
   Converts snake_case :next_run_at → :next-run-at and coerces types.

   - nil body → returns nil (the form component merges defaults itself).
   - empty map {} → returns a map of all keys set to their default values
     (each missing key gets its default: enabled false, hour 6, minute 0,
     what \"all\", marketplace \"all\", period \"last-7-days\")."
  [body]
  (when body
    {:enabled     (boolean (:enabled body))
     :hour        (or (:hour body) 6)
     :minute      (or (:minute body) 0)
     :what        (or (:what body) "all")
     :marketplace (or (:marketplace body) "all")
     :period      (or (:period body) "last-7-days")
     :next-run-at (or (:next_run_at body) (:next-run-at body))}))

(defn schedule-form->body
  "Extract only the keys the POST /api/sync/schedule endpoint expects."
  [form]
  (select-keys form [:enabled :hour :minute :what :marketplace :period]))

(defn validate-schedule-form
  "Returns nil if valid; returns an error string describing the first problem.

   :enabled is not validated — the checkbox onChange guarantees a boolean,
   so any non-boolean value cannot reach the validator under normal UI flow."
  [form]
  (let [h (:hour form)
        m (:minute form)]
    (cond
      (not (and (int? h) (<= 0 h 23)))  (str "Час должен быть от 0 до 23 (получено: " h ")")
      (not (and (int? m) (<= 0 m 59)))  (str "Минута должна быть от 0 до 59 (получено: " m ")")
      (not (valid-what-values    (:what form)))        "Недопустимое значение «что синкать»"
      (not (valid-mp-values      (:marketplace form))) "Недопустимый маркетплейс"
      (not (valid-period-values  (:period form)))      "Недопустимый период"
      :else nil)))

;; ---------------------------------------------------------------------------
;; Schedule editor component
;; ---------------------------------------------------------------------------

(def ^:private defaults
  {:enabled false :hour 6 :minute 0
   :what "all" :marketplace "all" :period "last-7-days"})

(defui schedule-editor
  [{:keys [schedule on-save loading? saving? save-error save-status]}]
  (let [[form set-form!] (use-state (merge defaults (dissoc schedule :next-run-at)))
        next-run        (:next-run-at schedule)
        ;; dirty-ref tracks whether the user has touched the form in this
        ;; component lifetime.  Once true it is never reset — the form is the
        ;; source of truth and the server's state (arriving via the :schedule
        ;; prop) must not overwrite the user's work.
        ;;
        ;; Design rationale: the race we guard against is narrow — the user
        ;; starts editing before GET /api/sync/schedule returns.  After a
        ;; successful save the user typically navigates away or the page
        ;; refreshes, so the "never reset" policy is acceptable.  If future
        ;; requirements demand post-save re-sync, pass a :saved? prop and add
        ;; a second use-effect that resets dirty-ref when saved? becomes true.
        dirty-ref       (use-ref false)
        on-field        (fn [k v]
                          (reset! dirty-ref true)
                          (set-form! #(assoc % k v)))]

    ;; Sync form from prop only on the nil→loaded transition and only when the
    ;; user has not yet touched anything.  Prevents the GET response from
    ;; clobbering edits already entered while the request was in-flight.
    (use-effect
     (fn []
       (when (and schedule (not @dirty-ref))
         (set-form! (merge defaults (dissoc schedule :next-run-at)))))
     [schedule])

    ($ :section {:class "card section-card"}
       ($ :div {:class "section-head"}
          ($ :h3 {:class "section-title"} "Расписание")
          ($ :div {:class "section-subtitle"}
             "Ежедневный автоматический sync."))

       (when loading?
         ($ :div {:style {:padding "16px"}}
            (for [i (range 3)]
              ($ :div {:key i :class "skel"
                       :style {:height "20px" :margin-bottom "8px"
                               :border-radius "var(--radius-sm)"}}))))

       (when-not loading?
         ($ :div {:style {:padding "0 16px 16px" :display "flex" :flex-direction "column" :gap "12px"}}

            ;; Enabled toggle
            ($ :label {:style {:display "flex" :align-items "center" :gap "8px"
                                :font-size "14px" :cursor "pointer"}}
               ($ :input {:type      "checkbox"
                           :checked   (boolean (:enabled form))
                           :on-change #(on-field :enabled (.. % -target -checked))})
               "Включено")

            ;; Time
            ($ :div {:style {:display "flex" :align-items "center" :gap "6px"
                              :font-size "14px"}}
               ($ :span {:style {:color "var(--color-fg-muted)"}} "Время")
               ($ :input {:type      "number" :min 0 :max 23 :value (:hour form)
                           :style     {:width "56px" :text-align "center"}
                           :on-change #(on-field :hour (js/parseInt (.. % -target -value) 10))})
               ($ :span ":")
               ($ :input {:type      "number" :min 0 :max 59 :value (:minute form)
                           :style     {:width "56px" :text-align "center"}
                           :on-change #(on-field :minute (js/parseInt (.. % -target -value) 10))}))

            ;; What
            ($ :div {:style {:display "flex" :align-items "center" :gap "8px" :font-size "14px"}}
               ($ :span {:style {:color "var(--color-fg-muted)" :min-width "110px"}} "Что синкать")
               ($ :select {:value (:what form) :on-change #(on-field :what (.. % -target -value))}
                  (for [v ["all" "sales" "orders" "finance" "storage" "stocks"
                            "stats" "prices" "regions" "cashflow"]]
                    ($ :option {:key v :value v} (get what-labels v v)))))

            ;; Marketplace
            ($ :div {:style {:display "flex" :align-items "center" :gap "8px" :font-size "14px"}}
               ($ :span {:style {:color "var(--color-fg-muted)" :min-width "110px"}} "Маркетплейс")
               ($ :select {:value (:marketplace form) :on-change #(on-field :marketplace (.. % -target -value))}
                  (for [v ["all" "wb" "ozon" "ym"]]
                    ($ :option {:key v :value v} (get mp-labels v v)))))

            ;; Period
            ($ :div {:style {:display "flex" :align-items "center" :gap "8px" :font-size "14px"}}
               ($ :span {:style {:color "var(--color-fg-muted)" :min-width "110px"}} "Период")
               ($ :select {:value (:period form) :on-change #(on-field :period (.. % -target -value))}
                  (for [v ["last-week" "last-7-days" "last-30-days" "this-month"]]
                    ($ :option {:key v :value v} (get period-labels v v)))))

            ;; Next-run hint
            (when (and (:enabled form) next-run)
              ($ :div {:style {:font-size "13px" :color "var(--color-fg-muted)"}}
                 "Следующий запуск: " next-run))

            ;; Status / error feedback
            (when (= save-status :saved)
              ($ :div {:class "alert alert-success"}
                 ($ :div {:class "alert-body"} "Расписание сохранено")))
            (when save-error
              ($ :div {:class "alert alert-danger"}
                 ($ icon {:name :danger :class "alert-icon"})
                 ($ :div {:class "alert-body"} save-error)))

            ;; Save button
            ($ :button {:class    (str "btn btn-primary" (when saving? " btn-disabled"))
                        :disabled saving?
                        :on-click #(on-save form)}
               (if saving? "Сохраняем…" "Сохранить")))))))
