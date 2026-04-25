(ns analitica.sync.scheduler-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.sync.scheduler :as scheduler]
            [analitica.web.api.sync :as sync-api]
            [analitica.test-helpers :as h]
            [analitica.db :as db])
  (:import [java.time LocalDateTime]))

;; ---------------------------------------------------------------------------
;; DB fixture — test-analitica.db, seeded with singleton row
;; ---------------------------------------------------------------------------

(defn- with-seeded-schedule [f]
  ;; Ensure the singleton row exists (idempotent INSERT OR IGNORE)
  (db/execute! ["INSERT OR IGNORE INTO sync_schedule (id, created_at, updated_at)
                 VALUES (1, '2026-01-01T00:00:00', '2026-01-01T00:00:00')"])
  ;; Reset to known defaults
  (db/execute! ["UPDATE sync_schedule
                 SET enabled=0, hour=6, minute=0, what='all', marketplace='all',
                     period='last-7-days', last_run_at=NULL, last_run_id=NULL, next_run_at=NULL
                 WHERE id=1"])
  (f))

(use-fixtures :once h/with-test-db)
(use-fixtures :each with-seeded-schedule)

;; ---------------------------------------------------------------------------
;; 1. compute-initial-delay-ms — target is in the future today
;; ---------------------------------------------------------------------------

(deftest compute-initial-delay-future
  (testing "When target time is later today, delay is approximately correct"
    (let [now      (LocalDateTime/of 2026 4 25 12 0 0)
          delay    (scheduler/compute-initial-delay-ms now 14 0)
          expected 7200000]  ; 2 hours in ms
      (is (= expected delay)
          "12:00 → 14:00 should be exactly 7200000 ms"))))

;; ---------------------------------------------------------------------------
;; 2. compute-initial-delay-ms — target is in the past today → tomorrow
;; ---------------------------------------------------------------------------

(deftest compute-initial-delay-past-today-fires-tomorrow
  (testing "When target time has already passed today, delay rolls to tomorrow"
    (let [now      (LocalDateTime/of 2026 4 25 14 0 0)
          delay    (scheduler/compute-initial-delay-ms now 12 0)
          expected (* 22 3600 1000)]  ; 22 hours in ms
      (is (= expected delay)
          "14:00 → 12:00 (next day) should be 22h = 79200000 ms"))))

;; ---------------------------------------------------------------------------
;; 3. update-schedule! persists fields
;; ---------------------------------------------------------------------------

(deftest update-schedule-persists-fields
  (testing "update-schedule! writes all fields and populates next-run-at when enabled"
    ;; Stub start-timer! so no real ScheduledExecutorService fires
    (with-redefs [scheduler/start! (fn [] nil)]
      (let [row (scheduler/update-schedule!
                 {:enabled?    true
                  :hour        7
                  :minute      30
                  :what        "all"
                  :marketplace "all"
                  :period      "last-7-days"})]
        (is (= 1 (:enabled row)))
        (is (= 7 (:hour row)))
        (is (= 30 (:minute row)))
        (is (= "all" (:what row)))
        (is (= "all" (:marketplace row)))
        (is (= "last-7-days" (:period row)))
        (is (some? (:next-run-at row)) "next-run-at should be set when enabled")))))

;; ---------------------------------------------------------------------------
;; 4. update-schedule! with enabled=false clears current-handle
;; ---------------------------------------------------------------------------

(deftest update-schedule-disable-cancels-future
  (testing "Disabling schedule clears current-handle"
    (with-redefs [scheduler/start! (fn [] nil)]
      ;; Disable
      (let [row (scheduler/update-schedule!
                 {:enabled? false :hour 6 :minute 0 :what "all"
                  :marketplace "all" :period "last-7-days"})]
        (is (= 0 (:enabled row)))
        ;; After disabling, the atom's value (the handle) should be nil
        (is (nil? @@#'scheduler/current-handle))))))

;; ---------------------------------------------------------------------------
;; 5. fire! skips when sync-running? is true
;; ---------------------------------------------------------------------------

(deftest fire-skips-if-sync-running
  (testing "fire! does not call start-sync! when a manual sync is running"
    (let [call-count (atom 0)]
      (with-redefs [sync-api/sync-running? (atom true)
                    sync-api/start-sync!   (fn [& _] (swap! call-count inc) {:ok true :run-id "test"})
                    scheduler/start!       (fn [] nil)]
        (#'scheduler/fire!))
      (is (= 0 @call-count)
          "start-sync! must NOT be called when sync-running? is true"))))

;; ---------------------------------------------------------------------------
;; 6. fire! records last-run-at and last-run-id
;; ---------------------------------------------------------------------------

(deftest fire-records-last-run
  (testing "fire! updates last-run-at and last-run-id after a successful sync"
    (with-redefs [sync-api/sync-running? (atom false)
                  sync-api/start-sync!   (fn [& _] {:ok true :run-id "abc-run-id"})
                  scheduler/start!       (fn [] nil)]
      (#'scheduler/fire!))
    (let [row (scheduler/get-schedule)]
      (is (some? (:last-run-at row)) "last-run-at should be populated after fire!")
      (is (= "abc-run-id" (:last-run-id row))
          "last-run-id should match the returned run-id"))))
