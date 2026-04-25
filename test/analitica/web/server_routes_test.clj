(ns analitica.web.server-routes-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.web.server :as server]
            [analitica.web.api.sync :as sync-api]
            [analitica.util.time :as time]
            [jsonista.core :as json]
            [ring.core.protocols :as ring-protocols]))

(use-fixtures :each
  (fn [f]
    ;; Reset sync state before each test
    (reset! sync-api/sync-running? false)
    (reset! sync-api/progress-channel nil)
    (f)
    ;; Wait for any background threads to complete
    (Thread/sleep 500)
    ;; Force reset state after each test
    (reset! sync-api/sync-running? false)
    (reset! sync-api/progress-channel nil)))

(deftest test-parse-period
  (testing "parse-period utility function"
    (let [result (time/parse-period "last-week")]
      (is (vector? result)
          "last-week should return a vector")
      (is (= 2 (count result))
          "last-week should return [from to]"))
    (is (vector? (time/parse-period "last-7-days"))
        "last-7-days should return a vector")
    (is (vector? (time/parse-period "last-30-days"))
        "last-30-days should return a vector")
    (is (vector? (time/parse-period "this-month"))
        "this-month should return a vector")
    (is (= ["2026-04-01" "2026-04-30"]
           (time/parse-period "2026-04-01,2026-04-30"))
        "custom date range should be parsed correctly")
    (is (thrown? Exception (time/parse-period "invalid"))
        "invalid period should throw exception")
    (is (thrown? Exception (time/parse-period nil))
        "nil period should throw exception")))

(deftest test-sync-start-route
  (testing "POST /api/sync/start route"
    (let [handler (server/app)]
      
      (testing "with JSON body"
        (let [body-str (json/write-value-as-string {:what "1c"})
              request {:request-method :post
                       :uri "/api/sync/start"
                       :body (java.io.ByteArrayInputStream. (.getBytes body-str "UTF-8"))}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for valid sync request")
          (is (string? (:body response))
              "Body should be a JSON string")
          (let [body (json/read-value (:body response) json/keyword-keys-object-mapper)]
            (is (true? (:ok body))
                "Response should contain {:ok true}"))))
      
      (testing "with query parameters"
        ;; Force reset before this test
        (reset! sync-api/sync-running? false)
        (let [request {:request-method :post
                       :uri "/api/sync/start"
                       :params {:what "1c"}
                       :query-params {:what "1c"}
                       :body nil}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for valid sync request with query params")))
      
      (testing "without what parameter"
        (let [request {:request-method :post
                       :uri "/api/sync/start"
                       :body nil}
              response (handler request)]
          (is (= 400 (:status response))
              "Should return 400 when 'what' parameter is missing")))
      
      (testing "concurrent sync protection"
        ;; Force reset before this test
        (reset! sync-api/sync-running? false)
        ;; Start first sync
        (let [body-str (json/write-value-as-string {:what "1c"})
              request1 {:request-method :post
                        :uri "/api/sync/start"
                        :body (java.io.ByteArrayInputStream. (.getBytes body-str "UTF-8"))}
              response1 (handler request1)]
          (is (= 200 (:status response1))
              "First sync should succeed")
          
          ;; Try to start second sync immediately
          (let [body-str2 (json/write-value-as-string {:what "sales"})
                request2 {:request-method :post
                          :uri "/api/sync/start"
                          :body (java.io.ByteArrayInputStream. (.getBytes body-str2 "UTF-8"))}
                response2 (handler request2)]
            (is (= 409 (:status response2))
                "Second sync should return 409 Conflict")
            (let [body (json/read-value (:body response2) json/keyword-keys-object-mapper)]
              (is (= "already running" (:error body))
                  "Error message should indicate sync is already running"))))))))

(deftest ^:integration test-sync-start-with-period-and-marketplace
  (testing "POST /api/sync/start with period and marketplace"
    (let [handler (server/app)]
      
      (testing "with period parameter"
        (let [body-str (json/write-value-as-string {:what "sales" :period "last-week"})
              request {:request-method :post
                       :uri "/api/sync/start"
                       :body (java.io.ByteArrayInputStream. (.getBytes body-str "UTF-8"))}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for sync with period")))
      
      (testing "with marketplace parameter"
        (let [body-str (json/write-value-as-string {:what "sales" :marketplace "wb"})
              request {:request-method :post
                       :uri "/api/sync/start"
                       :body (java.io.ByteArrayInputStream. (.getBytes body-str "UTF-8"))}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for sync with marketplace")))
      
      (testing "with both period and marketplace"
        (let [body-str (json/write-value-as-string {:what "sales" 
                                                     :period "last-30-days" 
                                                     :marketplace "ozon"})
              request {:request-method :post
                       :uri "/api/sync/start"
                       :body (java.io.ByteArrayInputStream. (.getBytes body-str "UTF-8"))}
              response (handler request)]
          (is (= 200 (:status response))
              "Should return 200 for sync with both period and marketplace"))))))

(deftest test-sync-stream-route
  (testing "GET /api/sync/stream route"
    (let [handler (server/app)
          request {:request-method :get
                   :uri "/api/sync/stream"}
          response (handler request)]
      (is (= 200 (:status response))
          "Should return 200")
      (is (= "text/event-stream" (get-in response [:headers "Content-Type"]))
          "Should have correct Content-Type header")
      (is (= "no-cache" (get-in response [:headers "Cache-Control"]))
          "Should have no-cache header")
      (is (satisfies? ring-protocols/StreamableResponseBody (:body response))
          "Body should implement StreamableResponseBody"))))
