(ns marker.pages.reports-test
  "Tests for the generic report page (016 US1/US4/US5):
   - kpi-tiles-shown (LT1, pre-existing)
   - descriptor-driven table render (hint ⓘ, suffix, delta colour, ABC badge,
     identifier plain) via react-dom/server static markup
   - client-side filter / sort / group pure helpers
   - combo-cell markup
   - user-metric constructor formula helpers."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [uix.core :refer [$]]
            [uix.dom.server :as dom.server]
            [marker.ui.metric-hint :refer [combo-cell metric-hint]]
            [marker.pages.reports :as reports
             :refer [kpi-tiles-shown apply-filters sort-rows group-rows
                     active-filter-count column-filter-type parse-num
                     delta-badge-class abc-badge-class ensure-abc-columns
                     format-cell tokens->formula formula->human name->slug
                     report-table]]))

;; ---------------------------------------------------------------------------
;; Shared mock descriptors + rows (UE-like shape)
;; ---------------------------------------------------------------------------

(def ^:private cols
  [{:key :article :title "Артикул" :group :identity :format :text
    :default-visible? true :linkable? true :filterType :text-contains}
   {:key :sales-qty :title "Продажи" :group :ue1 :format :int
    :suffix :qty :filterType :number-range :positiveIfGrow true
    :default-visible? true}
   {:key :revenue :title "Выручка" :group :ue2 :format :rub
    :suffix :rub :filterType :number-range :positiveIfGrow true
    :default-visible? true
    :hint "Выручка (GROSS-реализация): цена × шт."}
   {:key :logistics :title "Логистика" :group :ue2 :format :rub
    :suffix :rub :filterType :number-range :positiveIfGrow false
    :default-visible? true
    :hint "Логистика: доставка заказов и возвратов."}
   {:key :margin-pct :title "Маржа %" :group :ue7 :format :pct
    :suffix :pct :filterType :number-range :positiveIfGrow true
    :default-visible? true}])

(def ^:private rows
  [{:article "SKU-1" :sales-qty 10 :revenue 1000 :logistics 100 :margin-pct 25
    :revenue_delta 200 :revenue_delta_pct 25
    :logistics_delta 50 :logistics_delta_pct 100}
   {:article "SKU-2" :sales-qty 5 :revenue 500 :logistics 60 :margin-pct 10}])

(defn- render [el] (dom.server/render-to-static-markup el))

;; ---------------------------------------------------------------------------
;; kpi-tiles-shown (pre-existing LT1 tests, kept)
;; ---------------------------------------------------------------------------

(def ^:private kpi
  [{:key :total-revenue :title "Выручка"        :format :rub}
   {:key :total-sales   :title "Продажи"         :format :int}
   {:key :profit-current :title "Прибыль сейчас" :format :rub}])

(deftest tile-shown-when-key-present
  (testing "kpi key present in totals → tile shown"
    (let [shown (kpi-tiles-shown kpi {:total-revenue 1000.0 :total-sales 42})]
      (is (= [:total-revenue :total-sales] (mapv :key shown))))))

(deftest tile-hidden-when-key-absent
  (testing "kpi key absent from totals → no tile"
    (let [shown (kpi-tiles-shown kpi {:total-revenue 1000.0})]
      (is (= [:total-revenue] (mapv :key shown))))))

(deftest tile-hidden-when-value-nil
  (testing "kpi key present but nil-valued → no tile (honest)"
    (let [shown (kpi-tiles-shown kpi {:total-revenue 1000.0
                                      :total-sales   nil
                                      :profit-current nil})]
      (is (= [:total-revenue] (mapv :key shown))))))

(deftest no-tiles-on-empty-totals
  (testing "empty totals → no tiles"
    (is (empty? (kpi-tiles-shown kpi {})))))

(deftest zero-value-is-shown
  (testing "a real 0 is a backing number → tile shown (some? 0 is true)"
    (let [shown (kpi-tiles-shown kpi {:total-revenue 0.0 :total-sales 0})]
      (is (= [:total-revenue :total-sales] (mapv :key shown))))))

(deftest caps-at-six
  (testing "no more than 6 tiles even when totals has more matching keys"
    (let [big-kpi (mapv (fn [i] {:key (keyword (str "k" i)) :title (str i) :format :int})
                        (range 10))
          totals  (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 10)))
          shown   (kpi-tiles-shown big-kpi totals)]
      (is (= 6 (count shown))))))

