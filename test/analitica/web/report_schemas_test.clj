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
