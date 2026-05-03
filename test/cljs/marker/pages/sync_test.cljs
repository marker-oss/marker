(ns marker.pages.sync-test
  "Tests for pure helpers in marker.pages.sync."
  (:require [cljs.test :refer [deftest is testing]]
            [marker.pages.sync :refer [task-display-id task-row-data
                                       freshness-class parse-coverage-cell
                                       parse-schedule-payload
                                       schedule-form->body
                                       validate-schedule-form]]))

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

;; ---------------------------------------------------------------------------
;; parse-schedule-payload
;; ---------------------------------------------------------------------------

(deftest parse-schedule-payload-nil
  (testing "nil body returns defaults"
    (let [r (parse-schedule-payload nil)]
      (is (nil? r)))))

(deftest parse-schedule-payload-full-body
  (testing "full snake_case body is normalised"
    (let [r (parse-schedule-payload {:enabled true :hour 3 :minute 30
                                     :what "finance" :marketplace "wb"
                                     :period "last-30-days"
                                     :next_run_at "2026-05-04T03:30:00Z"})]
      (is (true? (:enabled r)))
      (is (= 3 (:hour r)))
      (is (= 30 (:minute r)))
      (is (= "finance" (:what r)))
      (is (= "wb" (:marketplace r)))
      (is (= "last-30-days" (:period r)))
      (is (= "2026-05-04T03:30:00Z" (:next-run-at r)))))
  (testing "missing next_run_at produces nil :next-run-at"
    (let [r (parse-schedule-payload {:enabled false :hour 6 :minute 0
                                     :what "all" :marketplace "all"
                                     :period "last-7-days"})]
      (is (nil? (:next-run-at r)))))
  (testing "defaults fill in missing keys"
    (let [r (parse-schedule-payload {})]
      (is (false? (:enabled r)))
      (is (= 6 (:hour r)))
      (is (= 0 (:minute r)))
      (is (= "all" (:what r)))
      (is (= "all" (:marketplace r)))
      (is (= "last-7-days" (:period r))))))

;; ---------------------------------------------------------------------------
;; schedule-form->body
;; ---------------------------------------------------------------------------

(deftest schedule-form->body-strips-extras
  (testing "only expected keys are forwarded"
    (let [body (schedule-form->body {:enabled true :hour 6 :minute 0
                                     :what "all" :marketplace "all"
                                     :period "last-7-days"
                                     :next-run-at "2026-05-04T06:00:00Z"
                                     :extra-junk 42})]
      (is (= #{:enabled :hour :minute :what :marketplace :period}
             (set (keys body))))
      (is (true? (:enabled body)))
      (is (= 6 (:hour body)))
      (is (nil? (:next-run-at body)))
      (is (nil? (:extra-junk body))))))

;; ---------------------------------------------------------------------------
;; validate-schedule-form
;; ---------------------------------------------------------------------------

(def ^:private valid-form
  {:enabled true :hour 6 :minute 0 :what "all" :marketplace "all" :period "last-7-days"})

(deftest validate-schedule-form-valid
  (testing "valid form returns nil"
    (is (nil? (validate-schedule-form valid-form))))
  (testing "hour 0 is valid boundary"
    (is (nil? (validate-schedule-form (assoc valid-form :hour 0)))))
  (testing "hour 23 is valid boundary"
    (is (nil? (validate-schedule-form (assoc valid-form :hour 23)))))
  (testing "minute 0 is valid boundary"
    (is (nil? (validate-schedule-form (assoc valid-form :minute 0)))))
  (testing "minute 59 is valid boundary"
    (is (nil? (validate-schedule-form (assoc valid-form :minute 59))))))

(deftest validate-schedule-form-invalid-hour
  (testing "hour 24 is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :hour 24)))))
  (testing "hour -1 is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :hour -1)))))
  (testing "nil hour is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :hour nil)))))
  (testing "string hour is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :hour "6"))))))

(deftest validate-schedule-form-invalid-minute
  (testing "minute 60 is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :minute 60)))))
  (testing "minute -1 is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :minute -1)))))
  (testing "nil minute is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :minute nil))))))

(deftest validate-schedule-form-invalid-what
  (testing "unknown what value is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :what "unknown")))))
  (testing "nil what is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :what nil)))))
  (testing "all known what values are valid"
    (doseq [v ["all" "sales" "orders" "finance" "storage" "stocks"
               "stats" "prices" "regions" "cashflow"]]
      (is (nil? (validate-schedule-form (assoc valid-form :what v)))
          (str "expected nil for what=" v)))))

(deftest validate-schedule-form-invalid-marketplace
  (testing "unknown marketplace is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :marketplace "amazon")))))
  (testing "nil marketplace is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :marketplace nil)))))
  (testing "all valid marketplaces pass"
    (doseq [v ["all" "wb" "ozon" "ym"]]
      (is (nil? (validate-schedule-form (assoc valid-form :marketplace v)))
          (str "expected nil for marketplace=" v)))))

(deftest validate-schedule-form-invalid-period
  (testing "unknown period is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :period "last-14-days")))))
  (testing "nil period is invalid"
    (is (some? (validate-schedule-form (assoc valid-form :period nil)))))
  (testing "all valid periods pass"
    (doseq [v ["last-week" "last-7-days" "last-30-days" "this-month"]]
      (is (nil? (validate-schedule-form (assoc valid-form :period v)))
          (str "expected nil for period=" v)))))
