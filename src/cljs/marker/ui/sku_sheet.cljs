(ns marker.ui.sku-sheet
  "SKU detail panel content — rendered inside the Sheet chrome component.
   Subscribes to ::subs/sheet-sku; looks up the SKU in mock/skus and
   renders KPI mini-cards, 30-day revenue chart, plan-fact, stocks table."
  (:require ["chart.js/auto" :refer [Chart]]
            [uix.core :refer [$ defui use-effect use-ref use-memo]]
            [uix.re-frame :refer [use-subscribe]]
            [re-frame.core :as rf]
            [marker.state.subs   :as subs]
            [marker.state.events :as events]
            [marker.ui.chrome    :refer [delta mp-badge sparkline]]
            [marker.ui.icons     :refer [icon]]
            [marker.mock         :as mock]
            [marker.util.format  :as fmt]))

;; ---------------------------------------------------------------------------
;; Revenue chart canvas
;; ---------------------------------------------------------------------------

(defui ^:private revenue-chart [{:keys [sku]}]
  (let [ref (use-ref nil)]
    (use-effect
     (fn []
       (when (and sku @ref)
         (let [ctx (.getContext @ref "2d")
               labels (mapv #(str (-> (inc %) str (.padStart 2 "0")) ".05") (range 30))
               c (Chart.
                  ctx
                  #js {:type "line"
                       :data #js {:labels (clj->js labels)
                                  :datasets #js [#js {:label         "Выручка"
                                                       :data          (clj->js (:spark sku))
                                                       :borderColor   "#4f46e5"
                                                       :backgroundColor "rgba(79,70,229,0.12)"
                                                       :fill          true
                                                       :tension       0.3
                                                       :borderWidth   2
                                                       :pointRadius   0}]}
                       :options #js {:responsive          true
                                     :maintainAspectRatio false
                                     :plugins #js {:legend  #js {:display false}
                                                   :tooltip #js {:backgroundColor "#0f172a"}}
                                     :scales #js {:x #js {:grid  #js {:display false}
                                                           :ticks #js {:font #js {:size 10 :family "Inter"}
                                                                       :color "#94a3b8"
                                                                       :maxTicksLimit 6}}
                                                   :y #js {:grid  #js {:color "#f1f5f9"}
                                                           :ticks #js {:font #js {:size 10 :family "Inter"}
                                                                       :color "#94a3b8"
                                                                       :callback (fn [v] (fmt/format-short v))}
                                                           :beginAtZero true}}}})]
           #(do (.destroy c)))))
     [sku])
    ($ :div {:style {:height "160px"}}
       ($ :canvas {:ref ref}))))

;; ---------------------------------------------------------------------------
;; Stock rows helper
;; ---------------------------------------------------------------------------

(defn- stock-rows [sku]
  (mapv (fn [m]
          (let [stock (js/Math.round (* (:stock sku)
                                        (case m :wb 0.6 :ozon 0.3 0.1)))
                speed (max 1 (js/Math.round (* (/ (:orders sku) 30)
                                               (case m :wb 0.6 0.3))))]
            {:mp    m
             :stock stock
             :speed speed
             :days  (js/Math.round (/ stock speed))}))
        (:mp sku)))

;; ---------------------------------------------------------------------------
;; Main SKU sheet content
;; ---------------------------------------------------------------------------

