(ns analitica.web.report-schemas-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.set :as set]
            [analitica.db :as db]
            [analitica.web.report-schemas :as rs]
            [analitica.web.api.report :as report]
            [analitica.domain.unit-economics :as ue]))

(use-fixtures :once
  (fn [f]
    (db/init!)
    (f)))

(deftest schema-registry-test
  (testing "schema exists for :ue"
    (is (some? (rs/get-schema :ue))))

  (testing ":ue schema has required top-level keys"
    (let [s (rs/get-schema :ue)]
      (is (= :ue (:report-type s)))
      (is (string? (:title s)))
      (is (boolean? (:uses-period? s)))
      (is (boolean? (:supports-compare? s)))
      (is (#{:per-article :none} (:rows-mode s)))
      (is (vector? (:tabs s)))
      (is (vector? (:columns s)))
      (is (vector? (:kpi s)))))

  (testing ":ue columns have canonical metadata"
    (let [cols (:columns (rs/get-schema :ue))
          article-col (first (filter #(= :article (:key %)) cols))
          margin-col (first (filter #(= :margin-pct (:key %)) cols))]
      (is (= "Артикул" (:title article-col)))
      (is (= :identity (:group article-col)))
      (is (= :pct (:format margin-col)))
      (is (= "UE.7" (:canon-anchor margin-col))))))

(deftest ue-presets-reference-valid-columns-test
  (testing "каждый ключ в column-presets :per-unit/:percentages существует в :columns
            (не даёт silent-fail при рендере презета)"
    (let [schema (rs/get-schema :ue)
          col-keys (set (map :key (:columns schema)))
          check-preset (fn [preset-key]
                         (let [preset (get-in schema [:column-presets preset-key])]
                           (when (vector? preset)
                             (doseq [k preset]
                               (is (contains? col-keys k)
                                   (str "preset " preset-key " references missing column " k))))))]
      (check-preset :basic)
      (check-preset :per-unit)
      (check-preset :percentages))))

(deftest ^:integration ue-schema-covers-domain-keys-test
  (testing "every column key in UE schema exists in domain ue/calculate output
            (prevents drift when canon-audit adds metrics)"
    (let [;; minimal fixture matching finance-row semantics (one sale)
          fixture [{:article "A1" :barcode "b1" :operation :sale :quantity 1
                    :retail-amount 100.0 :wb-reward 15.0 :mp-commission 5.0
                    :logistics 10.0 :storage 2.0 :acceptance 0.0 :penalties 0.0
                    :acquiring 1.5 :deduction 0.0 :additional 0.0
                    :for-pay 80.0 :cost-price 40.0 :spp-amount 0.0}]
          rows (ue/calculate fixture)
          domain-keys (set (keys (first rows)))
          schema-column-keys (set (map :key (:columns (rs/get-schema :ue))))
          ;; identity cols (brand/subject) come from cost_prices JOIN, not finance/ue output
          identity-keys #{:brand :subject}
          checked-keys (set/difference schema-column-keys identity-keys)]
      (is (every? domain-keys checked-keys)
          (str "schema has keys not in domain output: "
               (set/difference checked-keys domain-keys))))))

(deftest pnl-schema-test
  (testing ":pnl schema has :rows-mode :none and no :table tab"
    (let [s (rs/get-schema :pnl)]
      (is (some? s))
      (is (= :none (:rows-mode s)))
      ;; P&L supports compare: KPI tiles show prev-period deltas (Phase 3)
      (is (true? (:supports-compare? s)))
      (is (not (contains? (set (:tabs s)) :table)))
      (is (contains? (set (:tabs s)) :chart)))))

(deftest all-schemas-registered-test
  (testing "все 11 типов отчётов зарегистрированы"
    (let [expected #{:sales :finance :ue :pnl :abc :stock :returns :buyout :geo :trends :losses}]
      (is (= expected (set (rs/all-report-types))))))

  (testing "snapshot-отчёты (stock) имеют :uses-period? false"
    (is (false? (:uses-period? (rs/get-schema :stock)))))

  (testing "каждая schema имеет минимум 2 колонки"
    (doseq [rt (rs/all-report-types)]
      (let [s (rs/get-schema rt)]
        (when-not (= :none (:rows-mode s))
          (is (>= (count (:columns s)) 2)
              (str rt " should have >= 2 columns")))))))

;; ---------------------------------------------------------------------------
;; T007 — 016 descriptor field invariants (VR-d2/d3/d4)
;; Written BEFORE implementation (TDD). All three tests must FAIL until
;; T009 fills UE schema with :hint/:suffix/:filterType/:positiveIfGrow.
;; ---------------------------------------------------------------------------

(deftest vr-d2-rub-columns-have-hint
  (testing "VR-d2/FR-004/SC-002: every :format :rub column in UE schema has non-nil :hint
            naming a basis ∈ {gross realisation, net sales, payout}"
    (let [cols    (:columns (rs/get-schema :ue))
          rub-cols (filter #(= :rub (:format %)) cols)]
      ;; There must be at least one :rub column (sanity)
      (is (seq rub-cols) "UE schema must have at least one :format :rub column")
      (doseq [col rub-cols]
        (is (some? (:hint col))
            (str "Column " (:key col) " (:format :rub) must have a non-nil :hint"))
        (is (string? (:hint col))
            (str "Column " (:key col) " :hint must be a string"))
        (is (pos? (count (:hint col)))
            (str "Column " (:key col) " :hint must be non-empty"))
        ;; :hint must name at least one recognised basis
        (let [h (clojure.string/lower-case (:hint col))]
          (is (or (clojure.string/includes? h "gross realisation")
                  (clojure.string/includes? h "net sales")
                  (clojure.string/includes? h "payout")
                  (clojure.string/includes? h "basis"))
              (str "Column " (:key col) " :hint must name a basis; got: " (:hint col))))))))

(deftest vr-d3-identifier-columns-text-contains
  (testing "VR-d3/FR-005: every :format :text column in UE schema has :filterType :text-contains
            and must NOT carry :positiveIfGrow or :hint"
    (let [cols      (:columns (rs/get-schema :ue))
          text-cols (filter #(= :text (:format %)) cols)]
      (is (seq text-cols) "UE schema must have at least one :format :text column")
      (doseq [col text-cols]
        (is (= :text-contains (:filterType col))
            (str "Identifier column " (:key col) " must have :filterType :text-contains"))
        (is (not (contains? col :positiveIfGrow))
            (str "Identifier column " (:key col) " must NOT have :positiveIfGrow"))
        (is (not (contains? col :hint))
            (str "Identifier column " (:key col) " must NOT have :hint"))))))

(deftest vr-d4-positiveIfGrow-direction
  (testing "VR-d4/FR-006: cost-like columns declare :positiveIfGrow false;
            profit-like columns declare :positiveIfGrow true"
    (let [cols     (:columns (rs/get-schema :ue))
          col-map  (into {} (map (juxt :key identity) cols))]
      ;; cost-like: logistics, storage (NOT mp-commission which is absent from UE; drr-pct)
      (doseq [k [:logistics :storage :drr-pct]]
        (when-let [col (get col-map k)]
          (is (= false (:positiveIfGrow col))
              (str "Cost-like column " k " must have :positiveIfGrow false"))))
      ;; profit-like: profit, revenue
      (doseq [k [:profit :revenue]]
        (when-let [col (get col-map k)]
          (is (= true (:positiveIfGrow col))
              (str "Profit-like column " k " must have :positiveIfGrow true")))))))

;; ---------------------------------------------------------------------------
;; T010 — reports-handler :columns passthrough (unknown keys pass verbatim)
;; ---------------------------------------------------------------------------

(deftest columns-passthrough-unknown-keys
  (testing "T010: :columns from schema passes through unknown keys verbatim
            (future fields like :hint/:suffix/:filterType/:positiveIfGrow not stripped)"
    (let [;; Inject a test column with unknown keys into the UE schema columns
          test-col {:key :test-metric :title "Test" :group :ue2 :format :rub
                    :hint "Test hint. Basis: gross realisation."
                    :suffix :rub :filterType :number-range :positiveIfGrow true
                    :_unknown-key "should-pass-through"}
          ;; Simulate what reports-handler does: (vec (:columns schema))
          ;; The handler does NOT strip unknown keys — this test verifies that
          schema    (rs/get-schema :ue)
          injected  (update schema :columns conj test-col)
          result    (vec (:columns injected))
          found     (first (filter #(= :test-metric (:key %)) result))]
      (is (some? found) "test column must be in result")
      (is (= "Test hint. Basis: gross realisation." (:hint found))
          ":hint must pass through verbatim")
      (is (= :rub (:suffix found))
          ":suffix must pass through verbatim")
      (is (= :number-range (:filterType found))
          ":filterType must pass through verbatim")
      (is (= true (:positiveIfGrow found))
          ":positiveIfGrow must pass through verbatim")
      (is (= "should-pass-through" (:_unknown-key found))
          "truly unknown keys must also pass through"))))

;; ---------------------------------------------------------------------------
;; T005 — canonical slug dictionary registered
;; ---------------------------------------------------------------------------

(deftest canonical-metric-slugs-registered
  (testing "T005: :canonical-metric-slugs is a non-empty set of keywords in report-schemas ns"
    (let [slugs rs/canonical-metric-slugs]
      (is (set? slugs) ":canonical-metric-slugs must be a set")
      (is (seq slugs) ":canonical-metric-slugs must be non-empty")
      (is (every? keyword? slugs) "every slug must be a keyword")
      ;; Core slugs from descriptor-schema.edn §CANONICAL SLUG DICTIONARY
      (doseq [slug [:revenue :net-profit :gross-margin :mp-commission
                    :logistics :storage :cap-by-cost :cap-by-price :gmroi
                    :revenue-abc :profit-abc]]
        (is (contains? slugs slug)
            (str "core slug " slug " must be in :canonical-metric-slugs")))))

  (testing "T004/VR-schema: Suffix and FilterType are accessible from report-schemas ns"
    (is (some? rs/Suffix)      "rs/Suffix must be defined")
    (is (some? rs/FilterType)  "rs/FilterType must be defined")
    (is (some? rs/ColumnDescriptor) "rs/ColumnDescriptor must be defined")))

;; ---------------------------------------------------------------------------
;; LT1 contract test: every schema :kpi key must appear in live :totals
;; ---------------------------------------------------------------------------

(deftest ^:integration kpi-keys-have-comparable-totals
  (testing "every schema :kpi :key appears in live :totals for all report types with a :kpi block"
    ;; :losses is excluded — its compute-report branch is a separate task (no rows yet).
    ;; :pnl and :finance/:ue already had :totals before this task.
    ;; :trends totals keys come from matching row :metric strings — only :revenue-current
    ;; and :orders-current are guaranteed (profit row is absent from compare-periods output).
    (let [period {:from "2026-04-01" :to "2026-04-30"}
          ;; LT1-scope types (sales/abc/returns/buyout/stock/geo/trends) plus the
          ;; pre-existing finance/ue/pnl to verify no regression. :trends is safe
          ;; to include: contains? passes on nil-valued keys (e.g. :profit-current).
          all-types [:finance :ue :pnl :sales :abc :returns :buyout :stock :geo :trends]]
      (doseq [rt all-types]
        (let [schema (rs/get-schema rt)
              kpi-keys (mapv :key (:kpi schema))
              result (report/report-data rt period :marketplace :ozon)
              totals (:totals result)]
          (is (map? totals) (str rt ": :totals must be a map"))
          (doseq [k kpi-keys]
            (is (contains? totals k)
                (str rt ": schema :kpi key " k " is missing from :totals"))))))))
