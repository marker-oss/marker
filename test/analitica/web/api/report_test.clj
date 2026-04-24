(ns analitica.web.api.report-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.api.report :as report]))

(deftest report-data-returns-map-with-rows
  (testing "report-data returns a map with :rows vector for all report types"
    (let [period :last-week
          marketplace :wb]

      ;; Test that each report type returns {:rows [...] :totals {...}}
      (doseq [report-type [:sales :finance :ue :pnl :abc :stock :returns :buyout :geo :trends]]
        (let [result (report/report-data report-type period :marketplace marketplace)]
          (is (map? result)
              (str "report-data should return a map for " report-type))
          (is (contains? result :rows)
              (str "result should have :rows for " report-type))
          (is (vector? (:rows result))
              (str ":rows should be a vector for " report-type))
          (is (contains? result :totals)
              (str "result should have :totals for " report-type)))))))

(deftest report-data-handles-unknown-type
  (testing "report-data returns empty :rows for unknown report type"
    (let [result (report/report-data :unknown :last-week)]
      (is (map? result))
      (is (empty? (:rows result)))
      (is (map? (:totals result))))))

(deftest report-data-pnl-returns-totals-map
  (testing "P&L report returns totals map with empty rows"
    (let [result (report/report-data :pnl :last-week :marketplace :wb)]
      (is (map? result))
      (is (map? (:totals result)))
      ;; P&L returns summary in :totals, :rows is empty
      (is (vector? (:rows result))))))

(deftest report-data-trends-respects-trend-type
  (testing "Trends report respects trend-type parameter"
    ;; These should not throw exceptions
    (is (vector? (:rows (report/report-data :trends :last-week :trend-type :wow))))
    (is (vector? (:rows (report/report-data :trends :last-week :trend-type :mom))))
    (is (vector? (:rows (report/report-data :trends :last-week :trend-type :daily))))))

(deftest report-data-includes-totals-test
  (testing "report-data returns map with :rows and :totals for :ue"
    (let [result (report/report-data :ue {:from "2026-04-01" :to "2026-04-30"})]
      (is (map? result))
      (is (contains? result :rows))
      (is (contains? result :totals))
      (is (vector? (:rows result)))
      (is (map? (:totals result))))))
