(ns analitica.web.api.metrics-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.web.server :as server]
            [jsonista.core :as json]))

;; ---------------------------------------------------------------------------
;; Test Fixtures
;; ---------------------------------------------------------------------------

(defn init-test-db [f]
  (db/init!)
  (f))

(use-fixtures :once init-test-db)

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest ^:integration sync-coverage-endpoint-test
  (testing "GET /api/sync/coverage returns JSON with coverage data"
    (let [app (server/app)
          request {:request-method :get
                   :uri "/api/sync/coverage"}
          response (app request)]
      
      ;; Should return 200 OK
      (is (= 200 (:status response)))
      
      ;; Should return JSON content type
      (is (= "application/json; charset=utf-8" 
             (get-in response [:headers "Content-Type"])))
      
      ;; Body should be a string (JSON)
      (is (string? (:body response)))
      
      ;; Body should be valid JSON with coverage data structure
      (when (string? (:body response))
        (let [coverage (json/read-value (:body response) json/keyword-keys-object-mapper)]
          ;; Should be a map
          (is (map? coverage))
          ;; Should have marketplace keys or be empty
          (is (or (contains? coverage :wb)
                  (contains? coverage :ozon)
                  (contains? coverage :ym)
                  (contains? coverage :stats)
                  (contains? coverage :regions)
                  (contains? coverage :1c)
                  (contains? coverage :prices)
                  (empty? coverage))))))))

