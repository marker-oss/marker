(ns analitica.schema.normalized-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.schema.normalized :as sut]))

(def expected-tables
  #{:finance :sales :stocks :cost-prices :ad-stats
    :paid-storage :region-sales :cash-flow-periods})

(deftest eight-tables-registered
  (testing "aggregator registers exactly 8 tables"
    (is (= 8 (count sut/tables)))
    (is (= expected-tables sut/tables))))

(deftest every-table-has-schema
  (doseq [table sut/tables]
    (testing (str table " has a schema registered")
      (is (contains? sut/schemas table)
          (str "No schema registered for " table)))))

(deftest dispatch-valid-rejects-empty-map
  (doseq [table sut/tables]
    (testing (str table " rejects empty map")
      (is (not (sut/valid? table {}))))))

(deftest dispatch-unknown-table-throws
  (testing "valid? throws ex-info on unknown table"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown normalized table"
                          (sut/valid? :not-a-table {}))))
  (testing "validate-rows throws ex-info on unknown table"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown normalized table"
                          (sut/validate-rows :not-a-table [{}])))))
