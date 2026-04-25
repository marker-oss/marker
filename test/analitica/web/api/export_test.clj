(ns analitica.web.api.export-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.api.export :as export]
            [clojure.java.io :as io]))

(deftest test-export-report-structure
  (testing "export-report returns proper Ring response structure"
    ;; Use a date range that will work even with empty data
    (let [response (export/export-report :sales ["2024-01-01" "2024-01-07"] nil :csv)]
      (is (map? response))
      (is (contains? response :status))
      (is (contains? response :headers))
      (is (contains? response :body)))))

(deftest test-export-filename-format
  (testing "export generates correct filename format"
    (let [response (export/export-report :sales ["2024-01-01" "2024-01-07"] :wb :excel)]
      (is (= 200 (:status response)))
      (let [content-disp (get-in response [:headers "Content-Disposition"])]
        (is (string? content-disp))
        (is (re-find #"attachment; filename=\"sales-2024-01-01_2024-01-07-wb\.xlsx\"" content-disp))))))

(deftest test-export-csv-format
  (testing "export-report generates CSV with correct content type"
    (let [response (export/export-report :finance ["2024-01-01" "2024-01-31"] nil :csv)]
      (is (= 200 (:status response)))
      (is (= "text/csv; charset=utf-8" 
             (get-in response [:headers "Content-Type"]))))))

(deftest test-export-excel-format
  (testing "export-report generates Excel with correct content type"
    (let [response (export/export-report :ue ["2024-01-01" "2024-01-31"] :ozon :excel)]
      (is (= 200 (:status response)))
      (is (= "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
             (get-in response [:headers "Content-Type"]))))))
