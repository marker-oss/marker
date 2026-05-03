(ns marker.pages.unit
  "Unit-economics what-if calculator — Phase 6.
   6 sliders, live recalculation, delta-vs-baseline display."
  (:require [uix.core :refer [$ defui use-state use-memo]]
            [marker.ui.chrome   :refer [delta]]
            [marker.ui.icons    :refer [icon]]
            [marker.util.format :as fmt]))

;; ---------------------------------------------------------------------------
;; Pure calculation helper (also used by unit_test.cljs)
;; ---------------------------------------------------------------------------

(defn compute-unit-econ
  "Given a params map with keys :price :cogs :commission :logistics :returns :ads,
   returns {:profit :margin :roas :total-cost :break-even}.
   :margin is 0 when price is 0 (guards div-by-zero)."
  [{:keys [price cogs commission logistics returns ads]
    :or   {price 0 cogs 0 commission 0 logistics 0 returns 0 ads 0}}]
  (let [commission-amt (* price (/ commission 100))
        returns-amt    (* price (/ returns 100))
        total-cost     (+ cogs commission-amt logistics returns-amt ads)
        profit         (- price total-cost)
        margin         (if (zero? price) 0 (* (/ profit price) 100))
        roas           (if (pos? ads) (/ price ads) 0)
        break-even     (if (pos? profit) 0 js/Infinity)]
    {:profit      profit
     :margin      margin
     :roas        roas
     :total-cost  total-cost
     :break-even  break-even}))

;; ---------------------------------------------------------------------------
;; Default baseline
;; ---------------------------------------------------------------------------

(def ^:private baseline
  {:price 2500 :cogs 1200 :commission 17 :logistics 90 :returns 8 :ads 220})

;; ---------------------------------------------------------------------------
;; Slider rows spec
;; ---------------------------------------------------------------------------

