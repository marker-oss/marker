(ns analitica.web.layout-integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.server :as server]))

(deftest layout-integration-test
  (testing "Root route renders layout with correct active route"
    (let [app (server/app)
          response (app {:request-method :get :uri "/"})]
      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (string? (:body response)))
      (is (re-find #"Главная" (:body response)))
      (is (re-find #"Analitica" (:body response)))))
  
  (testing "WB route renders layout with WB active"
    (let [app (server/app)
          response (app {:request-method :get :uri "/wb"})]
      (is (= 200 (:status response)))
      (is (re-find #"Wildberries" (:body response)))))
  
  (testing "Sync route renders layout with sync active"
    (let [app (server/app)
          response (app {:request-method :get :uri "/sync"})]
      (is (= 200 (:status response)))
      (is (re-find #"Синхронизация" (:body response)))))
  
  (testing "Report route renders layout with report active"
    (let [app (server/app)
          response (app {:request-method :get :uri "/reports/sales"})]
      (is (= 200 (:status response)))
      (is (re-find #"Продажи" (:body response))))))
