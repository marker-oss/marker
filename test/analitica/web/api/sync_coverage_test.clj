(ns analitica.web.api.sync-coverage-test
  "Storage-row regression tests for the /sync heatmap data source.
   See claude-mem S72: Ozon storage lives in cash_flow_periods, not paid_storage;
   YM has no marketplace storage at all (FBS-only)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.web.api.sync-coverage :as sc]))

(use-fixtures :once
  (fn [f]
    (db/init!)
    (f)))

(deftest ^:integration ozon-storage-from-cashflow-test
  (testing "Ozon :storage days come from cash_flow_periods, expanded by period"
    (db/clear-table! :cash_flow_periods)
    (db/insert-batch! :cash_flow_periods
                      [:source :period_begin :period_end :synced_at :storage]
                      [["ozon" "2026-03-01" "2026-03-03" "2026-04-01T00:00:00" -50.0]
                       ["ozon" "2026-03-10" "2026-03-11" "2026-04-01T00:00:00" -75.0]])
    (let [days (get-in (sc/coverage-by-mp-and-type) [:ozon :storage :days])]
      (is (= ["2026-03-01" "2026-03-02" "2026-03-03"
              "2026-03-10" "2026-03-11"]
             days)))))

(deftest ^:integration ozon-storage-empty-when-no-cashflow-test
  (testing "Ozon :storage returns empty days vector when cash_flow_periods empty"
    (db/clear-table! :cash_flow_periods)
    (let [storage (get-in (sc/coverage-by-mp-and-type) [:ozon :storage])]
      (is (map? storage))
      (is (= [] (:days storage))))))

(deftest ^:integration ym-storage-omitted-test
  (testing "YM has no :storage key — FBS-only, omitted to avoid false-broken signal in heatmap"
    (let [ym (get (sc/coverage-by-mp-and-type) :ym)]
      (is (map? ym))
      (is (not (contains? ym :storage))))))

(deftest ^:integration wb-storage-still-from-paid-storage-test
  (testing "WB :storage continues to read from paid_storage (not regressed)"
    (let [wb (get (sc/coverage-by-mp-and-type) :wb)]
      (is (contains? wb :storage))
      (is (vector? (get-in wb [:storage :days]))))))

;; ---------------------------------------------------------------------------
;; P0-A Part B (specs/010) — honest per-calendar-day coverage semantics (pure)
;; ---------------------------------------------------------------------------

(deftest day-list->coverage-empty
  (let [r (sc/day-list->coverage :event-stream [])]
    (is (= 0 (:present r)))
    (is (= :missing (:status r)))
    (is (nil? (:span r)))))

(deftest day-list->coverage-snapshot
  (testing "snapshot reports as-of, never a span/holes/expected (point-in-time)"
    (let [r (sc/day-list->coverage :snapshot ["2026-05-03"])]
      (is (= :snapshot (:kind r)))
      (is (= "2026-05-03" (:as-of r)))
      (is (nil? (:holes r)))
      (is (nil? (:expected r))))))

(deftest day-list->coverage-event-with-holes
  (testing "calendar-day present/expected + hole ranges within span"
    (let [r (sc/day-list->coverage :event-stream
                                   ["2026-05-01" "2026-05-02" "2026-05-05"])]
      (is (= 3 (:present r)))
      (is (= {:from "2026-05-01" :to "2026-05-05"} (:span r)))
      (is (= 5 (:expected r)))
      (is (= [["2026-05-03" "2026-05-04"]] (:holes r)))
      (is (= :partial (:status r))))))

(deftest day-list->coverage-full
  (testing "contiguous coverage → :full, no holes"
    (let [r (sc/day-list->coverage :event-stream
                                   ["2026-05-01" "2026-05-02" "2026-05-03"])]
      (is (= :full (:status r)))
      (is (empty? (:holes r))))))

(deftest day-list->coverage-collapses-timestamps-to-calendar-days
  (testing "duplicate days collapse — fixes the 'WB orders 2652 дн' row-count bug"
    (let [r (sc/day-list->coverage :event-stream
                                   ["2026-05-02" "2026-05-01" "2026-05-01" "2026-05-02"])]
      (is (= 2 (:present r))
          "present must be distinct calendar days, not record count"))))
