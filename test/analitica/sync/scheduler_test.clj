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

;; ---------------------------------------------------------------------------
;; Coverage gap detection + backfill (audit 2026-07-02 P0-2)
;; ---------------------------------------------------------------------------

(deftest month-seq-inclusive
  (is (= ["2026-01"] (scheduler/month-seq "2026-01" "2026-01")))
  (is (= ["2025-12" "2026-01" "2026-02"]
         (scheduler/month-seq "2025-12" "2026-02"))))

(deftest missing-months-pure
  (testing "months absent from the present set, edges included"
    (is (= ["2026-02" "2026-03"]
           (scheduler/missing-months #{"2026-01" "2026-04"} "2026-01" "2026-04")))
    (is (= [] (scheduler/missing-months #{"2026-01" "2026-02"} "2026-01" "2026-02")))
    (is (= ["2026-01" "2026-02" "2026-03"]
           (scheduler/missing-months #{} "2026-01" "2026-03")))))

(defn- seed-finance-month! [mp event-date]
  (db/execute!
    ["INSERT INTO finance (rrd_id, marketplace, date_from, date_to, event_date,
                           retail_amount, for_pay, synced_at)
      VALUES (?, ?, ?, ?, ?, 100.0, 90.0, '2026-06-01T00:00:00')"
     (Math/abs (.hashCode (str mp event-date))) (name mp)
     event-date event-date event-date]))

(deftest detect-finance-gaps-finds-empty-month
  (testing "a month with rows is not a gap; an empty interior month is"
    ;; as-of 2026-06-15, lookback 90d ⇒ covers Mar/Apr/May/Jun.
    (db/execute! ["DELETE FROM finance WHERE marketplace='wb'"])
    (seed-finance-month! :wb "2026-04-10")   ; April present
    (seed-finance-month! :wb "2026-06-05")   ; June (current, partial) present
    (let [gaps (->> (scheduler/detect-finance-gaps "2026-06-15" 90)
                    (filter #(= :wb (:marketplace %))))
          months (set (map :month gaps))]
      (is (contains? months "2026-05") "empty May flagged as a gap")
      (is (contains? months "2026-03") "empty March flagged as a gap")
      (is (not (contains? months "2026-04")) "April has data → not a gap")
      (is (not (contains? months "2026-06")) "current partial month never a gap")
      (let [may (first (filter #(= "2026-05" (:month %)) gaps))]
        (is (= "2026-05-01" (:from may)))
        (is (= "2026-05-31" (:to may)))))))

(deftest backfill-gaps-invokes-sync-per-gap-idempotently
  (testing "backfill-gaps! calls the injected start-sync! once per gap with its window"
    (db/execute! ["DELETE FROM finance WHERE marketplace='wb'"])
    (db/execute! ["DELETE FROM finance WHERE marketplace='ozon'"])
    (db/execute! ["DELETE FROM finance WHERE marketplace='ym'"])
    (seed-finance-month! :wb "2026-05-10")
    (seed-finance-month! :ozon "2026-05-10")
    (seed-finance-month! :ym "2026-05-10")
    (let [calls (atom [])
          fake  (fn [what & {:keys [marketplace period]}]
                  (swap! calls conj [what marketplace period]))]
      ;; as-of 2026-05-20, lookback 40d ⇒ only April+May; April empty for all → 3 gaps.
      (scheduler/backfill-gaps! fake "2026-05-20" 40)
      (is (= 3 (count @calls)) "one backfill per (mp, empty April)")
      (is (every? #(= :finance (first %)) @calls))
      (is (every? #(= ["2026-04-01" "2026-04-30"] (nth % 2)) @calls)))))
