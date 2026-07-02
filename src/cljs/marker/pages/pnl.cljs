(ns marker.pages.pnl
  "P&L page — Phase 8 + 016-US3 layered waterfall.
   Data from /api/v1/marker/pnl via ::events/load-pnl.
   Renders the layered P&L waterfall (GROSS top-line → direct expenses →
   gross margin → advertising → operating expenses → EBITDA → tax → net
   profit) as an expandable table, plus the per-SKU breakdown + drilldown.
   Loading skeletons when data is nil."
  (:require [uix.core :refer [$ defui use-state use-memo use-ref use-effect]]
            [uix.re-frame :refer [use-subscribe]]
            [re-frame.core :as rf]
            [marker.state.subs   :as subs]
            [marker.state.events :as events]
            [marker.ui.chrome    :refer [delta mp-badge]]
            [marker.ui.icons     :refer [icon]]
            [marker.ui.metric-hint :refer [metric-hint delta-class]]
            [marker.ui.basis     :refer [coverage-banner]]
            [marker.util.format  :as fmt]))

;; ---------------------------------------------------------------------------
;; Safe helpers
;; ---------------------------------------------------------------------------

(defn- safe-num [v]
  (if (and (some? v) (not (js/isNaN v))) v 0))

;; ---------------------------------------------------------------------------
;; 016-US3 — waterfall grouping (PURE, tested)
;; ---------------------------------------------------------------------------
;;
;; The backend emits the waterfall as a FLAT ordered vector where the
;; expandable parents (:direct-expenses, :tax) carry a :children vector of
;; child KEYS, and the child lines themselves follow inline right after their
;; parent. To render an expandable table we re-nest: top-level rows keep their
;; order, each expandable parent carries its resolved child line maps.

(def waterfall-layer-order
  "Canonical top-level layer order (spec 016 US3 §0.1). Child lines
   (:cogs …, :tax-usn/:vat) are NOT top-level — they nest under their parent."
  [:sales :direct-expenses :gross-margin :advertising
   :operating-expenses :ebitda :tax :net-profit])

