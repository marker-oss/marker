(ns analitica.domain.abc-test
  "016 US4 (T038) — profit-ABC over the FULL filtered dataset.

   `abc/analyze-by` gains a :profit criterion (value-fn = per-article net
   profit = for-pay − total-cost, the profit computable in the finance
   aggregation pipeline). Classification uses the existing `classify`
   cumulative-share machinery unchanged (≤80→A, ≤95→B, else C).

   The load-bearing invariant (VR-a1 / FR-028 / FR-029 / SC-006): ABC is
   computed over the WHOLE dataset, never a truncated top-N or a single page —
   a row that lands on page 2 still carries its globally-correct class."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.abc :as abc]))

;; ---------------------------------------------------------------------------
;; Fixture — finance rows (minimal shape consumed by finance/by-article).
;; Five articles with widely-separated profit so A/B/C boundaries are crisp.
;; net profit per article = for-pay − total-cost. No cost-price → total-cost 0,
;; so profit == for-pay here, which keeps the expected cumulative shares exact.
;; ---------------------------------------------------------------------------

(defn- sale
  "A single sale finance row with a given article, retail and payout."
  [article retail for-pay]
  {:article       article
   :marketplace   :wb
   :operation     "sale"
   :quantity      1
   :retail-amount retail
   :for-pay       for-pay
   :mp-commission 0.0})

(def ^:private fixture
  ;; for-pay totals: A1=8000 A2=1000 A3=600 A4=250 A5=150  (Σ = 10000)
  ;; cumulative-share (sorted desc): 80% → A1(A); 90% → A2(B); 96% → A3(C);
  ;;   98.5% → A4(C); 100% → A5(C).
  [(sale "A1" 10000.0 8000.0)
   (sale "A2" 1200.0  1000.0)
   (sale "A3" 800.0   600.0)
   (sale "A4" 300.0   250.0)
   (sale "A5" 200.0   150.0)])

(defn- by-article-cat
  "Map article → :abc-category for an analyze-by result."
  [rows]
  (into {} (map (juxt :article :abc-category) rows)))

(deftest analyze-by-profit-classifies-full-dataset
  (testing "T038/VR-a1: analyze-by :profit classifies every article by cumulative
            profit share (≤80→A, ≤95→B, else C) over the FULL dataset"
    (let [rows (abc/analyze-by fixture :profit)
          cat  (by-article-cat rows)]
      (is (= 5 (count rows)) "every article in the dataset is classified (not a top-N slice)")
      (is (= "A" (cat "A1")) "top profit article (80% share) → A")
      (is (= "B" (cat "A2")) "next article (cum 90%) → B")
      (is (= "C" (cat "A3")) "cum 96% → C")
      (is (= "C" (cat "A4")) "cum 98.5% → C")
      (is (= "C" (cat "A5")) "tail article → C"))))

(deftest analyze-by-profit-uses-net-profit-not-revenue
  (testing "T038: :profit criterion ranks by per-article net profit (for-pay − cost),
            NOT by revenue — an article can outrank another on revenue yet rank lower
            on profit"
    ;; B has higher revenue but lower payout/profit than A.
    (let [rows (abc/analyze-by [(sale "HI-REV" 10000.0 100.0)
                                (sale "HI-PROFIT" 5000.0 4000.0)]
                               :profit)
          ;; sorted desc by profit → HI-PROFIT first
          first-art (:article (first rows))]
      (is (= "HI-PROFIT" first-art)
          "the higher-PROFIT article sorts first under :profit, regardless of revenue"))))

(deftest analyze-by-profit-full-set-vs-page-slice
  (testing "T038/SC-006: classifying the full set then taking a page yields the SAME
            classes as classifying the full set — proof that ABC is a whole-dataset
            computation. A per-page ABC (classifying only the tail) would misclassify
            page-2 rows as A."
    (let [full      (abc/analyze-by fixture :profit)
          full-cat  (by-article-cat full)
          ;; simulate 'page 2' = the last three articles, taken AFTER whole-set classify
          page-2    (drop 2 full)
          ;; a WRONG per-page ABC would re-run analyze-by on just the tail:
          per-page  (abc/analyze-by (filter #(#{"A3" "A4" "A5"} (:article %)) fixture) :profit)
          per-page-cat (by-article-cat per-page)]
      ;; correct: page-2 rows keep their global class (all C)
      (is (every? #(= "C" (:abc-category %)) page-2)
          "page-2 rows carry the globally-correct class (C)")
      ;; the naive per-page recompute would call the tail's top row 'A' — demonstrating
      ;; why the whole-set computation matters
      (is (= "A" (per-page-cat "A3"))
          "a per-page ABC would (wrongly) call A3 an 'A' — full-set classify avoids this")
      (is (not= (full-cat "A3") (per-page-cat "A3"))
          "full-set class (C) differs from the per-page misclassification (A)"))))

(deftest analyze-by-profit-empty-and-zero
  (testing "T038: empty dataset → nil (classify guard); all-zero profit → nil, never a crash"
    (is (nil? (abc/analyze-by [] :profit)))
    (is (nil? (abc/analyze-by [(sale "Z1" 0.0 0.0) (sale "Z2" 0.0 0.0)] :profit)))))
