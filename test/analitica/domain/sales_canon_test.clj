(ns analitica.domain.sales-canon-test
  "Per-metric-group tests anchored to canonical-formulas.md §Sales.

   Every deftest maps to one Sales.N block in the canon. If canon changes,
   this file changes in lockstep.

   Data model note: the `sales` table is a pure activity log — one row per
   unit event. This fixture uses in-memory maps; no finance rows, no DB."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.sales :as sales]
            [analitica.util.math :as math]))

;; ---------------------------------------------------------------------------
;; Shared fixture: 3 articles / 2 brands / 2 warehouses / 2 regions / 2+ days.
;;
;; Article A — 3 sales + 1 return  — "shirts", brand B1, warehouse Koledino,  region RU-MOW
;; Article B — 2 sales + 0 returns — "pants",  brand B2, warehouse Elektrostal, region RU-SPB
;; Article C — 1 sale  + 1 return  — "hats",   brand B1, warehouse Koledino,  region RU-MOW
;;
;; Revenue (for-pay, sales only):
;;   A = 3 × 800 = 2400
;;   B = 2 × 500 = 1000
;;   C = 1 × 300 =  300   total = 3700
;;
;; avg-price (finished-price, sales only):
;;   A = 3×1000/3 = 1000.0
;;   B = 2×700 /2 =  700.0
;;   C = 1×400 /1 =  400.0
;;
;; total-sales = 6, total-returns = 2
;; return-rate = percentage(2, 8) = 25.0
;; unique-skus = 3  (A, B, C — across sales AND returns)
;; ---------------------------------------------------------------------------

(def fx
  "Inline sales/returns rows — cross-article / cross-day distribution for canon assertions.
   Each row = one unit event. :type is :sale or :return, :for-pay is net per-unit payout,
   :finished-price is buyer-paid gross price."
  [;; Article A — sale 1
   {:date "2026-03-03T10:00:00" :type :sale   :article "A" :subject "shirts" :brand "B1"
    :warehouse "Koledino"   :region "RU-MOW" :for-pay 800.0 :finished-price 1000.0}
   ;; Article A — sale 2
   {:date "2026-03-03T14:00:00" :type :sale   :article "A" :subject "shirts" :brand "B1"
    :warehouse "Koledino"   :region "RU-MOW" :for-pay 800.0 :finished-price 1000.0}
   ;; Article A — sale 3 (different day)
   {:date "2026-03-05T09:00:00" :type :sale   :article "A" :subject "shirts" :brand "B1"
    :warehouse "Koledino"   :region "RU-MOW" :for-pay 800.0 :finished-price 1000.0}
   ;; Article A — return 1
   {:date "2026-03-05T11:00:00" :type :return :article "A" :subject "shirts" :brand "B1"
    :warehouse "Koledino"   :region "RU-MOW" :for-pay -800.0 :finished-price 1000.0}
   ;; Article B — sale 1
   {:date "2026-03-04T08:00:00" :type :sale   :article "B" :subject "pants"  :brand "B2"
    :warehouse "Elektrostal" :region "RU-SPB" :for-pay 500.0 :finished-price 700.0}
   ;; Article B — sale 2
   {:date "2026-03-04T16:00:00" :type :sale   :article "B" :subject "pants"  :brand "B2"
    :warehouse "Elektrostal" :region "RU-SPB" :for-pay 500.0 :finished-price 700.0}
   ;; Article C — sale 1
   {:date "2026-03-05T12:00:00" :type :sale   :article "C" :subject "hats"   :brand "B1"
    :warehouse "Koledino"   :region "RU-MOW" :for-pay 300.0 :finished-price 400.0}
   ;; Article C — return 1
   {:date "2026-03-05T15:00:00" :type :return :article "C" :subject "hats"   :brand "B1"
    :warehouse "Koledino"   :region "RU-MOW" :for-pay -300.0 :finished-price 400.0}])