;; ---------------------------------------------------------------------------
;; US1 — descriptor-driven table render
;; ---------------------------------------------------------------------------

(deftest table-renders-hint-icons-from-descriptor
  (let [html (render ($ report-table {:columns cols :rows rows}))]
    (testing "ⓘ hint icon rendered for hinted columns only"
      (is (= 2 (count (re-seq #"metric-hint" html)))
          "exactly the two :hint-carrying columns get a ⓘ")
      (is (str/includes? html "Выручка (GROSS-реализация): цена × шт.")
          "tooltip carries the descriptor :hint verbatim"))
    (testing "identifier column renders plain — no data-tip on its header"
      (is (str/includes? html "Артикул"))
      (is (str/includes? html "SKU-1")))))

(deftest table-applies-suffix-from-descriptor
  (let [html (render ($ report-table {:columns cols :rows rows}))]
    (testing ":qty suffix → «шт»"
      (is (str/includes? html "шт")))
    (testing ":rub suffix → ₽"
      (is (str/includes? html "₽")))
    (testing ":pct suffix → formatted percent with comma"
      (is (str/includes? html "25,0%")))))

(deftest table-delta-colour-follows-positive-if-grow
  (let [html (render ($ report-table {:columns cols :rows rows}))]
    (testing "revenue grew (+25%) and positiveIfGrow=true → green .delta.up"
      (is (str/includes? html "delta up")))
    (testing "logistics grew (+100%) and positiveIfGrow=false → red .delta.down"
      (is (str/includes? html "delta down")))))

(deftest table-money-cell-carries-share-of-revenue
  (let [html (render ($ report-table {:columns cols :rows rows}))]
    (testing "logistics 100 of revenue 1000 → «(10,0%)» share in the combo cell"
      (is (str/includes? html "(10,0%)")))))

(deftest table-renders-abc-badges-when-rows-carry-classes
  (let [rows+ (mapv #(assoc %1 :profit-abc %2) rows ["A" "C"])
        html  (render ($ report-table {:columns cols :rows rows+}))]
    (testing "synthetic ABC column appended and rendered as badges"
      (is (str/includes? html "ABC (прибыль)"))
      (is (str/includes? html "badge badge-success"))
      (is (str/includes? html "badge badge-neutral")))))

(deftest nil-values-render-as-dash
  (let [rows' [{:article "SKU-X" :sales-qty nil :revenue nil
                :logistics nil :margin-pct nil}]
        html  (render ($ report-table {:columns cols :rows rows'}))]
    (is (str/includes? html "—"))
    (is (not (str/includes? html "NaN")))))

;; ---------------------------------------------------------------------------
;; US1 — format-cell / descriptor helpers
;; ---------------------------------------------------------------------------

(deftest format-cell-descriptor-driven
  (testing "suffix wins over format"
    (is (= "4,2×" (format-cell {:suffix :ratio :format :ratio} 4.2))))
  (testing "nil → «—» regardless of descriptor"
    (is (= "—" (format-cell {:suffix :rub :format :rub} nil)))
    (is (= "—" (format-cell {:format :pct} nil))))
  (testing "identity renders raw"
    (is (= "SKU-1" (format-cell {:group :identity :format :text} "SKU-1")))))

(deftest delta-badge-class-honours-descriptor
  (testing "positiveIfGrow true: growth green, drop red"
    (is (= "up"   (delta-badge-class 10 true)))
    (is (= "down" (delta-badge-class -10 true))))
  (testing "positiveIfGrow false (cost-like): growth red, drop green"
    (is (= "down" (delta-badge-class 10 false)))
    (is (= "up"   (delta-badge-class -10 false))))
  (testing "flag omitted → neutral flat, never a guessed colour (VR-d4)"
    (is (= "flat" (delta-badge-class 10 nil)))
    (is (= "flat" (delta-badge-class -10 nil))))
  (testing "neutral band"
    (is (= "flat" (delta-badge-class 0.01 true)))
    (is (= "flat" (delta-badge-class nil true)))))

(deftest abc-badge-classes
  (is (= "badge badge-success" (abc-badge-class "A")))
  (is (= "badge badge-warning" (abc-badge-class "B")))
  (is (= "badge badge-neutral" (abc-badge-class "C")))
  (is (= "badge badge-neutral" (abc-badge-class "?"))))

(deftest ensure-abc-columns-appends-only-when-present
  (let [with-abc (ensure-abc-columns cols [{:article "S" :profit-abc "A"}])
        without  (ensure-abc-columns cols rows)]
    (is (= :profit-abc (:key (last with-abc))))
    (is (= (count cols) (count without)) "no phantom columns")))

;; ---------------------------------------------------------------------------
;; US4 — filters
;; ---------------------------------------------------------------------------

(deftest filter-type-from-descriptor-with-fallback
  (testing "explicit :filterType wins"
    (is (= :text-contains (column-filter-type (first cols))))
    (is (= :number-range  (column-filter-type (second cols)))))
  (testing "fallback: identifier → text, numeric format → range"
    (is (= :text-contains (column-filter-type {:key :brand :group :identity :format :text})))
    (is (= :number-range  (column-filter-type {:key :x :format :rub})))))

(deftest text-filter-is-case-insensitive-substring
  (let [out (apply-filters rows cols {:article {:text "sku-1"}})]
    (is (= ["SKU-1"] (mapv :article out))))
  (let [out (apply-filters rows cols {:article {:text "  "}})]
    (is (= 2 (count out)) "blank query filters nothing")))

(deftest number-filter-min-max
  (testing "min only"
    (is (= ["SKU-1"] (mapv :article (apply-filters rows cols {:revenue {:min "600"}})))))
  (testing "max only"
    (is (= ["SKU-2"] (mapv :article (apply-filters rows cols {:revenue {:max "600"}})))))
  (testing "min+max band"
    (is (= ["SKU-2"] (mapv :article (apply-filters rows cols {:revenue {:min "400" :max "600"}})))))
  (testing "comma decimal separator accepted"
    (is (= 2 (count (apply-filters rows cols {:margin-pct {:min "9,5"}})))))
  (testing "nil value fails an active bound (honest, not zero)"
    (let [rows' (conj rows {:article "SKU-3" :revenue nil})]
      (is (= 2 (count (apply-filters rows' cols {:revenue {:min "0"}}))))))
  (testing "empty spec keeps everything"
    (is (= 2 (count (apply-filters rows cols {:revenue {:min "" :max ""}}))))))

(deftest filters-combine-with-and
  (let [out (apply-filters rows cols {:article {:text "sku"}
                                      :revenue {:min "600"}})]
    (is (= ["SKU-1"] (mapv :article out)))))

(deftest active-filter-count-ignores-blank-specs
  (is (zero? (active-filter-count {:article {:text ""} :revenue {:min "" :max ""}})))
  (is (= 2 (active-filter-count {:article {:text "x"} :revenue {:min "1"}}))))

(deftest parse-num-tolerates-ru-input
  (is (= 12.5 (parse-num "12,5")))
  (is (= -3 (parse-num " -3 ")))
  (is (nil? (parse-num "")))
  (is (nil? (parse-num "abc")))
  (is (nil? (parse-num nil))))

;; ---------------------------------------------------------------------------
;; US4 — sorting
;; ---------------------------------------------------------------------------

(deftest sort-rows-asc-desc-nils-last
  (let [data [{:a 1} {:a nil} {:a 3} {:a 2}]]
    (is (= [3 2 1 nil] (mapv :a (sort-rows data :a :desc))))
    (is (= [1 2 3 nil] (mapv :a (sort-rows data :a :asc))))
    (is (= [1 nil 3 2] (mapv :a (sort-rows data nil :desc))) "no key → untouched")))

(deftest sort-rows-strings
  (let [data [{:s "b"} {:s "a"} {:s nil}]]
    (is (= ["a" "b" nil] (mapv :s (sort-rows data :s :asc))))))

;; ---------------------------------------------------------------------------
;; US4 — grouping
;; ---------------------------------------------------------------------------

(def ^:private cols+brand
  (conj cols {:key :brand :title "Бренд" :group :identity :format :text
              :default-visible? true :filterType :text-contains}))

(def ^:private branded-rows
  [{:article "S1" :brand "Acme" :revenue 100 :sales-qty 1 :margin-pct 10
    :revenue_delta 10}
   {:article "S2" :brand "Acme" :revenue 200 :sales-qty 2 :margin-pct 20
    :revenue_delta 20}
   {:article "S3" :brand "Beta" :revenue 50  :sales-qty 1 :margin-pct 5}])

(deftest group-rows-sums-additive-metrics-only
  (let [g    (group-rows branded-rows :brand cols+brand :article)
        acme (first (filter #(= "Acme" (:brand %)) g))]
    (is (= 2 (count g)))
    (testing "₽ and шт columns sum"
      (is (= 300 (:revenue acme)))
      (is (= 3 (:sales-qty acme))))
    (testing "deltas (₽) sum too"
      (is (= 30 (:revenue_delta acme))))
    (testing "percentages have no honest aggregate → nil (renders «—»)"
      (is (nil? (:margin-pct acme))))
    (testing "identifier cell carries the group value + collapsed count"
      (is (= "Acme" (:article acme)))
      (is (= 2 (:__group-count acme))))))

(deftest group-rows-ordered-by-first-summable-desc
  (let [g (group-rows branded-rows :brand cols+brand :article)]
    (is (= ["Acme" "Beta"] (mapv :brand g)))))

(deftest groupable-columns-need-whitelisted-key-and-data
  (is (= [:brand] (mapv :key (reports/groupable-columns cols+brand branded-rows))))
  (is (empty? (reports/groupable-columns cols rows))
      "no whitelisted group key in plain UE columns → toggle omitted"))

;; ---------------------------------------------------------------------------
;; combo-cell + metric-hint primitives (as used by the table)
;; ---------------------------------------------------------------------------

(deftest combo-cell-markup
  (let [html (render ($ combo-cell {:abs 1000 :share-pct 12.3
                                    :delta 100 :delta-pct 10
                                    :positive-if-grow false}))]
    (is (str/includes? html "₽"))
    (is (str/includes? html "(12,3%)"))
    (is (str/includes? html "delta down") "cost grew → red")
    (is (str/includes? html "10,0%"))))

(deftest combo-cell-nil-abs-renders-dash
  (let [html (render ($ combo-cell {:abs nil}))]
    (is (str/includes? html "—"))))

(deftest metric-hint-renders-only-with-hint
  (is (str/includes? (render ($ metric-hint {:hint "Формула"})) "data-tip"))
  (is (= "" (render ($ metric-hint {:hint nil}))))
  (is (= "" (render ($ metric-hint {:hint "  "})))))

;; ---------------------------------------------------------------------------
;; US5 — constructor formula helpers
;; ---------------------------------------------------------------------------

(deftest tokens-build-left-nested-ast
  (is (= :revenue (tokens->formula [{:op nil :operand :revenue}])))
  (is (= [:- :revenue :cogs]
         (tokens->formula [{:op nil :operand :revenue}
                           {:op :- :operand :cogs}])))
  (is (= [:/ [:- :revenue :cogs] :revenue]
         (tokens->formula [{:op nil :operand :revenue}
                           {:op :- :operand :cogs}
                           {:op :/ :operand :revenue}])))
  (is (= [:* :net-profit 0.87]
         (tokens->formula [{:op nil :operand :net-profit}
                           {:op :* :operand 0.87}])))
  (is (nil? (tokens->formula []))))

(deftest formula-human-uses-ru-labels
  (is (= "((Выручка − Себестоимость) ÷ Выручка)"
         (formula->human [:/ [:- :revenue :cogs] :revenue])))
  (is (= "Выручка" (formula->human :revenue)))
  (is (= "0.87" (formula->human 0.87)))
  (testing "string slugs (post-JSON round trip) resolve too"
    (is (= "Выручка" (formula->human "revenue")))))

(deftest name-slug-translit-and-collision-guard
  (testing "RU names transliterate to stable latin slugs"
    (is (= :u-moya-marzha (name->slug "Моя маржа")))
    (is (= (name->slug "Моя маржа") (name->slug "Моя маржа")) "stable"))
  (testing "u- prefix keeps user slugs out of the canonical row-key space"
    (is (= :u-revenue (name->slug "revenue"))))
  (testing "unmappable name still yields a non-blank slug"
    (is (str/starts-with? (name (name->slug "!!!")) "u-"))))
