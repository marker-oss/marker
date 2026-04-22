(ns analitica.web.api.metrics-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.web.api.metrics :as metrics]))

;; ---------------------------------------------------------------------------
;; Test Fixtures
;; ---------------------------------------------------------------------------

(defn init-test-db [f]
  (db/init!)
  (f))

(use-fixtures :once init-test-db)

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest ^:integration sync-coverage-test
  (testing "sync-coverage returns a map with expected structure"
    (let [coverage (metrics/sync-coverage)]
      ;; Should return a map
      (is (map? coverage))
      
      ;; Should have marketplace keys
      (is (contains? coverage :wb))
      (is (contains? coverage :ozon))
      (is (contains? coverage :ym))
      
      ;; Should have non-marketplace keys
      (is (contains? coverage :stats))
      (is (contains? coverage :regions))
      (is (contains? coverage :1c))
      (is (contains? coverage :prices))
      
      ;; Each marketplace should have data type keys
      (let [wb-data (:wb coverage)]
        (is (map? wb-data))
        (is (contains? wb-data :sales))
        (is (contains? wb-data :orders))
        (is (contains? wb-data :finance))
        (is (contains? wb-data :storage))
        (is (contains? wb-data :stocks)))
      
      ;; Coverage data should be nil or have :from, :to, :days
      (let [sales-coverage (get-in coverage [:wb :sales])]
        (when sales-coverage
          (is (contains? sales-coverage :from))
          (is (contains? sales-coverage :to))
          (is (contains? sales-coverage :days))
          (is (string? (:from sales-coverage)))
          (is (string? (:to sales-coverage)))
          (is (number? (:days sales-coverage))))))))

(deftest ^:integration sync-coverage-empty-db-test
  (testing "sync-coverage handles empty database gracefully"
    ;; Clear all tables
    (db/clear-table! :sales)
    (db/clear-table! :orders)
    (db/clear-table! :finance)
    (db/clear-table! :paid_storage)
    (db/clear-table! :stocks)
    (db/clear-table! :product_stats)
    (db/clear-table! :region_sales)
    (db/clear-table! :cost_prices)
    (db/clear-table! :prices)
    
    (let [coverage (metrics/sync-coverage)]
      ;; Should still return a map with all keys
      (is (map? coverage))
      (is (contains? coverage :wb))
      (is (contains? coverage :ozon))
      (is (contains? coverage :ym))
      
      ;; All coverage values should be nil for empty database
      (is (nil? (get-in coverage [:wb :sales])))
      (is (nil? (get-in coverage [:wb :orders])))
      (is (nil? (get-in coverage [:ozon :finance])))
      (is (nil? (:stats coverage)))
      (is (nil? (:regions coverage)))
      (is (nil? (:1c coverage)))
      (is (nil? (:prices coverage))))))

(deftest summary-metrics-test
  (testing "summary-metrics returns expected structure"
    (let [metrics (metrics/summary-metrics :last-7-days)]
      ;; Should return a map
      (is (map? metrics))
      
      ;; Should have all required keys
      (is (contains? metrics :revenue))
      (is (contains? metrics :orders))
      (is (contains? metrics :profit))
      (is (contains? metrics :return-rate))
      (is (contains? metrics :revenue-wow))
      (is (contains? metrics :orders-wow))
      (is (contains? metrics :profit-wow))
      (is (contains? metrics :return-rate-wow))
      (is (contains? metrics :by-marketplace))
      
      ;; Numeric values should be numbers
      (is (number? (:revenue metrics)))
      (is (number? (:orders metrics)))
      (is (number? (:profit metrics)))
      (is (number? (:return-rate metrics)))
      (is (number? (:revenue-wow metrics)))
      (is (number? (:orders-wow metrics)))
      (is (number? (:profit-wow metrics)))
      (is (number? (:return-rate-wow metrics)))
      
      ;; by-marketplace should be a collection
      (is (or (nil? (:by-marketplace metrics))
              (sequential? (:by-marketplace metrics)))))))

(deftest summary-metrics-with-marketplace-test
  (testing "summary-metrics with marketplace filter"
    (let [metrics (metrics/summary-metrics :last-7-days :marketplace :wb)]
      ;; Should return a map
      (is (map? metrics))
      
      ;; Should have all required keys
      (is (contains? metrics :revenue))
      (is (contains? metrics :orders))
      (is (contains? metrics :profit))
      (is (contains? metrics :return-rate))
      
      ;; by-marketplace should be nil when marketplace is specified
      (is (nil? (:by-marketplace metrics))))))

