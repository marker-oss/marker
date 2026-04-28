(ns analitica.domain.abc-canon-test
  "Per-metric-group tests anchored to canonical-formulas.md §ABC.

   Every deftest maps to one ABC.N block in the canon. If canon changes,
   this file changes in lockstep.

   Data model note: `classify` operates on pre-aggregated article rows
   (same shape as `finance/by-article` output). The public `analyze-by`
   takes raw finance rows and calls `finance/by-article` internally.
   We test `classify` directly via #'abc/classify (private fn access)
   and test `analyze-by` once with a raw-finance fixture."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.abc :as abc]
            [analitica.util.math :as math]))

;; ---------------------------------------------------------------------------
;; Fixture A: pre-aggregated article rows (same shape as finance/by-article).
;; Total revenue = 1000.
;; Cumulative by :revenue: 50 / 80 / 90 / 97 / 100
;; Expected categories:     A  /  A /  B /  C /   C
;; (article "A" cum=50 ≤ 80 → A; "B" cum=80 ≤ 80 → A; "C" cum=90 ≤ 95 → B;
;;  "D" cum=97 > 95 → C; "E" cum=100 > 95 → C)
;; ---------------------------------------------------------------------------

(def fx-articles
  "Pre-aggregated article rows (same shape as finance/by-article output)."
  [{:article "A" :revenue 500.0 :for-pay 400.0 :sales-qty 5  :returns-qty 0}
   {:article "B" :revenue 300.0 :for-pay 240.0 :sales-qty 3  :returns-qty 0}
   {:article "C" :revenue 100.0 :for-pay  80.0 :sales-qty 1  :returns-qty 0}
   {:article "D" :revenue  70.0 :for-pay  55.0 :sales-qty 1  :returns-qty 0}
   {:article "E" :revenue  30.0 :for-pay  20.0 :sales-qty 1  :returns-qty 0}])
;; Total revenue = 1000. Cumulative: 50 / 80 / 90 / 97 / 100.
;; Categories expected by :revenue:
;;   A=50       → A (cum ≤ 80)
;;   B=80       → A (cum = 80, still ≤ 80)
;;   C=90       → B (80 < cum ≤ 95)
;;   D=97       → C (cum > 95)
;;   E=100      → C (cum > 95)

;; ---------------------------------------------------------------------------
;; Fixture B: raw WB finance rows (reused from finance/pnl canon tests).
;; Article A: 5 sales × 100 (for-pay=80), Article B: 3 sales × 50 (for-pay=42),
;; Article C: return-only.
;; By revenue: A=500, B=150, C=0 → total=650.
;; Cumulative: A=76.92%, B=100%, C=100% → A→A, B→C, C→C (by revenue)
;; By for-pay: A=400, B=126, C=0 → total=526.
;; ---------------------------------------------------------------------------

(def fx-finance
  "Raw WB finance rows: 3 articles (A/B/C), March 2026."
  (concat
    ;; Article A — 5 sales × (retail=100, for-pay=80)
    (for [i (range 5)]
      {:marketplace :wb :rrd-id (+ 100 i)
       :date-from "2026-03-01" :date-to "2026-03-07"
       :event-date "2026-03-03"
       :article "A" :operation "sale" :quantity 1
       :retail-amount 100.0 :retail-price 100.0
       :for-pay 80.0 :mp-commission 15.0 :wb-reward 15.0
       :delivery-cost 1.0 :storage-fee 0.5 :acceptance 0.2
       :penalty 0.0 :acquiring-fee 2.0 :deduction 0.1
       :additional-payment 0.0 :ad-cost 0.0})
    ;; Article A — 2 returns (for-pay=0 per WB convention)
    (for [i (range 2)]
      {:marketplace :wb :rrd-id (+ 200 i)
       :date-from "2026-03-01" :date-to "2026-03-07"
       :event-date "2026-03-05"
       :article "A" :operation "return" :quantity 1
       :retail-amount 0.0 :retail-price 0.0
       :for-pay 0.0 :mp-commission 0.0 :wb-reward 0.0
       :delivery-cost 0.5 :storage-fee 0.0 :acceptance 0.0
       :penalty 0.0 :acquiring-fee 0.0 :deduction 0.0
       :additional-payment 0.0 :ad-cost 0.0})
    ;; Article B — 3 sales × (retail=50, for-pay=42)
    (for [i (range 3)]
      {:marketplace :wb :rrd-id (+ 300 i)
       :date-from "2026-03-01" :date-to "2026-03-07"
       :event-date "2026-03-04"
       :article "B" :operation "sale" :quantity 1
       :retail-amount 50.0 :retail-price 50.0
       :for-pay 42.0 :mp-commission 8.0 :wb-reward 8.0
       :delivery-cost 0.5 :storage-fee 0.2 :acceptance 0.1
       :penalty 0.0 :acquiring-fee 1.0 :deduction 0.0
       :additional-payment 0.0 :ad-cost 0.0})
    ;; Article C — return only (for-pay=0)
    (for [i (range 1)]
      {:marketplace :wb :rrd-id (+ 400 i)
       :date-from "2026-03-01" :date-to "2026-03-07"
       :event-date "2026-03-06"
       :article "C" :operation "return" :quantity 1
       :retail-amount 0.0 :retail-price 0.0
       :for-pay 0.0 :mp-commission 0.0 :wb-reward 0.0
       :delivery-cost 0.5 :storage-fee 0.0 :acceptance 0.0
       :penalty 0.0 :acquiring-fee 0.0 :deduction 0.0
       :additional-payment 0.0 :ad-cost 0.0})))

