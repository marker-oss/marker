(ns analitica.domain.geography-canon-test
  "Per-metric-group tests anchored to canonical-formulas.md §Geography.

   Every deftest maps to one Geography.N block in the canon. If canon changes,
   this file changes in lockstep.

   Data model note: `region_sales` rows carry BOTH DB-shape keys (:region
   :city :qty :sum-price) and WB-API camelCase keys (:regionName :cityName
   :saleItemInvoiceQty :saleInvoiceCostPrice). The dual-key-read in by-region
   and by-city accepts both shapes via (or ...) fallbacks — see §Geography.1/.2.

   No DB or API calls are made. Fixtures are pure in-memory vectors."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.geography :as geo]
            [analitica.util.math :as math]))

;; ---------------------------------------------------------------------------
;; Fixtures
;;
;; Three regions:
;;   "Центральный"    — 2 cities: "Москва" (qty=10, sum=5000.0)
;;                                "Тула"   (qty=3,  sum=900.0)
;;                      region total: qty=13, sum=5900.0
;;
;;   "Северо-Западный" — 1 city: "Санкт-Петербург" (qty=8, sum=3200.0)
;;                       region total: qty=8, sum=3200.0
;;
;;   "Южный"          — 1 city: "Краснодар" (qty=5, sum=1500.0)
;;                      region total: qty=5, sum=1500.0
;;
;; Sort by :sum desc → Центральный (5900) > Северо-Западный (3200) > Южный (1500)
;; ---------------------------------------------------------------------------

(def fx-db
  "DB-shape rows: snake_case-ish Clojure keys (:region :city :qty :sum-price)."
  [{:region "Центральный"    :city "Москва"           :qty 10 :sum-price 5000.0}
   {:region "Центральный"    :city "Тула"             :qty  3 :sum-price  900.0}
   {:region "Северо-Западный" :city "Санкт-Петербург" :qty  8 :sum-price 3200.0}
   {:region "Южный"          :city "Краснодар"        :qty  5 :sum-price 1500.0}])

(def fx-api
  "WB-API-shape rows: camelCase keys (:regionName :cityName :saleItemInvoiceQty :saleInvoiceCostPrice)."
  [{:regionName "Центральный"    :cityName "Москва"           :saleItemInvoiceQty 10 :saleInvoiceCostPrice 5000.0}
   {:regionName "Центральный"    :cityName "Тула"             :saleItemInvoiceQty  3 :saleInvoiceCostPrice  900.0}
   {:regionName "Северо-Западный" :cityName "Санкт-Петербург" :saleItemInvoiceQty  8 :saleInvoiceCostPrice 3200.0}
   {:regionName "Южный"          :cityName "Краснодар"        :saleItemInvoiceQty  5 :saleInvoiceCostPrice 1500.0}])

(def fx-mixed
  "Mixed-shape rows: some rows carry DB keys, others carry API (camelCase) keys."
  [;; DB-shape row for Центральный/Москва
   {:region "Центральный"    :city "Москва"           :qty 10 :sum-price 5000.0}
   ;; API-shape row for Центральный/Тула
   {:regionName "Центральный"    :cityName "Тула"     :saleItemInvoiceQty 3 :saleInvoiceCostPrice 900.0}
   ;; DB-shape row for Северо-Западный
   {:region "Северо-Западный" :city "Санкт-Петербург" :qty 8 :sum-price 3200.0}
   ;; API-shape row for Южный
   {:regionName "Южный"       :cityName "Краснодар"   :saleItemInvoiceQty 5 :saleInvoiceCostPrice 1500.0}])