(deftest ^:integration summary-metrics-empty-db-test
  (testing "summary-metrics handles empty database gracefully"
    ;; Clear all tables
    (db/clear-table! :sales)
    (db/clear-table! :finance)
    
    (let [metrics (metrics/summary-metrics :last-7-days)]
      ;; Should return zero values, not throw exception
      (is (map? metrics))
      (is (= 0.0 (:revenue metrics)))
      (is (= 0 (:orders metrics)))
      (is (= 0.0 (:profit metrics)))
      (is (= 0.0 (:return-rate metrics)))
      (is (= 0.0 (:revenue-wow metrics)))
      (is (= 0.0 (:orders-wow metrics)))
      (is (= 0.0 (:profit-wow metrics)))
      (is (= 0.0 (:return-rate-wow metrics))))))

(deftest marketplace-metrics-test
  (testing "marketplace-metrics returns expected structure"
    (let [metrics (metrics/marketplace-metrics :wb :last-7-days)]
      ;; Should return a map
      (is (map? metrics))
      
      ;; Should have base metrics
      (is (contains? metrics :revenue))
      (is (contains? metrics :orders))
      (is (contains? metrics :profit))
      (is (contains? metrics :return-rate))
      (is (contains? metrics :revenue-wow))
      (is (contains? metrics :orders-wow))
      (is (contains? metrics :profit-wow))
      (is (contains? metrics :return-rate-wow))
      
      ;; Should have marketplace-specific fields
      (is (contains? metrics :top-products))
      (is (contains? metrics :finance-breakdown))
      (is (contains? metrics :abc-summary))
      (is (contains? metrics :top-returns))
      
      ;; top-products should be a vector
      (is (vector? (:top-products metrics)))
      
      ;; finance-breakdown should be a map with expected keys
      (is (map? (:finance-breakdown metrics)))
      (is (contains? (:finance-breakdown metrics) :commission))
      (is (contains? (:finance-breakdown metrics) :logistics))
      (is (contains? (:finance-breakdown metrics) :storage))
      (is (contains? (:finance-breakdown metrics) :profit))
      
      ;; abc-summary should be a vector
      (is (vector? (:abc-summary metrics)))
      
      ;; top-returns should be a vector
      (is (vector? (:top-returns metrics))))))

(deftest marketplace-metrics-top-products-test
  (testing "marketplace-metrics top-products structure"
    (let [metrics (metrics/marketplace-metrics :wb :last-7-days)
          top-products (:top-products metrics)]
      ;; Should be a vector (possibly empty)
      (is (vector? top-products))
      
      ;; If not empty, each product should have expected keys
      (when (seq top-products)
        (let [product (first top-products)]
          (is (contains? product :article))
          (is (contains? product :revenue))
          (is (contains? product :orders))
          (is (number? (:revenue product)))
          (is (number? (:orders product)))))
      
      ;; Should have at most 10 products
      (is (<= (count top-products) 10)))))

(deftest marketplace-metrics-abc-summary-test
  (testing "marketplace-metrics ABC summary structure"
    (let [metrics (metrics/marketplace-metrics :wb :last-7-days)
          abc-summary (:abc-summary metrics)]
      ;; Should be a vector
      (is (vector? abc-summary))
      
      ;; If not empty, each category should have expected keys
      (when (seq abc-summary)
        (let [category (first abc-summary)]
          (is (contains? category :category))
          (is (contains? category :count))
          (is (contains? category :revenue-pct))
          (is (number? (:count category)))
          (is (number? (:revenue-pct category))))))))

(deftest marketplace-metrics-top-returns-test
  (testing "marketplace-metrics top returns structure"
    (let [metrics (metrics/marketplace-metrics :wb :last-7-days)
          top-returns (:top-returns metrics)]
      ;; Should be a vector
      (is (vector? top-returns))
      
      ;; If not empty, each return should have expected keys
      (when (seq top-returns)
        (let [return-item (first top-returns)]
          (is (contains? return-item :article))
          (is (contains? return-item :return-rate))
          (is (contains? return-item :returned))
          (is (contains? return-item :sold))
          (is (number? (:return-rate return-item)))
          (is (number? (:returned return-item)))
          (is (number? (:sold return-item)))))
      
      ;; Should have at most 10 items
      (is (<= (count top-returns) 10)))))

(deftest ^:integration marketplace-metrics-empty-db-test
  (testing "marketplace-metrics handles empty database gracefully"
    ;; Clear all tables
    (db/clear-table! :sales)
    (db/clear-table! :finance)
    
    (let [metrics (metrics/marketplace-metrics :wb :last-7-days)]
      ;; Should return zero values, not throw exception
      (is (map? metrics))
      (is (= 0.0 (:revenue metrics)))
      (is (= 0 (:orders metrics)))
      (is (= 0.0 (:profit metrics)))
      (is (= 0.0 (:return-rate metrics)))
      
      ;; Should have empty collections
      (is (= [] (:top-products metrics)))
      (is (= [] (:abc-summary metrics)))
      (is (= [] (:top-returns metrics)))
      
      ;; Should have zero finance breakdown
      (is (= {:commission 0.0 :logistics 0.0 :storage 0.0 :profit 0.0}
             (:finance-breakdown metrics))))))
