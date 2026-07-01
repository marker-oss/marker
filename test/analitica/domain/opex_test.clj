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
