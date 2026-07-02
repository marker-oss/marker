(ns analitica.domain.opex-test
  "TDD tests for US3 — OPEX store + sum-by-category.
   Tasks T022-T025 (INV-7/SC-008 allocation, event-date discipline, store round-trip).

   Run focused:
     clojure -M:test --focus analitica.domain.opex-test"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.domain.opex :as opex]
            [analitica.db :as db]
            [malli.core :as m]
            analitica.test-helpers))

(use-fixtures :once analitica.test-helpers/with-test-db)

(defn- clear-opex!
  "Remove all opex_rows between tests to prevent cross-test contamination."
  []
  (db/execute! ["DELETE FROM opex_rows"])
  (db/execute! ["DELETE FROM opex_auto_rules"]))

;; ---------------------------------------------------------------------------
;; T022 — Malli schema validation + store round-trip
;; ---------------------------------------------------------------------------

(deftest t022-opex-row-schema-validation
  (testing "Valid OpexRow passes Malli schema"
    (is (m/validate opex/OpexRow
                    {:period-month "2026-05"
                     :category     "rent"
                     :amount       50000.0
                     :marketplace  nil
                     :note         "офис"})))

  (testing "amount <= 0 is rejected"
    (is (not (m/validate opex/OpexRow
                         {:period-month "2026-05"
                          :category     "rent"
                          :amount       0.0})))
    (is (not (m/validate opex/OpexRow
                         {:period-month "2026-05"
                          :category     "rent"
                          :amount       -100.0}))))

  (testing "period-month '2026-13' is rejected (month 13 invalid)"
    (is (not (m/validate opex/OpexRow
                         {:period-month "2026-13"
                          :category     "rent"
                          :amount       50000.0}))))

  (testing "period-month '2026-00' is rejected (month 0 invalid)"
    (is (not (m/validate opex/OpexRow
                         {:period-month "2026-00"
                          :category     "rent"
                          :amount       50000.0}))))

  (testing "valid marketplace keyword accepted"
    (is (m/validate opex/OpexRow
                    {:period-month "2026-05"
                     :category     "salary"
                     :amount       120000.0
                     :marketplace  :wb})))

  (testing "invalid marketplace keyword rejected"
    (is (not (m/validate opex/OpexRow
                         {:period-month "2026-05"
                          :category     "salary"
                          :amount       120000.0
                          :marketplace  :unknown})))))

