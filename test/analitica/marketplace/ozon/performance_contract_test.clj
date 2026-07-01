(ns analitica.marketplace.ozon.performance-contract-test
  "T011 — contract test for the Ozon Performance API Malli schemas.

   The synthetic fixtures under test/fixtures/ozon/performance/ MUST validate
   against the :ozon/performance-* contract schemas (FR-011). A deliberately
   broken row (extra / renamed field) must be logged by the validator, NOT
   thrown — same posture as the Ozon realization contract."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [jsonista.core :as j]
            [analitica.marketplace.ozon.performance.transform :as perf-transform]
            [analitica.schema.validator :as validator]
            [analitica.schema.registry :as reg]))

;; The :ozon/performance-* contracts register at perf-transform load-time, but
;; the schema registry is a global atom another test may reset! before this ns
;; runs (load-all! only adds, but a registry/loader test can clear it). Re-register
;; once before these tests so the lookups are order-independent. Idempotent.
(use-fixtures :once
  (fn [f] (perf-transform/register-contracts!) (f)))

(def ^:private mapper (j/object-mapper {:decode-key-fn true}))

(defn- load-fixture [name]
  (-> (io/file "test/fixtures/ozon/performance" name)
      slurp
      (j/read-value mapper)))

(deftest fixtures-validate-against-contracts
  (testing "token fixture is :ok against :ozon/performance-token"
    (let [resp   (load-fixture "token.json")
          result (validator/validate (reg/lookup :ozon/performance-token) resp)]
      (is (= :ok (:result/status result)))))

  (testing "campaign fixture is :ok against :ozon/performance-campaign"
    (let [resp   (load-fixture "campaign.json")
          result (validator/validate (reg/lookup :ozon/performance-campaign) resp)]
      (is (= :ok (:result/status result)))))

  (testing "daily fixture is :ok against :ozon/performance-daily"
    (let [resp   (load-fixture "daily.json")
          result (validator/validate (reg/lookup :ozon/performance-daily) resp)]
      (is (= :ok (:result/status result)))))

  (testing "statistics-report fixture is :ok against :ozon/performance-statistics-report"
    (let [resp   (load-fixture "statistics-report.json")
          result (validator/validate
                   (reg/lookup :ozon/performance-statistics-report) resp)]
      (is (= :ok (:result/status result))))))

(deftest broken-row-critical-missing-key
  (testing "a daily row missing the REQUIRED campaignId (:id) is a critical
            violation that validate! throws on — proving the schema is enforced
            (a row we cannot attribute is dropped, not silently materialised)"
    (let [broken {:rows [{:date "2026-04-10" :moneySpent 100.0}]}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (validator/validate! :ozon/performance-daily broken))))))

(deftest drifted-row-warns-not-throws
  (testing "an EXTRA / renamed field is logged (top-level extra → :warned),
            never fatal — validate! returns the response unchanged (FR-011,
            same posture as realization)"
    ;; Top-level extra key (the validator introspects the top-level map/seq
    ;; shape; a drift here is a warning, not a critical failure).
    (let [drifted {:rows [{:date "2026-04-10" :id "78901" :moneySpent 100.0}]
                   :unexpectedTopLevel 42}
          result  (validator/validate
                    (reg/lookup :ozon/performance-daily) drifted)]
      (is (= :warned (:result/status result)) "extra key → warning, not critical")
      ;; validate! logs the warning and returns the response — does NOT throw.
      (is (= drifted (validator/validate! :ozon/performance-daily drifted))))))
