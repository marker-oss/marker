(ns marker.pages.reports
  "Generic schema-driven report page (Phase 9 + 013/016 US1/US4/US5).
   Renders any of the 10 report types (sales, finance, ue, abc, stock,
   returns, buyout, geo, trends, losses) using the column DESCRIPTORS
   returned by /api/v1/marker/reports/:type.

   016 US1 — descriptor-driven table: header ⓘ hint from :hint, value
   formatting from :suffix, delta colour from :positiveIfGrow. No
   hardcoded per-column formulas or suffixes.
   016 US4 — CLIENT-SIDE per-column filters (:filterType), sortable
   headers, grouping by brand/category/…, ABC badge columns, combo
   ₽+share cells. (The backend reports-handler takes no filter/sort/
   group query params — rows are the full period dataset.)
   016 US5 — user-metric constructor: canonical slugs + [+ − × ÷] →
   POST /api/v1/metrics; saved metrics come back as ordinary column
   descriptors merged server-side.

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
            [marker.ui.metric-hint :refer [metric-hint combo-cell delta-class]]
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
;; Descriptor helpers — everything the table renders comes from the column
;; descriptor (:key :title/:label :hint :suffix :filterType :positiveIfGrow
;; :format :group). Pure & exported for tests.
;; ---------------------------------------------------------------------------

(defn col-label
  "Column display label: :title (schema) or :label (contract alias)."
  [col]
  (or (:title col) (:label col) (some-> (:key col) name) ""))

(defn identifier-col?
  "Identifier columns (article/brand/date/…) render plain: no suffix,
   no delta colour, no ⓘ (they carry no :hint per VR-d3)."
  [col]
  (or (= :identity (:group col))
      (contains? #{:text :date} (:format col))))

(defn abc-col?
  "ABC-classification columns render as A/B/C badges."
  [col]
  (let [k (:key col)]
    (and (keyword? k)
         (or (= k :abc-category)
             (str/ends-with? (name k) "-abc")))))

(defn abc-badge-class
  "Badge class for an ABC class value (A → green, B → amber, C → neutral)."
  [v]
  (case (-> (str v) (str/replace #"^:" "") str/upper-case)
    "A" "badge badge-success"
    "B" "badge badge-warning"
    "C" "badge badge-neutral"
    "badge badge-neutral"))

(defn ensure-abc-columns
  "US4: when rows carry :revenue-abc / :profit-abc keys but the schema has no
   descriptor for them (server-computed classification), append synthetic
   identifier descriptors so the classes show up. No-op otherwise."
  [columns rows]
  (let [row0     (first rows)
        has-col? (set (map :key columns))
        mk       (fn [k title]
                   {:key k :title title :group :identity :format :text
                    :default-visible? true})]
    (cond-> (vec columns)
      (and row0 (contains? row0 :revenue-abc) (not (has-col? :revenue-abc)))
      (conj (mk :revenue-abc "ABC (выручка)"))
      (and row0 (contains? row0 :profit-abc) (not (has-col? :profit-abc)))
      (conj (mk :profit-abc "ABC (прибыль)")))))

(defn format-cell
  "Render a value according to the descriptor: :suffix wins (via
   fmt/format-suffixed; :ratio → «×»), else :format. nil → «—»."
  [{:keys [format suffix group]} v]
  (cond
    (nil? v)                "—"
    (= group :identity)     (str v)
    (and (some? suffix)
         (number? v))       (fmt/format-suffixed v (if (= suffix :ratio) :mul suffix))
    (= format :money)       (fmt/format-rub v)
    (= format :rub)         (fmt/format-rub v)
    (= format :int)         (fmt/format-int v)
    (= format :pct)         (fmt/format-pct v)
    (= format :percent)     (fmt/format-pct v)
    (= format :mul)         (fmt/format-mul v)
    (= format :ratio)       (fmt/format-mul v)
    (= format :date)        (str v)        ; backend already sends ISO
    (= format :text)        (str v)
    (number? v)             (fmt/format-int v)
    :else                   (str v)))

(defn numeric-col?
  "Right-aligned (.num) columns: any suffixed or numeric-formatted column."
  [col]
  (and (not (identifier-col? col))
       (not (abc-col? col))
       (or (some? (:suffix col))
           (contains? #{:money :rub :int :pct :percent :mul :ratio} (:format col)))))

(defn delta-badge-class
  "Delta colour per descriptor :positiveIfGrow. Omitted flag = neutral
   metric (VR-d4) → always \"flat\", never an inverted guess."
  [delta positive-if-grow]
  (if (some? positive-if-grow)
    (delta-class delta positive-if-grow)
    "flat"))

(defn- delta-arrow [cls]
  (case cls "up" "↑" "down" "↓" "→"))

(defn- visible-columns
  "Pick columns marked :default-visible? = true; user-defined metric columns
   (:user-defined?) are always shown. Falls back to all columns if none flagged."
  [columns]
  (let [vis (filterv #(or (:default-visible? %) (:user-defined? %)) columns)]
    (if (seq vis) vis columns)))

;; ---------------------------------------------------------------------------
;; US4 — client-side filtering (raw input strings live in the filter spec;
;; parsing happens here so the fns stay pure and controlled inputs stay simple).
;; filters = {col-key {:text "…"} | {:min "…" :max "…"}}
;; ---------------------------------------------------------------------------

(defn column-filter-type
  "Descriptor :filterType, with an honest fallback for pre-016 schemas:
   identifier → :text-contains, numeric format → :number-range, else nil."
  [col]
  (or (:filterType col)
      (cond
        (identifier-col? col) :text-contains
        (numeric-col? col)    :number-range
        :else                 nil)))

(defn parse-num
  "Parse a user-typed number («12,5» ok). Blank/invalid → nil."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (let [n (js/parseFloat (str/replace (str/trim s) "," "."))]
      (when-not (js/isNaN n) n))))

(defn row-passes?
  "Does `row` pass one column's filter spec?"
  [row col fspec]
  (let [v (get row (:key col))]
    (case (column-filter-type col)
      :text-contains
      (let [q (some-> (:text fspec) str/trim str/lower-case)]
        (or (str/blank? q)
            (str/includes? (str/lower-case (str (or v ""))) q)))

      :number-range
      (let [lo (parse-num (:min fspec))
            hi (parse-num (:max fspec))]
        (cond
          (and (nil? lo) (nil? hi)) true
          (not (number? v))         false
          :else (and (or (nil? lo) (>= v lo))
                     (or (nil? hi) (<= v hi)))))

      true)))

(defn apply-filters
  "Filter `rows` by the {col-key fspec} map. Unknown keys are ignored."
  [rows columns filters]
  (if (empty? filters)
    (vec rows)
    (let [by-key (into {} (map (juxt :key identity)) columns)
          active (keep (fn [[k fspec]]
                         (when-let [col (get by-key k)] [col fspec]))
                       filters)]
      (filterv (fn [row]
                 (every? (fn [[col fspec]] (row-passes? row col fspec)) active))
               rows))))

(defn active-filter-count
  "How many filters carry an effective constraint (for the toolbar badge)."
  [filters]
  (count (filter (fn [[_ f]]
                   (or (not (str/blank? (:text f)))
                       (some? (parse-num (:min f)))
                       (some? (parse-num (:max f)))))
                 filters)))

;; ---------------------------------------------------------------------------
;; US4 — sorting (full client set; nil values always sink to the bottom).
;; ---------------------------------------------------------------------------

(defn sort-rows
  [rows sort-key sort-dir]
  (if (nil? sort-key)
    (vec rows)
    (let [{nils true vals false} (group-by #(nil? (get % sort-key)) rows)
          cmp    (if (= sort-dir :desc) #(compare %2 %1) compare)
          sorted (sort-by #(get % sort-key) cmp vals)]
      (vec (concat sorted nils)))))

;; ---------------------------------------------------------------------------
;; US4 — grouping. Only additive metrics (₽ / шт) are summed; percentages,
;; ratios and days have no honest aggregate → «—» (nil). Deltas (₽) sum too.
;; ---------------------------------------------------------------------------

(def groupable-keys
  "Row keys the grouping toggle may offer (when present in the data)."
  {:brand        "Бренд"
   :subject      "Категория"
   :category     "Категория"
   :store        "Магазин"
   :warehouses   "Склад"
   :abc-category "ABC-класс"})

(defn groupable-columns
  "Columns eligible as a group key: whitelisted key + at least one non-nil value."
  [columns rows]
  (filterv (fn [col]
             (and (contains? groupable-keys (:key col))
                  (some #(some? (get % (:key col))) rows)))
           columns))

(defn summable-col?
  "Additive under grouping: ₽/шт suffix, or money/int format when no suffix."
  [col]
  (and (not (identifier-col? col))
       (not (abc-col? col))
       (or (contains? #{:rub :qty} (:suffix col))
           (and (nil? (:suffix col))
                (contains? #{:money :rub :int} (:format col))))))

(defn group-rows
  "Aggregate `rows` by `group-key`: sums for summable columns (+ their _delta),
   nil for everything else. The primary identifier cell shows the group value;
   :__group-count carries the collapsed row count. Ordered by the first
   summable column, descending."
  [rows group-key columns primary-key]
  (let [sum-cols (filterv summable-col? columns)
        sum-vals (fn [grows k]
                   (let [vs (keep #(let [v (get % k)] (when (number? v) v)) grows)]
                     (when (seq vs) (reduce + vs))))]
    (->> (group-by #(get % group-key) rows)
         (mapv (fn [[gval grows]]
                 (let [sums (reduce (fn [acc col]
                                      (let [k  (:key col)
                                            dk (keyword (str (name k) "_delta"))
                                            s  (sum-vals grows k)
                                            ds (sum-vals grows dk)]
                                        (cond-> acc
                                          (some? s)  (assoc k s)
                                          (some? ds) (assoc dk ds))))
                                    {} sum-cols)]
                   (merge sums
                          {primary-key    (if (nil? gval) "—" (str gval))
                           group-key      gval
                           :__group-count (count grows)}))))
         (sort-by (fn [r]
                    (- (or (some #(let [v (get r (:key %))]
                                    (when (number? v) v))
                                 sum-cols)
                           0))))
         vec)))

;; ---------------------------------------------------------------------------
;; US5 — user-metric constructor helpers (pure, exported for tests).
;; The canonical slug vocabulary is OWNED by the backend
;; (analitica.web.report-schemas/canonical-metric-slugs); this list mirrors the
;; arithmetic-capable subset with RU labels (no API exposes the dictionary yet).
;; ---------------------------------------------------------------------------

(def metric-slug-options
  [[:revenue            "Выручка"]
   [:orders             "Заказы"]
   [:net-profit         "Чистая прибыль"]
   [:gross-margin       "Валовая прибыль"]
   [:margin-pct         "Маржа %"]
   [:cogs               "Себестоимость"]
   [:mp-commission      "Комиссия МП"]
   [:logistics          "Логистика"]
   [:storage            "Хранение"]
   [:acceptance         "Приёмка"]
   [:penalties          "Штрафы"]
   [:deduction          "Удержания"]
   [:additional         "Прочее"]
   [:advertising        "Реклама"]
   [:drr-pct            "ДРР %"]
   [:operating-expenses "Опер. расходы"]
   [:ebitda             "EBITDA"]
   [:tax                "Налог"]
   [:vat                "НДС"]
   [:cap-by-cost        "Капитализация (себест.)"]
   [:cap-by-price       "Капитализация (цена)"]
   [:gmroi              "GMROI"]
   [:days-of-cover      "Оборачиваемость"]])

(def ^:private slug-labels (into {} metric-slug-options))

(defn op-label [op]
  (case (keyword op) :+ "+" :- "−" :* "×" :/ "÷" (str op)))

(defn tokens->formula
  "Build the left-nested SAFE EDN-AST from constructor tokens
   [{:op nil|kw :operand kw|number} …] → [:/ [:- :revenue :cogs] :revenue]."
  [tokens]
  (reduce (fn [acc {:keys [op operand]}]
            (if (nil? acc) operand [op acc operand]))
          nil tokens))

(defn formula->human
  "Infix RU rendering of a formula AST (slug → label when known)."
  [f]
  (cond
    (nil? f)     ""
    (number? f)  (str f)
    (keyword? f) (get slug-labels f (name f))
    (string? f)  (get slug-labels (keyword f) f)
    (sequential? f)
    (let [[op a b] (vec f)]
      (str "(" (formula->human a) " " (op-label op) " " (formula->human b) ")"))
    :else (str f)))

(def ^:private ru->lat
  {"а" "a" "б" "b" "в" "v" "г" "g" "д" "d" "е" "e" "ё" "e" "ж" "zh" "з" "z"
   "и" "i" "й" "j" "к" "k" "л" "l" "м" "m" "н" "n" "о" "o" "п" "p" "р" "r"
   "с" "s" "т" "t" "у" "u" "ф" "f" "х" "h" "ц" "c" "ч" "ch" "ш" "sh" "щ" "sch"
   "ъ" "" "ы" "y" "ь" "" "э" "e" "ю" "yu" "я" "ya"})

(defn name->slug
  "Derive a stable slug from the metric name (translit + sanitize). The «u-»
   prefix guarantees no collision with canonical row keys — enrich-user
   assoc'es the slug into every row, so a bare :revenue slug would clobber
   the real column. Same name → same slug (server upserts on slug)."
  [s]
  (let [base (-> (str/lower-case (str s))
                 (str/replace #"[а-яё]" #(get ru->lat % %))
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-+|-+$" ""))]
    (keyword (str "u-" (if (str/blank? base)
                         (str "m" (js/Math.abs (hash (str s))))
                         base)))))

(def ^:private suffix-labels
  {:rub "₽" :pct "%" :qty "шт" :days "Дн." :ratio "×"})

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
     (when on-retry
       ($ :button {:class "btn btn-ghost btn-sm"
                   :style {:color "inherit" :border "1px solid currentColor"}
                   :on-click on-retry}
          "Повторить"))))

;; ---------------------------------------------------------------------------
;; Header (title + KPI tiles + totals)
;; ---------------------------------------------------------------------------

(defui ^:private kpi-tile [{:keys [label value sub]}]
  ($ :div {:class "kpi"}
     ($ :div {:class "kpi-label"} label)
     ($ :div {:class "kpi-value mono"} value)
     (when sub
       ($ :div {:class "kpi-sub"} sub))))

(defn kpi-tiles-shown
  "Pure tile-selection: given a schema :kpi vector and a backend :totals map,
   return the kpi specs that have a non-nil backing number in totals.
   Honest — a kpi key absent from totals, or present with a nil value, is
   dropped (no tile without a real number). Caps at 6."
  [kpi totals]
  (->> kpi
       (filterv #(some? (get totals (:key %))))
       (take 6)
       vec))

(defui ^:private totals-block [{:keys [kpi totals]}]
  ;; Render schema :kpi entries as KPI tiles. Keys come from the schema :kpi
  ;; block (e.g. :total-revenue) which matches the backend :totals keys —
  ;; unlike column keys (:revenue) which do not.
  (let [shown (kpi-tiles-shown kpi totals)]
    (when (seq shown)
      ($ :section {:class "card section-card"
                   :style {:margin-bottom "12px"}}
         ($ :div {:class "kpi-row"
                  :style {:display "grid"
                          :grid-template-columns "repeat(auto-fit, minmax(160px, 1fr))"
                          :gap "12px"}}
            (for [spec shown]
              ($ kpi-tile {:key   (name (:key spec))
                           :label (:title spec)
                           :value (format-cell spec (get totals (:key spec)))})))))))

;; ---------------------------------------------------------------------------
;; Table — US1 descriptor-driven cells
;; ---------------------------------------------------------------------------

(defui value-cell
  "One metric cell, rendered entirely from the descriptor:
   - ABC column   → A/B/C badge
   - identifier   → plain text (+ collapsed-row count in grouped mode)
   - ₽ (suffix)   → combo-cell: absolute + share-of-revenue + coloured delta
   - anything else → format-suffixed/format value + delta badge.
   Delta colour comes from delta-badge-class(:positiveIfGrow); descriptors
   without the flag render a NEUTRAL (flat) delta, never a guessed colour."
  [{:keys [col row primary?]}]
  (let [k         (:key col)
        v         (get row k)
        delta     (get row (keyword (str (name k) "_delta")))
        delta-pct (get row (keyword (str (name k) "_delta_pct")))
        pig       (:positiveIfGrow col)]
    (cond
      (abc-col? col)
      (if (nil? v)
        ($ :span "—")
        ($ :span {:class (abc-badge-class v)}
           (-> (str v) (str/replace #"^:" ""))))

      (identifier-col? col)
      ($ :span {}
         (if (nil? v) "—" (str v))
         (when (and primary? (:__group-count row))
           ($ :span {:class "muted"
                     :style {:margin-left "6px" :font-size "12px" :font-weight 400}}
              (str "· " (:__group-count row) " "
                   (fmt/plural-ru (:__group-count row) "позиция" "позиции" "позиций")))))

      ;; money column → combo ₽ + share-of-revenue % (+ coloured delta)
      (= :rub (:suffix col))
      (let [rev   (get row :revenue)
            share (when (and (not= k :revenue) (number? v)
                             (number? rev) (pos? rev))
                    (* 100 (/ v rev)))]
        (if (some? pig)
          ($ combo-cell {:abs v :share-pct share
                         :delta delta :delta-pct delta-pct
                         :positive-if-grow pig})
          ($ :<>
             ($ combo-cell {:abs v :share-pct share})
             (when (some? delta-pct)
               ($ :span {:class "delta flat" :style {:margin-left "6px"}}
                  (delta-arrow "flat") " " (fmt/format-pct (js/Math.abs delta-pct)))))))

      :else
      ($ :span {}
         (format-cell col v)
         (when (or (some? delta-pct) (some? delta))
           (let [cls (delta-badge-class (or delta delta-pct) pig)]
             ($ :span {:class (str "delta " cls) :style {:margin-left "6px"}}
                (delta-arrow cls) " "
                (if (some? delta-pct)
                  (fmt/format-pct (js/Math.abs delta-pct))
                  (fmt/format-rub (js/Math.abs delta))))))))))

(defui ^:private filter-input [{:keys [col fspec on-change]}]
  (case (column-filter-type col)
    :text-contains
    ($ :input {:class "input"
               :style {:height "24px" :font-size "12px" :width "100%" :min-width "70px"}
               :placeholder "Поиск…"
               :value (or (:text fspec) "")
               :on-change #(on-change (assoc fspec :text (.. % -target -value)))})

    :number-range
    ($ :div {:style {:display "flex" :gap "4px"}}
       ($ :input {:class "input"
                  :style {:height "24px" :font-size "12px" :width "56px"}
                  :placeholder "от"
                  :value (or (:min fspec) "")
                  :on-change #(on-change (assoc fspec :min (.. % -target -value)))})
       ($ :input {:class "input"
                  :style {:height "24px" :font-size "12px" :width "56px"}
                  :placeholder "до"
                  :value (or (:max fspec) "")
                  :on-change #(on-change (assoc fspec :max (.. % -target -value)))}))

    ($ :span)))

(defui report-table
  "US1/US4 descriptor-driven table: ⓘ hints, suffix formatting, sortable
   headers, per-column filters, grouping toggle, ABC badges, combo ₽ cells.
   Self-contained: filter/sort/group state is local (client-side over the
   full period dataset — reports are not paginated)."
  [{:keys [columns rows on-article-click]}]
  (let [all-cols      (ensure-abc-columns columns rows)
        vis-cols      (visible-columns all-cols)
        identity-col  (first (filterv #(= :identity (:group %)) vis-cols))
        join-key      (:key identity-col)
        [sort-key set-sort-key!]         (use-state nil)
        [sort-dir set-sort-dir!]         (use-state :desc)
        [filters set-filters!]           (use-state {})
        [show-filters? set-show-filters!] (use-state false)
        [group-key set-group-key!]       (use-state nil)
        ;; guard against stale state when the descriptor set changes
        group-key*    (when (and group-key (some #(= group-key (:key %)) vis-cols))
                        group-key)
        groupables    (use-memo (fn [] (groupable-columns vis-cols rows))
                                [vis-cols rows])
        n-active      (active-filter-count filters)
        processed     (use-memo
                       (fn []
                         (let [fr (apply-filters rows vis-cols filters)
                               gr (if group-key*
                                    (group-rows fr group-key* vis-cols join-key)
                                    fr)]
                           (sort-rows gr sort-key sort-dir)))
                       [rows vis-cols filters group-key* sort-key sort-dir join-key])
        sort-click!   (fn [k]
                        (if (= sort-key k)
                          (set-sort-dir! (if (= sort-dir :asc) :desc :asc))
                          (do (set-sort-key! k)
                              (set-sort-dir! :desc))))
        sort-icon     (fn [k]
                        (when (= sort-key k)
                          ($ icon {:name (if (= sort-dir :asc) :arrow-up :arrow-down)
                                   :size 12})))
        linkable?     (and identity-col on-article-click
                           (:linkable? identity-col) (nil? group-key*))]
    ($ :div
       ;; ── US4 toolbar: grouping + filter toggle ──
       (when (or (seq groupables) (some column-filter-type vis-cols))
         ($ :div {:class "row" :style {:margin-bottom "10px" :flex-wrap "wrap"}}
            (when (seq groupables)
              ($ :<>
                 ($ :span {:class "field-label" :style {:margin-bottom 0}} "Группировка")
                 ($ :select {:class "input select"
                             :style {:height "28px" :font-size "12px" :width "auto"}
                             :value (if group-key* (name group-key*) "")
                             :on-change #(let [v (.. % -target -value)]
                                           (set-group-key! (when (seq v) (keyword v))))}
                    ($ :option {:value ""} "Без группировки")
                    (for [col groupables]
                      ($ :option {:key (name (:key col)) :value (name (:key col))}
                         (get groupable-keys (:key col) (col-label col)))))))
            ($ :button {:class (str "btn btn-sm "
                                    (if show-filters? "btn-secondary" "btn-ghost"))
                        :on-click #(set-show-filters! (not show-filters?))}
               ($ icon {:name :filter :size 14})
               "Фильтры"
               (when (pos? n-active)
                 ($ :span {:class "badge badge-info" :style {:margin-left "4px"}}
                    n-active)))
            (when (pos? n-active)
              ($ :button {:class "btn btn-ghost btn-sm"
                          :on-click #(set-filters! {})}
                 "Сбросить"))
            (when (or (pos? n-active) group-key*)
              ($ :span {:class "muted" :style {:font-size "12px"}}
                 (str "Показано " (count processed) " из " (count rows))))))

       ($ :div {:style {:overflow-x "auto"}}
          ($ :table {:class "tbl"}
             ($ :thead
                ($ :tr
                   (for [col vis-cols]
                     ($ :th {:key      (name (:key col))
                             :class    (when (numeric-col? col) "num")
                             :style    {:cursor "pointer" :user-select "none"}
                             :on-click #(sort-click! (:key col))}
                        ($ :span {:style {:display "inline-flex"
                                          :align-items "center" :gap "2px"}}
                           (col-label col)
                           ($ metric-hint {:hint (:hint col)})
                           (sort-icon (:key col))))))
                (when show-filters?
                  ($ :tr {:class "tbl-filter-row"}
                     (for [col vis-cols]
                       ($ :th {:key   (name (:key col))
                               :style {:padding "4px 8px"
                                       :background "var(--color-bg-subtle)"}}
                          ($ filter-input
                             {:col       col
                              :fspec     (get filters (:key col) {})
                              :on-change (fn [fspec]
                                           (set-filters!
                                            (assoc filters (:key col) fspec)))}))))))
             ($ :tbody
                (for [row processed]
                  (let [row-key (or (get row join-key) (hash row))]
                    ($ :tr {:key      row-key
                            :style    (when linkable? {:cursor "pointer"})
                            :on-click (when linkable?
                                        #(on-article-click (get row join-key)))}
                       (for [col vis-cols]
                         (let [primary? (= (:key col) join-key)
                               v        (get row (:key col))]
                           ($ :td {:key   (name (:key col))
                                   :class (when (numeric-col? col) "num mono")}
                              (if (and primary? (:linkable? col) (nil? group-key*))
                                ($ :span {:class "tbl-link"} (format-cell col v))
                                ($ value-cell {:col col :row row
                                               :primary? primary?}))))))))))))))

;; ---------------------------------------------------------------------------
;; US5 — user-metric constructor panel
;; ---------------------------------------------------------------------------

(def ^:private user-metrics-url "/api/v1/metrics")

(defui ^:private metric-list-row [{:keys [m]}]
  ($ :div {:class "row"
           :style {:border "1px solid var(--color-border-subtle)"
                   :border-radius "var(--radius-md)"
                   :padding "6px 10px"}}
     ($ :span {:style {:font-weight 500 :font-size "13px"}} (:name m))
     ($ :span {:class "muted mono" :style {:font-size "12px"}}
        (formula->human (:formula m)))
     (when-let [sfx (some-> (:suffix m) keyword)]
       ($ :span {:class "badge badge-neutral"} (get suffix-labels sfx (name sfx))))
     ($ :div {:class "spacer"})
     ($ :button {:class "icon-btn" :title "Удалить метрику"
                 :on-click #(rf/dispatch [::events/delete-user-metric (:id m)])}
        ($ icon {:name :trash :size 14}))))

(defui ^:private metric-constructor [{:keys [error on-save on-cancel]}]
  (let [[m-name set-name!]         (use-state "")
        [tokens set-tokens!]       (use-state [])
        [op set-op!]               (use-state :+)
        [operand-slug set-oslug!]  (use-state "revenue")
        [operand-num set-onum!]    (use-state "")
        [suffix set-suffix!]       (use-state "rub")
        [pig set-pig!]             (use-state "true")
        [basis set-basis!]         (use-state "")
        formula    (tokens->formula tokens)
        add-token! (fn []
                     (let [num     (parse-num operand-num)
                           operand (or num
                                       (when (seq operand-slug) (keyword operand-slug)))]
                       (when (some? operand)
                         (set-tokens! (conj tokens {:op      (when (seq tokens) op)
                                                    :operand operand}))
                         (set-onum! ""))))
        save!      (fn []
                     (when (and formula (not (str/blank? m-name)))
                       (let [pig-val (case pig "true" true "false" false nil)]
                         (on-save
                          (cond-> {:slug       (name->slug m-name)
                                   :name       (str/trim m-name)
                                   :formula    formula
                                   :suffix     (keyword suffix)
                                   :filterType :number-range}
                            (some? pig-val)            (assoc :positiveIfGrow pig-val)
                            (not (str/blank? basis))   (assoc :basis (str/trim basis)))))))]
    ($ :div {:style {:border "1px solid var(--color-border-subtle)"
                     :border-radius "var(--radius-md)"
                     :padding "12px" :margin-top "10px"
                     :display "flex" :flex-direction "column" :gap "10px"}}
       (when error
         ($ :div {:class "alert alert-danger"}
            ($ icon {:name :danger :class "alert-icon"})
            ($ :div {:class "alert-body"}
               ($ :div {:class "alert-title"} "Метрика не сохранена")
               ($ :div error))))

       ($ :div
          ($ :label {:class "field-label"} "Название")
          ($ :input {:class "input" :style {:width "100%" :max-width "360px"}
                     :placeholder "Например: Маржа после рекламы"
                     :value m-name
                     :on-change #(set-name! (.. % -target -value))}))

       ($ :div
          ($ :label {:class "field-label"} "Формула")
          ($ :div {:class "mono"
                   :style {:font-size "13px" :padding "8px 10px"
                           :background "var(--color-bg-subtle)"
                           :border-radius "6px" :min-height "34px"}}
             (if formula
               (formula->human formula)
               ($ :span {:class "muted"} "Добавьте метрику или число…"))))

       ($ :div {:class "row" :style {:flex-wrap "wrap"}}
          (when (seq tokens)
            ($ :div {:class "row" :style {:gap "2px"}}
               (for [o [:+ :- :* :/]]
                 ($ :button {:key   (name o)
                             :class (str "btn btn-sm "
                                         (if (= op o) "btn-secondary" "btn-ghost"))
                             :style {:width "30px" :padding 0}
                             :on-click #(set-op! o)}
                    (op-label o)))))
          ($ :select {:class "input select"
                      :style {:height "28px" :font-size "12px" :width "auto"}
                      :value operand-slug
                      :on-change #(set-oslug! (.. % -target -value))}
             (for [[k label] metric-slug-options]
               ($ :option {:key (name k) :value (name k)} label)))
          ($ :span {:class "muted" :style {:font-size "12px"}} "или число")
          ($ :input {:class "input"
                     :style {:height "28px" :font-size "12px" :width "80px"}
                     :placeholder "1.2"
                     :value operand-num
                     :on-change #(set-onum! (.. % -target -value))})
          ($ :button {:class "btn btn-secondary btn-sm" :on-click add-token!}
             ($ icon {:name :plus :size 14}) "Добавить")
          (when (seq tokens)
            ($ :button {:class "btn btn-ghost btn-sm"
                        :on-click #(set-tokens! [])}
               "Очистить")))

       ($ :div {:class "row" :style {:flex-wrap "wrap"}}
          ($ :div
             ($ :label {:class "field-label"} "Единица")
             ($ :select {:class "input select"
                         :style {:height "28px" :font-size "12px"}
                         :value suffix
                         :on-change #(set-suffix! (.. % -target -value))}
                ($ :option {:value "rub"}   "₽")
                ($ :option {:value "pct"}   "%")
                ($ :option {:value "qty"}   "шт")
                ($ :option {:value "days"}  "Дн.")
                ($ :option {:value "ratio"} "× (отношение)")))
          ($ :div
             ($ :label {:class "field-label"} "Рост метрики")
             ($ :select {:class "input select"
                         :style {:height "28px" :font-size "12px"}
                         :value pig
                         :on-change #(set-pig! (.. % -target -value))}
                ($ :option {:value "true"}  "Рост — хорошо (зелёный)")
                ($ :option {:value "false"} "Рост — плохо (красный)")
                ($ :option {:value "none"}  "Нейтрально")))
          ($ :div {:style {:flex 1 :min-width "160px"}}
             ($ :label {:class "field-label"} "База расчёта (для ⓘ подсказки)")
             ($ :input {:class "input" :style {:width "100%" :height "28px" :font-size "12px"}
                        :placeholder "например: net sales (payout)"
                        :value basis
                        :on-change #(set-basis! (.. % -target -value))})))

       ($ :div {:class "row"}
          ($ :button {:class "btn btn-primary btn-sm"
                      :disabled (or (nil? formula) (str/blank? m-name))
                      :on-click save!}
             "Сохранить")
          ($ :button {:class "btn btn-ghost btn-sm" :on-click on-cancel}
             "Отмена")))))

(defui ^:private user-metrics-panel []
  (let [data        (use-subscribe [::subs/user-metrics])
        api-errors  (use-subscribe [::subs/api-errors])
        error       (get-in api-errors [user-metrics-url :message])
        metrics     (vec (:metrics data))
        [open? set-open!]       (use-state false)
        [pending? set-pending!] (use-state false)
        prev-metrics (use-ref ::init)]
    ;; close the form once a pending save lands (the metrics list changed)
    (use-effect
     (fn []
       (let [prev @prev-metrics]
         (reset! prev-metrics metrics)
         (when (and pending? (not= prev ::init) (not= prev metrics))
           (set-pending! false)
           (set-open! false)))
       js/undefined)
     [metrics pending?])
    ;; a save error keeps the form open for correction
    (use-effect
     (fn []
       (when error (set-pending! false))
       js/undefined)
     [error])
    ($ :section {:class "card section-card" :style {:margin-top "12px"}}
       ($ :div {:class "section-head"}
          ($ :div
             ($ :h3 {:class "section-title"} "Пользовательские метрики")
             ($ :div {:class "section-subtitle"}
                "Собственные колонки из канонических метрик — появляются во всех отчётах"))
          ($ :button {:class "btn btn-secondary btn-sm"
                      :on-click (fn []
                                  (rf/dispatch [::events/clear-api-error user-metrics-url])
                                  (set-open! (not open?)))}
             ($ icon {:name :plus :size 14}) "Метрика"))
       (when (seq metrics)
         ($ :div {:style {:display "flex" :flex-direction "column" :gap "6px"}}
            (for [m metrics]
              ($ metric-list-row {:key (str (:id m)) :m m}))))
       (when (and (empty? metrics) (not open?))
         ($ :p {:class "muted" :style {:font-size "13px" :margin 0}}
            "Пока нет ни одной метрики. Нажмите «Метрика», чтобы собрать свою."))
       (when open?
         ($ metric-constructor
            {:error    error
             :on-save  (fn [m]
                         (rf/dispatch [::events/clear-api-error user-metrics-url])
                         (set-pending! true)
                         (rf/dispatch [::events/save-user-metric m]))
             :on-cancel (fn []
                          (rf/dispatch [::events/clear-api-error user-metrics-url])
                          (set-open! false))})))))

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
        user-metrics (use-subscribe [::subs/user-metrics])
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
                           (rf/dispatch [::events/open-sheet-and-load (str art)])))
        prev-user-metrics (use-ref ::init)]

    (use-effect
     (fn []
       (rf/dispatch [::events/load-report report-type
                     {:mp-filter mps :period period :compare compare?}])
       (when chart?
         (rf/dispatch [::events/load-report-chart report-type
                       {:mp-filter mps :period period :compare compare?}]))
       js/undefined)
     [report-type mps period compare? chart?])

    ;; US5: load saved user metrics once (they merge into :columns server-side).
    (use-effect
     (fn []
       (rf/dispatch [::events/load-user-metrics])
       js/undefined)
     [])

    ;; US5: when the metric set changes (save/delete), the report columns are
    ;; stale → drop the cache and refetch. Initial nil→data load is skipped.
    (use-effect
     (fn []
       (let [prev @prev-user-metrics]
         (reset! prev-user-metrics user-metrics)
         (when (and (not= prev ::init) (some? prev) (not= prev user-metrics))
           (rf/dispatch [::events/clear-cache])
           (rf/dispatch [::events/load-report report-type
                         {:mp-filter mps :period period :compare compare?}])))
       js/undefined)
     [user-metrics report-type mps period compare?])

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
            kpi           (get-in data [:schema :kpi])
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
           ($ totals-block {:kpi kpi :totals totals})

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
                  ($ report-table {:key              (name report-type)
                                   :columns          columns
                                   :rows             rows
                                   :on-article-click article-click!}))))

           ;; US5 constructor — saved metrics render as ordinary columns above.
           ($ user-metrics-panel))))))
