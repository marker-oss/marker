(ns analitica.web.api.tax-opex-test
  "Contract tests for spec 015 US5 auto-rules HTTP API (T059).

   Tests call handlers directly (no ring.mock), using a fresh temp-file SQLite
   DB per test (same pattern as feedback-api-test / db_test with-temp-db).

   Contracts: specs/015-management-taxes-opex/contracts/tax-opex-api.md §2b.

   Run focused:
     clojure -M:test --focus analitica.web.api.tax-opex-test"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.web.api.tax-opex :as api])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Fixture: fresh temp-file SQLite DB per test
;; ---------------------------------------------------------------------------

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "tax-opex-test-" ".db"
                                   (make-array FileAttribute 0))
        f    (.toFile path)]
    (.delete f)
    (.getAbsolutePath f)))

(defn- delete-temp-db! [path]
  (doseq [suffix ["" "-shm" "-wal"]]
    (let [f (File. (str path suffix))]
      (when (.exists f) (.delete f)))))

(defn with-temp-db [f]
  (let [path      (fresh-temp-db-path)
        orig-spec (deref #'db/db-spec)]
    (try
      (alter-var-root #'db/db-spec (constantly {:dbtype "sqlite" :dbname path}))
      (db/init!)
      (f)
      (finally
        (alter-var-root #'db/db-spec (constantly orig-spec))
        (reset! @#'db/datasource nil)
        (delete-temp-db! path)))))

(use-fixtures :each with-temp-db)

;; ---------------------------------------------------------------------------
;; T059 — GET /api/v1/opex/auto-rules
;; ---------------------------------------------------------------------------

(deftest t059-get-auto-rules-returns-empty-list
  (testing "GET auto-rules with no rules returns {:rules []}"
    (let [resp (api/get-auto-rules {})]
      (is (= 200 (:status resp)))
      (is (map? (:body resp)))
      (is (contains? (:body resp) :rules))
      (is (vector? (get-in resp [:body :rules])))
      (is (empty? (get-in resp [:body :rules]))))))

(deftest t059-get-auto-rules-returns-saved-rules
  (testing "GET auto-rules after POST returns the saved rule"
    ;; Create a rule via POST
    (api/post-auto-rule {:body {:category       "rent"
                                 :amount         50000.0
                                 :marketplace    nil
                                 :cadence        :monthly
                                 :effective-from "2026-01"
                                 :effective-to   "2026-12"
                                 :note           "офис"}})
    (let [resp  (api/get-auto-rules {})
          rules (get-in resp [:body :rules])]
      (is (= 200 (:status resp)))
      (is (= 1 (count rules)))
      (let [rule (first rules)]
        (is (pos-int? (:id rule)))
        (is (= "rent" (:category rule)))
        (is (= 50000.0 (:amount rule)))
        (is (= :monthly (:cadence rule)))
        (is (= "2026-01" (:effective-from rule)))
        (is (= "2026-12" (:effective-to rule)))))))

;; ---------------------------------------------------------------------------
;; T059 — POST /api/v1/opex/auto-rules
;; ---------------------------------------------------------------------------

(deftest t059-post-auto-rule-returns-ok-with-id
  (testing "POST valid auto-rule returns {:ok true :id n}"
    (let [resp (api/post-auto-rule {:body {:category       "services"
                                            :amount         9900.0
                                            :marketplace    nil
                                            :cadence        :monthly
                                            :effective-from "2026-01"
                                            :effective-to   nil
                                            :note           "ПО"}})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :ok])))
      (is (pos-int? (get-in resp [:body :id]))))))

(deftest t059-post-auto-rule-amount-zero-returns-error
  (testing "POST auto-rule with amount <= 0 returns {:ok false :error ...}"
    (let [resp (api/post-auto-rule {:body {:category       "rent"
                                            :amount         0.0
                                            :effective-from "2026-01"}})]
      (is (= 422 (:status resp)))
      (is (false? (get-in resp [:body :ok])))
      (is (string? (get-in resp [:body :error]))))))

(deftest t059-post-auto-rule-negative-amount-returns-error
  (testing "POST auto-rule with negative amount returns {:ok false :error ...}"
    (let [resp (api/post-auto-rule {:body {:category       "rent"
                                            :amount         -100.0
                                            :effective-from "2026-01"}})]
      (is (= 422 (:status resp)))
      (is (false? (get-in resp [:body :ok]))))))

(deftest t059-post-auto-rule-invalid-effective-from-returns-error
  (testing "POST auto-rule with invalid effective-from (month 13) returns {:ok false :error ...}"
    (let [resp (api/post-auto-rule {:body {:category       "rent"
                                            :amount         50000.0
                                            :effective-from "2026-13"}})]
      (is (= 422 (:status resp)))
      (is (false? (get-in resp [:body :ok])))
      (is (string? (get-in resp [:body :error]))))))

(deftest t059-post-auto-rule-with-marketplace-string-coercion
  (testing "POST auto-rule with marketplace as string is coerced to keyword"
    (let [resp (api/post-auto-rule {:body {:category       "salary"
                                            :amount         120000.0
                                            :marketplace    "wb"
                                            :cadence        "monthly"
                                            :effective-from "2026-03"}})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :ok])))
      ;; Verify the saved rule has keyword marketplace
      (let [rules (get-in (api/get-auto-rules {}) [:body :rules])]
        (is (= 1 (count rules)))
        (is (= :wb (:marketplace (first rules))))))))