(defn- find-group [groups key]
  (first (filter #(= key (:group %)) groups)))

;; ---------------------------------------------------------------------------
;; Sales.1 — by-day dimension rollup
;; ---------------------------------------------------------------------------

(deftest by-day-groups-and-sums
  ;; 3 distinct days: 2026-03-03 (2 sales A), 2026-03-04 (2 sales B), 2026-03-05 (1 sale A + 1 sale C + 2 returns)
  (let [groups (sales/by-day fx)]
    (testing "produces 3 day groups"
      (is (= 3 (count groups))))

    (let [d03 (find-group groups "2026-03-03")]
      (testing "2026-03-03: sales-count = 2"
        (is (= 2 (:sales-count d03))))
      (testing "2026-03-03: returns-count = 0"
        (is (= 0 (:returns-count d03))))
      (testing "2026-03-03: revenue = 2×800 = 1600.0"
        (is (= 1600.0 (:revenue d03))))
      (testing "2026-03-03: avg-price = 2×1000/2 = 1000.0"
        (is (= 1000.0 (:avg-price d03)))))

    (let [d04 (find-group groups "2026-03-04")]
      (testing "2026-03-04: sales-count = 2"
        (is (= 2 (:sales-count d04))))
      (testing "2026-03-04: returns-count = 0"
        (is (= 0 (:returns-count d04))))
      (testing "2026-03-04: revenue = 2×500 = 1000.0"
        (is (= 1000.0 (:revenue d04))))
      (testing "2026-03-04: avg-price = 2×700/2 = 700.0"
        (is (= 700.0 (:avg-price d04)))))

    (let [d05 (find-group groups "2026-03-05")]
      (testing "2026-03-05: sales-count = 2 (A + C)"
        (is (= 2 (:sales-count d05))))
      (testing "2026-03-05: returns-count = 2 (A + C)"
        (is (= 2 (:returns-count d05))))
      (testing "2026-03-05: revenue = 800 + 300 = 1100.0"
        (is (= 1100.0 (:revenue d05)))))

    (testing "sorted by revenue desc: 2026-03-03 first (1600), then 2026-03-05 (1100), then 2026-03-04 (1000)"
      (is (= ["2026-03-03" "2026-03-05" "2026-03-04"] (mapv :group groups))))))

;; ---------------------------------------------------------------------------
;; Sales.1 — by-article dimension rollup
;; ---------------------------------------------------------------------------

(deftest by-article-groups-and-sums
  (let [groups (sales/by-article fx)]
    (testing "produces 3 article groups"
      (is (= 3 (count groups))))

    (let [a (find-group groups "A")]
      (testing "A sales-count = 3"
        (is (= 3 (:sales-count a))))
      (testing "A returns-count = 1"
        (is (= 1 (:returns-count a))))
      (testing "A revenue = 3×800 = 2400.0"
        (is (= 2400.0 (:revenue a))))
      (testing "A avg-price = 3×1000/3 = 1000.0"
        (is (= 1000.0 (:avg-price a)))))

    (let [b (find-group groups "B")]
      (testing "B sales-count = 2"
        (is (= 2 (:sales-count b))))
      (testing "B returns-count = 0"
        (is (= 0 (:returns-count b))))
      (testing "B revenue = 2×500 = 1000.0"
        (is (= 1000.0 (:revenue b)))))

    (let [c (find-group groups "C")]
      (testing "C sales-count = 1"
        (is (= 1 (:sales-count c))))
      (testing "C returns-count = 1"
        (is (= 1 (:returns-count c))))
      (testing "C revenue = 1×300 = 300.0"
        (is (= 300.0 (:revenue c)))))

    (testing "sorted desc by revenue: A(2400) B(1000) C(300)"
      (is (= ["A" "B" "C"] (mapv :group groups))))))

;; ---------------------------------------------------------------------------
;; Sales.1 — by-category dimension rollup
;; ---------------------------------------------------------------------------

(deftest by-category-groups-and-sums
  ;; shirts=A(2400), pants=B(1000), hats=C(300)
  (let [groups (sales/by-category fx)]
    (testing "produces 3 category groups"
      (is (= 3 (count groups))))

    (let [shirts (find-group groups "shirts")]
      (testing "shirts sales-count = 3"
        (is (= 3 (:sales-count shirts))))
      (testing "shirts returns-count = 1"
        (is (= 1 (:returns-count shirts))))
      (testing "shirts revenue = 2400.0"
        (is (= 2400.0 (:revenue shirts)))))

    (let [pants (find-group groups "pants")]
      (testing "pants sales-count = 2"
        (is (= 2 (:sales-count pants))))
      (testing "pants revenue = 1000.0"
        (is (= 1000.0 (:revenue pants)))))

    (testing "sorted desc by revenue: shirts > pants > hats"
      (is (= ["shirts" "pants" "hats"] (mapv :group groups))))))

;; ---------------------------------------------------------------------------
;; Sales.4 — totals period rollup
;; ---------------------------------------------------------------------------

(deftest totals-period-rollup
  (let [t (sales/totals fx)]
    (testing "total-sales = 6"
      (is (= 6 (:total-sales t))))
    (testing "total-returns = 2"
      (is (= 2 (:total-returns t))))
    (testing "total-revenue = 2400 + 1000 + 300 = 3700.0"
      (is (= 3700.0 (:total-revenue t))))
    (testing "avg-price = (3×1000 + 2×700 + 1×400) / 6 = (3000+1400+400)/6 = 4800/6 = 800.0"
      (is (= 800.0 (:avg-price t))))
    (testing "return-rate = percentage(2, 8) = 25.0"
      (is (= 25.0 (:return-rate t))))
    (testing "unique-skus = 3 (A B C across sales and returns)"
      (is (= 3 (:unique-skus t))))))

;; ---------------------------------------------------------------------------
;; Sales.3 — returns do NOT reduce revenue
;; ---------------------------------------------------------------------------

(deftest returns-do-not-reduce-revenue
  ;; Add an extra return row with negative :for-pay to the fixture.
  ;; Revenue must remain SUM of :sale rows only — the return must be excluded.
  (let [extra-return {:date "2026-03-06T10:00:00" :type :return :article "A"
                      :subject "shirts" :brand "B1" :warehouse "Koledino"
                      :region "RU-MOW" :for-pay -800.0 :finished-price 1000.0}
        data (conj fx extra-return)
        t    (sales/totals data)]
    (testing "revenue unchanged with extra return — still 3700.0"
      (is (= 3700.0 (:total-revenue t))))
    (testing "returns-count increases by 1 to 3"
      (is (= 3 (:total-returns t))))
    (testing "sales-count unchanged = 6"
      (is (= 6 (:total-sales t))))))

;; ---------------------------------------------------------------------------
;; Sales.2 — avg-price uses :finished-price, not :for-pay
;; ---------------------------------------------------------------------------

(deftest avg-price-uses-finished-not-forpay
  ;; One article with different finished-price and for-pay values.
  ;; avg-price must equal finished-price / count, NOT for-pay / count.
  (let [data [{:date "2026-03-10T10:00:00" :type :sale :article "Z"
               :subject "socks" :brand "BZ" :warehouse "X" :region "Y"
               :for-pay 600.0 :finished-price 900.0}
              {:date "2026-03-10T11:00:00" :type :sale :article "Z"
               :subject "socks" :brand "BZ" :warehouse "X" :region "Y"
               :for-pay 600.0 :finished-price 900.0}]
        groups (sales/by-article data)
        z      (find-group groups "Z")]
    (testing "revenue uses for-pay: 2×600 = 1200.0"
      (is (= 1200.0 (:revenue z))))
    (testing "avg-price uses finished-price: 2×900/2 = 900.0 (NOT 600)"
      (is (= 900.0 (:avg-price z))))
    (testing "avg-price does NOT equal for-pay average (600.0)"
      (is (not= 600.0 (:avg-price z))))))

;; ---------------------------------------------------------------------------
;; Sales.4 — empty input → zero totals
;; ---------------------------------------------------------------------------

(deftest group-reconcile-empty
  (let [t (sales/totals [])]
    (testing "total-sales = 0"
      (is (= 0 (:total-sales t))))
    (testing "total-returns = 0"
      (is (= 0 (:total-returns t))))
    (testing "total-revenue = 0.0"
      (is (= 0.0 (:total-revenue t))))
    (testing "avg-price = 0.0 (safe-div guard returns 0.0 on zero denominator)"
      (is (= 0.0 (double (:avg-price t)))))
    (testing "return-rate = nil (math/percentage 0/0)"
      (is (nil? (:return-rate t))))
    (testing "unique-skus = 0"
      (is (= 0 (:unique-skus t)))))

  (testing "by-day on empty → empty vector"
    (is (= [] (sales/by-day []))))
  (testing "by-article on empty → empty vector"
    (is (= [] (sales/by-article [])))))
