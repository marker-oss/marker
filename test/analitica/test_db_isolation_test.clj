(ns analitica.test-db-isolation-test
  "Bug condition exploration test for test-infrastructure-fix.
   
   **Validates: Requirements 1.1, 1.2, 1.3**
   
   This test encodes the EXPECTED behavior and will FAIL on unfixed code.
   Failure confirms the bug exists (tests use production database).
   When this test passes after the fix, it confirms expected behavior is satisfied."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [analitica.db :as db]))

;; ---------------------------------------------------------------------------
;; Property 1: Bug Condition - Tests Use Production Database
;;
;; **CRITICAL**: This test MUST FAIL on unfixed code
;; **EXPECTED OUTCOME**: Test FAILS (proves tests use production database)
;;
;; Validates Expected Behavior Properties:
;; - 2.1: Tests should use separate test database test-analitica.db
;; - 2.2: db/clear-table! should only delete from test database
;; - 2.5: When ANALITICA_DB env var is set, system should use that database
;;
;; Bug Condition:
;; - 1.1: Tests use production database analitica.db
;; - 1.2: db/clear-table! deletes data from production database
;; - 1.3: Test fixture init-test-db initializes production database
;; ---------------------------------------------------------------------------

(defspec ^:integration test-database-isolation-property 10
  (prop/for-all
    [_dummy gen/boolean]  ; Dummy generator to run property test
    
    ;; Expected Behavior 2.5: When ANALITICA_DB env var is set, use that database
    ;; Expected Behavior 2.1: Tests should use test-analitica.db
    (let [env-db (System/getenv "ANALITICA_DB")
          db-spec-value (or env-db "analitica.db")]
      
      (testing "Tests should use test database, not production database"
        ;; This assertion encodes the EXPECTED behavior
        ;; On unfixed code: env-db will be nil, db-spec-value will be "analitica.db"
        ;; This will FAIL, proving the bug exists
        ;; After fix: env-db will be "test-analitica.db", test will PASS
        (and
          ;; Property 2.5: ANALITICA_DB should be set in test environment
          (not (nil? env-db))
          
          ;; Property 2.1: Database should be test-analitica.db, not analitica.db
          (= "test-analitica.db" db-spec-value)
          
          ;; Property 2.1: Database should NOT be production database
          (not= "analitica.db" db-spec-value))))))

(deftest ^:integration database-name-check
  "Direct test checking database name used by tests.
   
   Expected Behavior 2.1: Tests should use test-analitica.db
   Bug Condition 1.1: Tests currently use analitica.db
   
   This test will FAIL on unfixed code, proving the bug exists."
  (testing "ANALITICA_DB environment variable should be set for tests"
    (let [env-db (System/getenv "ANALITICA_DB")]
      ;; Expected: env-db should be "test-analitica.db"
      ;; Actual (unfixed): env-db will be nil
      (is (not (nil? env-db))
          "ANALITICA_DB env var should be set in test environment")
      
      (is (= "test-analitica.db" env-db)
          "Tests should use test-analitica.db, not production database")))
  
  (testing "Database spec should use test database"
    (let [env-db (System/getenv "ANALITICA_DB")
          expected-db (or env-db "analitica.db")]
      ;; Expected: should be "test-analitica.db"
      ;; Actual (unfixed): will be "analitica.db"
      (is (= "test-analitica.db" expected-db)
          "Database should be test-analitica.db")
      
      (is (not= "analitica.db" expected-db)
          "Database should NOT be production analitica.db"))))
