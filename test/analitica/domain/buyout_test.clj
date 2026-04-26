(ns analitica.domain.buyout-test
  "Tests for §Buyout.7 — marketplace scoping and orders-aware true-buyout
   metrics. The §Buyout.1 sales-only assertions live in
   `buyout_canon_test.clj`; this file covers the kwargs added on top."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.buyout :as buyout]
            [analitica.domain.sales :as sales]))

(def ^:private fx-mixed
  "Sales fixture spanning two MPs. Same article 'X' on both — exercises the
   marketplace-scoping kwarg without confounding by article diversity."
  [{:type :sale   :article "X" :marketplace :wb}
   {:type :sale   :article "X" :marketplace :wb}
   {:type :sale   :article "X" :marketplace :wb}
   {:type :return :article "X" :marketplace :wb}
   {:type :sale   :article "X" :marketplace :ozon}
   {:type :return :article "X" :marketplace :ozon}
   {:type :return :article "X" :marketplace :ozon}])

(defn- only-mp
  "Stub that mimics the real `sales/fetch-sales` :marketplace filter."
  [period & {:keys [marketplace]}]
  (if marketplace
    (filterv #(= marketplace (:marketplace %)) fx-mixed)
    fx-mixed))

(deftest marketplace-kwarg-scopes-fetch
  (testing "fetch-sales receives :marketplace and resulting counts are MP-scoped"
    (with-redefs [sales/fetch-sales only-mp]
      (let [wb   (first (buyout/analyze [:last-7-days] :marketplace :wb))
            ozon (first (buyout/analyze [:last-7-days] :marketplace :ozon))
            all  (first (buyout/analyze [:last-7-days]))]

        (testing "WB-only view: 3 sold, 1 returned"
          (is (= 3 (:bought wb)))
          (is (= 1 (:returned wb)))
          (is (= 75.0 (:buyout-rate wb))))

        (testing "Ozon-only view: 1 sold, 2 returned"
          (is (= 1 (:bought ozon)))
          (is (= 2 (:returned ozon)))
          (is (= 33.33 (:buyout-rate ozon))))

        (testing "All-MP view: 4 sold, 3 returned"
          (is (= 4 (:bought all)))
          (is (= 3 (:returned all))))))))

;; ---------------------------------------------------------------------------
;; §Buyout.7 — orders-by-article enables :true-buyout-rate
;; ---------------------------------------------------------------------------

(def ^:private fx-orders
  "Two articles with sold/returned counts known. :placed > :ordered for both —
   that is the whole point: many orders get cancelled before they ever
   become a `sales` row."
  [{:type :sale   :article "A"}
   {:type :sale   :article "A"}
   {:type :sale   :article "A"}     ; sold = 3
   {:type :return :article "A"}      ; returned = 1
   {:type :sale   :article "B"}     ; sold = 1
   {:type :return :article "B"}      ; returned = 1
   {:type :return :article "B"}])    ; returned = 2

(def ^:private orders-map
  {"A" {:placed 10 :cancelled 6}      ; 3 sold + 1 returned + 6 cancelled = 10 ✓
   "B" {:placed 5  :cancelled 2}})    ; 1 sold + 2 returned + 2 cancelled = 5 ✓

(deftest orders-by-article-adds-true-buyout-rate
  (with-redefs [sales/fetch-sales (fn [_period & _opts] fx-orders)]
    (let [rows (buyout/analyze [:last-7-days] :orders-by-article orders-map)
          a    (first (filter #(= "A" (:article %)) rows))
          b    (first (filter #(= "B" (:article %)) rows))]

      (testing "Article A: legacy 75% (3/4) vs true 30% (3/10) — accounts for cancellations"
        (is (= 75.0 (:buyout-rate a)))
        (is (= 10   (:placed a)))
        (is (= 6    (:cancelled a)))
        (is (= 30.0 (:true-buyout-rate a)))
        (is (= 60.0 (:cancel-rate a))))

      (testing "Article B: legacy ~33% vs true 20% (1/5)"
        (is (= 33.33 (:buyout-rate b)))
        (is (= 5     (:placed b)))
        (is (= 20.0  (:true-buyout-rate b)))
        (is (= 40.0  (:cancel-rate b)))))))

(deftest no-orders-map-keeps-legacy-shape
  (testing "Without :orders-by-article, rows do NOT carry the §Buyout.7 keys."
    (with-redefs [sales/fetch-sales (fn [_period & _opts] fx-orders)]
      (let [rows (buyout/analyze [:last-7-days])
            a    (first (filter #(= "A" (:article %)) rows))]
        (is (= #{:article :subject :ordered :bought :returned :buyout-rate}
               (set (keys a)))
            "Backward-compatible: callers that don't pass orders see the same shape as before.")))))

(deftest article-without-orders-data-omits-true-rate
  (testing "Article missing from orders-map: legacy fields populated, §Buyout.7 fields absent."
    (with-redefs [sales/fetch-sales (fn [_period & _opts]
                                      [{:type :sale :article "C"}
                                       {:type :sale :article "C"}])]
      (let [rows (buyout/analyze [:last-7-days] :orders-by-article {})
            c    (first rows)]
        (is (= 2 (:bought c)))
        (is (= 100.0 (:buyout-rate c)))
        (is (nil? (:true-buyout-rate c))
            "Cannot compute true rate without order count — must not silently default to 0 or 100.")
        (is (nil? (:placed c)))))))
