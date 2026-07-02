(ns marker.pages.plan-fact
  "Per-SKU «План/Факт» surface (017).
   Rendered by the Финансы section's :plan-fact tab (marker.pages.finance).

   Two cards:
     1. Plan × fact table — GET /api/v1/plan/sku via ::events/load-plan-fact,
        filtered by a local month (YYYY-MM) + marketplace selector.
        Variance rendering is edge-safe: nil план → «—» (NOT −100%);
        Δ% is coloured by delta-class with the metric's grow polarity.
     2. Import — file input (CSV/XLSX) → «Предпросмотр» dispatches
        ::events/preview-plan-import (multipart, NO DB write), rendering
        loaded/rejected counts + per-line error reasons; «Импортировать»
        commits via ::events/commit-plan-import, whose success handler
        already re-dispatches ::events/load-plan-fact (table refresh)."
  (:require [uix.core :refer [$ defui use-state use-effect use-ref]]
            [uix.re-frame :refer [use-subscribe]]
            [re-frame.core :as rf]
            [clojure.string :as str]
            [marker.state.subs :as subs]
            [marker.state.events :as events]
            [marker.ui.icons :refer [icon]]
            [marker.ui.metric-hint :refer [delta-class]]
            [marker.util.format :as fmt]))

;; ---------------------------------------------------------------------------
;; Metric catalogue (mirrors 016 canonical slugs consumed by the backend —
;; analitica.domain.plan/canonical-metric-slugs). 017 only CONSUMES metrics;
;; the current GET endpoint emits :revenue rows, the rest are future-safe.
;; ---------------------------------------------------------------------------

(def metric-info
  {:revenue      {:label "Выручка"          :suffix :rub :positive-if-grow true}
   :orders       {:label "Заказы"           :suffix :qty :positive-if-grow true}
   :gross-margin {:label "Валовая прибыль"  :suffix :rub :positive-if-grow true}
   :margin-pct   {:label "Маржа"            :suffix :pct :positive-if-grow true}
   :advertising  {:label "Реклама"          :suffix :rub :positive-if-grow false}
   :drr-pct      {:label "ДРР"              :suffix :pct :positive-if-grow false}
   :net-profit   {:label "Чистая прибыль"   :suffix :rub :positive-if-grow true}})

(defn metric->info
  "Resolve a row :metric (keyword via Transit, or string) to its display info.
   Unknown metric → raw name as label, ₽ suffix, grow-good polarity."
  [metric]
  (let [k (cond
            (keyword? metric) metric
            (string? metric)  (keyword metric)
            :else             nil)]
    (or (get metric-info k)
        {:label            (if k (name k) "—")
         :suffix           :rub
         :positive-if-grow true})))

;; ---------------------------------------------------------------------------
;; Pure render helpers (tested in marker.pages.plan-fact-test)
;; ---------------------------------------------------------------------------

(defn fmt-variance-abs
  "Δ abs cell: nil → «—»; ₽ values get an explicit +/− sign via format-rub;
   other suffixes get a manual «+» prefix for positives."
  [v suffix]
  (cond
    (nil? v)        "—"
    (= suffix :rub) (fmt/format-rub v true)
    :else           (str (when (pos? v) "+") (fmt/format-suffixed v suffix))))

(defn fmt-variance-pct
  "Δ % cell: nil → «—» (nil план must NOT read as −100%); signed otherwise."
  [v]
  (if (nil? v)
    "—"
    (str (when (pos? v) "+") (fmt/format-pct v 1))))

(defn variance-tone
  "delta-class tone (\"up\"/\"down\"/\"flat\") for a PlanFactRow.
   Favourable = green: actual ≥ plan on a grow-good metric (e.g. выручка),
   actual ≤ plan on a grow-bad metric (e.g. ДРР). nil variance → \"flat\".
   Prefers Δ% (scale-free); falls back to Δ abs when plan=0 (pct undefined)."
  [{:keys [variance-pct variance-abs metric]}]
  (delta-class (or variance-pct variance-abs)
               (:positive-if-grow (metric->info metric))))

