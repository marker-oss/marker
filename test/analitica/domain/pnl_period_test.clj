(ns analitica.domain.pnl-period-test
  "Regression coverage for the period boundary used inside pnl/calculate
   side-queries (ad-spend, cf-adjustments).

   Bug context (2026-05-02): pnl/calculate derived its ad-spend lookup
   range from the min/max date_from/date_to of the finance rows it
   was given. For pre-aggregated reports (YM order-stats, WB weekly,
   Ozon realization) those columns span the whole report window even
   though event_date is point-in-time. As a result:

     1. Monthly ad-spend was computed over a window WIDER than the
        requested period, pulling in unrelated rows.
     2. Weekly slices yielded OVERLAPPING derived windows; the same
        ad_cost was counted in 2-3 adjacent weeks. Σ(weekly net) <
        monthly net by the duplicated ad-spend.

   Invariant under test: ad-spend (and net-profit) for the input
   period equals the SUM of finance.ad_cost for rows whose
   event_date falls inside the period — regardless of how wide
   date_from/date_to extend."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.db :as db]
            [analitica.domain.finance :as finance]
            [analitica.domain.pnl :as pnl]
            [analitica.sync :as sync])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temp-file SQLite DB fixture
;; ---------------------------------------------------------------------------

(def ^:dynamic *test-db-path* nil)

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-pnl-period-test-" ".db"
                                   (make-array FileAttribute 0))
        f    (.toFile path)]
    (.delete f)
    (.getAbsolutePath f)))

(defn- delete-test-db! [path]
  (doseq [suffix ["" "-shm" "-wal"]]
    (let [f (File. (str path suffix))]
      (when (.exists f) (.delete f)))))

(defn with-temp-db [f]
  (let [path      (fresh-temp-db-path)
        orig-spec (deref #'db/db-spec)]
    (try
      (alter-var-root #'db/db-spec (constantly {:dbtype "sqlite" :dbname path}))
      (binding [*test-db-path* path]
        (db/init!)
        (f))
      (finally
        (alter-var-root #'db/db-spec (constantly orig-spec))
        (reset! @#'db/datasource nil)
        (delete-test-db! path)))))

(use-fixtures :each with-temp-db)

;; ---------------------------------------------------------------------------
;; Fixture: pre-aggregated report rows (YM/Ozon shape).
;; All four rows share the same wide date_from/date_to ("April report
;; window") but differ on event_date — one per ISO week of April.
;; ---------------------------------------------------------------------------

(defn- seed-row! [{:keys [rrd-id event-date for-pay ad-cost]}]
  (let [row (sync/finance->row {:rrd-id        rrd-id
                                :date-from     "2026-04-01"
                                :date-to       "2026-04-30"
                                :event-date    event-date
                                :article       "ART-A"
                                :operation     "sale"
                                :quantity      1
                                :retail-amount 1000.0
                                :for-pay       (or for-pay 800.0)
                                :ad-cost       ad-cost
                                :marketplace   :ym})]
    (db/insert-batch! :finance sync/finance-columns [row])))

(defn- seed-april-fixture! []
  (seed-row! {:rrd-id 1 :event-date "2026-04-03" :ad-cost 100.0})  ; w1
  (seed-row! {:rrd-id 2 :event-date "2026-04-10" :ad-cost  50.0})  ; w2
  (seed-row! {:rrd-id 3 :event-date "2026-04-17" :ad-cost  25.0})  ; w3
  (seed-row! {:rrd-id 4 :event-date "2026-04-24" :ad-cost  10.0})) ; w4

(def april         {:from "2026-04-01" :to "2026-04-30"})
(def april-w1      {:from "2026-04-01" :to "2026-04-07"})
(def april-w2      {:from "2026-04-08" :to "2026-04-14"})
(def april-w3      {:from "2026-04-15" :to "2026-04-21"})
(def april-w4      {:from "2026-04-22" :to "2026-04-28"})

(defn- pnl-for [period]
  (let [fin (finance/fetch-finance period :marketplace :ym :source :db)]
    (pnl/calculate fin :marketplace :ym :from (:from period) :to (:to period))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest monthly-ad-spend-matches-rows-in-period
  (testing "Monthly ad-spend = Σ(ad_cost) of rows whose event_date is in [from..to]"
    (seed-april-fixture!)
    (let [p (pnl-for april)]
      ;; 100 + 50 + 25 + 10 = 185 — all four rows have event_date in April.
      (is (= 185.0 (:ad-spend p))
          "Monthly ad-spend must reflect rows whose event_date is in the
           input period — not the wider date_from/date_to span."))))

(deftest weekly-slices-match-respective-rows
  (testing "Each weekly slice picks up only the row whose event_date falls in that week"
    (seed-april-fixture!)
    (is (= 100.0 (:ad-spend (pnl-for april-w1))) "w1 should see only the 04-03 row")
    (is (=  50.0 (:ad-spend (pnl-for april-w2))) "w2 should see only the 04-10 row")
    (is (=  25.0 (:ad-spend (pnl-for april-w3))) "w3 should see only the 04-17 row")
    (is (=  10.0 (:ad-spend (pnl-for april-w4))) "w4 should see only the 04-24 row")))

(deftest sum-of-weeks-equals-month
  (testing "Σ(weekly ad-spend) == monthly ad-spend for non-overlapping weeks"
    (seed-april-fixture!)
    (let [m       (:ad-spend (pnl-for april))
          weekly  (->> [april-w1 april-w2 april-w3 april-w4]
                       (map pnl-for)
                       (map :ad-spend)
                       (reduce + 0.0))]
      (is (= m weekly)
          (format "Σ(weeks)=%s must equal monthly=%s — overlapping derived
                   ranges must not double-count ad-spend."
                  weekly m)))))

(deftest sum-of-weeks-equals-month-net-profit
  (testing "Σ(weekly net-profit) == monthly net-profit"
    (seed-april-fixture!)
    (let [m      (:net-profit (pnl-for april))
          weekly (->> [april-w1 april-w2 april-w3 april-w4]
                      (map pnl-for)
                      (map :net-profit)
                      (reduce + 0.0))]
      (is (== m weekly)
          (format "Σ(weeks net)=%s must equal monthly net=%s." weekly m)))))
