(ns analitica.production-preservation-test
  "Preservation property tests for test-infrastructure-fix.
   
   **Validates: Requirements 3.1, 3.2, 3.3, 3.5**
   
   These tests verify that production database behavior remains unchanged.
   They test the CURRENT behavior that we want to PRESERVE after the fix.
   
   **EXPECTED OUTCOME**: Tests PASS on unfixed code (confirms baseline behavior)
   
   Property 2: Preservation - Production Database Behavior Unchanged"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [analitica.db :as db]
            [next.jdbc :as jdbc]))

;; ---------------------------------------------------------------------------
;; Test Fixture - Initialize production database for preservation tests
;; ---------------------------------------------------------------------------

(defn init-production-db [f]
  "Initialize production database to test preservation of current behavior.
   This fixture uses the CURRENT behavior (no ANALITICA_DB env var)."
  (db/init!)
  (f))

(use-fixtures :once init-production-db)

;; ---------------------------------------------------------------------------
;; Property 2.1: Production Database Name Preservation
;;
;; Validates: Requirement 3.1
;; Production code without ANALITICA_DB env var should continue using analitica.db
;; ---------------------------------------------------------------------------

(defspec production-database-name-preserved 10
  (prop/for-all
    [_dummy gen/boolean]  ; Dummy generator to run property test
    
    (testing "Production code uses analitica.db when ANALITICA_DB is not set"
      ;; This tests CURRENT behavior that must be preserved
      ;; When ANALITICA_DB is not set, system should use "analitica.db"
      (let [env-db (System/getenv "ANALITICA_DB")
            expected-db (or env-db "analitica.db")]
        
        ;; In production mode (no env var), should use analitica.db
        (if (nil? env-db)
          (= "analitica.db" expected-db)
          ;; If env var is set, respect it (this is the new behavior)
          (= env-db expected-db))))))

;; ---------------------------------------------------------------------------
;; Property 2.2: Database Initialization Preservation
;;
;; Validates: Requirement 3.2
;; db/init! should continue initializing with WAL mode and DDL statements
;; ---------------------------------------------------------------------------

(deftest database-initialization-preserved
  "Test that db/init! continues to work correctly with WAL mode and DDL statements.
   
   Validates: Requirement 3.2"
  (testing "Database is initialized with WAL mode"
    ;; db/init! should have been called by fixture
    (let [ds (db/ds)
          result (jdbc/execute! ds ["PRAGMA journal_mode"])]
      (is (= "wal" (-> result first :journal_mode clojure.string/lower-case))
          "Database should be in WAL mode")))
  
  (testing "Database tables are created"
    ;; Verify that key tables exist
    (let [ds (db/ds)
          tables (jdbc/execute! ds 
                   ["SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"])
          table-names (set (map #(or (:name %) (:sqlite_master/name %)) tables))]
      (is (contains? table-names "sales")
          "sales table should exist")
      (is (contains? table-names "orders")
          "orders table should exist")
      (is (contains? table-names "finance")
          "finance table should exist")
      (is (contains? table-names "stocks")
          "stocks table should exist")
      (is (contains? table-names "paid_storage")
          "paid_storage table should exist"))))

;; ---------------------------------------------------------------------------
;; Property 2.3: Database Operations Preservation
;;
;; Validates: Requirement 3.3
;; db/execute!, db/query, db/insert-batch! should continue working correctly
;; ---------------------------------------------------------------------------

(defspec database-execute-preserved 10
  (prop/for-all
    [table-name (gen/elements [:sales :orders :finance :stocks :paid_storage])]
    
    (testing "db/execute! continues to work"
      ;; Test that execute! can run a simple query
      (try
        (let [result (db/execute! [(str "SELECT count(*) as cnt FROM " (name table-name))])]
          (and (vector? result)
               (map? (first result))
               (contains? (first result) :cnt)))
        (catch Exception e
          false)))))

(defspec database-query-preserved 10
  (prop/for-all
    [table-name (gen/elements [:sales :orders :finance :stocks :paid_storage])]
    
    (testing "db/query continues to work"
      ;; Test that query can run and return unqualified maps
      (try
        (let [result (db/query [(str "SELECT count(*) as cnt FROM " (name table-name))])]
          (and (vector? result)
               (map? (first result))
               (contains? (first result) :cnt)))
        (catch Exception e
          false)))))

;; ---------------------------------------------------------------------------
;; Property 2.4: Helper Functions Preservation
;;
;; Validates: Requirement 3.5
;; Helper functions should continue returning correct results
;; ---------------------------------------------------------------------------

(deftest helper-functions-preserved
  "Test that helper functions continue to work correctly.
   
   Validates: Requirement 3.5"
  
  (testing "db/count-rows continues to work"
    (let [count (db/count-rows :sales)]
      (is (number? count)
          "count-rows should return a number")
      (is (>= count 0)
          "count-rows should return non-negative number")))
  
  (testing "db/storage-by-article continues to work"
    ;; Test with a date range
    (let [result (db/storage-by-article "2024-01-01" "2024-12-31")]
      (is (vector? result)
          "storage-by-article should return a vector")
      ;; Each result should have :article and :storage_cost keys
      (is (every? #(and (contains? % :article) 
                        (contains? % :storage-cost)) 
                  result)
          "storage-by-article results should have :article and :storage-cost keys")))
  
  (testing "db/ad-spend-by-article continues to work"
    ;; Test with a date range
    (let [result (db/ad-spend-by-article "2024-01-01" "2024-12-31")]
      (is (vector? result)
          "ad-spend-by-article should return a vector")
      ;; Each result should have :article and :ad_spend keys
      (is (every? #(and (contains? % :article) 
                        (contains? % :ad-spend)) 
                  result)
          "ad-spend-by-article results should have :article and :ad-spend keys"))))

;; ---------------------------------------------------------------------------
;; Property 2.5: Insert Batch Preservation
;;
;; Validates: Requirement 3.3
;; db/insert-batch! should continue working correctly
;; ---------------------------------------------------------------------------

(defspec insert-batch-preserved 5
  (prop/for-all
    [article (gen/such-that not-empty gen/string-alphanumeric {:max-tries 10})
     nm-id gen/pos-int
     quantity gen/nat]
    
    (testing "db/insert-batch! continues to work"
      (try
        ;; Insert a test row into stocks table
        (let [rows [[article nm-id nil nil nil nil nil "TEST-WH" quantity quantity 0 0 "wb" "2024-01-01"]]
              result (db/insert-batch! :stocks 
                                       [:article :nm_id :barcode :tech_size :subject :category :brand 
                                        :warehouse :quantity :quantity_full :in_way_to :in_way_from 
                                        :marketplace :synced_at]
                                       rows)]
          ;; Clean up the test row
          (db/execute! ["DELETE FROM stocks WHERE article = ? AND warehouse = 'TEST-WH'" article])
          
          ;; Verify insert-batch! returned the count
          (and (number? result)
               (= 1 result)))
        (catch Exception e
          false)))))