(defn totals-variance
  "Footer Δ from backend :totals {:plan :actual}. Backend sums plans with
   (reduce + 0.0 …) so «no plans at all» arrives as plan=0.0 — treat any
   non-positive plan as «no plan» → {:abs nil :pct nil} (render «—»)."
  [{:keys [plan actual]}]
  (if (and (number? plan) (pos? plan) (number? actual))
    {:abs (- actual plan)
     :pct (* (/ (- actual plan) plan) 100.0)}
    {:abs nil :pct nil}))

(defn fmt-total-plan
  "Footer план cell: positive sum → ₽, otherwise «—» (target_value is
   validated positive on import, so Σplan=0 ⇔ no plans exist)."
  [plan]
  (if (and (number? plan) (pos? plan))
    (fmt/format-rub plan)
    "—"))

(defn current-month
  "Today as YYYY-MM (default period for the selector)."
  []
  (let [d (js/Date.)
        m (inc (.getMonth d))]
    (str (.getFullYear d) "-" (when (< m 10) "0") m)))

;; ---------------------------------------------------------------------------
;; API-error lookup — :marker/api-errors is keyed by full URL (the plan-fact
;; GET carries query params, so match by prefix; import URLs are exact).
;; ---------------------------------------------------------------------------

(def ^:private plan-sku-url     "/api/v1/plan/sku")
(def ^:private plan-preview-url "/api/v1/plan/sku/preview")
(def ^:private plan-import-url  "/api/v1/plan/sku/import")

(defn table-error
  "Error entry for the plan-fact GET, whatever query string it was sent with."
  [api-errors]
  (some (fn [[url err]]
          (when (and (string? url)
                     (or (= url plan-sku-url)
                         (str/starts-with? url (str plan-sku-url "?"))))
            err))
        api-errors))

(defn import-error
  "Error entry for either import endpoint (preview or commit)."
  [api-errors]
  (or (get api-errors plan-preview-url)
      (get api-errors plan-import-url)))

;; ---------------------------------------------------------------------------
;; Filter row (month + marketplace)
;; ---------------------------------------------------------------------------

(def ^:private mp-options
  [["all" "Все МП"] ["wb" "WB"] ["ozon" "Ozon"] ["ym" "YM"]])

(defui ^:private filters
  [{:keys [period mp on-period on-mp]}]
  ($ :div {:style {:display "flex" :gap "12px" :align-items "flex-end"
                   :flex-wrap "wrap"}}
     ($ :div {}
        ($ :label {:class "field-label"} "Месяц")
        ($ :input {:class     "input"
                   :type      "month"
                   :value     period
                   :on-change (fn [e]
                                (let [v (.. e -target -value)]
                                  ;; clearing a <input type=month> yields "" —
                                  ;; keep the previous valid YYYY-MM instead.
                                  (when (seq v) (on-period v))))}))
     ($ :div {}
        ($ :label {:class "field-label"} "Маркетплейс")
        ($ :select {:class     "input select"
                    :value     mp
                    :on-change (fn [e] (on-mp (.. e -target -value)))}
           (for [[v label] mp-options]
             ($ :option {:key v :value v} label))))))

;; ---------------------------------------------------------------------------
;; Plan/fact table
;; ---------------------------------------------------------------------------

(defui ^:private table-skeleton []
  ($ :div {:class "tbl-wrap"}
     ($ :table {:class "tbl"}
        ($ :tbody
           (for [i (range 8)]
             ($ :tr {:key i}
                (for [j (range 6)]
                  ($ :td {:key j}
                     ($ :div {:class "skel"
                              :style {:height "14px" :border-radius "4px"}})))))))))

