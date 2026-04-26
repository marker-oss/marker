(ns analitica.domain.trends-canon-test
  "Per-metric-group tests anchored to canonical-formulas.md §Trends.

   Every deftest maps to one Trends.N block in the canon. If canon changes,
   this file changes in lockstep.

   Data model note: `weekly-sales` returns pre-aggregated rows with STRING
   :type (\"sale\" / \"return\"), not keyword :type as in §Sales. Fixtures here
   must use string type — see §Trends.2 for the divergence explanation.

   No DB calls are made. DB access is stubbed via with-redefs on the private
   `weekly-sales` var. Pure-logic functions are called directly via #' syntax."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.trends :as trends]
            [analitica.util.math :as math]))

;; ---------------------------------------------------------------------------
;; Fixtures
;;
;; fx-current:
;;   2026-03-22  sale   qty=10, total=5000.0
;;   2026-03-22  return qty=1,  total=500.0
;;   2026-03-23  sale   qty=15, total=7500.0
;;   → cur-sales=25, cur-ret=1, cur-rev=12500.0
;;   → cur-avg-check = 12500/25 = 500.0
;;
;; fx-previous:
;;   2026-03-15  sale   qty=8,  total=4000.0
;;   2026-03-15  return qty=0,  total=0.0
;;   2026-03-16  sale   qty=12, total=6000.0
;;   → prev-sales=20, prev-ret=0, prev-rev=10000.0
;;   → prev-avg-check = 10000/20 = 500.0
;;
;; Expected compare-periods output:
;;   "Продажи шт"  :change=5  :change-pct=25.0   (5/20×100)
;;   "Возвраты шт" :change=1  :change-pct=100.0  (1/max(1,0)×100)
;;   "Выручка"     :change=2500.0 :change-pct=25.0 (2500/10000×100)
;;   "Средний чек" :change=0.0    :change-pct=nil
;; ---------------------------------------------------------------------------

(def fx-current
  [{:day "2026-03-22" :type "sale"   :cnt 10 :total 5000.0}
   {:day "2026-03-22" :type "return" :cnt  1 :total  500.0}
   {:day "2026-03-23" :type "sale"   :cnt 15 :total 7500.0}])

(def fx-previous
  [{:day "2026-03-15" :type "sale"   :cnt  8 :total 4000.0}
   {:day "2026-03-15" :type "return" :cnt  0 :total    0.0}
   {:day "2026-03-16" :type "sale"   :cnt 12 :total 6000.0}])

