(ns marker.pages.unit
  "Unit-economics what-if calculator.

   Two modes:
   - DEMO mode (default): hardcoded baseline so the calculator is usable
     without any data context.
   - ARTICLE mode: load per-unit baseline from the backend for a real SKU
     in the active period (price, cogs, commission %, logistics ₽/шт,
     returns %, ads ₽/шт). The user can then drag sliders to explore
     scenarios for THAT article. No save / no overwrite — purely local.

   compute-unit-econ stays as the pure CLJS function used by unit_test.cljs."
  (:require [uix.core :refer [$ defui use-state use-memo use-effect]]
            [uix.re-frame :refer [use-subscribe]]
            [re-frame.core :as rf]
            [marker.state.events :as events]
            [marker.state.subs   :as subs]
            [marker.ui.chrome   :refer [delta]]
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
;; Default demo baseline (used when no article is loaded)
;; ---------------------------------------------------------------------------

(def ^:private demo-baseline
  {:price 2500 :cogs 1200 :commission 17 :logistics 90 :returns 8 :ads 220})

;; ---------------------------------------------------------------------------
;; Slider rows spec — mins/maxes adapt to the active baseline so the slider
;; never pins to its edge when an article has unusual values.
;; ---------------------------------------------------------------------------

(defn- pct-bounds
  "Symmetric bounds around v with a sensible floor/ceiling."
  [v lo-floor hi-ceil]
  (let [v   (double (or v 0))
        spn (max (* 0.6 (js/Math.abs v)) 5)
        lo  (max lo-floor (- v spn))
        hi  (min hi-ceil (+ v spn))]
    [(js/Math.floor lo) (js/Math.ceil hi)]))

(defn- rub-bounds
  [v hi-floor]
  (let [v   (double (or v 0))
        spn (max (* 0.5 v) hi-floor)
        lo  (max 0 (- v spn))
        hi  (+ v spn)]
    [(js/Math.floor lo) (js/Math.ceil hi)]))

(defn- sliders-for-baseline
  "Build slider definitions adapted to a baseline so values land mid-range."
  [b]
  (let [[p-lo p-hi] (rub-bounds (:price b)     2000)
        [c-lo c-hi] (rub-bounds (:cogs b)      800)
        [l-lo l-hi] (rub-bounds (:logistics b) 100)
        [a-lo a-hi] (rub-bounds (:ads b)       200)
        [k-lo k-hi] (pct-bounds (:commission b) -10 60)
        [r-lo r-hi] (pct-bounds (:returns b)    0   60)]
    [{:k :price      :label "Цена розничная, ₽"  :min p-lo :max (max p-hi (+ p-lo 100))
      :step (max 50 (js/Math.round (/ (- p-hi p-lo) 100))) :fmt fmt/format-rub}
     {:k :cogs       :label "Себестоимость, ₽"   :min c-lo :max (max c-hi (+ c-lo 100))
      :step (max 25 (js/Math.round (/ (- c-hi c-lo) 100))) :fmt fmt/format-rub}
     {:k :commission :label "Комиссия МП, %"      :min k-lo :max k-hi
      :step 0.5 :fmt #(fmt/format-pct %)}
     {:k :logistics  :label "Логистика, ₽"        :min l-lo :max (max l-hi (+ l-lo 50))
      :step (max 5 (js/Math.round (/ (- l-hi l-lo) 100))) :fmt fmt/format-rub}
     {:k :returns    :label "Возвраты, %"          :min r-lo :max r-hi
      :step 0.5 :fmt #(fmt/format-pct %)}
     {:k :ads        :label "Реклама, ₽/шт"       :min a-lo :max (max a-hi (+ a-lo 50))
      :step (max 10 (js/Math.round (/ (- a-hi a-lo) 100))) :fmt fmt/format-rub}]))

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
;; Article picker — search + select an SKU; submitting loads its baseline
;; ---------------------------------------------------------------------------

(defui ^:private article-picker
  [{:keys [active-article on-load on-clear]}]
  (let [[input set-input!] (use-state (or active-article ""))
        submit  (fn [e]
                  (.preventDefault e)
                  (when (seq input) (on-load input)))]
    ($ :form {:on-submit submit
              :style     {:display          "flex"
                          :gap              "8px"
                          :align-items      "center"
                          :flex-wrap        "wrap"}}
       ($ :input {:type        "text"
                  :class       "input"
                  :placeholder "Артикул (например, 3467/белый) или nm-id"
                  :value       input
                  :on-change   #(set-input! (.. % -target -value))
                  :style       {:flex      1
                                :min-width "240px"
                                :font-size "12px"}})
       ($ :button {:type  "submit"
                   :class "btn btn-primary btn-sm"}
          "Загрузить артикул")
       (when active-article
         ($ :button {:type     "button"
                     :class    "btn btn-ghost btn-sm"
                     :on-click on-clear}
            "Очистить")))))

(defui ^:private article-info
  [{:keys [data]}]
  (let [{:keys [name article qty revenue period found?]} data]
    (if found?
      ($ :div {:style {:display         "flex"
                       :gap             "16px"
                       :flex-wrap       "wrap"
                       :font-size       "11px"
                       :color           "var(--color-fg-muted)"
                       :padding         "8px 10px"
                       :background      "var(--color-bg-muted)"
                       :border-radius   "6px"
                       :margin-top      "8px"}}
         ($ :span ($ :strong {:style {:color "var(--color-fg-primary)"}}
                    (or name article))
            (when (and name (not= name article))
              (str " · " article)))
         ($ :span (str (:from period) " — " (:to period)))
         ($ :span (str "продано: " qty " шт"))
         ($ :span (str "выручка: " (fmt/format-rub revenue))))
      ($ :div {:style {:padding       "8px 10px"
                       :margin-top    "8px"
                       :background    "var(--color-bg-muted)"
                       :border-radius "6px"
                       :font-size     "11px"
                       :color         "var(--color-warning-fg)"}}
         (str "За период данных по артикулу «" article "» нет — попробуй другой период или артикул.")))))

;; ---------------------------------------------------------------------------
;; Main page
;; ---------------------------------------------------------------------------

(defui unit []
  (let [loaded      (use-subscribe [::subs/unit-baseline-data])
        bl-loading? (use-subscribe [::subs/unit-baseline-loading?])
        bl-article  (use-subscribe [::subs/unit-baseline-article])

        loaded-ok? (boolean (and loaded (:found? loaded)))
        baseline   (if loaded-ok?
                     (merge demo-baseline (:params loaded))
                     demo-baseline)

        [params set-params!] (use-state baseline)
        set-k! (fn [k v] (set-params! #(assoc % k v)))

        ;; When backend returns a new baseline, replace params so sliders reset
        ;; to the loaded article's values. Depends on `loaded` map identity
        ;; (changes only when the subscribed value changes), so editing sliders
        ;; doesn't re-trigger this effect.
        _ (use-effect
           (fn []
             (when loaded-ok? (set-params! baseline))
             js/undefined)
           [loaded loaded-ok? baseline])

        cur  (use-memo #(compute-unit-econ params)   [params])
        base (use-memo #(compute-unit-econ baseline) [baseline])

        d-profit (if (zero? (:profit base))
                   0
                   (* (/ (- (:profit cur) (:profit base))
                         (js/Math.abs (:profit base)))
                      100))
        d-margin (- (:margin cur) (:margin base))
        d-roas   (- (:roas cur) (:roas base))

        sliders  (use-memo #(sliders-for-baseline baseline) [baseline])]

    ;; Debounced server-side validation (keeps prior behavior)
    (use-effect
     (fn []
       (let [t (js/setTimeout
                #(rf/dispatch [::events/what-if-recalc params])
                300)]
         (fn [] (js/clearTimeout t))))
     [params])

    ($ :div {:class "page-content"}

       ;; Article picker bar — full width above the grid
       ($ :div {:class "card section-card"
                :style {:margin-bottom "12px"}}
          ($ :div {:class "section-head"}
             ($ :div
                ($ :h3 {:class "section-title"} "Источник данных")
                ($ :div {:class "section-subtitle"}
                   (cond
                     bl-loading?
                     "загружаем данные артикула…"
                     loaded-ok?
                     "what-if на реальных данных артикула"
                     :else
                     "демо-сценарий — введи артикул, чтобы посчитать на своих данных")))
             ($ article-picker
                {:active-article bl-article
                 :on-load        #(rf/dispatch [::events/load-unit-baseline %])
                 :on-clear       #(rf/dispatch [::events/clear-unit-baseline])}))
          (when loaded
            ($ article-info {:data loaded})))

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
                        ($ :span (fmt max)))))))

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
