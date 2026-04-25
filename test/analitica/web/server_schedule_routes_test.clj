(ns analitica.web.server-schedule-routes-test
  "Phase 9 — tests for GET/POST /api/sync/schedule routes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.web.server :as server]
            [analitica.sync.scheduler :as scheduler]
            [analitica.db :as db]
            [jsonista.core :as json]))

(use-fixtures :once
  (fn [f]
    (db/init!)
    ;; Ensure the singleton schedule row exists.
    (db/execute! ["INSERT OR IGNORE INTO sync_schedule (id, created_at, updated_at)
                   VALUES (1, '2026-01-01T00:00:00', '2026-01-01T00:00:00')"])
    (db/execute! ["UPDATE sync_schedule
                   SET enabled=0, hour=6, minute=0, what='all', marketplace='all',
                       period='last-7-days', last_run_at=NULL, last_run_id=NULL, next_run_at=NULL
                   WHERE id=1"])
    (f)))

;; ---------------------------------------------------------------------------
;; Test 7 — GET /api/sync/schedule returns singleton
;; ---------------------------------------------------------------------------

(deftest schedule-get-returns-singleton
  (testing "GET /api/sync/schedule returns the singleton schedule row"
    (let [handler  (server/app)
          request  {:request-method :get
                    :uri            "/api/sync/schedule"
                    :params         {}}
          response (handler request)]
      (is (= 200 (:status response))
          "Should return 200")
      (let [body (json/read-value (:body response) json/keyword-keys-object-mapper)]
        (is (map? body) "Body should be a map")
        (is (contains? body :enabled)   "Should contain :enabled")
        (is (contains? body :hour)      "Should contain :hour")
        (is (contains? body :minute)    "Should contain :minute")
        (is (contains? body :what)      "Should contain :what")
        (is (contains? body :marketplace) "Should contain :marketplace")
        (is (contains? body :period)    "Should contain :period")))))

;; ---------------------------------------------------------------------------
;; Test 8 — POST /api/sync/schedule returns 200
;; ---------------------------------------------------------------------------

(deftest schedule-update-returns-200
  (testing "POST /api/sync/schedule with valid payload returns 200 and updated row"
    (let [handler  (server/app)
          payload  (json/write-value-as-string
                    {:enabled     false
                     :hour        8
                     :minute      15
                     :what        "all"
                     :marketplace "all"
                     :period      "last-7-days"})
          request  {:request-method :post
                    :uri            "/api/sync/schedule"
                    :headers        {"content-type" "application/json"}
                    :body           (java.io.ByteArrayInputStream.
                                     (.getBytes payload "UTF-8"))
                    :params         {}}
          ;; Stub start! so no real timer arms during test
          _        (with-redefs [scheduler/start! (fn [] nil)]
                     (let [response (handler request)]
                       (is (= 200 (:status response))
                           "Should return 200 for valid schedule update")
                       (let [body (json/read-value (:body response) json/keyword-keys-object-mapper)]
                         (is (map? body) "Body should be a map")
                         (is (= 0 (:enabled body)) "enabled should be 0 (false)")
                         (is (= 8 (:hour body))     "hour should be updated to 8")
                         (is (= 15 (:minute body))  "minute should be updated to 15"))))])))

(deftest schedule-update-rejects-bad-hour
  (testing "POST /api/sync/schedule with out-of-range hour returns 400"
    (let [handler (server/app)
          payload (json/write-value-as-string
                   {:enabled false :hour 25 :minute 0
                    :what "all" :marketplace "all" :period "last-7-days"})
          request {:request-method :post
                   :uri            "/api/sync/schedule"
                   :headers        {"content-type" "application/json"}
                   :body           (java.io.ByteArrayInputStream.
                                    (.getBytes payload "UTF-8"))
                   :params         {}}
          response (handler request)]
      (is (= 400 (:status response)) "Should return 400 for hour > 23"))))
