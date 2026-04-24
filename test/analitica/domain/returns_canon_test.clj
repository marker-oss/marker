(ns analitica.domain.returns-canon-test
  "Per-metric-group tests anchored to canonical-formulas.md §Returns.

   Every deftest maps to one Returns.N block in the canon. If canon changes,
   this file changes in lockstep.

   Data model note: the `sales` table is a pure activity log — one row per
   unit event. This fixture uses in-memory maps; no finance rows, no DB.
   Returns functions are unit-count-based: monetary fields are ignored."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.returns :as returns]))

;; ---------------------------------------------------------------------------
;; Shared fixture: 3 articles, 3 return-rate profiles, 2+ distinct dates.
;;
;; Article A: 8 sales + 2 returns → total=10, return-rate=20.0%
;; Article B: 5 sales + 0 returns → total=5,  return-rate=0.0%
;; Article C: 1 sale  + 1 return  → total=2,  return-rate=50.0%
;;
;; Returns distributed across 2 dates:
;;   2026-03-01 — 1 return (A)
;;   2026-03-15 — 1 return (A) + 1 return (C)
;;
;; Period totals:
;;   sold     = 8+5+1 = 14
;;   returned = 2+0+1 = 3
;;   total    = 17
;;   rate     = math/percentage(3, 17) = round2(3/17 × 100) = 17.65%
;; ---------------------------------------------------------------------------

(def fx
  "Inline sales/returns rows. Monetary :for-pay present but should NOT affect counts."
  [;; Article A — 8 sales
   {:date "2026-03-01T08:00:00" :type :sale   :article "A" :subject "shirts" :for-pay 800.0}
   {:date "2026-03-01T09:00:00" :type :sale   :article "A" :subject "shirts" :for-pay 800.0}
   {:date "2026-03-01T10:00:00" :type :sale   :article "A" :subject "shirts" :for-pay 800.0}
   {:date "2026-03-05T08:00:00" :type :sale   :article "A" :subject "shirts" :for-pay 800.0}
   {:date "2026-03-05T09:00:00" :type :sale   :article "A" :subject "shirts" :for-pay 800.0}
   {:date "2026-03-05T10:00:00" :type :sale   :article "A" :subject "shirts" :for-pay 800.0}
   {:date "2026-03-10T08:00:00" :type :sale   :article "A" :subject "shirts" :for-pay 800.0}
   {:date "2026-03-10T09:00:00" :type :sale   :article "A" :subject "shirts" :for-pay 800.0}
   ;; Article A — 2 returns on 2 different dates
   {:date "2026-03-01T15:00:00" :type :return :article "A" :subject "shirts" :for-pay -800.0}
   {:date "2026-03-15T11:00:00" :type :return :article "A" :subject "shirts" :for-pay -800.0}
   ;; Article B — 5 sales, 0 returns
   {:date "2026-03-03T08:00:00" :type :sale   :article "B" :subject "pants"  :for-pay 500.0}
   {:date "2026-03-03T09:00:00" :type :sale   :article "B" :subject "pants"  :for-pay 500.0}
   {:date "2026-03-03T10:00:00" :type :sale   :article "B" :subject "pants"  :for-pay 500.0}
   {:date "2026-03-07T08:00:00" :type :sale   :article "B" :subject "pants"  :for-pay 500.0}
   {:date "2026-03-07T09:00:00" :type :sale   :article "B" :subject "pants"  :for-pay 500.0}
   ;; Article C — 1 sale + 1 return
   {:date "2026-03-10T12:00:00" :type :sale   :article "C" :subject "hats"   :for-pay 300.0}
   {:date "2026-03-15T14:00:00" :type :return :article "C" :subject "hats"   :for-pay -300.0}])

(defn- find-article [rows article]
  (first (filter #(= article (:article %)) rows)))

;; ---------------------------------------------------------------------------
;; Returns.1 — by-article computes return-rate correctly
;; ---------------------------------------------------------------------------

(deftest by-article-computes-return-rate
  (let [rows (returns/by-article fx)
        a    (find-article rows "A")
        b    (find-article rows "B")
        c    (find-article rows "C")]

    (testing "Article A: 8 sold, 2 returned, total=10, rate=20.0%"
      (is (= 8 (:sold a)))
      (is (= 2 (:returned a)))
      (is (= 10 (:total a)))
      (is (= 20.0 (:return-rate a))))

    (testing "Article B: 5 sold, 0 returned, total=5, rate=0.0%"
      (is (= 5 (:sold b)))
      (is (= 0 (:returned b)))
      (is (= 5 (:total b)))
      (is (= 0.0 (:return-rate b))))

    (testing "Article C: 1 sold, 1 returned, total=2, rate=50.0%"
      (is (= 1 (:sold c)))
      (is (= 1 (:returned c)))
      (is (= 2 (:total c)))
      (is (= 50.0 (:return-rate c))))))

;; ---------------------------------------------------------------------------
;; Returns.1 — by-article sorts descending by return-rate
;; ---------------------------------------------------------------------------

(deftest by-article-sorts-by-rate-desc
  (let [rows (returns/by-article fx)]
    (testing "produces 3 rows"
      (is (= 3 (count rows))))
    (testing "first row has highest return-rate (C = 50%)"
      (is (= "C" (:article (first rows)))))
    (testing "second row is A (20%)"
      (is (= "A" (:article (second rows)))))
    (testing "last row has lowest return-rate (B = 0%)"
      (is (= "B" (:article (last rows)))))))

;; ---------------------------------------------------------------------------
;; Returns.2 — by-day filters to returns only, groups by date
;; ---------------------------------------------------------------------------

(deftest by-day-filters-to-returns-only
  ;; Fixture has 3 return rows: 1 on 2026-03-01, 2 on 2026-03-15
  (let [days (returns/by-day fx)]

    (testing "produces 2 day groups (only return dates)"
      (is (= 2 (count days))))

    (let [d01 (first (filter #(= "2026-03-01" (:date %)) days))
          d15 (first (filter #(= "2026-03-15" (:date %)) days))]
      (testing "2026-03-01 has 1 return (Article A only)"
        (is (= 1 (:returns d01))))
      (testing "2026-03-15 has 2 returns (Article A + Article C)"
        (is (= 2 (:returns d15)))))

    (testing "sorted ascending by date: 2026-03-01 first"
      (is (= "2026-03-01" (:date (first days)))))

    (testing "no sale-only dates appear (e.g. 2026-03-03, 2026-03-05, 2026-03-07)"
      (let [dates (set (map :date days))]
        (is (not (contains? dates "2026-03-03")))
        (is (not (contains? dates "2026-03-05")))
        (is (not (contains? dates "2026-03-07")))))))

;; ---------------------------------------------------------------------------
;; Returns.3 — totals period rollup
;; ---------------------------------------------------------------------------

(deftest totals-overall-rate
  ;; sold=14, returned=3, total=17, rate=round2(3/17×100)=17.65
  (let [t (returns/totals fx)]
    (testing "sold = 8+5+1 = 14"
      (is (= 14 (:sold t))))
    (testing "returned = 2+0+1 = 3"
      (is (= 3 (:returned t))))
    (testing "return-rate = round2(3/17×100) = 17.65"
      (is (= 17.65 (:return-rate t))))))

;; ---------------------------------------------------------------------------
;; Returns.3 — totals edge case: empty denominator → nil rate
;; ---------------------------------------------------------------------------

(deftest totals-empty-denominator
  (let [t (returns/totals [])]
    (testing "sold = 0"
      (is (= 0 (:sold t))))
    (testing "returned = 0"
      (is (= 0 (:returned t))))
    (testing "return-rate = nil (math/percentage returns nil on zero denominator)"
      (is (nil? (:return-rate t))))))

;; ---------------------------------------------------------------------------
;; Returns.5 / Returns.7.1 — monetary fields do not affect unit counts
;; ---------------------------------------------------------------------------

(deftest returns-are-unit-counted-not-monetary
  ;; Add a row with a very large :for-pay value to verify counts are unchanged.
  ;; Returns domain ignores monetary fields entirely.
  (let [extra-sale   {:date "2026-03-20T08:00:00" :type :sale   :article "A"
                      :subject "shirts" :for-pay 999999.0}
        extra-return {:date "2026-03-20T09:00:00" :type :return :article "A"
                      :subject "shirts" :for-pay -999999.0}
        data  (into fx [extra-sale extra-return])
        rows  (returns/by-article data)
        a     (find-article rows "A")
        t     (returns/totals data)]

    (testing "A sold increases by 1 (from 8 to 9) regardless of :for-pay"
      (is (= 9 (:sold a))))
    (testing "A returned increases by 1 (from 2 to 3)"
      (is (= 3 (:returned a))))
    (testing "total sold increases by 1 (14→15)"
      (is (= 15 (:sold t))))
    (testing "total returned increases by 1 (3→4)"
      (is (= 4 (:returned t))))))

;; ---------------------------------------------------------------------------
;; Returns.4 — by-article includes single-operation articles (filter is report-layer)
;; ---------------------------------------------------------------------------

(deftest single-operation-articles-not-excluded-from-by-article
  ;; Add an article with total=1 (1 return only, no sales).
  ;; by-article must include it; the report layer applies total≥2 filter separately.
  (let [single-return {:date "2026-03-25T10:00:00" :type :return :article "D"
                       :subject "socks" :for-pay -100.0}
        data  (conj fx single-return)
        rows  (returns/by-article data)
        d     (find-article rows "D")]

    (testing "Article D with total=1 appears in by-article output"
      (is (some? d)))
    (testing "Article D has sold=0, returned=1, total=1"
      (is (= 0 (:sold d)))
      (is (= 1 (:returned d)))
      (is (= 1 (:total d))))
    (testing "return-rate for D = 100.0% (1 out of 1)"
      (is (= 100.0 (:return-rate d))))))
