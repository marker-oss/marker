(ns analitica.web.api.search-test
  "Unit tests for the /api/search handler.
   Uses with-redefs to mock db/query — no real DB needed."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.api.search :as search]
            [analitica.db :as db]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private sample-sku-rows
  [{:article "Платье 3452/Беж"   :subject "Платья" :brand "FashionBrand"}
   {:article "Платье 3452/Чёрн"  :subject "Платья" :brand "FashionBrand"}])

;; ---------------------------------------------------------------------------
;; Empty / short query
;; ---------------------------------------------------------------------------

(deftest empty-query-returns-empty
  (testing "nil query returns {:results []}"
    (is (= {:results []} (search/search nil))))
  (testing "empty string returns {:results []}"
    (is (= {:results []} (search/search ""))))
  (testing "single-char query returns {:results []}"
    (is (= {:results []} (search/search "а")))))

;; ---------------------------------------------------------------------------
;; SKU results
;; ---------------------------------------------------------------------------

(deftest sku-match-returns-results
  (testing "query 'платье' matches mocked SKU rows"
    (with-redefs [db/query (fn [_] sample-sku-rows)]
      (let [res (:results (search/search "платье"))]
        (is (seq res) "Should return at least one result")
        (is (every? #(= :sku (:type %)) res)
            "All SKU results have :type :sku")
        (is (every? :title res)
            "All results have a :title")
        (is (every? :route res)
            "All results have a :route")
        (is (every? #(clojure.string/starts-with? (:route %) "/reports/sales?article=")
                    res)
            "Routes point to /reports/sales")))))

(deftest sku-route-url-encoded
  (testing "article with slash and Cyrillic is URL-encoded in route"
    (with-redefs [db/query (fn [_] [{:article "Платье 3452/Беж" :subject "Платья" :brand "FB"}])]
      (let [res   (:results (search/search "платье"))
            route (:route (first res))]
        (is (string? route))
        ;; The raw article "Платье 3452/Беж" must be URL-encoded — no bare Cyrillic/slash
        (is (not (re-find #"[А-яЁё]" route))
            "Route should not contain raw Cyrillic")))))

(deftest no-sku-match-empty-db
  (testing "empty DB returns no SKU results but may still return static matches"
    (with-redefs [db/query (fn [_] [])]
      (let [res (:results (search/search "ничего"))]
        (is (vector? res))
        ;; Only static matches could be present; no :sku entries
        (is (every? #(not= :sku (:type %)) res))))))

;; ---------------------------------------------------------------------------
;; Report results
;; ---------------------------------------------------------------------------

(deftest report-match-returns-report-result
  (testing "query 'юнит' returns Юнит-экономика report"
    (with-redefs [db/query (fn [_] [])]
      (let [res (:results (search/search "юнит"))]
        (is (seq res) "Should find at least one report")
        (let [reports (filter #(= :report (:type %)) res)]
          (is (seq reports) "Should have report-type results")
          (is (some #(clojure.string/includes?
                       (clojure.string/lower-case (:title %)) "юнит")
                    reports)))))))

(deftest report-match-pnl
  (testing "query 'p&l' returns P&L report"
    (with-redefs [db/query (fn [_] [])]
      (let [res     (:results (search/search "p&l"))
            reports (filter #(= :report (:type %)) res)]
        (is (some #(clojure.string/includes?
                     (clojure.string/lower-case (:title %)) "p&l")
                  reports))))))

;; ---------------------------------------------------------------------------
;; Page results
;; ---------------------------------------------------------------------------

(deftest page-match-returns-page-result
  (testing "query 'главная' returns Главная page"
    (with-redefs [db/query (fn [_] [])]
      (let [res   (:results (search/search "главная"))
            pages (filter #(= :page (:type %)) res)]
        (is (seq pages) "Should find at least one page result")
        (is (some #(= "/" (:route %)) pages)
            "Главная page route should be /")))))

(deftest page-match-sync
  (testing "query 'синхр' returns Синхронизация page"
    (with-redefs [db/query (fn [_] [])]
      (let [res   (:results (search/search "синхр"))
            pages (filter #(= :page (:type %)) res)]
        (is (seq pages))
        (is (some #(= "/sync" (:route %)) pages))))))

;; ---------------------------------------------------------------------------
;; Result shape
;; ---------------------------------------------------------------------------

(deftest result-shape
  (testing "every result has :type :title :hint :route keys"
    (with-redefs [db/query (fn [_] sample-sku-rows)]
      (let [res (:results (search/search "платье"))]
        (doseq [r res]
          (is (contains? r :type))
          (is (contains? r :title))
          (is (contains? r :hint))
          (is (contains? r :route)))))))

;; ---------------------------------------------------------------------------
;; Limit / total cap
;; ---------------------------------------------------------------------------

(deftest results-capped
  (testing "results never exceed 20 entries"
    ;; Return 15 SKU rows from mock so combined total stays bounded
    (let [big-rows (vec (for [i (range 15)]
                          {:article (str "ART-" i) :subject "Категория" :brand "Brand"}))]
      (with-redefs [db/query (fn [_] big-rows)]
        (let [res (:results (search/search "art"))]
          (is (<= (count res) 20)))))))
