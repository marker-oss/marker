(ns marker.pages.kit
  "UI Kit catalog — read-only showcase of all design-system components.
   Phase 6."
  (:require [uix.core :refer [$ defui use-state]]
            [marker.ui.chrome   :refer [delta mp-badge sparkline]]
            [marker.ui.icons    :refer [icon]]
            [marker.util.format :as fmt]))

(def ^:private color-swatches
  [["bg-app"              "#f8fafc"]
   ["bg-surface"          "#ffffff"]
   ["bg-subtle"           "#f1f5f9"]
   ["fg-primary"          "#0f172a"]
   ["fg-secondary"        "#334155"]
   ["fg-muted"            "#64748b"]
   ["accent-primary"      "#1e293b"]
   ["accent-interactive"  "#4f46e5"]
   ["delta-positive"      "#16a34a"]
   ["delta-negative"      "#dc2626"]
   ["warning-fg"          "#92400e"]
   ["mp-wb"               "#CB11AB"]])

(defui ^:private section [{:keys [title children]}]
  ($ :section {:class "card section-card"}
     ($ :div {:class "section-head"}
        ($ :h3 {:class "section-title"} title))
     children))

(defui ^:private tab-example []
  (let [[active set-active!] (use-state 0)]
    ($ :div
       ($ :div {:class "tabs"}
          (for [[i label] (map-indexed vector ["Обзор" "Динамика" "Сравнение"])]
            ($ :button {:key      i
                        :class    (str "tab" (when (= active i) " active"))
                        :on-click #(set-active! i)}
               label)))
       ($ :div {:style {:padding "16px 0" :color "var(--color-fg-muted)" :font-size "13px"}}
          (str "Контент вкладки «" (nth ["Обзор" "Динамика" "Сравнение"] active) "»")))))

(defui kit []
  ($ :div {:class "page-content"}

     ;; Colors
     ($ section {:title "Цвета — семантические токены"}
        ($ :div {:style {:display               "grid"
                          :grid-template-columns "repeat(6, 1fr)"
                          :gap                   "8px"}}
           (for [[k v] color-swatches]
             ($ :div {:key   k
                      :style {:border        "1px solid var(--color-border-subtle)"
                               :border-radius "6px"
                               :overflow      "hidden"}}
                ($ :div {:style {:height "56px" :background v}})
                ($ :div {:style {:padding "8px" :font-size "11px"}}
                   ($ :div {:class "mono"} k)
                   ($ :div {:class "mono"
                             :style {:color "var(--color-fg-muted)"}} v))))))

     ;; Buttons
     ($ section {:title "Кнопки"}
        ($ :div {:class "row" :style {:flex-wrap "wrap" :gap "8px"}}
           ($ :button {:class "btn btn-primary"} "Primary")
           ($ :button {:class "btn btn-secondary"} "Secondary")
           ($ :button {:class "btn btn-ghost"} "Ghost")
           ($ :button {:class "btn btn-link"} "Link")
           ($ :button {:class "btn btn-primary btn-sm"} "Small")
           ($ :button {:class "btn btn-primary btn-lg"} "Large")
           ($ :button {:class "btn btn-primary" :disabled true} "Disabled")
           ($ :button {:class "btn btn-secondary"}
              ($ icon {:name :download :size 14})
              "С иконкой")))

     ;; Badges & chips
     ($ section {:title "Бейджи и chips"}
        ($ :div {:class "row" :style {:flex-wrap "wrap" :gap "8px"}}
           ($ :span {:class "badge badge-success"} "success")
           ($ :span {:class "badge badge-warning"} "warning")
           ($ :span {:class "badge badge-danger"} "danger")
           ($ :span {:class "badge badge-info"} "info")
           ($ :span {:class "badge badge-neutral"} "neutral")
           ($ :span {:class "chip is-active"} "chip активный")
           ($ :span {:class "chip"} "chip default")
           ($ :span {:class "chip chip-mp-wb"}
              ($ mp-badge {:mp :wb}) "Wildberries")
           ($ :span {:class "chip chip-mp-ozon"}
              ($ mp-badge {:mp :ozon}) "Ozon")
           ($ :span {:class "chip chip-mp-ym"}
              ($ mp-badge {:mp :ym}) "YM")))

     ;; Deltas
     ($ section {:title "Delta-индикаторы"}
        ($ :div {:class "row" :style {:gap "24px"}}
           ($ delta {:pct 12.4})
           ($ delta {:pct -3.2})
           ($ delta {:pct 0.02})
           ($ delta {:pct 5.5 :inverted true})
           ($ delta {:pct -1.8 :inverted true})))

     ;; Inputs
     ($ section {:title "Поля ввода"}
        ($ :div {:style {:display               "grid"
                          :grid-template-columns "repeat(3, 1fr)"
                          :gap                   "14px"
                          :max-width             "720px"}}
           ($ :div
              ($ :label {:class "field-label"} "Текст")
              ($ :input {:class "input" :placeholder "Введите…"}))
           ($ :div
              ($ :label {:class "field-label"} "Число")
              ($ :input {:class "input mono"
                          :default-value "1 234 567"
                          :style {:text-align "right"}}))
           ($ :div
              ($ :label {:class "field-label"} "Селект")
              ($ :select {:class "input select"}
                 ($ :option "Все МП")
                 ($ :option "WB")
                 ($ :option "Ozon")))))

     ;; Alerts
     ($ section {:title "Алерты"}
        ($ :div {:style {:display "flex" :flex-direction "column" :gap "10px"}}
           (for [k ["info" "success" "warning" "danger"]]
             ($ :div {:key   k
                      :class (str "alert alert-" k)}
                ($ icon {:name  (keyword k)
                          :class "alert-icon"})
                ($ :div {:class "alert-body"}
                   ($ :div {:class "alert-title"} (str "Заголовок alert · " k))
                   ($ :div "Тестовое описание для демонстрации компонента."))))))

     ;; Progress
     ($ section {:title "Прогресс"}
        ($ :div {:style {:display "flex" :flex-direction "column" :gap "14px" :max-width "600px"}}
           (for [[p l] [[35 "danger"] [62 ""] [88 "warning"] [110 "success"]]]
             ($ :div {:key p}
                ($ :div {:class "row"}
                   ($ :span {:class "muted" :style {:font-size "12px"}}
                      (str p "% выполнения")))
                ($ :div {:class (str "progress" (when (seq l) (str " " l)))}
                   ($ :div {:style {:width (str (min 100 p) "%")}}))))))

     ;; Skeleton
     ($ section {:title "Skeleton"}
        ($ :div {:style {:display "flex" :flex-direction "column" :gap "8px" :max-width "400px"}}
           ($ :div {:class "skel" :style {:height "12px" :width "40%"}})
           ($ :div {:class "skel" :style {:height "24px" :width "70%"}})
           ($ :div {:class "skel" :style {:height "12px" :width "90%"}})
           ($ :div {:class "skel" :style {:height "80px"}})))

     ;; Tabs
     ($ section {:title "Табы"}
        ($ tab-example {}))

     ;; KPI card example
     ($ section {:title "KPI-карточка"}
        ($ :div {:style {:display               "grid"
                          :grid-template-columns "repeat(4, 1fr)"
                          :gap                   "14px"}}
           (for [[l v d] [["Выручка" "8 420 000 ₽" 12.4]
                           ["Заказы" "3 214 шт" -2.1]
                           ["Маржа" "27,5%" 1.8]
                           ["ROAS" "3,2×" 5.0]]]
             ($ :div {:key l :class "kpi-card"}
                ($ :div {:class "kpi-label"} l)
                ($ :div {:class "kpi-value"} v)
                ($ delta {:pct d})))))

     ;; Sparkline examples
     ($ section {:title "Sparklines"}
        ($ :div {:class "row" :style {:gap "24px" :align-items "center"}}
           ($ sparkline {:data [100 120 110 130 150 140 160 170 155 180]
                          :width 80
                          :height 28})
           ($ sparkline {:data [200 190 180 170 185 175 160 155 140 130]
                          :width 80
                          :height 28})
           ($ sparkline {:data [50 50 50 50 50 50 50 50 50 50]
                          :width 80
                          :height 28})))))