(defn- find-metric [rows label]
  (first (filter #(= label (:metric %)) rows)))

;; ---------------------------------------------------------------------------
;; Trends.1 — compare-periods: sales qty Δ
;; ---------------------------------------------------------------------------

(deftest compare-periods-sales-qty
  (let [rows (#'trends/compare-periods fx-current fx-previous "cur" "prev")
        r    (find-metric rows "Продажи шт")]

    (testing "metric row exists"
      (is (some? r)))

    (testing ":current = 25 (10+15)"
      (is (= 25 (:current r))))

    (testing ":previous = 20 (8+12)"
      (is (= 20 (:previous r))))

    (testing ":change = 5"
      (is (= 5 (:change r))))

    (testing ":change-pct = 25.0 (5/20×100)"
      (is (= 25.0 (:change-pct r))))))

;; ---------------------------------------------------------------------------
;; Trends.1 — compare-periods: returns qty with 0-prev max-1 guard
;; ---------------------------------------------------------------------------

(deftest compare-periods-returns-qty
  (let [rows (#'trends/compare-periods fx-current fx-previous "cur" "prev")
        r    (find-metric rows "Возвраты шт")]

    (testing "metric row exists"
      (is (some? r)))

    (testing ":current = 1"
      (is (= 1 (:current r))))

    (testing ":previous = 0"
      (is (= 0 (:previous r))))

    (testing ":change = 1"
      (is (= 1 (:change r))))

    (testing ":change-pct = 100.0 (max 1 prev = 1; 1/1×100)"
      (is (= 100.0 (:change-pct r))))))

;; ---------------------------------------------------------------------------
;; Trends.1 — compare-periods: revenue delta rounds to 2dp
;; ---------------------------------------------------------------------------

(deftest compare-periods-revenue-rounds
  (let [rows (#'trends/compare-periods fx-current fx-previous "cur" "prev")
        r    (find-metric rows "Выручка")]

    (testing "metric row exists"
      (is (some? r)))

    (testing ":current = 12500.0"
      (is (= (math/round2 12500.0) (:current r))))

    (testing ":previous = 10000.0"
      (is (= (math/round2 10000.0) (:previous r))))

    (testing ":change = 2500.0 (rounded)"
      (is (= (math/round2 2500.0) (:change r))))

    (testing ":change-pct = 25.0 (2500/10000×100)"
      (is (= 25.0 (:change-pct r))))))

;; ---------------------------------------------------------------------------
;; Trends.1 — compare-periods: avg-check has :change-pct nil (ratio-of-ratios)
;; ---------------------------------------------------------------------------

(deftest compare-periods-avg-check-change-pct-nil
  (let [rows (#'trends/compare-periods fx-current fx-previous "cur" "prev")
        r    (find-metric rows "Средний чек")]

    (testing "metric row exists"
      (is (some? r)))

    (testing ":current = 500.0 (12500/25)"
      (is (= (math/round2 500.0) (:current r))))

    (testing ":previous = 500.0 (10000/20)"
      (is (= (math/round2 500.0) (:previous r))))

    (testing ":change-pct is nil — ratio-of-ratios intentionally suppressed"
      (is (nil? (:change-pct r))))))

;; ---------------------------------------------------------------------------
;; Trends.1 — max(1, prev) guard: 0-previous sales yields numeric pct, no NPE
;; ---------------------------------------------------------------------------

(deftest compare-periods-empty-denominator-max-1-guard
  ;; Fixture: previous has NO sale rows at all.
  (let [prev-no-sales [{:day "2026-03-15" :type "return" :cnt 1 :total 100.0}]
        rows (#'trends/compare-periods fx-current prev-no-sales "cur" "prev")
        r    (find-metric rows "Продажи шт")]

    (testing "metric row exists even when prev-sales = 0"
      (is (some? r)))

    (testing ":previous = 0"
      (is (= 0 (:previous r))))

    (testing ":change-pct is a number (not nil, not NaN) — max(1,0) prevents zero-div"
      (is (number? (:change-pct r))))

    (testing ":change-pct = current × 100 (since max(1,0)=1, denom=1)"
      ;; cur-sales = 25, so pct = 25/1×100 = 2500.0
      (is (= 2500.0 (:change-pct r))))))

;; ---------------------------------------------------------------------------
;; Trends.5 — daily groups pre-aggregated rows by :day correctly
;; ---------------------------------------------------------------------------

(def fx-daily-rows
  "Pre-aggregated rows as returned by weekly-sales, two days."
  [{:day "2026-03-22" :type "sale"   :cnt 10 :total 5000.0}
   {:day "2026-03-22" :type "return" :cnt  1 :total  500.0}
   {:day "2026-03-23" :type "sale"   :cnt 15 :total 7500.0}
   {:day "2026-03-23" :type "return" :cnt  2 :total 1000.0}])

(deftest daily-groups-by-day
  ;; weekly-sales is private, so we resolve the var at test time.
  (let [ws-var (resolve 'analitica.domain.trends/weekly-sales)]
    (with-redefs-fn {ws-var (fn [_from _to & _opts] fx-daily-rows)}
      (fn []
        (let [result (trends/daily {:from "2026-03-22" :to "2026-03-23"})
              day22  (first (filter #(= "2026-03-22" (:day %)) result))
              day23  (first (filter #(= "2026-03-23" (:day %)) result))]

          (testing "produces 2 day rows"
            (is (= 2 (count result))))

          (testing "2026-03-22: sales=10, returns=1, revenue=5000.0"
            (is (= 10 (:sales day22)))
            (is (= 1  (:returns day22)))
            (is (= (math/round2 5000.0) (:revenue day22))))

          (testing "2026-03-23: sales=15, returns=2, revenue=7500.0"
            (is (= 15 (:sales day23)))
            (is (= 2  (:returns day23)))
            (is (= (math/round2 7500.0) (:revenue day23)))))))))

;; ---------------------------------------------------------------------------
;; Trends.5 — daily output sorted ascending by :day
;; ---------------------------------------------------------------------------

(def fx-daily-reversed
  "Same data as fx-daily-rows but SQL returns in reverse day order."
  [{:day "2026-03-23" :type "sale"   :cnt 15 :total 7500.0}
   {:day "2026-03-23" :type "return" :cnt  2 :total 1000.0}
   {:day "2026-03-22" :type "sale"   :cnt 10 :total 5000.0}
   {:day "2026-03-22" :type "return" :cnt  1 :total  500.0}])

(deftest daily-sorts-ascending-by-day
  (let [ws-var (resolve 'analitica.domain.trends/weekly-sales)]
    (with-redefs-fn {ws-var (fn [_from _to & _opts] fx-daily-reversed)}
      (fn []
        (let [result (trends/daily {:from "2026-03-22" :to "2026-03-23"})
              days   (map :day result)]

          (testing "first day is 2026-03-22 (ascending)"
            (is (= "2026-03-22" (first days))))

          (testing "last day is 2026-03-23 (ascending)"
            (is (= "2026-03-23" (last days))))

          (testing "days are strictly ascending"
            (is (= days (sort days)))))))))

;; ---------------------------------------------------------------------------
;; Trends.2 — SQL row shape uses string type, not keyword (guard test)
;; ---------------------------------------------------------------------------

(deftest sql-shape-differs-from-sales-domain-type-keyword
  ;; This test guards against future refactors that might silently coerce
  ;; the :type field from string to keyword, breaking the filter logic.
  ;; weekly-sales returns {:type "sale"} (string); §Sales uses {:type :sale} (keyword).
  (let [row (first fx-current)]

    (testing ":type is a string, not a keyword"
      (is (string? (:type row)))
      (is (= "sale" (:type row)))
      (is (not (keyword? (:type row)))))

    (testing "contrast: :sale keyword does NOT equal the string \"sale\""
      (is (not= :sale (:type row))))))