(deftest t022-store-round-trip
  (clear-opex!)
  (testing "save-row! returns {:id n} where n is a positive integer"
    (let [result (opex/save-row! {:period-month "2026-05"
                                  :category     "rent"
                                  :amount       50000.0
                                  :marketplace  nil
                                  :note         "офис"})]
      (is (map? result))
      (is (pos-int? (:id result)))))

  (testing "fetch-rows returns saved rows for the period"
    (clear-opex!)
    (opex/save-row! {:period-month "2026-05" :category "rent"   :amount 50000.0  :marketplace nil})
    (opex/save-row! {:period-month "2026-05" :category "salary" :amount 120000.0 :marketplace :wb})
    (let [rows (opex/fetch-rows "2026-05")]
      (is (= 2 (count rows)))
      (is (every? #(= "2026-05" (:period-month %)) rows))))

  (testing "delete-row! removes the row by id"
    (clear-opex!)
    (let [{:keys [id]} (opex/save-row! {:period-month "2026-05" :category "rent" :amount 50000.0})]
      (opex/delete-row! id)
      (is (empty? (opex/fetch-rows "2026-05"))))))

;; ---------------------------------------------------------------------------
;; T023 — sum-by-category blended (marketplace nil)
;; ---------------------------------------------------------------------------

(deftest t023-sum-by-category-blended
  (clear-opex!)
  (testing "blended sum includes both NULL-marketplace and tagged rows"
    ;; rent 50000 with nil marketplace
    (opex/save-row! {:period-month "2026-05" :category "rent"   :amount 50000.0  :marketplace nil})
    ;; salary 120000 tagged :wb
    (opex/save-row! {:period-month "2026-05" :category "salary" :amount 120000.0 :marketplace :wb})

    (let [s (opex/sum-by-category "2026-05-01" "2026-05-31" nil)]
      (testing ":total is 170000.0"
        (is (= 170000.0 (:total s))))
      (testing ":by-category has rent 50000.0 and salary 120000.0"
        (is (= {"rent" 50000.0 "salary" 120000.0} (:by-category s))))
      (testing ":rows is present"
        (is (sequential? (:rows s))))
      (testing ":rows count is 2"
        (is (= 2 (count (:rows s))))))))

;; ---------------------------------------------------------------------------
;; T024 — Period-discipline: row outside window is excluded
;; ---------------------------------------------------------------------------

(deftest t024-period-discipline
  (clear-opex!)
  (testing "rows from previous period do not leak into current window"
    ;; May rows (in window)
    (opex/save-row! {:period-month "2026-05" :category "rent"   :amount 50000.0  :marketplace nil})
    (opex/save-row! {:period-month "2026-05" :category "salary" :amount 120000.0 :marketplace nil})
    ;; April row — outside the May window
    (opex/save-row! {:period-month "2026-04" :category "rent"   :amount 999.0    :marketplace nil})

    (let [s (opex/sum-by-category "2026-05-01" "2026-05-31" nil)]
      (testing ":total remains 170000.0 (April row excluded)"
        (is (= 170000.0 (:total s))))
      (testing "only May rows are returned"
        (is (= 2 (count (:rows s))))))))

(deftest t024-period-discipline-no-future-leak
  (clear-opex!)
  (testing "rows from future period do not leak into current window"
    (opex/save-row! {:period-month "2026-05" :category "rent" :amount 50000.0 :marketplace nil})
    (opex/save-row! {:period-month "2026-06" :category "rent" :amount 9999.0  :marketplace nil})

    (let [s (opex/sum-by-category "2026-05-01" "2026-05-31" nil)]
      (is (= 50000.0 (:total s))))))

;; ---------------------------------------------------------------------------
;; T025 — Allocation rule R11
;;   tagged→own MP only; NULL→blended only (no double-count per-MP)
;; ---------------------------------------------------------------------------

(deftest t025-allocation-tagged-row-goes-to-own-mp-only
  (clear-opex!)
  (testing "tagged :wb row appears in :wb query but not in :ozon query"
    (opex/save-row! {:period-month "2026-05" :category "salary" :amount 120000.0 :marketplace :wb})

    (let [wb-sum   (opex/sum-by-category "2026-05-01" "2026-05-31" :wb)
          ozon-sum (opex/sum-by-category "2026-05-01" "2026-05-31" :ozon)]
      (is (= 120000.0 (:total wb-sum))   "tagged :wb row included in :wb query")
      (is (= 0.0      (:total ozon-sum)) "tagged :wb row excluded from :ozon query"))))

(deftest t025-allocation-null-row-is-blended-only
  (clear-opex!)
  (testing "NULL-marketplace row appears in blended but NOT in per-MP queries (R11)"
    (opex/save-row! {:period-month "2026-05" :category "rent" :amount 50000.0 :marketplace nil})

    (let [blended-sum (opex/sum-by-category "2026-05-01" "2026-05-31" nil)
          wb-sum      (opex/sum-by-category "2026-05-01" "2026-05-31" :wb)
          ozon-sum    (opex/sum-by-category "2026-05-01" "2026-05-31" :ozon)
          ym-sum      (opex/sum-by-category "2026-05-01" "2026-05-31" :ym)]
      (is (= 50000.0 (:total blended-sum)) "NULL row appears in blended")
      (is (= 0.0     (:total wb-sum))      "NULL row excluded from :wb")
      (is (= 0.0     (:total ozon-sum))    "NULL row excluded from :ozon")
      (is (= 0.0     (:total ym-sum))      "NULL row excluded from :ym"))))

(deftest t025-allocation-no-double-count
  (clear-opex!)
  (testing "NULL-marketplace row counted only once in blended (not per-MP-double-counted)"
    (opex/save-row! {:period-month "2026-05" :category "rent"   :amount 50000.0  :marketplace nil})
    (opex/save-row! {:period-month "2026-05" :category "salary" :amount 120000.0 :marketplace :wb})

    (let [blended-sum (opex/sum-by-category "2026-05-01" "2026-05-31" nil)
          wb-sum      (opex/sum-by-category "2026-05-01" "2026-05-31" :wb)]
      ;; blended = NULL(50000) + tagged-wb(120000) = 170000
      (is (= 170000.0 (:total blended-sum)) "blended = all rows (NULL + tagged)")
      ;; wb = tagged-wb only (120000); NULL not double-counted per-MP
      (is (= 120000.0 (:total wb-sum))      "per-MP = tagged rows only"))))

;; ---------------------------------------------------------------------------
;; T051 — OpexAutoRule Malli schema validation + store round-trip (US5)
;; ---------------------------------------------------------------------------

(deftest t051-opex-auto-rule-schema-validation
  (testing "Valid OpexAutoRule passes Malli schema"
    (is (m/validate opex/OpexAutoRule
                    {:category       "rent"
                     :amount         50000.0
                     :marketplace    nil
                     :cadence        :monthly
                     :effective-from "2026-01"
                     :effective-to   "2026-12"
                     :note           "офис"})))

  (testing "amount <= 0 is rejected"
    (is (not (m/validate opex/OpexAutoRule
                         {:category       "rent"
                          :amount         0.0
                          :effective-from "2026-01"})))
    (is (not (m/validate opex/OpexAutoRule
                         {:category       "rent"
                          :amount         -100.0
                          :effective-from "2026-01"}))))

  (testing "effective-from '2026-13' is rejected (month 13 invalid)"
    (is (not (m/validate opex/OpexAutoRule
                         {:category       "rent"
                          :amount         50000.0
                          :effective-from "2026-13"}))))

  (testing "effective-from '2026-00' is rejected (month 0 invalid)"
    (is (not (m/validate opex/OpexAutoRule
                         {:category       "rent"
                          :amount         50000.0
                          :effective-from "2026-00"}))))

  (testing "cadence outside enum is rejected"
    (is (not (m/validate opex/OpexAutoRule
                         {:category       "rent"
                          :amount         50000.0
                          :effective-from "2026-01"
                          :cadence        :weekly}))))

  (testing "optional fields can be absent"
    (is (m/validate opex/OpexAutoRule
                    {:category       "salary"
                     :amount         120000.0
                     :effective-from "2026-03"})))

  (testing "marketplace keyword accepted"
    (is (m/validate opex/OpexAutoRule
                    {:category       "salary"
                     :amount         120000.0
                     :marketplace    :wb
                     :effective-from "2026-03"})))

  (testing "invalid marketplace keyword rejected"
    (is (not (m/validate opex/OpexAutoRule
                         {:category       "salary"
                          :amount         120000.0
                          :marketplace    :unknown
                          :effective-from "2026-03"})))))

(deftest t051-auto-rule-store-round-trip
  (clear-opex!)
  (testing "save-rule! returns {:id n}"
    (let [result (opex/save-rule! {:category       "rent"
                                    :amount         50000.0
                                    :marketplace    nil
                                    :cadence        :monthly
                                    :effective-from "2026-01"
                                    :effective-to   "2026-12"
                                    :note           "офис"})]
      (is (map? result))
      (is (pos-int? (:id result)))))

  (testing "fetch-rules returns saved rule"
    (clear-opex!)
    (opex/save-rule! {:category       "rent"
                       :amount         50000.0
                       :effective-from "2026-01"})
    (opex/save-rule! {:category       "salary"
                       :amount         120000.0
                       :marketplace    :wb
                       :effective-from "2026-03"})
    (let [rules (opex/fetch-rules)]
      (is (= 2 (count rules)))
      (is (every? #(contains? % :id) rules))
      (is (every? #(contains? % :category) rules))))

  (testing "delete-rule! removes the rule by id"
    (clear-opex!)
    (let [{:keys [id]} (opex/save-rule! {:category       "rent"
                                          :amount         50000.0
                                          :effective-from "2026-01"})]
      (opex/delete-rule! id)
      (is (empty? (opex/fetch-rules))))))

;; ---------------------------------------------------------------------------
;; T052 — materialize-rules! invariant: correct period → 1 row; outside → 0 rows
;; ---------------------------------------------------------------------------

(deftest t052-materialize-rules-creates-row-in-window
  (clear-opex!)
  (testing "rule covering 2026-01..2026-12 materializes 1 row for 2026-05"
    (let [{:keys [id]} (opex/save-rule! {:category       "rent"
                                          :amount         50000.0
                                          :marketplace    nil
                                          :cadence        :monthly
                                          :effective-from "2026-01"
                                          :effective-to   "2026-12"})]
      (let [result (opex/materialize-rules! "2026-05")]
        (is (= 1 (:materialized result)) "exactly 1 row materialized")
        (is (= 0 (:skipped result))      "0 skipped on first run"))
      ;; Verify the row attributes
      (let [rows (opex/fetch-rows "2026-05")]
        (is (= 1 (count rows)) "exactly 1 row in opex_rows for 2026-05")
        (let [row (first rows)]
          (is (= "rent" (:category row)))
          (is (= 50000.0 (:amount row)))
          (is (= :auto (:source row))    "source must be :auto")
          (is (= id (:rule-id row))      "rule-id must match the rule's id"))))))

(deftest t052-materialize-rules-outside-window-inserts-nothing
  (clear-opex!)
  (testing "rule effective 2026-01..2026-12 does NOT materialize for 2025-12 (before window)"
    (opex/save-rule! {:category       "rent"
                       :amount         50000.0
                       :cadence        :monthly
                       :effective-from "2026-01"
                       :effective-to   "2026-12"})
    (let [result (opex/materialize-rules! "2025-12")]
      (is (= 0 (:materialized result)) "0 rows materialized for period before effective-from"))
    (is (empty? (opex/fetch-rows "2025-12")) "no rows for out-of-window period")))

(deftest t052-materialize-rules-open-ended-rule
  (clear-opex!)
  (testing "rule with effective-to nil (open-ended) materializes for any period >= effective-from"
    (opex/save-rule! {:category       "salary"
                       :amount         120000.0
                       :marketplace    :wb
                       :cadence        :monthly
                       :effective-from "2026-03"
                       :effective-to   nil})
    (let [result (opex/materialize-rules! "2027-06")]
      (is (= 1 (:materialized result)) "open-ended rule materializes for any future period"))))

;; ---------------------------------------------------------------------------
;; T053 — materialize-rules! idempotency + override-safety
;; ---------------------------------------------------------------------------

(deftest t053-materialize-rules-idempotent
  (clear-opex!)
  (testing "double materialize-rules! for same period does NOT create duplicate rows"
    (opex/save-rule! {:category       "rent"
                       :amount         50000.0
                       :cadence        :monthly
                       :effective-from "2026-01"
                       :effective-to   "2026-12"})
    ;; First run
    (let [r1 (opex/materialize-rules! "2026-05")]
      (is (= 1 (:materialized r1)) "first run: 1 materialized"))
    ;; Second run — must be idempotent (DO NOTHING)
    (let [r2 (opex/materialize-rules! "2026-05")]
      (is (= 0 (:materialized r2)) "second run: 0 materialized (idempotent)")
      (is (= 1 (:skipped r2))      "second run: 1 skipped"))
    ;; Exactly one row total
    (is (= 1 (count (opex/fetch-rows "2026-05")))
        "exactly 1 row after 2× materialize-rules!")))

(deftest t053-materialize-rules-override-safe
  (clear-opex!)
  (testing "manual override of a materialized row's amount is preserved on re-materialize"
    (let [{rule-id :id} (opex/save-rule! {:category       "rent"
                                           :amount         50000.0
                                           :cadence        :monthly
                                           :effective-from "2026-01"
                                           :effective-to   "2026-12"})]
      ;; Materialize the rule
      (opex/materialize-rules! "2026-05")
      ;; Fetch the auto row and override its amount manually
      (let [auto-row (first (opex/fetch-rows "2026-05"))
            row-id   (:id auto-row)]
        (is (= 50000.0 (:amount auto-row)) "original amount from rule")
        ;; Simulate manual override: delete the auto row and insert a manual one
        ;; with same rule-id (simulates UI editing the amount)
        (opex/delete-row! row-id)
        ;; Insert with source=:auto to keep rule-id link but with overridden amount
        ;; (this is what the UI override does — user edits via PUT /api/v1/opex/:id)
        (opex/save-row! {:period-month "2026-05"
                          :category     "rent"
                          :amount       99000.0   ;; overridden amount
                          :source       :auto
                          :rule-id      rule-id}))
      ;; Re-materialize — must NOT overwrite the overridden row
      (opex/materialize-rules! "2026-05")
      ;; The row with the overridden amount must still be present
      (let [rows (opex/fetch-rows "2026-05")]
        (is (= 1 (count rows)) "still exactly 1 row after re-materialize")
        (is (= 99000.0 (:amount (first rows)))
            "overridden amount preserved — re-materialize did not clobber it")))))

;; ---------------------------------------------------------------------------
;; T054 — sum-by-category includes auto row exactly ONCE; allocation R11
;; ---------------------------------------------------------------------------

(deftest t054-sum-by-category-includes-auto-row-once
  (clear-opex!)
  (testing "materialized auto row enters sum-by-category exactly once alongside manual rows"
    ;; Manual row: salary 120000 tagged :wb
    (opex/save-row! {:period-month "2026-05" :category "salary" :amount 120000.0 :marketplace :wb})
    ;; Auto rule: rent 50000 (nil mp) materializes
    (opex/save-rule! {:category       "rent"
                       :amount         50000.0
                       :marketplace    nil
                       :cadence        :monthly
                       :effective-from "2026-01"
                       :effective-to   "2026-12"})
    (opex/materialize-rules! "2026-05")
    ;; Blended: manual salary(120000) + auto rent(50000) = 170000
    (let [s (opex/sum-by-category "2026-05-01" "2026-05-31" nil)]
      (is (= 170000.0 (:total s))
          "blended total = manual + auto row (no double-count)")
      (is (= {"rent" 50000.0 "salary" 120000.0} (:by-category s))
          "by-category includes both auto and manual rows exactly once"))
    ;; per-:wb: only tagged salary row (auto rent is nil-mp → blended only)
    (let [wb (opex/sum-by-category "2026-05-01" "2026-05-31" :wb)]
      (is (= 120000.0 (:total wb))
          ":wb total = only tagged salary (auto nil-mp rent excluded per R11"))
    ;; per-:ozon: nothing
    (let [ozon (opex/sum-by-category "2026-05-01" "2026-05-31" :ozon)]
      (is (= 0.0 (:total ozon)) "no :ozon-tagged rows"))))

(deftest t054-auto-tagged-rule-allocation
  (clear-opex!)
  (testing "auto rule tagged :wb materializes into :wb query; excluded from :ozon (R11)"
    (opex/save-rule! {:category       "salary"
                       :amount         120000.0
                       :marketplace    :wb
                       :cadence        :monthly
                       :effective-from "2026-01"
                       :effective-to   "2026-12"})
    (opex/materialize-rules! "2026-05")
    (let [wb-sum   (opex/sum-by-category "2026-05-01" "2026-05-31" :wb)
          ozon-sum (opex/sum-by-category "2026-05-01" "2026-05-31" :ozon)
          blended  (opex/sum-by-category "2026-05-01" "2026-05-31" nil)]
      (is (= 120000.0 (:total wb-sum))   "tagged :wb auto row in :wb query")
      (is (= 0.0      (:total ozon-sum)) "tagged :wb auto row excluded from :ozon")
      (is (= 120000.0 (:total blended))  "tagged :wb auto row in blended"))))

;; ---------------------------------------------------------------------------
;; opex-categories vocabulary
;; ---------------------------------------------------------------------------

(deftest opex-categories-vocabulary
  (testing "opex-categories contains expected hint values"
    (is (sequential? opex/opex-categories))
    (is (some #{"salary"} opex/opex-categories))
    (is (some #{"rent"} opex/opex-categories))
    (is (some #{"services"} opex/opex-categories))
    (is (some #{"marketing"} opex/opex-categories))
    (is (some #{"other"} opex/opex-categories))))
