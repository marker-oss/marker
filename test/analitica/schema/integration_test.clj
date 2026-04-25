(ns analitica.schema.integration-test
  "End-to-end US1 test: swap one WB API response with a mock that violates
   the contract, verify that ingest aborts AND that raw_data was saved
   before the throw (FR-004 invariant)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.audit.test-helpers :as th]
            [analitica.db :as db]
            [analitica.ingest :as ingest]
            [analitica.marketplace.wb.api :as wb-api]
            [analitica.schema.loader :as schema-loader]
            [analitica.schema.registry :as schema-registry]))

(defn- with-isolated-db-and-schemas [f]
  (th/with-isolated-db
    (fn []
      (schema-registry/clear!)
      (schema-loader/load-all!)
      (try (f) (finally (schema-registry/clear!))))))

(use-fixtures :each with-isolated-db-and-schemas)

(defn- valid-wb-finance-row
  "A minimally-complete WB finance row that satisfies the registered
   contract in resources/schemas/wb/report-detail-by-period.edn."
  []
  {:rrd_id                1
   :realizationreport_id  100
   :date_from             "2026-03-01"
   :date_to               "2026-03-07"
   :sa_name               "ART-001"
   :nm_id                 12345
   :barcode               "1000000000001"
   :supplier_oper_name    "Продажа"
   :quantity              1
   :retail_price          1000.0
   :retail_amount         1000.0
   :ppvz_sales_commission 120.0
   :ppvz_for_pay          850.0})

(deftest validation-accepts-valid-wb-response
  (let [client :mock-client]
    (with-redefs [wb-api/report-detail-by-period-all
                  (constantly [(valid-wb-finance-row)])]
      (is (integer?
            (#'ingest/ingest-wb-finance-chunk! client "2026-03-01" "2026-03-07"))))))

(deftest validation-rejects-wrong-type-in-wb-response
  (let [client :mock-client
        ;; :rrd_id as string instead of int — type-mismatch
        bad-row (assoc (valid-wb-finance-row) :rrd_id "not-an-int")]
    (with-redefs [wb-api/report-detail-by-period-all
                  (constantly [bad-row])]
      (try
        (#'ingest/ingest-wb-finance-chunk! client "2026-03-01" "2026-03-07")
        (is false "should have thrown on type mismatch")
        (catch clojure.lang.ExceptionInfo e
          (is (= :schema-violation (:type (ex-data e))))
          (is (= :wb/report-detail-by-period (:endpoint-id (ex-data e)))))))))

(deftest raw-data-is-persisted-even-when-validation-fails
  ;; FR-004: the raw response must land in raw_data before validate! throws,
  ;; so operators can diagnose the violation post-mortem.
  (let [client :mock-client
        bad-row (assoc (valid-wb-finance-row) :rrd_id "not-an-int")]
    (with-redefs [wb-api/report-detail-by-period-all
                  (constantly [bad-row])]
      (try
        (#'ingest/ingest-wb-finance-chunk! client "2026-03-01" "2026-03-07")
        (catch clojure.lang.ExceptionInfo _ nil))
      (let [saved (db/get-raw :wb :finance "2026-03-01" "2026-03-07")]
        (is (some? saved) "raw_data row exists despite validation failure")
        (is (= "not-an-int" (:rrd_id (first saved)))
            "saved payload matches the (bad) mocked response")))))

(deftest missing-required-field-aborts-ingest
  (let [client :mock-client
        ;; Remove :supplier_oper_name — declared as required in the EDN schema
        bad-row (dissoc (valid-wb-finance-row) :supplier_oper_name)]
    (with-redefs [wb-api/report-detail-by-period-all
                  (constantly [bad-row])]
      (try
        (#'ingest/ingest-wb-finance-chunk! client "2026-03-01" "2026-03-07")
        (is false "should have thrown on missing required field")
        (catch clojure.lang.ExceptionInfo e
          (is (= :schema-violation (:type (ex-data e))))
          (let [viols (get-in (ex-data e) [:result :result/violations])]
            (is (some #(= :required-missing (:violation/kind %)) viols))))))))