(defn child-key-set
  "Set of every :key that appears as a child of some line — i.e. the keys
   that must NOT be rendered as top-level rows (they nest under a parent)."
  [waterfall]
  (into #{} (mapcat :children) waterfall))

(defn expandable?
  "True when a waterfall line carries a non-empty :children key vector."
  [line]
  (boolean (seq (:children line))))

(defn group-waterfall
  "Re-nest a FLAT waterfall vector into an ordered vector of
     {:line <top-level line> :children [<child line maps> …]}
   Top-level rows keep their backend order; child lines are resolved from the
   flat vector by key and attached under their expandable parent. Lines whose
   key is a child of some parent are dropped from the top level.
   Returns [] for nil / empty input."
  [waterfall]
  (if (empty? waterfall)
    []
    (let [child-keys (child-key-set waterfall)
          by-key     (into {} (map (juxt :key identity)) waterfall)]
      (into []
            (comp
             (remove #(contains? child-keys (:key %)))
             (map (fn [line]
                    {:line     line
                     :children (mapv #(get by-key %) (:children line))})))
            waterfall))))

(defn line-pct-of-revenue
  "Line amount as an ABS percentage share of the GROSS top-line revenue
   (the amount column already carries the sign — prototype convention).
   nil when revenue is 0 (no honest denominator → render «—», never 0)."
  [amount revenue]
  (when (and (number? amount) (number? revenue) (not (zero? revenue)))
    (* (/ (js/Math.abs amount) revenue) 100)))

;; ---------------------------------------------------------------------------
;; Skeleton helpers
;; ---------------------------------------------------------------------------

(defui ^:private skel-row []
  ($ :tr
     (for [i (range 5)]
       ($ :td {:key i}
          ($ :div {:class "skel"
                   :style {:height "14px" :border-radius "4px"}})))))

(defui ^:private pnl-skeleton []
  ($ :div {:class "page-content"}
     ($ :section {:class "card section-card"}
        ($ :table {:class "tbl"}
           ($ :tbody
              (for [i (range 11)]
                ($ skel-row {:key i})))))
     ($ :section {:class "card section-card"}
        ($ :table {:class "tbl"}
           ($ :tbody
              (for [i (range 8)]
                ($ skel-row {:key i})))))))

;; ---------------------------------------------------------------------------
;; Error banner
;; ---------------------------------------------------------------------------

(defui ^:private error-banner [{:keys [message on-retry]}]
  ($ :div {:class "alert alert-danger"
           :style {:margin-bottom "12px"}}
     ($ icon {:name :danger :class "alert-icon"})
     ($ :div {:class "alert-body"}
        ($ :div {:class "alert-title"} "Не удалось загрузить данные P&L")
        ($ :div (or message "Проверьте соединение с сервером.")))
     ($ :button {:class    "btn btn-ghost btn-sm"
                 :style    {:color "inherit" :border "1px solid currentColor"}
                 :on-click on-retry}
        "Повторить")))

;; ---------------------------------------------------------------------------
;; Waterfall — per-line delta cells.
;; The SIGN shown is the arithmetic delta direction (+ grew / − shrank, like
;; the prototype); the COLOUR is the delta-class polarity — a cost that GREW
;; renders "+12 345 ₽" in red (adverse), a cost that shrank "−12 345 ₽" in
;; green (favourable). nil delta → neutral "—".
;; ---------------------------------------------------------------------------

(defui ^:private wf-delta-cell [{:keys [delta positive-if-grow]}]
  (if (or (nil? delta) (js/isNaN delta))
    ($ :span {:class "delta flat"} "—")
    ($ :span {:class (str "delta " (delta-class delta positive-if-grow))}
       (fmt/format-rub delta true))))

(defui ^:private wf-delta-pct-cell [{:keys [delta delta-pct positive-if-grow]}]
  (if (or (nil? delta-pct) (js/isNaN delta-pct))
    ($ :span {:class "delta flat"} "—")
    ($ :span {:class (str "delta " (delta-class delta positive-if-grow))}
       (str (when (pos? delta-pct) "+")
            (fmt/format-pct delta-pct)))))

;; ---------------------------------------------------------------------------
;; Waterfall — a single rendered row (parent, child, or plain layer)
;; ---------------------------------------------------------------------------

(def ^:private subtotal-layers
  "Layers that read as running-total / bold rows in the waterfall."
  #{:sales :gross-margin :ebitda :net-profit})

(defui ^:private wf-row
  [{:keys [line compare? revenue child? expandable? expanded? on-toggle]}]
  (let [amount    (safe-num (:amount line))
        pos-grow  (:positive-if-grow line)
        subtotal? (contains? subtotal-layers (:layer line))
        cost?     (neg? amount)
        pct-rev   (line-pct-of-revenue amount revenue)]
    ($ :tr
       {:style (cond
                 subtotal? {:background  "var(--color-bg-subtle)"
                            :font-weight 600}
                 child?    {:background "transparent"}
                 :else     nil)}
       ;; ── label cell (indent children; caret on expandable parents) ──
       ($ :td {:style {:padding-left (if child? "36px" "12px")
                       :cursor       (when expandable? "pointer")}
               :on-click (when expandable? on-toggle)}
          (when expandable?
            ($ :span {:style {:display      "inline-flex"
                              :margin-right "6px"
                              :color        "var(--color-fg-muted)"
                              :transform    (when expanded? "rotate(90deg)")
                              :transition   "transform .12s ease"}}
               ($ icon {:name :chev-right :size 14})))
          ($ :span {:style {:font-weight (when subtotal? 600)}}
             (:label line))
          (when (:hint line)
            ($ metric-hint {:hint (:hint line)})))
       ;; ── amount ──
       ($ :td {:class "num mono"
               :style {:color       (when (and cost? (not subtotal?))
                                      "var(--color-delta-negative)")
                       :font-weight (when subtotal? 600)}}
          (fmt/format-rub amount))
       ;; ── compare columns ──
       (when compare?
         ($ :td {:class "num mono"}
            ($ wf-delta-cell {:delta (:delta line) :positive-if-grow pos-grow})))
       (when compare?
         ($ :td {:class "num mono"}
            ($ wf-delta-pct-cell {:delta     (:delta line)
                                  :delta-pct (:delta-pct line)
                                  :positive-if-grow pos-grow})))
       ;; ── % of revenue ──
       ($ :td {:class "num mono"
               :style {:color "var(--color-fg-muted)"}}
          (if (nil? pct-rev) "—" (fmt/format-pct pct-rev))))))

;; ---------------------------------------------------------------------------
;; Waterfall section — expandable layered table
;; ---------------------------------------------------------------------------

(defui ^:private waterfall-section [{:keys [compare? waterfall managed?]}]
  (let [grouped   (use-memo (fn [] (group-waterfall waterfall)) [waterfall])
        revenue   (safe-num (some #(when (= :sales (get-in % [:line :key]))
                                     (get-in % [:line :amount]))
                                  grouped))
        [open set-open!] (use-state #{})
        toggle!   (fn [k]
                    (set-open! (fn [o] (if (contains? o k) (disj o k) (conj o k)))))]
    ($ :section {:class "card section-card"}
       ($ :div {:class "section-head"}
          ($ :div
             ($ :h3 {:class "section-title"} "P&L — водопад")
             ($ :div {:class "section-subtitle"}
                (cond
                  (not managed?) "управленческий слой не настроен — налог и OPEX = 0"
                  compare?       "сравнение с предыдущим периодом"
                  :else          "без сравнения")))
          ($ :div {:class "row"}
             ($ :button {:class "btn btn-secondary btn-sm"}
                ($ icon {:name :download :size 14})
                "Export")
             ($ :button {:class "icon-btn"}
                ($ icon {:name :more-h}))))
       ($ :div {:class "tbl-wrap"}
          ($ :table {:class "tbl"}
             ($ :thead
                ($ :tr
                   ($ :th "Статья")
                   ($ :th {:class "num"} "Сумма")
                   (when compare? ($ :th {:class "num"} "Δ ₽"))
                   (when compare? ($ :th {:class "num"} "Δ %"))
                   ($ :th {:class "num"} "% от выручки")))
             ($ :tbody
                (for [{:keys [line children]} grouped]
                  (let [k     (:key line)
                        exp?  (expandable? line)
                        open? (contains? open k)]
                    ($ :<> {:key (str "grp-" (name k))}
                       ($ wf-row {:line        line
                                  :compare?    compare?
                                  :revenue     revenue
                                  :child?      false
                                  :expandable? exp?
                                  :expanded?   open?
                                  :on-toggle   #(toggle! k)})
                       (when (and exp? open?)
                         (for [c children]
                           ($ wf-row {:key      (str "child-" (name k) "-" (name (:key c)))
                                      :line     c
                                      :compare? compare?
                                      :revenue  revenue
                                      :child?   true}))))))))))))

;; ---------------------------------------------------------------------------
;; Per-SKU detail table — reads from API :sku-detail
;; ---------------------------------------------------------------------------

(defn- sku-visible
  "Filter skus by active MPs and search query."
  [skus mps q]
  (filterv
   (fn [s]
     (let [mp-ok  (or (empty? mps) (some (set mps) (:mp s)))
           id-str (.toLowerCase (str (:id s)))
           nm-str (.toLowerCase (str (:name s)))
           q-ok   (or (empty? q)
                      (let [ql (.toLowerCase q)]
                        (or (.includes id-str ql)
                            (.includes nm-str ql))))]
       (and mp-ok q-ok)))
   skus))

(defui ^:private sku-table [{:keys [compare? sku-rows]}]
  (let [skus           (or sku-rows [])
        mps            (use-subscribe [::subs/mp-filter])
        [selected    set-selected!]  (use-state #{})
        [q           set-q!]         (use-state "")
        all-cb-ref     (use-ref nil)
        visible        (use-memo
                        (fn [] (sku-visible skus mps q))
                        [skus mps q])
        all-selected?  (and (pos? (count visible))
                            (every? #(contains? selected (:id %)) visible))
        some-selected? (boolean (some #(contains? selected (:id %)) visible))
        toggle-all!    (fn []
                         (if all-selected?
                           (set-selected! (reduce disj selected (map :id visible)))
                           (set-selected! (reduce conj selected (map :id visible)))))
        toggle-one!    (fn [id]
                         (set-selected!
                          (if (contains? selected id)
                            (disj selected id)
                            (conj selected id))))]

    (use-effect
     (fn []
       (when @all-cb-ref
         (set! (.-indeterminate @all-cb-ref)
               (and (not all-selected?) some-selected?)))
       js/undefined)
     [all-selected? some-selected?])

    ($ :<> {}
       ;; Bulk action bar
       (when (pos? (count selected))
         ($ :div {:class "bulk-bar"}
            ($ :strong
               (str "Выбрано " (count selected) " "
                    (fmt/plural-ru (count selected) "строка" "строки" "строк")))
            ($ :button {:class    "btn-link"
                        :style    {:color "var(--color-fg-muted)"}
                        :on-click #(set-selected! #{})}
               "Снять")
            ($ :div {:class "spacer"})
            ($ :button {:class "btn btn-secondary btn-sm"} "Экспорт")
            ($ :button {:class    "icon-btn"
                        :style    {:color "var(--color-fg-muted)"}
                        :on-click #(set-selected! #{})}
               ($ icon {:name :x :size 14}))))

       ;; SKU table card
       ($ :section {:class "card section-card"}
          ($ :div {:class "section-head"}
             ($ :div
                ($ :h3 {:class "section-title"} "Прибыль по артикулам")
                ($ :div {:class "section-subtitle"}
                   (str "показано " (count visible) " из " (count skus))))
             ($ :div {:class "row"}
                ($ :input {:class       "input"
                           :placeholder "Найти артикул…"
                           :value       q
                           :on-change   #(set-q! (.. % -target -value))
                           :style       {:width "220px"}})
                ($ :button {:class "btn btn-secondary btn-sm"}
                   ($ icon {:name :download :size 14})
                   "CSV")))
          ($ :div {:class "tbl-wrap"}
             ($ :table {:class "tbl"}
                ($ :thead
                   ($ :tr
                      ($ :th {:class "tbl-checkbox"}
                         ($ :input (assoc {:type      "checkbox"
                                           :checked   all-selected?
                                           :on-change toggle-all!}
                                          :ref all-cb-ref)))
                      ($ :th "Артикул")
                      ($ :th "МП")
                      ($ :th {:class "num"} "Выручка")
                      (when compare? ($ :th {:class "num"} "Δ %"))
                      ($ :th {:class "num"} "Себестоимость")
                      ($ :th {:class "num"} "Комиссия")
                      ($ :th {:class "num"} "Реклама")
                      ($ :th {:class "num"} "К выплате")
                      ($ :th)))
                ($ :tbody
                   (for [s visible]
                     ;; Cost magnitudes: the API sends :commission signed
                     ;; (negative) but :cogs/:ads unsigned — abs-normalize all
                     ;; three so the uniform (− x) render shows every cost as
                     ;; «−… ₽» in red.
                     (let [rev  (safe-num (:revenue s))
                           cogs (js/Math.abs (safe-num (:cogs s)))
                           comm (js/Math.abs (safe-num (:commission s)))
                           ads  (js/Math.abs (safe-num (:ads s)))
                           net  (safe-num (:net s))]
                       ($ :tr
                          {:key   (:id s)
                           :class (when (contains? selected (:id s)) "selected")}
                          ($ :td {:class    "tbl-checkbox"
                                  :on-click #(.stopPropagation %)}
                             ($ :input {:type      "checkbox"
                                        :checked   (boolean (contains? selected (:id s)))
                                        :on-change #(toggle-one! (:id s))}))
                          ($ :td
                             ($ :span {:class    "tbl-link"
                                       :on-click #(rf/dispatch
                                                   [::events/open-sheet-and-load (:id s)])}
                                (:id s))
                             ($ :div {:style {:font-size  "12px"
                                              :color      "var(--color-fg-muted)"
                                              :margin-top "2px"}}
                                (:name s)))
                          ($ :td
                             (for [m (:mp s)]
                               ($ mp-badge {:key (name m) :mp m})))
                          ($ :td {:class "num mono"
                                  :style {:font-weight 600}}
                             (fmt/format-rub rev))
                          (when compare?
                            ($ :td {:class "num mono"}
                               ($ delta {:pct (:delta-pct s)})))
                          ($ :td {:class "num mono"
                                  :style {:color "var(--color-delta-negative)"}}
                             (fmt/format-rub (- cogs)))
                          ($ :td {:class "num mono"
                                  :style {:color "var(--color-delta-negative)"}}
                             (fmt/format-rub (- comm)))
                          ($ :td {:class "num mono"
                                  :style {:color "var(--color-delta-negative)"}}
                             (fmt/format-rub (- ads)))
                          ($ :td {:class "num mono"
                                  :style {:color       (if (pos? net)
                                                         "var(--color-delta-positive)"
                                                         "var(--color-delta-negative)")
                                          :font-weight 600}}
                             (fmt/format-rub net))
                          ($ :td
                             ($ :button {:class "icon-btn"}
                                ($ icon {:name :more-v :size 14})))))))
                ($ :tfoot
                   ($ :tr
                      ($ :td {:class "tbl-checkbox"})
                      ($ :td (str "Итого (" (count visible) ")"))
                      ($ :td)
                      ($ :td {:class "num mono"
                              :style {:font-weight 600}}
                         (fmt/format-rub (reduce #(+ %1 (safe-num (:revenue %2))) 0 visible)))
                      (when compare? ($ :td))
                      ($ :td) ($ :td) ($ :td)
                      ($ :td {:class "num mono"
                              :style {:font-weight 600}}
                         (fmt/format-rub (reduce #(+ %1 (safe-num (:net %2))) 0 visible)))
                      ($ :td)))))))))

;; ---------------------------------------------------------------------------
;; Page root
;; ---------------------------------------------------------------------------

(defui pnl []
  (let [compare?   (use-subscribe [::subs/compare])
        mp-filter  (use-subscribe [::subs/mp-filter])
        period     (use-subscribe [::subs/period])
        data       (use-subscribe [::subs/pnl-data])
        loading?   (use-subscribe [::subs/pnl-loading?])
        api-errors (use-subscribe [::subs/api-errors])
        error-msg  (get-in api-errors ["/api/v1/marker/pnl" :message])
        fs         {:mp-filter mp-filter :period period :compare compare?}]

    ;; Single effect on [mp-filter period compare?] handles both mount and
    ;; subsequent filter changes — a separate `[]` mount effect duplicates.
    (use-effect
     (fn []
       (rf/dispatch [::events/load-pnl
                     {:mp-filter mp-filter :period period :compare compare?}])
       js/undefined)
     [mp-filter period compare?])

    (cond
      (and loading? (nil? data))
      ($ pnl-skeleton)

      (and error-msg (nil? data))
      ($ :div {:class "page-content"}
         ($ error-banner
            {:message  error-msg
             :on-retry #(do (rf/dispatch [::events/clear-cache])
                            (rf/dispatch [::events/load-pnl fs]))}))

      :else
      (let [waterfall    (or (:waterfall data) [])
            sku-rows     (or (:sku-detail data) [])
            completeness (:completeness data)
            managed?     (boolean (:management-configured? data))
            empty-data?  (= :empty completeness)]
        ($ :div {:class "page-content"}
           (when error-msg
             ($ error-banner
                {:message  error-msg
                 :on-retry #(do (rf/dispatch [::events/clear-cache])
                                (rf/dispatch [::events/load-pnl fs]))}))
           ;; LT3: honesty banner from the backend envelope.
           ($ coverage-banner {:completeness completeness
                               :date-basis   (:date-basis data)
                               :preliminary? (:preliminary? data)})
           ;; On a no-data window, show the empty-state INSTEAD of an
           ;; all-zero P&L table that would look like real (zero) numbers.
           (if empty-data?
             ($ :section {:class "card section-card"}
                ($ :div {:style {:text-align "center" :padding "48px 24px"
                                 :color "var(--color-fg-muted)" :font-size "14px"}}
                   "За выбранный период нет финансовых данных для P&L."))
             ($ :<> {}
                ($ waterfall-section {:compare?  compare?
                                      :waterfall waterfall
                                      :managed?  managed?})
                ($ sku-table {:compare? compare? :sku-rows sku-rows}))))))))
