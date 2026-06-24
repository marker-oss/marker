(ns analitica.web.server-export-routes-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.web.server :as server]
            [analitica.config :as config]
            [analitica.db :as db]))

;; (server/app) now reads config (wrap-api-key → config/api-key; CORS →
;; config/cors-origins), which throw "Config not loaded" in this unloaded test
;; process (M4: surface load errors, don't swallow to nil). Pin both getters.
(use-fixtures :once
  (fn [f]
    (db/init!)
    (with-redefs [config/api-key      (constantly nil)
                  config/cors-origins (constantly ["http://localhost:3000"])]
      (f))))

(deftest test-export-route-csv
  (testing "GET /api/export/:report with CSV format"
    (let [app (server/app)
          request {:request-method :get
                   :uri "/api/export/sales"
                   :query-string "period=2024-01-01,2024-01-07&format=csv"}
          response (app request)]
      (is (= 200 (:status response)))
      (is (= "text/csv; charset=utf-8" 
             (get-in response [:headers "Content-Type"])))
      (is (re-find #"attachment; filename=\"sales-.*\.csv\"" 
                   (get-in response [:headers "Content-Disposition"]))))))

(deftest test-export-route-excel
  (testing "GET /api/export/:report with Excel format"
    (let [app (server/app)
          request {:request-method :get
                   :uri "/api/export/finance"
                   :query-string "period=2024-01-01,2024-01-31&marketplace=wb&format=excel"}
          response (app request)]
      (is (= 200 (:status response)))
      (is (= "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
             (get-in response [:headers "Content-Type"])))
      (is (re-find #"attachment; filename=\"finance-.*-wb\.xlsx\"" 
                   (get-in response [:headers "Content-Disposition"]))))))

(deftest test-export-route-default-format
  (testing "GET /api/export/:report defaults to Excel format"
    (let [app (server/app)
          request {:request-method :get
                   :uri "/api/export/ue"
                   :query-string "period=last-7-days"}
          response (app request)]
      (is (= 200 (:status response)))
      (is (= "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
             (get-in response [:headers "Content-Type"]))))))

(deftest test-export-route-invalid-period
  (testing "GET /api/export/:report with invalid period returns 400"
    (let [app (server/app)
          request {:request-method :get
                   :uri "/api/export/sales"
                   :query-string "period=invalid"}
          response (app request)]
      (is (= 400 (:status response))))))