(defui ^:private plan-fact-table
  [{:keys [rows totals]}]
  (let [{tot-abs :abs tot-pct :pct} (totals-variance totals)
        tot-tone (delta-class tot-pct true)]
    ($ :div {:class "tbl-wrap"}
       ($ :table {:class "tbl"}
          ($ :thead
             ($ :tr
                ($ :th "SKU")
                ($ :th "Метрика")
                ($ :th {:class "num"} "План")
                ($ :th {:class "num"} "Факт")
                ($ :th {:class "num"} "Δ")
                ($ :th {:class "num"} "Δ %")))
          ($ :tbody
             (for [{:keys [sku metric plan actual variance-abs variance-pct]
                    :as   row} rows]
               (let [{:keys [label suffix]} (metric->info metric)
                     tone (variance-tone row)]
                 ($ :tr {:key (str sku "|" (if (keyword? metric) (name metric) (str metric)))}
                    ($ :td {:class "mono"} sku)
                    ($ :td label)
                    ($ :td {:class "num tnum"} (fmt/format-suffixed plan suffix))
                    ($ :td {:class "num tnum"} (fmt/format-suffixed actual suffix))
                    ($ :td {:class "num"}
                       ($ :span {:class (str "delta " tone)}
                          (fmt-variance-abs variance-abs suffix)))
                    ($ :td {:class "num"}
                       ($ :span {:class (str "delta " tone)}
                          (fmt-variance-pct variance-pct)))))))
          ($ :tfoot
             ($ :tr
                ($ :td "Итого")
                ($ :td "")
                ($ :td {:class "num tnum"} (fmt-total-plan (:plan totals)))
                ($ :td {:class "num tnum"} (fmt/format-rub (:actual totals)))
                ($ :td {:class "num"}
                   ($ :span {:class (str "delta " tot-tone)}
                      (fmt-variance-abs tot-abs :rub)))
                ($ :td {:class "num"}
                   ($ :span {:class (str "delta " tot-tone)}
                      (fmt-variance-pct tot-pct)))))))))

;; ---------------------------------------------------------------------------
;; Import preview / outcome rendering
;; ---------------------------------------------------------------------------

(def ^:private error-display-cap 20)
(def ^:private rows-display-cap 10)

(defui ^:private preview-summary
  [{:keys [preview]}]
  (let [{:keys [total loaded rejected]} preview]
    ($ :div {:style {:display "flex" :gap "8px" :align-items "center"
                     :flex-wrap "wrap" :margin-top "12px"}}
       ($ :span {:class "badge badge-neutral"}
          "Всего строк: " (fmt/format-int (or total 0)))
       ($ :span {:class (str "badge " (if (pos? (or loaded 0))
                                        "badge-success" "badge-neutral"))}
          "Будет загружено: " (fmt/format-int (or loaded 0)))
       ($ :span {:class (str "badge " (if (pos? (or rejected 0))
                                        "badge-danger" "badge-neutral"))}
          "Отброшено: " (fmt/format-int (or rejected 0))))))

(defui ^:private preview-errors-table
  [{:keys [errors]}]
  (when (seq errors)
    ($ :div {:style {:margin-top "12px"}}
       ($ :div {:class "field-label"} "Отброшенные строки")
       ($ :div {:class "tbl-wrap"}
          ($ :table {:class "tbl"}
             ($ :thead
                ($ :tr
                   ($ :th {:class "num"} "Строка")
                   ($ :th "SKU")
                   ($ :th "Причина")))
             ($ :tbody
                (for [{:keys [line sku reason]} (take error-display-cap errors)]
                  ($ :tr {:key line}
                     ($ :td {:class "num mono"} line)
                     ($ :td {:class "mono"} (if (seq (str sku)) sku "—"))
                     ($ :td reason))))))
       (when (> (count errors) error-display-cap)
         ($ :div {:style {:font-size "12px" :color "var(--color-fg-muted)"
                          :margin-top "6px"}}
            "…и ещё " (- (count errors) error-display-cap) " строк")))))

(defui ^:private preview-rows-table
  [{:keys [rows]}]
  (when (seq rows)
    ($ :div {:style {:margin-top "12px"}}
       ($ :div {:class "field-label"} "Будут загружены (первые строки)")
       ($ :div {:class "tbl-wrap"}
          ($ :table {:class "tbl"}
             ($ :thead
                ($ :tr
                   ($ :th "SKU")
                   ($ :th "Метрика")
                   ($ :th "МП")
                   ($ :th {:class "num"} "Цель")))
             ($ :tbody
                ;; index keys: duplicate SKU+metric lines are legal in the
                ;; file (last-wins at upsert), so sku|metric is NOT unique.
                (for [[i {:keys [sku metric marketplace target-value]}]
                      (map-indexed vector (take rows-display-cap rows))]
                  (let [{:keys [label suffix]} (metric->info metric)]
                    ($ :tr {:key i}
                       ($ :td {:class "mono"} sku)
                       ($ :td label)
                       ($ :td (or (some-> marketplace str) "—"))
                       ($ :td {:class "num tnum"}
                          (fmt/format-suffixed target-value suffix))))))))
       (when (> (count rows) rows-display-cap)
         ($ :div {:style {:font-size "12px" :color "var(--color-fg-muted)"
                          :margin-top "6px"}}
            "…и ещё " (- (count rows) rows-display-cap) " строк")))))