;; ---------------------------------------------------------------------------
;; ABC.1 — classify: 80/95 Pareto boundaries
;; ---------------------------------------------------------------------------

(deftest abc-classify-80-95-boundaries
  ;; Access private fn via Var reference.
  (let [classify #'abc/classify
        result   (classify fx-articles :revenue)]

    (testing "classify returns a seq of 5 tagged rows"
      (is (= 5 (count result))))

    (testing "article A: cum=50.0 ≤ 80 → category A"
      (let [row (first (filter #(= "A" (:article %)) result))]
        (is (= "A" (:abc-category row)))
        (is (= 50.0 (:cum-pct row)))))

    (testing "article B: cum=80.0 ≤ 80 (inclusive) → category A, not B"
      (let [row (first (filter #(= "B" (:article %)) result))]
        (is (= "A" (:abc-category row)))
        (is (= 80.0 (:cum-pct row)))))

    (testing "article C: cum=90.0 → 80 < 90 ≤ 95 → category B"
      (let [row (first (filter #(= "C" (:article %)) result))]
        (is (= "B" (:abc-category row)))
        (is (= 90.0 (:cum-pct row)))))

    (testing "article D: cum=97.0 > 95 → category C"
      (let [row (first (filter #(= "D" (:article %)) result))]
        (is (= "C" (:abc-category row)))
        (is (= 97.0 (:cum-pct row)))))

    (testing "article E: cum=100.0 > 95 → category C"
      (let [row (first (filter #(= "E" (:article %)) result))]
        (is (= "C" (:abc-category row)))
        (is (= 100.0 (:cum-pct row)))))

    (testing "all rows carry :abc-category and :cum-pct keys"
      (is (every? #(contains? % :abc-category) result))
      (is (every? #(contains? % :cum-pct) result)))))

;; ---------------------------------------------------------------------------
;; ABC.2 — analyze-by: full chain from raw finance rows (revenue criterion)
;; ---------------------------------------------------------------------------

(deftest abc-analyze-by-revenue
  ;; analyze-by calls finance/by-article internally.
  ;; fx-finance: A has revenue=500, B=150, C=0. Total=650.
  ;; Cumulative by revenue: A=76.92% → A; B=100% → C; C=100% → C.
  (let [result (abc/analyze-by fx-finance :revenue)]

    (testing "analyze-by returns non-nil result for non-empty finance data"
      (is (some? result)))

    (testing "result contains 3 rows (one per distinct article in fx-finance)"
      (is (= 3 (count result))))

    (testing "article A (highest revenue=500) lands in category A"
      (let [row (first (filter #(= "A" (:article %)) result))]
        (is (some? row))
        (is (= "A" (:abc-category row)))))

    (testing "all rows carry :abc-category"
      (is (every? #(contains? % :abc-category) result)))

    (testing "categories are a subset of #{A B C}"
      (is (every? #{"A" "B" "C"} (map :abc-category result))))))

;; ---------------------------------------------------------------------------
;; ABC.2 — analyze-by: criterion dispatch (revenue / for-pay / sales-qty)
;; ---------------------------------------------------------------------------

(deftest abc-analyze-by-criterion-dispatch
  ;; For each criterion, the top-ranked article (highest value) should be
  ;; in category A. With only 3 articles in fx-finance the distributions
  ;; are steep enough that the top article is always A.
  (doseq [criterion [:revenue :for-pay :sales-qty]]
    (testing (str "criterion " criterion " → top article has category A")
      (let [result (abc/analyze-by fx-finance criterion)
            ;; Result is sorted by criterion desc already (output of classify
            ;; preserves the sort-by order). The first row is the top article.
            top    (first result)]
        (is (some? top)
            (str "analyze-by returned nil for criterion " criterion))
        (is (= "A" (:abc-category top))
            (str "top article for " criterion " should be A, got "
                 (:abc-category top))))))

  (testing "all 3 criteria produce :abc-category on every result row"
    (doseq [criterion [:revenue :for-pay :sales-qty]]
      (let [result (abc/analyze-by fx-finance criterion)]
        (is (every? #(contains? % :abc-category) result)
            (str "missing :abc-category for criterion " criterion))))))

;; ---------------------------------------------------------------------------
;; ABC.4 — summary rollup: per-category aggregates + alphabetic sort
;; ---------------------------------------------------------------------------

(deftest abc-summary-rollup
  ;; Use classify output on fx-articles (5 rows, known categories A/A/B/C/C).
  (let [classify #'abc/classify
        tagged   (classify fx-articles :revenue)
        summ     (abc/summary tagged)]

    (testing "summary returns exactly 3 rows (A, B, C all present)"
      (is (= 3 (count summ))))

    (testing "sort order is alphabetic: A first, B second, C third"
      (is (= ["A" "B" "C"] (mapv :category summ))))

    (let [a-row (first (filter #(= "A" (:category %)) summ))
          b-row (first (filter #(= "B" (:category %)) summ))
          c-row (first (filter #(= "C" (:category %)) summ))]

      (testing "A: count=2 (articles 'A' and 'B' from fixture)"
        (is (= 2 (:count a-row))))
      (testing "A: revenue = 500+300 = 800.0"
        (is (= 800.0 (:revenue a-row))))
      (testing "A: for-pay = 400+240 = 640.0"
        (is (= 640.0 (:for-pay a-row))))
      (testing "A: sales-qty = 5+3 = 8"
        (is (= 8 (:sales-qty a-row))))
      (testing "A: returns-qty = 0+0 = 0"
        (is (= 0 (:returns-qty a-row))))

      (testing "B: count=1 (article 'C' from fixture)"
        (is (= 1 (:count b-row))))
      (testing "B: revenue = 100.0"
        (is (= 100.0 (:revenue b-row))))
      (testing "B: for-pay = 80.0"
        (is (= 80.0 (:for-pay b-row))))

      (testing "C: count=2 (articles 'D' and 'E' from fixture)"
        (is (= 2 (:count c-row))))
      (testing "C: revenue = 70+30 = 100.0"
        (is (= 100.0 (:revenue c-row))))
      (testing "C: for-pay = 55+20 = 75.0"
        (is (= 75.0 (:for-pay c-row))))
      (testing "C: sales-qty = 1+1 = 2"
        (is (= 2 (:sales-qty c-row)))))))

;; ---------------------------------------------------------------------------
;; ABC.7.1 — empty input → nil / empty (§ABC.7 known gaps)
;; ---------------------------------------------------------------------------

(deftest abc-empty-input-returns-nil
  (let [classify #'abc/classify]

    (testing "classify on [] returns nil (total=0 guard)"
      (is (nil? (classify [] :revenue))))

    (testing "classify on all-zero criterion returns nil"
      (let [zero-items [{:article "X" :revenue 0.0 :for-pay 0.0 :sales-qty 0 :returns-qty 0}]]
        (is (nil? (classify zero-items :revenue)))))

    (testing "analyze-by on empty finance data returns nil"
      (is (nil? (abc/analyze-by [] :revenue))))))

;; ---------------------------------------------------------------------------
;; ABC.2 — unknown criterion falls back to :revenue
;; ---------------------------------------------------------------------------

(deftest abc-unknown-criterion-falls-back-to-revenue
  (let [result-revenue (abc/analyze-by fx-finance :revenue)
        result-unknown (abc/analyze-by fx-finance :price)]

    (testing "unknown criterion :price returns non-nil result"
      (is (some? result-unknown)))

    (testing "unknown criterion :price produces same category assignments as :revenue"
      (let [cats-revenue (set (map (juxt :article :abc-category) result-revenue))
            cats-unknown (set (map (juxt :article :abc-category) result-unknown))]
        (is (= cats-revenue cats-unknown)
            (str "Expected categories to match :revenue fallback. Revenue: "
                 cats-revenue " Unknown: " cats-unknown))))))