(defn- find-region [rows region]
  (first (filter #(= region (:region %)) rows)))

(defn- find-city [rows city]
  (first (filter #(= city (:city %)) rows)))

;; ---------------------------------------------------------------------------
;; Geography.1 — by-region aggregates DB-shape rows
;; ---------------------------------------------------------------------------

(deftest by-region-aggregates-db-shape
  (let [rows (geo/by-region fx-db)]

    (testing "produces 3 region rows"
      (is (= 3 (count rows))))

    (testing "Центральный: qty=13, sum=5900.0"
      (let [r (find-region rows "Центральный")]
        (is (= 13 (:qty r)))
        (is (= 5900.0 (:sum r)))))

    (testing "Северо-Западный: qty=8, sum=3200.0"
      (let [r (find-region rows "Северо-Западный")]
        (is (= 8 (:qty r)))
        (is (= 3200.0 (:sum r)))))

    (testing "Южный: qty=5, sum=1500.0"
      (let [r (find-region rows "Южный")]
        (is (= 5 (:qty r)))
        (is (= 1500.0 (:sum r)))))))

;; ---------------------------------------------------------------------------
;; Geography.1 — by-region aggregates WB-API-shape (camelCase) rows
;; ---------------------------------------------------------------------------

(deftest by-region-aggregates-api-shape
  ;; Uses :regionName / :saleItemInvoiceQty / :saleInvoiceCostPrice fallback.
  ;; Output key is still :region (by-region extracts the group key as the map value).
  (let [rows (geo/by-region fx-api)]

    (testing "produces 3 region rows"
      (is (= 3 (count rows))))

    (testing "Центральный: qty=13, sum=5900.0 (via camelCase fallback)"
      ;; by-region groups by (or :region :regionName) → key is "Центральный"
      ;; result row has :region "Центральный"
      (let [r (find-region rows "Центральный")]
        (is (= 13 (:qty r)))
        (is (= 5900.0 (:sum r)))))

    (testing "Северо-Западный: qty=8, sum=3200.0"
      (let [r (find-region rows "Северо-Западный")]
        (is (= 8 (:qty r)))
        (is (= 3200.0 (:sum r)))))

    (testing "Южный: qty=5, sum=1500.0"
      (let [r (find-region rows "Южный")]
        (is (= 5 (:qty r)))
        (is (= 1500.0 (:sum r)))))))

;; ---------------------------------------------------------------------------
;; Geography.1 — by-region aggregates mixed-shape rows (DB + API keys together)
;; ---------------------------------------------------------------------------

(deftest by-region-aggregates-mixed-shape
  (let [rows (geo/by-region fx-mixed)]

    (testing "produces 3 region rows even with mixed key shapes"
      (is (= 3 (count rows))))

    (testing "Центральный merges DB row (Москва) + API row (Тула): qty=13, sum=5900.0"
      (let [r (find-region rows "Центральный")]
        (is (= 13 (:qty r)))
        (is (= 5900.0 (:sum r)))))

    (testing "Северо-Западный (DB shape): qty=8, sum=3200.0"
      (let [r (find-region rows "Северо-Западный")]
        (is (= 8 (:qty r)))
        (is (= 3200.0 (:sum r)))))

    (testing "Южный (API shape): qty=5, sum=1500.0"
      (let [r (find-region rows "Южный")]
        (is (= 5 (:qty r)))
        (is (= 1500.0 (:sum r)))))))

;; ---------------------------------------------------------------------------
;; Geography.1 — sort descending by :sum
;; ---------------------------------------------------------------------------

(deftest by-region-sorts-by-sum-desc
  (let [rows (geo/by-region fx-db)]

    (testing "first row has highest :sum (Центральный = 5900.0)"
      (is (= "Центральный" (:region (first rows)))))

    (testing "second row is Северо-Западный (3200.0)"
      (is (= "Северо-Западный" (:region (second rows)))))

    (testing "last row has lowest :sum (Южный = 1500.0)"
      (is (= "Южный" (:region (last rows)))))

    (testing "sums are strictly descending"
      (let [sums (map :sum rows)]
        (is (= sums (sort > sums)))))))

;; ---------------------------------------------------------------------------
;; Geography.2 — by-city aggregates with correct :qty / :sum per city
;; ---------------------------------------------------------------------------

(deftest by-city-aggregates
  (let [rows (geo/by-city fx-db)]

    (testing "produces 4 city rows (one per distinct city)"
      (is (= 4 (count rows))))

    (testing "Москва: qty=10, sum=5000.0"
      (let [r (find-city rows "Москва")]
        (is (= 10 (:qty r)))
        (is (= 5000.0 (:sum r)))))

    (testing "Тула: qty=3, sum=900.0"
      (let [r (find-city rows "Тула")]
        (is (= 3 (:qty r)))
        (is (= 900.0 (:sum r)))))

    (testing "Санкт-Петербург: qty=8, sum=3200.0"
      (let [r (find-city rows "Санкт-Петербург")]
        (is (= 8 (:qty r)))
        (is (= 3200.0 (:sum r)))))

    (testing "Краснодар: qty=5, sum=1500.0"
      (let [r (find-city rows "Краснодар")]
        (is (= 5 (:qty r)))
        (is (= 1500.0 (:sum r)))))

    (testing "cities sorted descending by :sum — first city is Москва (5000.0)"
      (is (= "Москва" (:city (first rows)))))))

;; ---------------------------------------------------------------------------
;; Geography.1 — :sum rounded to 2 decimal places via math/round2
;; ---------------------------------------------------------------------------

(deftest sum-rounded-to-2dp
  ;; Row whose raw sum is 12.345 — round2 must yield 12.35 (half-up).
  (let [rows (geo/by-region [{:region "Тест" :qty 1 :sum-price 12.345}])]

    (testing "single-region result"
      (is (= 1 (count rows))))

    (testing ":sum is rounded to 2 decimal places (12.345 → 12.35)"
      (let [r (first rows)]
        (is (= 12.35 (:sum r)))
        ;; Cross-check that math/round2 was applied (not truncated to 12.34)
        (is (= (:sum r) (math/round2 12.345)))))))

;; ---------------------------------------------------------------------------
;; Geography.1/.2 edge case — empty input returns empty seq
;; ---------------------------------------------------------------------------

(deftest empty-data-returns-empty-seq
  (testing "by-region on [] returns empty"
    (is (empty? (geo/by-region []))))

  (testing "by-city on [] returns empty"
    (is (empty? (geo/by-city [])))))
