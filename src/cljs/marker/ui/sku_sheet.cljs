(ns marker.ui.sku-sheet
  "SKU detail panel content — Phase 8.
   Fetches from /api/v1/marker/sku-detail/:id on open via ::events/open-sheet-and-load.
   Falls back to showing a loading skeleton while data arrives.
   marker.mock is no longer used here."
  (:require ["chart.js/auto" :refer [Chart]]
            [uix.core :refer [$ defui use-effect use-ref use-memo]]
            [uix.re-frame :refer [use-subscribe]]
            [re-frame.core :as rf]
            [marker.state.subs   :as subs]
            [marker.state.events :as events]
            [marker.ui.chrome    :refer [delta mp-badge kpi-card sparkline]]
            [marker.ui.icons     :refer [icon]]
            [marker.util.format  :as fmt]))

(defn- safe-num [v] (if (and (some? v) (not (js/isNaN v))) v 0))

;; ---------------------------------------------------------------------------
;; Revenue chart canvas
;; ---------------------------------------------------------------------------

(defui ^:private revenue-chart [{:keys [spark]}]
  (let [ref  (use-ref nil)]
    (use-effect
     (fn []
       ;; Q10: return the cleanup fn directly (no trailing js/undefined) so
       ;; Chart.js is destroyed on unmount or when spark deps change, preventing
       ;; "Canvas is already in use" warnings when the sheet is re-opened.
       (when @ref
         (let [data   (if (seq spark) spark [])
               labels (mapv #(str (-> (inc %) str (.padStart 2 "0")) ".05")
                            (range (count data)))
               c (Chart.
                  @ref
                  #js {:type "line"
                       :data #js {:labels (clj->js labels)
                                  :datasets #js [#js {:label         "Выручка"
                                                       :data          (clj->js data)
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
           ;; return cleanup fn — destroyed on unmount or deps change
           #(.destroy c))))
     [spark])
    ($ :div {:style {:height "160px"}}
       ($ :canvas {:ref ref}))))

;; ---------------------------------------------------------------------------
;; Skeleton
;; ---------------------------------------------------------------------------

(defui ^:private sheet-skeleton []
  ($ :<> {}
     ($ :div {:class "sheet-head"}
        ($ :div
           ($ :div {:class "skel" :style {:height "14px" :width "60%" :margin-bottom "8px" :border-radius "4px"}})
           ($ :div {:class "skel" :style {:height "20px" :width "80%" :border-radius "4px"}})))
     ($ :div {:class "sheet-body"}
        ($ :div {:style {:display "grid" :grid-template-columns "1fr 1fr" :gap "10px"}}
           (for [i (range 4)]
             ($ :div {:key i :class "skel card section-card" :style {:height "70px"}})))
        ($ :div {:class "skel card section-card" :style {:height "180px" :margin-top "12px"}}))))

;; ---------------------------------------------------------------------------
;; Main SKU sheet content
;; ---------------------------------------------------------------------------

(defui sku-sheet-content []
  (let [sheet-sku-id  (use-subscribe [::subs/sheet-sku])
        ;; Q3: parameterized per-SKU subs — loading? and sku-data are scoped to
        ;; sheet-sku-id so SKU A's in-flight response never affects SKU B's state.
        loading?      (use-subscribe [::subs/sku-detail-loading? sheet-sku-id])
        sku           (use-subscribe [::subs/sku-detail-data sheet-sku-id])
        ;; Phase 2 (UI restructure): per-warehouse + history drilldown.
        stock-detail  (use-subscribe [::subs/stock-article-data sheet-sku-id])]

    (when sheet-sku-id
      (cond
        ;; Loading — show skeleton
        (and loading? (nil? sku))
        ($ sheet-skeleton)

        ;; Error / not found
        (nil? sku)
        ($ :div {:class "sheet-body"
                 :style {:color "var(--color-fg-muted)" :font-size "13px"}}
           "Данные недоступны")

        :else
        (let [;; Extract KPIs from API shape
              kpis-raw  (:kpis sku)
              rev-kpi   (:revenue kpis-raw)
              ord-kpi   (:orders kpis-raw)
              mar-kpi   (:margin kpis-raw)
              ads-kpi   (:ads kpis-raw)

              spark     (or (:revenue-30d sku) [])
              plan-fact (or (:plan-fact sku) {})
              stocks    (or (:stocks-by-mp sku) [])

              plan      (:plan plan-fact)
              fact      (safe-num (:fact plan-fact))
              plan-pct  (if (and plan (pos? plan))
                          (js/Math.round (* (/ fact plan) 100))
                          nil)

              kpis      [{:l "Выручка 30 дн"
                          :v (fmt/format-rub (safe-num (:value rev-kpi)))
                          :d (:delta-pct rev-kpi)}
                         {:l "Заказы"
                          :v (str (fmt/format-int (safe-num (:value ord-kpi))) " шт")
                          :d nil}
                         {:l "Маржа"
                          :v (fmt/format-pct (safe-num (:value mar-kpi)))
                          :d nil}
                         {:l "Реклама"
                          :v (fmt/format-rub (safe-num (:value ads-kpi)))
                          :d nil}]]
          ($ :<> {}
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
                      (or (:name sku) (:id sku))))
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
                     ($ kpi-card {:key       l
                                  :label     l
                                  :value     v
                                  :delta-pct d
                                  :compare?  (some? d)})))

                ;; Revenue chart
                ($ :div {:class "card section-card"}
                   ($ :div {:class "section-head"}
                      ($ :h3 {:class "section-title" :style {:font-size "13px"}}
                         "Динамика выручки"))
                   ($ revenue-chart {:spark spark}))

                ;; Plan-fact (if plan is available)
                ($ :div {:class "card section-card"}
                   ($ :div {:class "section-head"}
                      ($ :h3 {:class "section-title" :style {:font-size "13px"}}
                         "Выручка / прогноз")
                      (when plan-pct
                        ($ :span {:class "badge badge-info"} (str plan-pct "%"))))
                   ($ :div {:class "row" :style {:margin-bottom "8px"}}
                      ($ :span {:class "mono" :style {:font-size "16px" :font-weight 600}}
                         (fmt/format-rub fact))
                      (when plan
                        ($ :span {:style {:color "var(--color-fg-muted)" :font-size "12px"}}
                           (str "из " (fmt/format-rub plan) " плана"))))
                   (when plan-pct
                     ($ :div {:class (str "progress"
                                          (cond (>= plan-pct 100) " success"
                                                (>= plan-pct 80)  " warning"
                                                :else             ""))}
                        ($ :div {:style {:width (str (min 100 plan-pct) "%")}}))))

                ;; Stocks by MP
                (when (seq stocks)
                  ($ :div {:class "card section-card"}
                     ($ :div {:class "section-head"}
                        ($ :h3 {:class "section-title" :style {:font-size "13px"}}
                           "Остатки"))
                     ($ :table {:class "tbl"}
                        ($ :thead
                           ($ :tr
                              ($ :th "МП")
                              ($ :th {:class "num"} "Шт")
                              ($ :th {:class "num"} "Дней")))
                        ($ :tbody
                           (for [{:keys [mp stock days]} stocks]
                             ($ :tr {:key (name (or mp :wb))}
                                ($ :td
                                   ($ :span {:class "row"}
                                      ($ mp-badge {:mp (or mp :wb)})
                                      (.toUpperCase (name (or mp :wb)))))
                                ($ :td {:class "num mono"} (fmt/format-int (safe-num stock)))
                                ($ :td {:class "num mono"} (or days "—"))))))))

                ;; Phase 2: per-warehouse breakdown + history.
                ;; Lazy — only renders when /stocks/article has loaded for this SKU.
                (let [per-wh  (or (:per-warehouse stock-detail) [])
                      history (or (:history stock-detail) [])]
                  (when (or (seq per-wh) (seq history))
                    ($ :div {:class "card section-card"}
                       ($ :div {:class "section-head"}
                          ($ :h3 {:class "section-title" :style {:font-size "13px"}}
                             "По складам"))
                       (when (seq history)
                         ($ :div {:style {:margin-bottom "10px"}}
                            ($ sparkline {:data   (mapv (fn [r] (or (:quantity r) 0)) history)
                                          :width  240
                                          :height 32})
                            ($ :div {:style {:font-size "11px"
                                              :color "var(--color-fg-muted)"
                                              :margin-top "4px"}}
                               (str "История остатка: "
                                    (count history) " "
                                    (fmt/plural-ru (count history) "день" "дня" "дней")))))
                       (when (seq per-wh)
                         ($ :table {:class "tbl"}
                            ($ :thead
                               ($ :tr
                                  ($ :th "Склад")
                                  ($ :th "МП")
                                  ($ :th {:class "num"} "Шт")
                                  ($ :th {:class "num"} "Полный")
                                  ($ :th {:class "num"} "В пути К")))
                            ($ :tbody
                               (for [{:keys [warehouse marketplace quantity quantity-full in-way-to]}
                                     (sort-by #(- (or (:quantity-full %) 0)) per-wh)]
                                 ($ :tr {:key (str warehouse "-" (name (or marketplace :wb)))}
                                    ($ :td (or warehouse "—"))
                                    ($ :td
                                       ($ :span {:class "row"}
                                          ($ mp-badge {:mp (or marketplace :wb)})
                                          (.toUpperCase (name (or marketplace :wb)))))
                                    ($ :td {:class "num mono"}
                                       (fmt/format-int (safe-num quantity)))
                                    ($ :td {:class "num mono"}
                                       (fmt/format-int (safe-num quantity-full)))
                                    ($ :td {:class "num mono"}
                                       (fmt/format-int (safe-num in-way-to))))))))))))

             ;; Footer
             ($ :div {:class "sheet-foot"}
                ($ :button {:class    "btn btn-ghost"
                            :on-click #(rf/dispatch [::events/close-sheet])}
                   "Закрыть")
                ($ :button {:class "btn btn-primary"}
                   "Открыть полную страницу "
                   ($ icon {:name :arrow-right :size 14})))))))))