;; ---------------------------------------------------------------------------
;; T059 — DELETE /api/v1/opex/auto-rules/:id
;; ---------------------------------------------------------------------------

(deftest t059-delete-auto-rule-returns-ok
  (testing "DELETE existing auto-rule returns {:ok true}"
    ;; Create a rule first
    (let [post-resp (api/post-auto-rule {:body {:category       "rent"
                                                 :amount         50000.0
                                                 :effective-from "2026-01"}})
          rule-id   (get-in post-resp [:body :id])]
      (is (pos-int? rule-id))
      ;; Delete it
      (let [del-resp (api/delete-auto-rule {:params {:id (str rule-id)}})]
        (is (= 200 (:status del-resp)))
        (is (true? (get-in del-resp [:body :ok]))))
      ;; Verify it's gone
      (let [rules (get-in (api/get-auto-rules {}) [:body :rules])]
        (is (empty? rules) "rule must be deleted")))))

(deftest t059-delete-auto-rule-nonexistent-still-returns-ok
  (testing "DELETE non-existent auto-rule is idempotent (no-op, returns {:ok true})"
    (let [resp (api/delete-auto-rule {:params {:id "9999"}})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :ok]))))))

(deftest t059-delete-auto-rule-missing-id-returns-error
  (testing "DELETE without :id returns {:ok false :error ...}"
    (let [resp (api/delete-auto-rule {:params {}})]
      (is (= 400 (:status resp)))
      (is (false? (get-in resp [:body :ok]))))))

;; ---------------------------------------------------------------------------
;; T059 — Round-trip: POST → GET → DELETE → GET
;; ---------------------------------------------------------------------------

(deftest t059-full-crud-round-trip
  (testing "Full CRUD round-trip: create, list, delete, confirm gone"
    ;; Start empty
    (is (empty? (get-in (api/get-auto-rules {}) [:body :rules])))
    ;; Create two rules
    (let [r1 (api/post-auto-rule {:body {:category "rent"   :amount 50000.0  :effective-from "2026-01"}})
          r2 (api/post-auto-rule {:body {:category "salary" :amount 120000.0 :effective-from "2026-03"}})
          id1 (get-in r1 [:body :id])
          id2 (get-in r2 [:body :id])]
      (is (= 2 (count (get-in (api/get-auto-rules {}) [:body :rules]))))
      ;; Delete first rule
      (api/delete-auto-rule {:params {:id (str id1)}})
      (let [rules (get-in (api/get-auto-rules {}) [:body :rules])]
        (is (= 1 (count rules)))
        (is (= id2 (:id (first rules))) "only second rule remains")))))
