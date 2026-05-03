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
    (let [m      (:net-profit (pnl/calculate
                                (finance/fetch-finance april :marketplace :ym :source :db)
                                :marketplace :ym
                                :from (:from april) :to (:to april)))
          weekly (->> [april-w1 april-w2 april-w3 april-w4]
                      (map pnl-for)
                      (map :net-profit)
                      (reduce + 0.0))]
      (is (== m weekly)
          (format "Σ(weeks net)=%s must equal monthly net=%s." weekly m)))))

;; ---------------------------------------------------------------------------
;; ad-spend-by-article — per-article canonical lookup
;; ---------------------------------------------------------------------------

(defn- seed-row-art!
  "Like seed-row! but takes the article and marketplace explicitly."
  [{:keys [rrd-id article event-date ad-cost marketplace]
    :or   {marketplace :ym}}]
  (let [row (sync/finance->row {:rrd-id        rrd-id
                                :date-from     "2026-04-01"
                                :date-to       "2026-04-30"
                                :event-date    event-date
                                :article       article
                                :operation     "sale"
                                :quantity      1
                                :retail-amount 1000.0
                                :for-pay       800.0
                                :ad-cost       ad-cost
                                :marketplace   marketplace})]
    (db/insert-batch! :finance sync/finance-columns [row])))

(deftest ad-spend-by-article-canonical
  (testing "Per-article SUM(finance.ad_cost) groups correctly within the period"
    (seed-row-art! {:rrd-id 10 :article "ART-A" :event-date "2026-04-03" :ad-cost 100.0})
    (seed-row-art! {:rrd-id 11 :article "ART-A" :event-date "2026-04-10" :ad-cost  50.0})
    (seed-row-art! {:rrd-id 12 :article "ART-B" :event-date "2026-04-15" :ad-cost  25.0})
    (let [m (pnl/ad-spend-by-article (:from april) (:to april) :ym)]
      (is (= 150.0 (get m "ART-A")) "ART-A: 100 + 50 across two weeks")
      (is (=  25.0 (get m "ART-B")) "ART-B: 25 in one week")
      (is (= 2 (count m))           "Articles with positive spend only"))))

(deftest ad-spend-by-article-respects-period
  (testing "Per-article totals exclude rows outside [from..to] event_date"
    (seed-row-art! {:rrd-id 20 :article "ART-A" :event-date "2026-04-03" :ad-cost 100.0})
    (seed-row-art! {:rrd-id 21 :article "ART-A" :event-date "2026-04-24" :ad-cost  10.0})
    (let [w1 (pnl/ad-spend-by-article (:from april-w1) (:to april-w1) :ym)
          w4 (pnl/ad-spend-by-article (:from april-w4) (:to april-w4) :ym)]
      (is (= 100.0 (get w1 "ART-A")) "w1 picks up only the 04-03 row")
      (is (=  10.0 (get w4 "ART-A")) "w4 picks up only the 04-24 row"))))

(deftest ad-spend-by-article-mp-scoped
  (testing "marketplace=nil includes all MPs; marketplace=:ym scopes to YM rows"
    (seed-row-art! {:rrd-id 30 :article "ART-Y" :event-date "2026-04-03" :ad-cost 40.0 :marketplace :ym})
    (seed-row-art! {:rrd-id 31 :article "ART-W" :event-date "2026-04-03" :ad-cost 60.0 :marketplace :wb})
    (let [ym  (pnl/ad-spend-by-article (:from april) (:to april) :ym)
          all (pnl/ad-spend-by-article (:from april) (:to april) nil)]
      (is (= {"ART-Y" 40.0} ym)         "YM scope returns YM articles only")
      (is (= 40.0 (get all "ART-Y")) "all-MP includes YM article")
      (is (= 60.0 (get all "ART-W")) "all-MP includes WB article"))))

(deftest ad-spend-by-article-empty
  (testing "Returns empty map when no rows match — never throws"
    (is (= {} (pnl/ad-spend-by-article (:from april) (:to april) :ym))
        "no seeded rows → empty")
    (is (= {} (pnl/ad-spend-by-article nil nil :ym))
        "nil from/to → empty (no DB call)")))

(deftest ad-spend-by-article-aggregate-parity
  (testing "Σ(per-article spend) equals the aggregate ad-spend-total for the same scope"
    (seed-row-art! {:rrd-id 40 :article "ART-A" :event-date "2026-04-03" :ad-cost 100.0})
    (seed-row-art! {:rrd-id 41 :article "ART-B" :event-date "2026-04-10" :ad-cost  50.0})
    (seed-row-art! {:rrd-id 42 :article "ART-C" :event-date "2026-04-17" :ad-cost  25.0})
    (let [per-art (pnl/ad-spend-by-article (:from april) (:to april) :ym)
          agg    (-> (pnl/calculate (finance/fetch-finance april :marketplace :ym :source :db)
                                    :marketplace :ym :from (:from april) :to (:to april))
                     :ad-spend)]
      (is (= 175.0 agg))
      (is (= 175.0 (reduce + 0.0 (vals per-art)))
          "Per-article totals must reconcile with aggregate ad-spend."))))
