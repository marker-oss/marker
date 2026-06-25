(ns analitica.web.api.report-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.web.api.report :as report]))

(use-fixtures :once
  (fn [f]
    (db/init!)
    (f)))

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

(deftest report-data-compare-test
  (testing "when :compare :prev, result includes :compare {:totals :rows}"
    (let [result (report/report-data :ue {:from "2026-04-01" :to "2026-04-30"}
                                      :compare :prev)]
      (is (contains? result :compare))
      (is (map? (:compare result)))
      (is (contains? (:compare result) :totals))
      (is (contains? (:compare result) :rows))))

  (testing "when :compare not provided (default :none), no :compare key"
    (let [result (report/report-data :ue {:from "2026-04-01" :to "2026-04-30"})]
      (is (not (contains? result :compare))))))

;; ---------------------------------------------------------------------------
;; LT1 — :totals population tests (TDD RED → GREEN)
;; ---------------------------------------------------------------------------

(deftest report-data-sales-totals
  (testing ":sales totals are populated with numeric values"
    (let [result (report/report-data :sales {:from "2026-04-01" :to "2026-04-30"}
                                     :marketplace :ozon)
          totals (:totals result)]
      (is (map? totals) "totals should be a map")
      (is (contains? totals :total-revenue) ":total-revenue key must be present")
      (is (contains? totals :total-sales)   ":total-sales key must be present")
      (is (contains? totals :total-returns) ":total-returns key must be present")
      (is (number? (:total-revenue totals)) ":total-revenue must be a number")
      (is (number? (:total-sales totals))   ":total-sales must be a number")
      (is (number? (:total-returns totals)) ":total-returns must be a number"))))

(deftest report-data-abc-totals
  (testing ":abc totals are populated with numeric values and category counts"
    (let [result (report/report-data :abc {:from "2026-04-01" :to "2026-04-30"}
                                     :marketplace :ozon)
          totals (:totals result)]
      (is (map? totals) "totals should be a map")
      (is (contains? totals :total-revenue) ":total-revenue key must be present")
      (is (contains? totals :a-count)       ":a-count key must be present")
      (is (contains? totals :b-count)       ":b-count key must be present")
      (is (contains? totals :c-count)       ":c-count key must be present")
      (is (number? (:total-revenue totals)) ":total-revenue must be a number")
      (is (number? (:a-count totals))       ":a-count must be a number")
      (is (number? (:b-count totals))       ":b-count must be a number")
      (is (number? (:c-count totals))       ":c-count must be a number"))))

(deftest report-data-returns-totals
  (testing ":returns totals are populated with numeric values"
    (let [result (report/report-data :returns {:from "2026-04-01" :to "2026-04-30"}
                                     :marketplace :ozon)
          totals (:totals result)]
      (is (map? totals) "totals should be a map")
      (is (contains? totals :total-sold)      ":total-sold key must be present")
      (is (contains? totals :total-returned)  ":total-returned key must be present")
      (is (contains? totals :avg-return-rate) ":avg-return-rate key must be present")
      (is (number? (:total-sold totals))     ":total-sold must be a number")
      (is (number? (:total-returned totals)) ":total-returned must be a number")))

  (testing ":returns avg-return-rate is nil (not 0) when denominator is 0"
    (let [result (report/report-data :returns {:from "2000-01-01" :to "2000-01-02"}
                                     :marketplace :ozon)
          totals (:totals result)]
      (is (map? totals))
      (is (nil? (:avg-return-rate totals))
          ":avg-return-rate must be nil when no data (not 0)"))))

(deftest report-data-buyout-totals
  (testing ":buyout totals are populated with numeric values"
    (let [result (report/report-data :buyout {:from "2026-04-01" :to "2026-04-30"}
                                     :marketplace :ozon)
          totals (:totals result)]
      (is (map? totals) "totals should be a map")
      (is (contains? totals :total-ordered)    ":total-ordered key must be present")
      (is (contains? totals :total-bought)     ":total-bought key must be present")
      (is (contains? totals :avg-buyout-rate)  ":avg-buyout-rate key must be present")
      (is (number? (:total-ordered totals))   ":total-ordered must be a number")
      (is (number? (:total-bought totals))    ":total-bought must be a number")))

  (testing ":buyout avg-buyout-rate is nil (not 0) when denominator is 0"
    (let [result (report/report-data :buyout {:from "2000-01-01" :to "2000-01-02"}
                                     :marketplace :ozon)
          totals (:totals result)]
      (is (map? totals))
      (is (nil? (:avg-buyout-rate totals))
          ":avg-buyout-rate must be nil when no data (not 0)"))))

(deftest report-data-trends-totals
  (testing ":trends totals contain the three KPI keys (wow)"
    (let [result (report/report-data :trends {:from "2026-04-01" :to "2026-04-30"}
                                     :marketplace :ozon :trend-type :wow)
          totals (:totals result)]
      (is (map? totals) "totals should be a map")
      ;; Keys present — values may be nil if a metric row is absent
      (is (contains? totals :revenue-current) ":revenue-current key must be present")
      (is (contains? totals :orders-current)  ":orders-current key must be present")
      (is (contains? totals :profit-current)  ":profit-current key must be present"))))

(deftest report-data-stock-totals
  (testing ":stock totals are populated"
    (let [result (report/report-data :stock {:from "2026-04-01" :to "2026-04-30"}
                                     :marketplace :ozon)
          totals (:totals result)]
      (is (map? totals) "totals should be a map")
      (is (contains? totals :total-quantity)  ":total-quantity key must be present")
      (is (contains? totals :total-in-way-to) ":total-in-way-to key must be present")
      (is (contains? totals :sku-count)       ":sku-count key must be present"))))

(deftest report-data-geo-totals
  (testing ":geo totals are populated"
    (let [result (report/report-data :geo {:from "2026-04-01" :to "2026-04-30"}
                                     :marketplace :ozon)
          totals (:totals result)]
      (is (map? totals) "totals should be a map")
      (is (contains? totals :total-sum)    ":total-sum key must be present")
      (is (contains? totals :total-qty)    ":total-qty key must be present")
      (is (contains? totals :region-count) ":region-count key must be present"))))