;; ---------------------------------------------------------------------------
;; Import card
;; ---------------------------------------------------------------------------

(defui ^:private import-card
  [{:keys [period mp]}]
  (let [preview      (use-subscribe [::subs/plan-import-preview])
        loading?     (use-subscribe [::subs/plan-import-preview-loading?])
        api-errors   (use-subscribe [::subs/api-errors])
        err          (import-error api-errors)
        [file       set-file!]       (use-state nil)
        ;; nil → nothing in flight for the CURRENT file;
        ;; :preview → предпросмотр requested; :commit → импорт requested.
        [mode       set-mode!]       (use-state nil)
        [committed? set-committed!]  (use-state false)
        prev-loading (use-ref false)
        ;; A failed preview/commit leaves its URL in api-errors until the SAME
        ;; url succeeds — clear both import urls before a retry so a stale
        ;; commit error can't permanently disable the button (err gate below).
        clear-import-errors!
        (fn []
          (rf/dispatch [::events/clear-api-error plan-preview-url])
          (rf/dispatch [::events/clear-api-error plan-import-url]))]

    ;; Filters changed → any pending preview belongs to the OLD period/MP;
    ;; drop the commit gate so the user must re-preview under new filters.
    (use-effect
     (fn []
       (set-mode! nil)
       (set-committed! false)
       js/undefined)
     [period mp])

    ;; Detect the commit request FINISHING (loading true→false, no error) to
    ;; show the success alert. The file stays selected (non-destructive): a
    ;; late-arriving ::api-error may follow one frame later — the effect
    ;; below then retracts the success alert, and nothing was lost.
    (use-effect
     (fn []
       (when (and @prev-loading (not loading?) (= mode :commit) (nil? err))
         (set-committed! true))
       (reset! prev-loading loading?)
       js/undefined)
     [loading? mode err])

    ;; An import error always wins over an optimistic success alert.
    (use-effect
     (fn []
       (when err (set-committed! false))
       js/undefined)
     [err])

    ($ :section {:class "card section-card"}
       ($ :div {:class "section-head"}
          ($ :div {}
             ($ :h3 {:class "section-title"} "Импорт плана")
             ($ :div {:class "section-subtitle"}
                "CSV или XLSX с колонками sku, metric, target — цели за "
                (or period "выбранный месяц"))))

       ($ :div {:style {:display "flex" :gap "12px" :align-items "center"
                        :flex-wrap "wrap"}}
          ($ :input {:type      "file"
                     :accept    ".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                     :style     {:flex "1" :min-width "220px"
                                 :font-size "13px" :padding "6px"}
                     :on-change (fn [e]
                                  (let [^js f (.. e -target -files (item 0))]
                                    (set-file! f)
                                    (set-mode! nil)
                                    (set-committed! false)
                                    (clear-import-errors!)))})
          ($ :button
             {:class    "btn btn-secondary"
              :disabled (or (nil? file) loading?)
              :on-click (fn [_]
                          (when file
                            (set-committed! false)
                            (clear-import-errors!)
                            (set-mode! :preview)
                            (rf/dispatch [::events/preview-plan-import
                                          file period mp])))}
             (if (and loading? (= mode :preview)) "Проверка…" "Предпросмотр"))
          ($ :button
             {:class    "btn btn-primary"
              ;; commit only after a clean preview of the CURRENT file
              ;; with at least one loadable row (preview does NOT write).
              :disabled (or (nil? file) loading? (some? err)
                            (not= mode :preview)
                            (not (pos? (or (:loaded preview) 0))))
              :on-click (fn [_]
                          (when file
                            (set-committed! false)
                            (clear-import-errors!)
                            (set-mode! :commit)
                            (rf/dispatch [::events/commit-plan-import
                                          file period mp])))}
             ($ icon {:name :download :size 14
                      :style {:transform "rotate(180deg)"}})
             (if (and loading? (= mode :commit)) "Импорт…" "Импортировать"))
          (when (and file (not loading?))
            ($ :div {:class "mono"
                     :style {:font-size "12px"
                             :color "var(--color-fg-muted)"}}
               (.-name ^js file) " · "
               (-> (.-size ^js file) (/ 1024) (.toFixed 1)) " KB")))

       ;; Import-side error (preview or commit)
       (when err
         ($ :div {:class "alert alert-danger" :style {:margin-top "12px"}}
            ($ icon {:name :danger :class "alert-icon"})
            ($ :div {:class "alert-body"}
               ($ :div {:class "alert-title"} "Ошибка импорта")
               ($ :div (or (:message err) "Проверьте файл и попробуйте ещё раз.")))))

       ;; Commit success (table refresh is dispatched by the event layer)
       (when committed?
         ($ :div {:class "alert alert-success" :style {:margin-top "12px"}}
            ($ icon {:name :check :class "alert-icon"})
            ($ :div {:class "alert-body"}
               ($ :div {:class "alert-title"} "Импорт выполнен")
               ($ :div
                  "Загружено: " ($ :strong (fmt/format-int (or (:loaded preview) 0)))
                  " · Отброшено: " ($ :strong (fmt/format-int (or (:rejected preview) 0)))
                  " — таблица обновлена."))))

       ;; Preview outcome for the current file (no DB write yet)
       (when (and (= mode :preview) preview (not loading?) (nil? err))
         ($ :<> {}
            ($ preview-summary {:preview preview})
            ($ preview-errors-table {:errors (:errors preview)})
            ($ preview-rows-table {:rows (:rows preview)})))

       ($ :p {:style {:font-size "12px" :color "var(--color-fg-muted)"
                      :margin-top "12px" :margin-bottom 0}}
          "Заголовки колонок: sku/артикул, metric/метрика, target/план/цель, "
          "опционально marketplace/mp. Метрики: revenue, orders, gross_profit, "
          "margin_pct, ad_spend, drr_pct, net_profit. Предпросмотр ничего не "
          "записывает; импорт перезаписывает цели выбранного месяца по ключу "
          "SKU + метрика."))))