(def ^:private sliders
  [{:k :price      :label "Цена розничная, ₽"  :min 1000 :max 5000 :step 50
    :fmt fmt/format-rub}
   {:k :cogs       :label "Себестоимость, ₽"   :min 400  :max 2500 :step 25
    :fmt fmt/format-rub}
   {:k :commission :label "Комиссия МП, %"      :min 5    :max 30   :step 0.5
    :fmt #(fmt/format-pct %)}
   {:k :logistics  :label "Логистика, ₽"        :min 30   :max 250  :step 5
    :fmt fmt/format-rub}
   {:k :returns    :label "Возвраты, %"          :min 0    :max 25   :step 0.5
    :fmt #(fmt/format-pct %)}
   {:k :ads        :label "Реклама, ₽/шт"       :min 0    :max 600  :step 10
    :fmt fmt/format-rub}])

;; ---------------------------------------------------------------------------
;; Cost structure bar rows
;; ---------------------------------------------------------------------------

(defn- cost-rows [params]
  [{:label "Себестоимость"
    :val   (:cogs params)
    :color "var(--chart-1)"}
   {:label "Комиссия МП"
    :val   (* (:price params) (/ (:commission params) 100))
    :color "var(--chart-2)"}
   {:label "Логистика"
    :val   (:logistics params)
    :color "var(--chart-3)"}
   {:label "Возвраты"
    :val   (* (:price params) (/ (:returns params) 100))
    :color "var(--chart-4)"}
   {:label "Реклама"
    :val   (:ads params)
    :color "var(--chart-5)"}])

;; ---------------------------------------------------------------------------
;; Page component
;; ---------------------------------------------------------------------------

(defui unit []
  (let [[params set-params!] (use-state baseline)
        set-k! (fn [k v] (set-params! #(assoc % k v)))

        cur  (use-memo #(compute-unit-econ params)  [params])
        base (use-memo #(compute-unit-econ baseline) [])

        d-profit (if (zero? (:profit base))
                   0
                   (* (/ (- (:profit cur) (:profit base))
                         (js/Math.abs (:profit base)))
                      100))
        d-margin (- (:margin cur) (:margin base))]

    ($ :div {:class "page-content"}
       ($ :div {:class "grid-12"}

          ;; Left: sliders panel (7 columns)
          ($ :section {:class "card section-card col-7"}
             ($ :div {:class "section-head"}
                ($ :div
                   ($ :h3 {:class "section-title"} "Параметры")
                   ($ :div {:class "section-subtitle"}
                      "передвиньте слайдер — расчёт обновится мгновенно"))
                ($ :button {:class    "btn btn-secondary btn-sm"
                            :on-click #(set-params! baseline)}
                   "Сбросить"))

             ($ :div {:style {:display        "flex"
                              :flex-direction  "column"
                              :gap             "18px"}}
                (for [{:keys [k label min max step fmt]} sliders]
                  ($ :div {:key   (name k)
                           :class "slider-row"}
                     ($ :div {:class "head"}
                        ($ :span {:class "label"} label)
                        ($ :span {:class "val"} (fmt (get params k))))
                     ($ :input {:type      "range"
                                :min       min
                                :max       max
                                :step      step
                                :value     (get params k)
                                :on-change #(set-k! k (js/parseFloat (.. % -target -value)))})
                     ($ :div {:style {:display         "flex"
                                      :justify-content  "space-between"
                                      :font-size        "10px"
                                      :color            "var(--color-fg-muted)"}}
                        ($ :span (fmt min))
                        ($ :span {:style {:color "var(--color-fg-disabled)"}}
                           (str "baseline " (fmt (get baseline k))))
                        ($ :span (fmt max))))))

             ;; Footer buttons
             ($ :div {:style {:margin-top    "24px"
                              :padding-top   "16px"
                              :border-top    "1px solid var(--color-border-subtle)"
                              :display       "flex"
                              :gap           "8px"}}
                ($ :button {:class "btn btn-primary btn-sm"}
                   "Применить как основной")
                ($ :button {:class "btn btn-ghost btn-sm"}
                   "Сохранить как сценарий")))

          ;; Right: metrics (5 columns)
          ($ :section {:class "col-5"
                       :style {:display        "flex"
                               :flex-direction  "column"
                               :gap             "16px"}}

             ;; Маржа card
             ($ :div {:class "card section-card"}
                ($ :div {:class "uppercase-label"} "Маржа")
                ($ :div {:style {:font-size 36 :font-weight 700 :margin-top "4px"}}
                   (fmt/format-pct (:margin cur)))
                ($ :div {:style {:margin-top "6px"}}
                   ($ delta {:pct d-margin :suffix " п.п. vs baseline"}))
                ;; Progress bar
                ($ :div {:style {:margin-top   "12px"
                                 :height       "6px"
                                 :background   "var(--color-bg-muted)"
                                 :border-radius "999px"
                                 :overflow     "hidden"}}
                   ($ :div {:style {:height     "100%"
                                    :width      (str (max 0 (min 100 (* (:margin cur) 2))) "%")
                                    :background (cond (> (:margin cur) 25) "var(--color-delta-positive)"
                                                      (> (:margin cur) 15) "var(--color-warning-fg)"
                                                      :else                "var(--color-delta-negative)")
                                    :transition "width 200ms"}})))

             ;; Прибыль card
             ($ :div {:class "card section-card"}
                ($ :div {:class "uppercase-label"} "Прибыль с штуки")
                ($ :div {:style {:font-size 28 :font-weight 700 :margin-top "4px"}}
                   (fmt/format-rub (:profit cur)))
                ($ :div {:style {:margin-top "6px"}}
                   ($ delta {:pct d-profit :suffix " vs baseline"})))

             ;; ROAS card
             ($ :div {:class "card section-card"}
                ($ :div {:class "uppercase-label"} "ROAS")
                ($ :div {:style {:font-size 28 :font-weight 700 :margin-top "4px"}}
                   (fmt/format-mul (:roas cur))))

             ;; Cost structure card
             ($ :div {:class "card section-card"}
                ($ :div {:class "uppercase-label"} "Структура затрат")
                ($ :div {:style {:margin-top     "10px"
                                 :display        "flex"
                                 :flex-direction  "column"
                                 :gap            "8px"}}
                   (for [{:keys [label val color]} (cost-rows params)]
                     ($ :div {:key   label
                              :style {:display     "flex"
                                      :align-items "center"
                                      :gap         "10px"
                                      :font-size   "12px"}}
                        ($ :span {:style {:width         "8px"
                                          :height        "8px"
                                          :border-radius "2px"
                                          :background    color}})
                        ($ :span {:style {:flex 1}} label)
                        ($ :span {:class "mono"} (fmt/format-rub val))
                        ($ :span {:class "mono"
                                  :style {:color      "var(--color-fg-muted)"
                                          :min-width  "44px"
                                          :text-align "right"}}
                           (let [pct (if (pos? (:price params))
                                       (/ (* val 100.0) (:price params))
                                       0.0)]
                             (str (.toFixed pct 0) "%"))))))))))))

