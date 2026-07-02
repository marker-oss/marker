(ns marker.ui.basis
  "Shared honesty / basis-contract UI — single source of truth for the
   date-basis composition markers that warn the seller when a finance value
   is an estimate or has no day-level meaning.

   These helpers were originally private to marker.pages.pulse; they are
   promoted here so P&L / reports / SKU pages and the topbar coverage chip
   render the SAME honesty signals from the SAME logic (LT3, specs/010 P0-A).

   The backend honesty envelope (see analitica.web.api.marker/basis-envelope)
   attaches {:completeness #{:empty :full :estimated} :date-basis {:api :spread
   :flat} :preliminary? bool} to every finance response. The components here
   consume that contract."
  (:require [uix.core :refer [$ defui]]
            [marker.ui.icons :refer [icon]]))

;; ---------------------------------------------------------------------------
;; Pure helpers (promoted from pulse.cljs — DO NOT duplicate elsewhere)
;; ---------------------------------------------------------------------------

(defn pct-str [x] (str (js/Math.round (* 100 (or x 0))) "%"))

(defn format-date-short
  "Truncate ISO date/datetime to YYYY-MM-DD for compact display."
  [iso]
  (when iso (subs (str iso) 0 10)))

(defn flat-heavy?
  "True when more than 20% of this KPI's value is flat-distributed
   (no day-level meaning). Mirrors backend's :flat-heavy-subperiod note."
  [kpi]
  (> (or (:flat (:date-basis kpi)) 0) 0.2))

(defn prelim-badge
  "Badge string for a KPI tile whose value is an ESTIMATE — either a
   preliminary source (Ozon cash-flow overlay), a date-basis flagged
   :estimated, or a materially flat-distributed (>20%) finance slice
   (P0-A Part A / FR-P1.2).  All mean a sub-period of this number has
   weak day-level meaning."
  [kpi]
  (when (or (= :preliminary (:source kpi))
            (= :estimated   (:completeness kpi))
            (flat-heavy? kpi))
    "≈"))

;; ---------------------------------------------------------------------------
;; LT5 — :preliminary-missing honesty state.
;; Ozon preliminary windows publish logistics/storage but NOT commission/COGS
;; (the realization-отчёт is not out yet). Such cost lines and the profit/margin
;; KPIs that depend on them carry {:value nil :source :preliminary-missing}.
;; This is DISTINCT from :none «реализация отсутствует» (no source at all):
;; here revenue IS known (preliminary cash-flow) but the cost side is not.
;; ---------------------------------------------------------------------------

(def preliminary-missing-text
  "Honest value-cell text for a :preliminary-missing line — never a number, never 0 ₽."
  "нет данных (предварительный период)")

(def preliminary-missing-tooltip
  "Hover tooltip explaining why a :preliminary-missing value is unavailable."
  "Комиссия и себестоимость недоступны до публикации realization-отчёта Ozon; выручка предварительная по cash-flow")

(defn preliminary-missing?
  "True when this KPI / cost-line is in the LT5 :preliminary-missing state."
  [m]
  (= :preliminary-missing (:source m)))

(defn basis-tooltip
  "Human tooltip describing a KPI's date-basis composition.
   - When no realization rows exist (:source :none / empty basis) → an
     'реализация отсутствует' / preliminary note (FR-P1.4).
   - Otherwise the verbose Russian breakdown PLUS a compact
     'api X% · spread Y% · flat Z%' line (FR-P1.2).
   - Appends the flat-heavy sub-month note when :basis-note is set."
  [kpi]
  (when-let [b (:date-basis kpi)]
    (let [sum  (+ (or (:api b) 0) (or (:spread b) 0) (or (:flat b) 0))
          note (when (= :flat-heavy-subperiod (:basis-note kpi))
                 "частичный месяц — реализация не разрешена по дням")
          body (cond
                 ;; No realization rows at all.
                 (zero? sum)
                 (if (= :none (:source kpi))
                   "Реализация отсутствует: realization-отчёт ещё не опубликован"
                   "Предварительная оценка: realization-отчёт ещё не опубликован")

                 :else
                 (str "Основа значения: "
                      (pct-str (:flat b))   " равномерно распределено (без дневного смысла), "
                      (pct-str (:spread b)) " по продажам, "
                      (pct-str (:api b))    " фактические даты"
                      "\n(api " (pct-str (:api b))
                      " · spread " (pct-str (:spread b))
                      " · flat " (pct-str (:flat b)) ")"))]
      (str body (when note (str "\n" note))))))

;; ---------------------------------------------------------------------------
;; Coverage chip text — used by the topbar chip AND as the chip's tooltip.
;; ---------------------------------------------------------------------------

(defn coverage-label
  "Short Russian label for a page-level :completeness state.
   :empty → «нет данных», :estimated/preliminary → «≈ оценка»,
   :full/nil → «полные данные»."
  [completeness preliminary?]
  (cond
    (= :empty completeness)     "нет данных"
    (or (= :estimated completeness) preliminary?) "≈ оценка"
    :else                        "полные данные"))

(defn coverage-badge-class
  "Badge flavour class for a coverage state. :empty/:estimated → warning,
   :full → neutral. Reuses tokens.css .badge-neutral / .badge-warning."
  [completeness preliminary?]
  (if (or (= :empty completeness)
          (= :estimated completeness)
          preliminary?)
    "badge-warning"
    "badge-neutral"))

;; ---------------------------------------------------------------------------
;; Coverage banner — page-level honesty note driven by the envelope.
;; ---------------------------------------------------------------------------

(defui coverage-banner
  "Page-level honesty banner driven by the backend envelope.
   Props: {:completeness #{:empty :full :estimated} :date-basis map :preliminary? bool}.

   - :empty               → .alert .alert-info «Нет данных за выбранный период».
   - :estimated / preliminary? → the «≈ предварительная оценка» note.
   - :full / nil          → renders nothing.

   Reuses tokens.css .alert / .alert-info only."
  [{:keys [completeness preliminary?]}]
  (cond
    (= :empty completeness)
    ($ :div {:class "alert alert-info" :style {:margin-bottom "12px"}}
       ($ icon {:name :info :class "alert-icon"})
       ($ :div {:class "alert-body"}
          ($ :div {:class "alert-title"} "Нет данных за выбранный период")
          ($ :div
             "Отчёт маркетплейса ещё не опубликован или продаж за этот период не было.")))

    (or (= :estimated completeness) preliminary?)
    ($ :div {:class "alert alert-info" :style {:margin-bottom "12px"}}
       ($ icon {:name :info :class "alert-icon"})
       ($ :div {:class "alert-body"}
          ($ :div
             "≈ предварительная оценка по неполным данным; финал — после публикации отчёта МП.")))

    :else nil))
