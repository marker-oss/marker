(ns analitica.web.layout-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.web.server :as server]
            [analitica.config :as config]))

;; (server/app) now reads config (wrap-api-key → config/api-key; CORS →
;; config/cors-origins), which throw "Config not loaded" in this unloaded test
;; process (M4: surface load errors, don't swallow to nil). Pin both getters so
;; the handler builds; the root-route 302 assertions here are a pre-existing
;; baseline failure unrelated to this work.
(use-fixtures :each
  (fn [f]
    (with-redefs [config/api-key      (constantly nil)
                  config/cors-origins (constantly ["http://localhost:3000"])]
      (f))))

(deftest layout-integration-test
  (testing "Root route redirects to the Marker SPA"
    ;; Root is the public entrypoint and now 302-redirects to the SPA
    ;; (server.clj GET "/"); the legacy server-rendered layout lives at /wb,
    ;; /sync, /report (asserted below) and /legacy.
    (let [app (server/app)
          response (app {:request-method :get :uri "/"})]
      (is (= 302 (:status response)))
      (is (= "/app/pulse" (get-in response [:headers "Location"])))))
  
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
