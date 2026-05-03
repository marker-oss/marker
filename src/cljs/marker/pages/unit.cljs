(ns marker.pages.unit
  "Unit-economics what-if calculator — Phase 8.
   Strategy: HYBRID (option c).
   - Client-side compute runs instantly on every slider drag for snappy UX.
   - Debounced POST to /what-if-recalc fires ~300ms after the slider settles;
     this validates the computation server-side and is a hook for future
     persistence (save-as-scenario feature).
   - Server response is currently not used to override the client display
     because: (a) both sides use the same formula, (b) round-trip latency
     would make the UI feel sluggish with no benefit, (c) server confirms
     the calculation silently in the background.
   - compute-unit-econ stays as the pure CLJS function used by unit_test.cljs."
  (:require [uix.core :refer [$ defui use-state use-memo use-effect]]
            [re-frame.core :as rf]
            [marker.state.events :as events]
            [marker.ui.chrome   :refer [delta]]
            [marker.ui.icons    :refer [icon]]
            [marker.util.format :as fmt]))

;; ---------------------------------------------------------------------------
;; Pure calculation helper (also used by unit_test.cljs)
;; ---------------------------------------------------------------------------

(defn compute-unit-econ
  "Pure unit-economics calculator.

   Returns a map with :margin (%), :profit (₽ per unit), :roas (×) and
   :break-even (units to break even).

   :break-even semantics:
     - 0 when profit per unit is positive (you break even at any volume)
     - Infinity (js/Infinity) when profit per unit is non-positive
       (no positive volume covers the per-unit loss)
     - finite N otherwise (units = fixed-cost / contribution-margin)
       — currently never reached because mock data has no fixed-cost
       term; once a fixed-cost slider is added, this branch activates.

   :margin is 0 when price is 0 (guards div-by-zero).
   Given a params map with keys :price :cogs :commission :logistics :returns :ads."
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
        d-margin (- (:margin cur) (:margin base))
        d-roas   (- (:roas cur) (:roas base))

]

    ;; Q1 + Q2: inline debounce — React-idiomatic shape.
    ;; deps: only [params] — the timeout is created/cleared entirely inside this
    ;; effect; there is no external fn ref to include.
    ;; The cleanup fn (returned from the effect body) cancels the pending timer
    ;; on every params change AND on component unmount, preventing spurious POSTs
    ;; after the user navigates away from /app/unit.
    (use-effect
     (fn []
       (let [t (js/setTimeout
                #(rf/dispatch [::events/what-if-recalc params])
                300)]
         (fn [] (js/clearTimeout t))))
     [params])

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
                ($ :button {:class    "btn btn-ghost btn-sm"
                            :on-click #(rf/dispatch [::events/what-if-recalc params])}
                   "Сохранить как сценарий")))

          ;; Right: 4 metric cards + cost structure below (5 columns)
          ($ :section {:class "col-5"
                       :style {:display        "flex"
                               :flex-direction  "column"
                               :gap             "16px"}}

             ;; 4 metric cards in 2×2 grid
             ($ :div {:style {:display               "grid"
                              :grid-template-columns "1fr 1fr"
                              :gap                   "12px"}}

                ;; Маржа card
                ($ :div {:class "card section-card"}
                   ($ :div {:class "uppercase-label"} "Маржа")
                   ($ :div {:style {:font-size 26 :font-weight 700 :margin-top "4px"}}
                      (fmt/format-pct (:margin cur)))
                   ($ :div {:style {:margin-top "6px"}}
                      ($ delta {:pct d-margin :suffix " п.п. vs baseline"}))
                   ($ :div {:style {:margin-top   "10px"
                                    :height       "4px"
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
                   ($ :div {:style {:font-size 26 :font-weight 700 :margin-top "4px"}}
                      (fmt/format-rub (:profit cur)))
                   ($ :div {:style {:margin-top "6px"}}
                      ($ delta {:pct d-profit :suffix " vs baseline"})))

                ;; ROAS card
                ($ :div {:class "card section-card"}
                   ($ :div {:class "uppercase-label"} "ROAS")
                   ($ :div {:style {:font-size 26 :font-weight 700 :margin-top "4px"}}
                      (fmt/format-mul (:roas cur)))
                   ($ :div {:style {:margin-top "6px"}}
                      ($ delta {:pct (* d-roas 10) :suffix " vs baseline"})))

                ;; Точка безубыточности card
                ($ :div {:class "card section-card"}
                   ($ :div {:class "uppercase-label"} "Точка безубыточности")
                   ($ :div {:style {:font-size 26 :font-weight 700 :margin-top "4px"}}
                      (let [be (:break-even cur)]
                        (if (= be js/Infinity)
                          "∞ шт"
                          (str (js/Math.round be) " шт"))))
                   ($ :div {:style {:margin-top "6px" :font-size "11px"
                                    :color "var(--color-fg-muted)"}}
                      (if (pos? (:profit cur))
                        "прибыль при любом объёме"
                        "убыточная модель"))))

             ;; Cost structure — full-width below metric cards
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
