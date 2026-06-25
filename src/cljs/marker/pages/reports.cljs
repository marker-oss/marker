(ns marker.pages.reports
  "Generic schema-driven report page (Phase 9).
   Renders any of the 10 report types (sales, finance, ue, abc, stock,
   returns, buyout, geo, trends, losses) using the columns metadata
   returned by /api/v1/marker/reports/:type.

   Pure UI: it knows nothing about the specifics of each report — all
   structure comes from the backend schema."
  (:require ["chart.js/auto" :refer [Chart]]
            [uix.core :refer [$ defui use-state use-memo use-effect use-ref]]
            [uix.re-frame :refer [use-subscribe]]
            [re-frame.core :as rf]
            [clojure.string :as str]
            [marker.state.subs   :as subs]
            [marker.state.events :as events]
            [marker.ui.icons     :refer [icon]]
            [marker.ui.basis     :refer [coverage-banner]]
            [marker.util.format  :as fmt]))

(def ^:private report-titles
  {:sales   "Продажи"
   :finance "Финансы"
   :ue      "Юнит-экономика"
   :pnl     "P&L"
   :abc     "ABC-анализ"
   :stock   "Остатки"
   :returns "Возвраты"
   :buyout  "Выкуп"
   :geo     "География"
   :trends  "Тренды"
   :losses  "Потери"})

;; Phase 3: charts.clj/compute-report-chart implements these types.
;; Other types (:geo, :losses) silently fall back to :table.
(def ^:private chart-supported
  #{:sales :finance :ue :pnl :abc :stock :returns :buyout :trends})

;; Default chart kind per report-type — drives the Chart.js :type field.
(def ^:private chart-kind
  {:sales   "line"
   :returns "line"
   :buyout  "bar"
   :abc     "bar"
   :trends  "bar"
   :ue      "bar"
   :stock   "bar"
   :finance "bar"
   :pnl     "bar"})

;; ---------------------------------------------------------------------------
;; Cell formatting — schema column → rendered string
;; ---------------------------------------------------------------------------

(defn- format-cell
  "Render a value according to schema column :format / :group.
   Returns string for table display."
  [{:keys [format group]} v]
  (cond
    (nil? v)                "—"
    (= group :identity)     (str v)
    (= format :money)       (fmt/format-rub v)
    (= format :rub)         (fmt/format-rub v)
    (= format :int)         (fmt/format-int v)
    (= format :pct)         (fmt/format-pct v)
    (= format :percent)     (fmt/format-pct v)
    (= format :mul)         (fmt/format-mul v)
    (= format :date)        (str v)        ; backend already sends ISO
    (= format :text)        (str v)
    (number? v)             (fmt/format-int v)
    :else                   (str v)))

(defn- delta-class [delta]
  (cond
    (or (nil? delta) (zero? delta)) "tag-neutral"
    (pos? delta)                    "tag-good"
    :else                           "tag-bad"))

(defn- format-delta-pct [v]
  (cond
    (nil? v)              ""
    (zero? v)             "0%"
    (pos? v)              (str "+" (fmt/format-pct v))
    :else                 (fmt/format-pct v)))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- visible-columns
  "Pick columns marked :default-visible? = true.
   Falls back to all columns if none flagged."
  [columns]
  (let [vis (filterv :default-visible? columns)]
    (if (seq vis) vis columns)))

(defn- safe-num [v] (if (and (some? v) (number? v) (not (js/isNaN v))) v 0))

;; ---------------------------------------------------------------------------
;; Loading skeleton
;; ---------------------------------------------------------------------------

(defui ^:private skeleton []
  ($ :div {:class "page-content"}
     ($ :section {:class "card section-card"}
        ($ :div {:class "skel" :style {:height "200px" :border-radius "var(--radius-md)"}}))))

;; ---------------------------------------------------------------------------
;; Error banner
;; ---------------------------------------------------------------------------

(defui ^:private error-banner [{:keys [message on-retry title]}]
  ($ :div {:class "alert alert-danger" :style {:margin-bottom "12px"}}
     ($ icon {:name :danger :class "alert-icon"})
     ($ :div {:class "alert-body"}
        ($ :div {:class "alert-title"} (or title "Не удалось загрузить отчёт"))
        ($ :div (or message "Проверьте соединение с сервером.")))
     ($ :button {:class "btn btn-ghost btn-sm"
                 :style {:color "inherit" :border "1px solid currentColor"}
                 :on-click on-retry}
        "Повторить")))

