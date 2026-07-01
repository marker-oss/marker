(ns analitica.web.api.tax-opex-test
  "Contract tests for spec 015 US5 auto-rules HTTP API (T059).

   Tests call handlers directly (no ring.mock), using a fresh temp-file SQLite
   DB per test (same pattern as feedback-api-test / db_test with-temp-db).

   Contracts: specs/015-management-taxes-opex/contracts/tax-opex-api.md §2b.

   Run focused:
     clojure -M:test --focus analitica.web.api.tax-opex-test"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.domain.tax :as tax]
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

;; ===========================================================================
;; §1 — Tax config API — /api/v1/settings/tax
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; GET /api/v1/settings/tax?year=YYYY — 12-month fill
;; ---------------------------------------------------------------------------

(deftest get-tax-empty-year-returns-12-none-months
  (testing "GET tax for an unconfigured year returns 12 default :none months"
    (let [resp   (api/get-tax {:params {:year "2026"}})
          body   (:body resp)
          months (:months body)]
      (is (= 200 (:status resp)))
      (is (= 2026 (:year body)))
      (is (= 12 (count months)) "must be exactly 12 rows")
      (is (= (range 1 13) (map :month months)) "months 1..12 in order")
      (doseq [m months]
        (is (= :none (:taxation-type m)))
        (is (= 0.0 (:usn-rate m)))
        (is (= 0.0 (:vat-rate m)))))))

(deftest get-tax-fills-missing-months-around-configured
  (testing "GET tax merges configured months and fills the rest with :none"
    ;; Persist just month 1 with 6% УСН via save-config! (normalizes pct→frac)
    (tax/save-config! [{:year 2026 :month 1 :taxation-type :usn-income
                        :usn-rate-pct 6 :vat-rate-pct 0
                        :official-cost-price true}])
    (let [resp   (api/get-tax {:params {:year "2026"}})
          months (get-in resp [:body :months])
          by-mon (into {} (map (juxt :month identity)) months)]
      (is (= 200 (:status resp)))
      (is (= 12 (count months)))
      ;; Month 1 = configured
      (let [m1 (by-mon 1)]
        (is (= :usn-income (:taxation-type m1)))
        (is (= 0.06 (:usn-rate m1)))
        (is (= true (:official-cost-price m1))))
      ;; Month 2 = default none/0
      (let [m2 (by-mon 2)]
        (is (= :none (:taxation-type m2)))
        (is (= 0.0 (:usn-rate m2)))))))

(deftest get-tax-defaults-year-when-missing
  (testing "GET tax without ?year still returns 12 months (current year default)"
    (let [resp (api/get-tax {:params {}})]
      (is (= 200 (:status resp)))
      (is (int? (get-in resp [:body :year])))
      (is (= 12 (count (get-in resp [:body :months])))))))

;; ---------------------------------------------------------------------------
;; PUT /api/v1/settings/tax — percent→fraction normalization
;; ---------------------------------------------------------------------------

(deftest put-tax-normalizes-percent-to-fraction
  (testing "PUT tax accepts percents; server normalizes to fractions; GET reads back"
    (let [resp (api/put-tax {:body {:year 2026
                                    :months [{:month 1 :taxation-type :usn-income
                                              :usn-rate-pct 6 :vat-rate-pct 0
                                              :official-cost-price true}
                                             {:month 2 :taxation-type :usn-income-expense
                                              :usn-rate-pct 15 :vat-rate-pct 20
                                              :official-cost-price false}]}})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :ok])))
      (is (= 2 (get-in resp [:body :saved])))
      ;; Read back — the persisted rates must be fractions
      (let [by-mon (into {} (map (juxt :month identity))
                         (get-in (api/get-tax {:params {:year "2026"}}) [:body :months]))]
        (is (= 0.06 (:usn-rate (by-mon 1))))
        (is (= 0.0  (:vat-rate (by-mon 1))))
        (is (= 0.15 (:usn-rate (by-mon 2))))
        (is (= 0.20 (:vat-rate (by-mon 2))))
        (is (= false (:official-cost-price (by-mon 2))))))))

(deftest put-tax-invalid-month-returns-error
  (testing "PUT tax with month 13 returns {:ok false :error ...}"
    (let [resp (api/put-tax {:body {:year 2026
                                    :months [{:month 13 :taxation-type :usn-income
                                              :usn-rate-pct 6}]}})]
      (is (= 422 (:status resp)))
      (is (false? (get-in resp [:body :ok])))
      (is (string? (get-in resp [:body :error]))))))

(deftest put-tax-coerces-string-enums-to-keywords
  (testing "PUT tax coerces taxation-type string → keyword"
    (let [resp (api/put-tax {:body {:year 2026
                                    :months [{:month 3 :taxation-type "usn-income"
                                              :usn-rate-pct 6 :official-cost-price true}]}})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :ok])))
      (let [by-mon (into {} (map (juxt :month identity))
                         (get-in (api/get-tax {:params {:year "2026"}}) [:body :months]))]
        (is (= :usn-income (:taxation-type (by-mon 3))))))))

;; ===========================================================================
;; §2 — OPEX rows API — /api/v1/opex
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; GET /api/v1/opex?period=YYYY-MM
;; ---------------------------------------------------------------------------