;; ---------------------------------------------------------------------------
;; Page root
;; ---------------------------------------------------------------------------

(defui plan-fact
  [_]
  (let [data       (use-subscribe [::subs/plan-fact])
        loading?   (use-subscribe [::subs/plan-fact-loading?])
        api-errors (use-subscribe [::subs/api-errors])
        err        (table-error api-errors)
        [period set-period!] (use-state (current-month))
        [mp     set-mp!]     (use-state "all")]

    ;; Single effect on [period mp] covers both mount and filter changes.
    (use-effect
     (fn []
       (rf/dispatch [::events/load-plan-fact period mp])
       js/undefined)
     [period mp])

    ($ :div {:class "page-content"}
       ($ :section {:class "card section-card"}
          ($ :div {:class "section-head"}
             ($ :div {}
                ($ :h3 {:class "section-title"} "План / Факт по SKU")
                ($ :div {:class "section-subtitle"}
                   "Плановые цели против факта продаж за месяц"))
             ($ filters {:period    period
                         :mp        mp
                         :on-period set-period!
                         :on-mp     set-mp!}))

          (cond
            (and loading? (nil? data))
            ($ table-skeleton)

            (and err (nil? data))
            ($ :div {:class "alert alert-danger"}
               ($ icon {:name :danger :class "alert-icon"})
               ($ :div {:class "alert-body"}
                  ($ :div {:class "alert-title"} "Не удалось загрузить план/факт")
                  ($ :div (or (:message err) "Проверьте соединение с сервером.")))
               ($ :button {:class    "btn btn-ghost btn-sm"
                           :style    {:color "inherit"
                                      :border "1px solid currentColor"}
                           :on-click #(rf/dispatch [::events/load-plan-fact period mp])}
                  "Повторить"))

            (empty? (:rows data))
            ($ :div {:style {:text-align "center" :padding "48px 24px"
                             :color "var(--color-fg-muted)" :font-size "14px"}}
               "За выбранный месяц нет ни продаж, ни плановых целей."
               ($ :br)
               "Загрузите план через импорт ниже — строки появятся здесь.")

            :else
            ($ plan-fact-table {:rows   (:rows data)
                                :totals (:totals data)})))

       ($ import-card {:period period :mp mp}))))