;; ---------------------------------------------------------------------------
;; Header (title + KPI tiles + totals)
;; ---------------------------------------------------------------------------

(defui ^:private kpi-tile [{:keys [label value sub]}]
  ($ :div {:class "kpi"}
     ($ :div {:class "kpi-label"} label)
     ($ :div {:class "kpi-value mono"} value)
     (when sub
       ($ :div {:class "kpi-sub"} sub))))

(defui ^:private totals-block [{:keys [columns totals]}]
  ;; Render numeric totals as KPI tiles.
  (let [num-cols (filterv (fn [{:keys [format group]}]
                            (and (not= group :identity)
                                 (#{:money :rub :int :pct :mul :percent} format)))
                          columns)
        ;; Show only columns that have a non-nil totals entry.
        shown    (filterv #(some? (get totals (:key %))) num-cols)]
    (when (seq shown)
      ($ :section {:class "card section-card"
                   :style {:margin-bottom "12px"}}
         ($ :div {:class "kpi-row"
                  :style {:display "grid"
                          :grid-template-columns "repeat(auto-fit, minmax(160px, 1fr))"
                          :gap "12px"}}
            (for [col (take 6 shown)]
              ($ kpi-tile {:key   (name (:key col))
                           :label (:title col)
                           :value (format-cell col (get totals (:key col)))})))))))

;; ---------------------------------------------------------------------------
;; Table
;; ---------------------------------------------------------------------------

(defn- compare-row
  "Find prev row in compare-rows by join key."
  [compare-rows join-key row]
  (when (and join-key (seq compare-rows))
    (some #(when (= (get % join-key) (get row join-key)) %) compare-rows)))

(defui ^:private report-table [{:keys [columns rows compare-rows on-article-click]}]
  (let [vis-cols       (visible-columns columns)
        identity-col   (first (filterv #(= :identity (:group %)) vis-cols))
        join-key       (:key identity-col)
        sortable-cols  (set (mapv :key vis-cols))
        [sort-key set-sort-key!] (use-state nil)
        [sort-dir set-sort-dir!] (use-state :desc)
        sorted (use-memo
                (fn []
                  (if (and sort-key (contains? sortable-cols sort-key))
                    (let [cmp (if (= sort-dir :desc) #(compare %2 %1) compare)
                          key-fn (fn [r]
                                   (let [v (get r sort-key)]
                                     (cond
                                       (nil? v)    (if (= sort-dir :desc)
                                                     js/Number.NEGATIVE_INFINITY
                                                     js/Number.POSITIVE_INFINITY)
                                       (number? v) v
                                       (string? v) v
                                       :else       (str v))))]
                      (vec (sort-by key-fn cmp rows)))
                    rows))
                [rows sort-key sort-dir sortable-cols])
        sort-click! (fn [k]
                      (if (= sort-key k)
                        (set-sort-dir! (if (= sort-dir :asc) :desc :asc))
                        (do (set-sort-key! k)
                            (set-sort-dir! :desc))))
        sort-icon (fn [k]
                    (when (= sort-key k)
                      ($ icon {:name (if (= sort-dir :asc) :arrow-up :arrow-down)
                               :size 12})))]
    ($ :div {:style {:overflow-x "auto"}}
       ($ :table {:class "tbl"}
          ($ :thead
             ($ :tr
                (for [col vis-cols]
                  ($ :th {:key (name (:key col))
                          :class (str (when (#{:money :rub :int :pct :mul :percent} (:format col)) "num ")
                                      "tbl-sortable")
                          :on-click #(sort-click! (:key col))}
                     (:title col) " " (sort-icon (:key col))))))
          ($ :tbody
             (for [row sorted]
               (let [row-key (or (get row join-key) (hash row))
                     prev    (compare-row compare-rows join-key row)]
                 ($ :tr {:key row-key
                         :style (when (and identity-col on-article-click (:linkable? identity-col))
                                  {:cursor "pointer"})
                         :on-click (when (and identity-col on-article-click (:linkable? identity-col))
                                     #(on-article-click (get row join-key)))}
                    (for [col vis-cols]
                      (let [v        (get row (:key col))
                            delta    (get row (keyword (str (name (:key col)) "_delta")))
                            delta-pct (get row (keyword (str (name (:key col)) "_delta_pct")))
                            num?     (#{:money :rub :int :pct :mul :percent} (:format col))]
                        ($ :td {:key   (name (:key col))
                                :class (when num? "num mono")}
                           (cond
                             (and (= :identity (:group col))
                                  (:linkable? col))
                             ($ :span {:class "tbl-link"} (format-cell col v))

                             :else
                             ($ :span {} (format-cell col v)))

                           (when (and (:delta-supported? col) (some? delta-pct))
                             ($ :span {:class (str "tag tag-sm " (delta-class delta-pct))
                                       :style {:margin-left "6px"}}
                                (format-delta-pct delta-pct))))))))))))))

;; ---------------------------------------------------------------------------
;; Page root
;; ---------------------------------------------------------------------------

(defui ^:private empty-state [{:keys [title]}]
  ($ :section {:class "card section-card"}
     ($ :div {:style {:text-align "center"
                      :padding    "48px 24px"
                      :color      "var(--color-fg-muted)"}}
        ($ :div {:style {:font-size "32px" :margin-bottom "8px"}} "📭")
        ($ :div {:style {:font-weight 600 :margin-bottom "4px"
                         :color "var(--color-fg-primary)"}}
           (str "Нет данных для отчёта «" title "»"))
        ($ :div {:style {:font-size "13px"}}
           "Попробуйте изменить период или выбрать другой маркетплейс."))))

;; ---------------------------------------------------------------------------
;; Chart canvas — renders Chart.js from {:labels [...] :datasets [...]}
;; ---------------------------------------------------------------------------

(defui ^:private chart-canvas
  "Render Chart.js bar/line for a {:labels :datasets} payload.
   Destroys the chart on unmount or when data/kind change so re-renders
   don't trigger «Canvas is already in use» warnings."
  [{:keys [data kind]}]
  (let [ref (use-ref nil)]
    (use-effect
     (fn []
       (when (and @ref (seq (:datasets data)))
         (let [c (Chart.
                   @ref
                   #js {:type    (or kind "bar")
                        :data    (clj->js data)
                        :options #js {:responsive          true
                                       :maintainAspectRatio false
                                       :plugins #js {:legend  #js {:display true
                                                                   :position "top"
                                                                   :labels #js {:font #js {:size 11 :family "Inter"}}}
                                                     :tooltip #js {:backgroundColor "#0f172a"}}
                                       :scales #js {:x #js {:grid  #js {:display false}
                                                             :ticks #js {:font #js {:size 10 :family "Inter"}
                                                                         :color "#94a3b8"
                                                                         :maxTicksLimit 12}}
                                                     :y #js {:grid  #js {:color "#f1f5f9"}
                                                             :ticks #js {:font #js {:size 10 :family "Inter"}
                                                                         :color "#94a3b8"}
                                                             :beginAtZero true}}}})]
           #(.destroy c))))
     [data kind])
    ($ :div {:style {:height "360px"}}
       ($ :canvas (assoc {} :ref ref)))))

(defui ^:private view-toggle [{:keys [view on-change]}]
  ($ :div {:class "row"
           :style {:gap "4px"
                   :padding "2px"
                   :border "1px solid var(--color-border-subtle)"
                   :border-radius "6px"}}
     ($ :button {:class    (str "btn btn-sm "
                                (if (= view :chart) "btn-secondary" "btn-ghost"))
                 :on-click #(when (not= view :chart) (on-change :chart))
                 :style    {:height "26px"}}
        ($ icon {:name :pulse :size 14})
        "График")
     ($ :button {:class    (str "btn btn-sm "
                                (if (= view :table) "btn-secondary" "btn-ghost"))
                 :on-click #(when (not= view :table) (on-change :table))
                 :style    {:height "26px"}}
        ($ icon {:name :layout :size 14})
        "Таблица")))

(defui report
  "Generic report page. Pass :type as a keyword (e.g. :finance, :ue, :abc)."
  [{:keys [type]}]
  (let [report-type type
        title       (get report-titles report-type (name report-type))
        mps         (use-subscribe [::subs/mp-filter])
        period      (use-subscribe [::subs/period])
        compare?    (use-subscribe [::subs/compare])
        data        (use-subscribe [::subs/report-data report-type])
        loading?    (use-subscribe [::subs/report-loading? report-type])
        chart-data  (use-subscribe [::subs/report-chart-data report-type])
        chart-loading? (use-subscribe [::subs/report-chart-loading? report-type])
        api-errors  (use-subscribe [::subs/api-errors])
        url         (str "/api/v1/marker/reports/" (name report-type))
        error-msg   (get-in api-errors [url :message])
        fs          {:mp-filter mps :period period :compare compare?}

        ;; Phase 3: dual-mode view. Default :chart for chart-supported
        ;; types; types that don't have a chart-builder go straight to
        ;; :table and the toggle is hidden.
        chart?         (contains? chart-supported report-type)
        [view set-view!] (use-state (if chart? :chart :table))
        retry!      #(do (rf/dispatch [::events/clear-cache])
                         (rf/dispatch [::events/load-report report-type fs])
                         (when chart?
                           (rf/dispatch [::events/load-report-chart
                                         report-type fs])))
        article-click! (fn [art]
                         (when art
                           (rf/dispatch [::events/open-sheet-and-load (str art)])))]

    (use-effect
     (fn []
       (rf/dispatch [::events/load-report report-type
                     {:mp-filter mps :period period :compare compare?}])
       (when chart?
         (rf/dispatch [::events/load-report-chart report-type
                       {:mp-filter mps :period period :compare compare?}]))
       js/undefined)
     [report-type mps period compare? chart?])

    ;; If user switched away from a chart-supported type, snap view back to a sane default.
    (use-effect
     (fn []
       (when (and (not chart?) (= view :chart))
         (set-view! :table))
       js/undefined)
     [report-type chart?])

    (cond
      (and loading? (nil? data))
      ($ skeleton)

      (and error-msg (nil? data))
      ($ :div {:class "page-content"}
         ($ error-banner {:message error-msg :on-retry retry!}))

      (nil? data)
      ($ :div {:class "page-content"}
         ($ empty-state {:title title}))

      :else
      (let [columns       (or (:columns data) [])
            rows          (or (:rows    data) [])
            totals        (or (:totals  data) {})
            compare-rows  (get-in data [:compare :rows])
            rows-mode     (get-in data [:schema :rows-mode])
            completeness  (:completeness data)
            empty-data?   (= :empty completeness)]
        ($ :div {:class "page-content"}
           (when error-msg
             ($ error-banner {:message error-msg :on-retry retry!}))

           ;; LT3: honesty banner from the backend envelope.
           ($ coverage-banner {:completeness completeness
                               :date-basis   (:date-basis data)
                               :preliminary? (:preliminary? data)})

           ;; Totals/KPI row
           ($ totals-block {:columns columns :totals totals})

           ;; Main table — only render when rows-mode != :none
           (cond
             ;; LT3: no monetary data at all → empty-state, never a zero-row grid.
             empty-data?
             ($ empty-state {:title title})

             (= rows-mode :none)
             ($ :section {:class "card section-card"}
                ($ :div {:class "section-head"}
                   ($ :h3 {:class "section-title"} title))
                ($ :p {:style {:color "var(--color-fg-muted)"
                               :font-size "13px"}}
                   "Сводные показатели в карточках выше. Детализация на этой вкладке не требуется."))

             (empty? rows)
             ($ empty-state {:title title})

             :else
             ($ :section {:class "card section-card"}
                ($ :div {:class "section-head"}
                   ($ :div
                      ($ :h3 {:class "section-title"} title)
                      ($ :div {:class "section-subtitle"}
                         (let [n (count rows)]
                           (str n " " (fmt/plural-ru n "строка" "строки" "строк")))))
                   (when chart?
                     ($ view-toggle {:view view :on-change set-view!})))
                (cond
                  ;; Chart view — only available for chart-supported types.
                  (and chart? (= view :chart))
                  (cond
                    (and chart-loading? (nil? chart-data))
                    ($ :div {:class "skel" :style {:height "360px"}})

                    (or (nil? chart-data) (empty? (:datasets chart-data)))
                    ($ :div {:style {:padding "32px" :text-align "center"
                                      :color "var(--color-fg-muted)"
                                      :font-size "13px"}}
                       "Нет данных для графика. Переключитесь на «Таблицу» или измените период.")

                    :else
                    ($ chart-canvas {:data chart-data
                                     :kind (get chart-kind report-type "bar")}))

                  ;; Table view (default for unsupported types)
                  :else
                  ($ report-table {:columns          columns
                                   :rows             rows
                                   :compare-rows     compare-rows
                                   :on-article-click article-click!})))))))))