(deftest get-opex-empty-period-returns-zero-total
  (testing "GET opex for an empty period returns empty rows and zero total"
    (let [resp (api/get-opex {:params {:period "2026-05"}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (= "2026-05" (:period body)))
      (is (vector? (:rows body)))
      (is (empty? (:rows body)))
      (is (= {} (:by-category body)))
      (is (= 0.0 (:total body))))))

(deftest get-opex-returns-rows-and-aggregates
  (testing "GET opex returns saved rows with by-category + total"
    (api/post-opex {:body {:period-month "2026-05" :category "rent"   :amount 50000.0}})
    (api/post-opex {:body {:period-month "2026-05" :category "salary" :amount 120000.0}})
    (api/post-opex {:body {:period-month "2026-05" :category "rent"   :amount 5000.0}})
    (let [resp (api/get-opex {:params {:period "2026-05"}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (= "2026-05" (:period body)))
      (is (= 3 (count (:rows body))))
      (is (= {"rent" 55000.0 "salary" 120000.0} (:by-category body)))
      (is (= 175000.0 (:total body))))))

(deftest get-opex-materializes-active-rules
  (testing "GET opex materializes an active auto-rule into the period rows"
    ;; Auto-rule active for 2026-05
    (api/post-auto-rule {:body {:category "services" :amount 9900.0
                                :cadence :monthly :effective-from "2026-01"
                                :effective-to nil}})
    (let [resp (api/get-opex {:params {:period "2026-05"}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (= 1 (count (:rows body))) "auto-rule materialized as one row")
      (is (= {"services" 9900.0} (:by-category body)))
      (is (= 9900.0 (:total body)))
      (is (= :auto (:source (first (:rows body))))))))

(deftest get-opex-marketplace-filter
  (testing "GET opex with &marketplace=wb returns only tagged rows"
    (api/post-opex {:body {:period-month "2026-05" :category "rent" :amount 50000.0}})
    (api/post-opex {:body {:period-month "2026-05" :category "salary" :amount 120000.0 :marketplace "wb"}})
    (let [resp (api/get-opex {:params {:period "2026-05" :marketplace "wb"}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (= {"salary" 120000.0} (:by-category body)))
      (is (= 120000.0 (:total body))))))

;; ---------------------------------------------------------------------------
;; POST /api/v1/opex
;; ---------------------------------------------------------------------------

(deftest post-opex-returns-ok-with-id
  (testing "POST valid opex row returns {:ok true :id n}"
    (let [resp (api/post-opex {:body {:period-month "2026-05" :category "services"
                                      :amount 9900.0 :marketplace nil :note "ПО"}})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :ok])))
      (is (pos-int? (get-in resp [:body :id]))))))

(deftest post-opex-amount-zero-returns-error
  (testing "POST opex with amount <= 0 returns {:ok false :error ...} (not persisted)"
    (let [resp (api/post-opex {:body {:period-month "2026-05" :category "rent" :amount 0.0}})]
      (is (= 422 (:status resp)))
      (is (false? (get-in resp [:body :ok])))
      (is (string? (get-in resp [:body :error])))
      ;; nothing persisted
      (is (empty? (get-in (api/get-opex {:params {:period "2026-05"}}) [:body :rows]))))))

(deftest post-opex-negative-amount-returns-error
  (testing "POST opex with negative amount returns {:ok false :error ...}"
    (let [resp (api/post-opex {:body {:period-month "2026-05" :category "rent" :amount -100.0}})]
      (is (= 422 (:status resp)))
      (is (false? (get-in resp [:body :ok]))))))

(deftest post-opex-coerces-marketplace-string
  (testing "POST opex coerces marketplace string → keyword"
    (let [resp (api/post-opex {:body {:period-month "2026-05" :category "salary"
                                      :amount 120000.0 :marketplace "wb"}})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :ok])))
      (let [row (first (get-in (api/get-opex {:params {:period "2026-05"}}) [:body :rows]))]
        (is (= :wb (:marketplace row)))))))

(deftest post-opex-coerces-integer-amount
  (testing "POST opex with integer amount is coerced to double and accepted"
    (let [resp (api/post-opex {:body {:period-month "2026-05" :category "rent" :amount 50000}})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :ok]))))))

;; ---------------------------------------------------------------------------
;; DELETE /api/v1/opex/:id
;; ---------------------------------------------------------------------------

(deftest delete-opex-returns-ok
  (testing "DELETE existing opex row returns {:ok true} and removes it"
    (let [post-resp (api/post-opex {:body {:period-month "2026-05" :category "rent" :amount 50000.0}})
          id        (get-in post-resp [:body :id])
          del-resp  (api/delete-opex {:params {:id (str id)}})]
      (is (= 200 (:status del-resp)))
      (is (true? (get-in del-resp [:body :ok])))
      (is (empty? (get-in (api/get-opex {:params {:period "2026-05"}}) [:body :rows]))))))

(deftest delete-opex-nonexistent-still-returns-ok
  (testing "DELETE non-existent opex row is idempotent"
    (let [resp (api/delete-opex {:params {:id "9999"}})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :ok]))))))

(deftest delete-opex-missing-id-returns-error
  (testing "DELETE opex without :id returns {:ok false :error ...}"
    (let [resp (api/delete-opex {:params {}})]
      (is (= 400 (:status resp)))
      (is (false? (get-in resp [:body :ok]))))))
