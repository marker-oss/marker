(ns analitica.web.server-metrics-routes-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.web.server :as server]
            [analitica.config :as config]
            [analitica.db :as db]
            [jsonista.core :as json]))

;; (server/app) now reads config (wrap-api-key → config/api-key; CORS →
;; config/cors-origins), which throw "Config not loaded" in this unloaded test
;; process (M4: surface load errors, don't swallow to nil). Pin both getters.
(use-fixtures :once
  (fn [f]
    (db/init!)
    (with-redefs [config/api-key      (constantly nil)
                  config/cors-origins (constantly ["http://localhost:3000"])]
      (f))))

(deftest test-api-metrics-route
  (testing "GET /api/metrics route"
    (let [handler (server/app)]
      
      (testing "with default period"
        (let [request {:request-method :get
                       :uri "/api/metrics"
                       :params {}}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for valid request")
          (is (string? (:body response))
              "Body should be a JSON string")
          (let [body (json/read-value (:body response) json/keyword-keys-object-mapper)]
            (is (map? body)
                "Response should be a map")
            (is (contains? body :revenue)
                "Response should contain :revenue")
            (is (contains? body :orders)
                "Response should contain :orders")
            (is (contains? body :profit)
                "Response should contain :profit")
            (is (contains? body :return-rate)
                "Response should contain :return-rate")
            (is (contains? body :revenue-wow)
                "Response should contain :revenue-wow")
            (is (contains? body :by-marketplace)
                "Response should contain :by-marketplace"))))
      
      (testing "with custom period"
        (let [request {:request-method :get
                       :uri "/api/metrics"
                       :params {:period "last-30-days"}}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for valid period")))
      
      (testing "with marketplace filter"
        (let [request {:request-method :get
                       :uri "/api/metrics"
                       :params {:period "last-week" :marketplace "wb"}}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for valid marketplace filter")
          (let [body (json/read-value (:body response) json/keyword-keys-object-mapper)]
            (is (or (nil? (:by-marketplace body))
                    (empty? (:by-marketplace body)))
                "by-marketplace should be nil or empty when marketplace filter is applied"))))
      
      (testing "with invalid period"
        (let [request {:request-method :get
                       :uri "/api/metrics"
                       :params {:period "invalid-period"}}
              response (handler request)]
          (is (= 400 (:status response))
              "Should return 400 for invalid period")
          (let [body (json/read-value (:body response) json/keyword-keys-object-mapper)]
            (is (contains? body :error)
                "Response should contain error message")))))))

(deftest test-api-metrics-marketplace-route
  (testing "GET /api/metrics/:marketplace route"
    (let [handler (server/app)]
      
      (testing "with wb marketplace"
        (let [request {:request-method :get
                       :uri "/api/metrics/wb"
                       :params {:marketplace "wb"}}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for wb marketplace")
          (let [body (json/read-value (:body response) json/keyword-keys-object-mapper)]
            (is (map? body)
                "Response should be a map")
            (is (contains? body :revenue)
                "Response should contain :revenue")
            (is (contains? body :top-products)
                "Response should contain :top-products")
            (is (contains? body :finance-breakdown)
                "Response should contain :finance-breakdown")
            (is (contains? body :abc-summary)
                "Response should contain :abc-summary")
            (is (contains? body :top-returns)
                "Response should contain :top-returns")
            (is (vector? (:top-products body))
                "top-products should be a vector")
            (is (map? (:finance-breakdown body))
                "finance-breakdown should be a map")
            (is (vector? (:abc-summary body))
                "abc-summary should be a vector")
            (is (vector? (:top-returns body))
                "top-returns should be a vector"))))
      
      (testing "with ozon marketplace"
        (let [request {:request-method :get
                       :uri "/api/metrics/ozon"
                       :params {:marketplace "ozon"}}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for ozon marketplace")))
      
      (testing "with ym marketplace"
        (let [request {:request-method :get
                       :uri "/api/metrics/ym"
                       :params {:marketplace "ym"}}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for ym marketplace")))
      
      (testing "with custom period"
        (let [request {:request-method :get
                       :uri "/api/metrics/wb"
                       :params {:marketplace "wb" :period "last-30-days"}}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for custom period")))
      
      (testing "with invalid period"
        (let [request {:request-method :get
                       :uri "/api/metrics/wb"
                       :params {:marketplace "wb" :period "invalid"}}
              response (handler request)]
          (is (= 400 (:status response))
              "Should return 400 for invalid period"))))))