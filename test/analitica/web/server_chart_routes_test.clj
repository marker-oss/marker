(ns analitica.web.server-chart-routes-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.web.server :as server]
            [analitica.db :as db]
            [jsonista.core :as json]))

(use-fixtures :once
  (fn [f]
    ;; Initialize database before running tests
    (db/init!)
    (f)))

(deftest test-chart-sales-route
  (testing "GET /api/chart/sales route"
    (let [handler (server/app)]
      
      (testing "with default period"
        (let [request {:request-method :get
                       :uri "/api/chart/sales"
                       :params {:period "last-week"}}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for valid request")
          (is (string? (:body response))
              "Body should be a JSON string")
          (let [body (json/read-value (:body response) json/keyword-keys-object-mapper)]
            (is (contains? body :labels)
                "Response should contain :labels")
            (is (contains? body :datasets)
                "Response should contain :datasets")
            (is (vector? (:labels body))
                ":labels should be a vector")
            (is (vector? (:datasets body))
                ":datasets should be a vector"))))
      
      (testing "with marketplace parameter"
        (let [request {:request-method :get
                       :uri "/api/chart/sales"
                       :params {:period "last-week" :marketplace "wb"}}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for request with marketplace")
          (let [body (json/read-value (:body response) json/keyword-keys-object-mapper)]
            (is (contains? body :labels)
                "Response should contain :labels")
            (is (contains? body :datasets)
                "Response should contain :datasets"))))
      
      (testing "with invalid period"
        (let [request {:request-method :get
                       :uri "/api/chart/sales"
                       :params {:period "invalid-period"}}
              response (handler request)]
          (is (= 400 (:status response))
              "Should return 400 for invalid period")
          (let [body (json/read-value (:body response) json/keyword-keys-object-mapper)]
            (is (contains? body :error)
                "Response should contain error message")))))))

(deftest ^:integration test-chart-share-route
  (testing "GET /api/chart/share route"
    (let [handler (server/app)]
      
      (testing "with default period"
        (let [request {:request-method :get
                       :uri "/api/chart/share"
                       :params {:period "last-week"}}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for valid request")
          (is (string? (:body response))
              "Body should be a JSON string")
          (let [body (json/read-value (:body response) json/keyword-keys-object-mapper)]
            (is (contains? body :labels)
                "Response should contain :labels")
            (is (contains? body :datasets)
                "Response should contain :datasets")
            (is (vector? (:labels body))
                ":labels should be a vector")
            (is (vector? (get-in body [:datasets 0 :data]))
                "datasets[0].data should be a vector"))))
      
      (testing "with custom period"
        (let [request {:request-method :get
                       :uri "/api/chart/share"
                       :params {:period "last-30-days"}}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for custom period")))
      
      (testing "with invalid period"
        (let [request {:request-method :get
                       :uri "/api/chart/share"
                       :params {:period "invalid-period"}}
              response (handler request)]
          (is (= 400 (:status response))
              "Should return 400 for invalid period")
          (let [body (json/read-value (:body response) json/keyword-keys-object-mapper)]
            (is (contains? body :error)
                "Response should contain error message")))))))


(deftest test-chart-report-route
  (testing "GET /api/chart/report route"
    (let [handler (server/app)]
      
      (testing "with sales report type"
        (let [request {:request-method :get
                       :uri "/api/chart/report"
                       :params {:type "sales" :period "last-week"}}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for valid sales chart request")
          (is (string? (:body response))
              "Body should be a JSON string")
          (let [body (json/read-value (:body response) json/keyword-keys-object-mapper)]
            (is (contains? body :labels)
                "Response should contain :labels")
            (is (contains? body :datasets)
                "Response should contain :datasets")
            (is (vector? (:labels body))
                ":labels should be a vector")
            (is (vector? (:datasets body))
                ":datasets should be a vector"))))
      
      (testing "with finance report type"
        (let [request {:request-method :get
                       :uri "/api/chart/report"
                       :params {:type "finance" :period "last-week" :marketplace "wb"}}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for finance chart request")
          (let [body (json/read-value (:body response) json/keyword-keys-object-mapper)]
            (is (contains? body :labels)
                "Response should contain :labels")
            (is (contains? body :datasets)
                "Response should contain :datasets"))))
      
      (testing "with pnl report type"
        (let [request {:request-method :get
                       :uri "/api/chart/report"
                       :params {:type "pnl" :period "last-week"}}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for pnl chart request")))
      
      (testing "with stock report type"
        (let [request {:request-method :get
                       :uri "/api/chart/report"
                       :params {:type "stock" :period "last-week"}}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for stock chart request")))
      
      (testing "with returns report type"
        (let [request {:request-method :get
                       :uri "/api/chart/report"
                       :params {:type "returns" :period "last-week"}}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for returns chart request")))
      
      (testing "with buyout report type"
        (let [request {:request-method :get
                       :uri "/api/chart/report"
                       :params {:type "buyout" :period "last-week"}}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for buyout chart request")))
      
      (testing "with trends report type"
        (let [request {:request-method :get
                       :uri "/api/chart/report"
                       :params {:type "trends" :period "last-week"}}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for trends chart request")))
      
      (testing "without type parameter"
        (let [request {:request-method :get
                       :uri "/api/chart/report"
                       :params {:period "last-week"}}
              response (handler request)]
          (is (= 400 (:status response))
              "Should return 400 when type parameter is missing")
          (let [body (json/read-value (:body response) json/keyword-keys-object-mapper)]
            (is (contains? body :error)
                "Response should contain error message"))))
      
      (testing "with invalid period"
        (let [request {:request-method :get
                       :uri "/api/chart/report"
                       :params {:type "sales" :period "invalid-period"}}
              response (handler request)]
          (is (= 400 (:status response))
              "Should return 400 for invalid period")
          (let [body (json/read-value (:body response) json/keyword-keys-object-mapper)]
            (is (contains? body :error)
                "Response should contain error message")))))))
