(ns marker.pages.sync-test
  "Tests for pure helpers in marker.pages.sync."
  (:require [cljs.test :refer [deftest is testing]]
            [marker.pages.sync :refer [task-display-id task-row-data
                                       freshness-class parse-coverage-cell]]))

;; ---------------------------------------------------------------------------
;; task-display-id
;; ---------------------------------------------------------------------------

(deftest task-display-id-normal
  (testing "returns last two segments"
    (is (= "sales/extract"
           (task-display-id "abc-123/wb/sales/extract"))))
  (testing "two-segment id is returned as-is"
    (is (= "sales/extract"
           (task-display-id "sales/extract"))))
  (testing "single-segment id is returned as-is"
    (is (= "extract"
           (task-display-id "extract"))))
  (testing "nil returns nil"
    (is (nil? (task-display-id nil)))))

;; ---------------------------------------------------------------------------
;; task-row-data
;; ---------------------------------------------------------------------------

(def ^:private sample-task
  {:id          "run-1/wb/sales/extract"
   :marketplace "wb"
   :entity-type "sales"
   :phase       "extract"
   :status      "completed"
   :items       42})

(deftest task-row-data-fields
  (testing "picks and transforms all display fields"
    (let [row (task-row-data sample-task)]
      (is (= "sales/extract" (:display-id row)))
      (is (= :wb             (:marketplace row)))
      (is (= "sales"         (:entity-type row)))
      (is (= "extract"       (:phase row)))
      (is (= "completed"     (:status row)))
      (is (= 42              (:items row)))))

  (testing "nil marketplace becomes nil (not keyword)"
    (let [row (task-row-data (dissoc sample-task :marketplace))]
      (is (nil? (:marketplace row)))))

  (testing "nil status stays nil"
    (let [row (task-row-data (dissoc sample-task :status))]
      (is (nil? (:status row)))))

  (testing "failed task has status 'failed'"
    (let [row (task-row-data (assoc sample-task :status "failed"))]
      (is (= "failed" (:status row))))))

;; ---------------------------------------------------------------------------
;; freshness-class
;; ---------------------------------------------------------------------------

;; Fixed reference point: 2026-05-04 12:00 UTC
(def ^:private now-fixed (js/Date. "2026-05-04T12:00:00Z"))

(deftest freshness-class-good
  (testing "same day is good"
    (is (= "good" (freshness-class "2026-05-04" now-fixed))))
  (testing "1 day old is good"
    (is (= "good" (freshness-class "2026-05-03" now-fixed))))
  (testing "2 days old is good (boundary)"
    (is (= "good" (freshness-class "2026-05-02" now-fixed)))))

(deftest freshness-class-stale
  (testing "4 days old is stale"
    (is (= "stale" (freshness-class "2026-04-30" now-fixed))))  ; 4d before May 4 UTC noon
  (testing "6 days old is stale"
    (is (= "stale" (freshness-class "2026-04-28" now-fixed)))))  ; 6d before May 4

(deftest freshness-class-old
  (testing "8 days old is old"
    (is (= "old" (freshness-class "2026-04-26" now-fixed))))
  (testing "30 days old is old"
    (is (= "old" (freshness-class "2026-04-04" now-fixed)))))

(deftest freshness-class-missing
  (testing "nil returns missing"
    (is (= "missing" (freshness-class nil now-fixed))))
  (testing "empty string returns missing"
    (is (= "missing" (freshness-class "" now-fixed)))))

(deftest freshness-class-boundaries
  (testing "exactly 2.0 days old → good (boundary between good and stale)"
    (is (= "good" (freshness-class "2026-05-02T12:00:00Z" now-fixed))))
  (testing "exactly 3.0 days old → stale (just past good threshold)"
    (is (= "stale" (freshness-class "2026-05-01T12:00:00Z" now-fixed))))
  (testing "exactly 7.0 days old → stale (at upper stale boundary)"
    (is (= "stale" (freshness-class "2026-04-27T12:00:00Z" now-fixed))))
  (testing "just over 7 days old → old (one second past stale boundary)"
    (is (= "old" (freshness-class "2026-04-27T11:59:59Z" now-fixed)))))

;; ---------------------------------------------------------------------------
;; parse-coverage-cell
;; ---------------------------------------------------------------------------

(deftest parse-coverage-cell-map
  (testing "valid map returns normalised map"
    (let [result (parse-coverage-cell {:from "2026-04-01" :to "2026-04-30" :days 30})]
      (is (= "2026-04-01" (:from result)))
      (is (= "2026-04-30" (:to result)))
      (is (= 30           (:days result)))))
  (testing "map without days defaults days to 0"
    (let [result (parse-coverage-cell {:from "2026-04-01" :to "2026-04-30"})]
      (is (= 0 (:days result))))))

(deftest parse-coverage-cell-absent
  (testing "nil returns nil"
    (is (nil? (parse-coverage-cell nil))))
  (testing "sentinel string \"—\" returns nil"
    (is (nil? (parse-coverage-cell "—"))))
  (testing "any string returns nil"
    (is (nil? (parse-coverage-cell "n/a"))))
  (testing "map with missing :from returns nil"
    (is (nil? (parse-coverage-cell {:to "2026-04-30" :days 30}))))
  (testing "map with missing :to returns nil"
    (is (nil? (parse-coverage-cell {:from "2026-04-01" :days 30})))))

;; ---------------------------------------------------------------------------
;; format-iso / duration-s are private defn- so not directly importable.
;; task-display-id / task-row-data are the extracted public surface.
;; ---------------------------------------------------------------------------