(defui sku-sheet-content []
  (let [sheet-sku-id (use-subscribe [::subs/sheet-sku])
        sku          (use-memo
                      (fn []
                        (when sheet-sku-id
                          (first (filter #(= (:id %) sheet-sku-id) mock/skus))))
                      [sheet-sku-id])]
    (when sku
      (let [plan-pct (js/Math.round (* (/ (:revenue sku) (:plan sku)) 100))
            kpis     [{:l "Выручка 30 дн"
                       :v (fmt/format-rub (:revenue sku))
                       :d (:delta-pct sku)}
                      {:l "Заказы"
                       :v (str (fmt/format-int (:orders sku)) " шт")
                       :d (* (:delta-pct sku) 0.7)}
                      {:l "Маржа"
                       :v (fmt/format-pct (* (:margin sku) 100))
                       :d -1.5}
                      {:l "ROAS"
                       :v (fmt/format-mul (:roas sku))
                       :d 4.2}]]
        ($ :<>
           ;; Header
           ($ :div {:class "sheet-head"}
              ($ :div
                 ($ :div {:class "row"}
                    ($ :span {:class "mono"
                              :style {:font-size "12px"
                                      :color     "var(--color-fg-muted)"}}
                       (:id sku))
                    (for [m (:mp sku)]
                      ($ mp-badge {:key (name m) :mp m})))
                 ($ :div {:style {:font-size "18px"
                                  :font-weight 600
                                  :margin-top "4px"}}
                    (:name sku)))
              ($ :div {:class "row"}
                 ($ :button {:class "icon-btn"}
                    ($ icon {:name :more-h}))
                 ($ :button {:class    "icon-btn"
                             :on-click #(rf/dispatch [::events/close-sheet])}
                    ($ icon {:name :x}))))

           ;; Body
           ($ :div {:class "sheet-body"}
              ;; 4 KPI mini-cards
              ($ :div {:style {:display               "grid"
                               :grid-template-columns "repeat(2, 1fr)"
                               :gap                   "10px"}}
                 (for [{:keys [l v d]} kpis]
                   ($ :div {:key l :class "card" :style {:padding "12px"}}
                      ($ :div {:class "uppercase-label"} l)
                      ($ :div {:style {:font-size "18px" :font-weight 600 :margin-top "4px"}} v)
                      ($ :div {:style {:margin-top "4px"}}
                         ($ delta {:pct d})))))

              ;; Revenue chart
              ($ :div {:class "card section-card"}
                 ($ :div {:class "section-head"}
                    ($ :h3 {:class "section-title" :style {:font-size "13px"}}
                       "Динамика выручки"))
                 ($ revenue-chart {:sku sku}))

              ;; Plan-fact
              ($ :div {:class "card section-card"}
                 ($ :div {:class "section-head"}
                    ($ :h3 {:class "section-title" :style {:font-size "13px"}}
                       "План — факт")
                    ($ :span {:class "badge badge-info"} (str plan-pct "%")))
                 ($ :div {:class "row" :style {:margin-bottom "8px"}}
                    ($ :span {:class "mono" :style {:font-size "16px" :font-weight 600}}
                       (fmt/format-rub (:revenue sku)))
                    ($ :span {:style {:color "var(--color-fg-muted)" :font-size "12px"}}
                       (str "из " (fmt/format-rub (:plan sku)))))
                 ($ :div {:class (str "progress"
                                      (cond (>= plan-pct 100) " success"
                                            (>= plan-pct 80)  " warning"
                                            :else             ""))}
                    ($ :div {:style {:width (str (min 100 plan-pct) "%")}})))

              ;; Stocks table
              ($ :div {:class "card section-card"}
                 ($ :div {:class "section-head"}
                    ($ :h3 {:class "section-title" :style {:font-size "13px"}}
                       "Остатки"))
                 ($ :table {:class "tbl"}
                    ($ :thead
                       ($ :tr
                          ($ :th "МП")
                          ($ :th {:class "num"} "Шт")
                          ($ :th {:class "num"} "Скорость")
                          ($ :th {:class "num"} "Дней")))
                    ($ :tbody
                       (for [{:keys [mp stock speed days]} (stock-rows sku)]
                         ($ :tr {:key (name mp)}
                            ($ :td
                               ($ :span {:class "row"}
                                  ($ mp-badge {:mp mp})
                                  (.toUpperCase (name mp))))
                            ($ :td {:class "num mono"} (fmt/format-int stock))
                            ($ :td {:class "num mono"} (str speed "/день"))
                            ($ :td {:class "num mono"} days)))))))

           ;; Footer
           ($ :div {:class "sheet-foot"}
              ($ :button {:class    "btn btn-ghost"
                          :on-click #(rf/dispatch [::events/close-sheet])}
                 "Закрыть")
              ($ :button {:class "btn btn-primary"}
                 "Открыть полную страницу "
                 ($ icon {:name :arrow-right :size 14}))))))))
