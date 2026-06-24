(ns analitica.marketplace.wb.ad-cost-migration-test
  "Tests for WB ad-cost migration (spec 003 US5, T039-T041).

   Covers:
     - T039: `materialize-wb-ad-cost!` populates `finance.ad_cost` for WB rows
       from `ad_stats` per B-003 proportional-to-revenue rule:
         * Direct attribution when ad_stats has per-nm_id rows (nm_id > 0)
         * Revenue-proportional allocation when only campaign-only rows exist
           (nm_id = 0 sentinel)
         * Idempotent: running twice yields the same ad_cost
         * Preserves B-005 invariant: for_pay unchanged
         * YM / Ozon finance rows untouched
     - T040: `pnl/ad-spend-total` prefers `:ad-cost` SUM over legacy JOIN
         * When ad_cost populated: uses new path
         * When ad_cost all zero but ad_stats has data: falls back to legacy
         * YM / Ozon always use ad_cost
     - T041: SC-009 validation — WB `SUM(finance.ad_cost)` ≥ legacy
       `ad-spend-by-article SUM`, delta ≥ 0 with documented coverage."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.db :as db]
            [analitica.materialize :as mat]
            [analitica.domain.pnl :as pnl]
            [analitica.sync :as sync])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temp-file SQLite DB fixture (mirrors other marketplace tests)
;; ---------------------------------------------------------------------------

(def ^:dynamic *test-db-path* nil)

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-wb-ad-cost-test-"
                                   ".db"
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
;; Fixture helpers
;; ---------------------------------------------------------------------------

(defn- seed-wb-finance-row!
  "Seed a WB finance row with defaults. `:ad-cost` defaults to 0 (via DDL)."
  [{:keys [rrd-id article nm-id date-from retail-amount for-pay operation]
    :or   {operation     "sale"
           retail-amount 1000.0
           for-pay       900.0}}]
  (let [row (sync/finance->row {:rrd-id        rrd-id
                                :date-from     date-from
                                :date-to       date-from
                                :article       article
                                :nm-id         nm-id
                                :operation     operation
                                :retail-amount retail-amount
                                :for-pay       for-pay
                                :marketplace   :wb})]
    (db/insert-batch! :finance sync/finance-columns [row])))

(defn- seed-ozon-finance-row!
  [{:keys [rrd-id article date-from for-pay ad-cost]
    :or   {for-pay 500.0 ad-cost 12.34}}]
  (let [row (sync/finance->row {:rrd-id      rrd-id
                                :date-from   date-from
                                :date-to     date-from
                                :article     article
                                :operation   "sale"
                                :for-pay     for-pay
                                :ad-cost     ad-cost
                                :marketplace :ozon})]
    (db/insert-batch! :finance sync/finance-columns [row])))

(defn- seed-ym-finance-row!
  [{:keys [rrd-id article date-from for-pay ad-cost]
    :or   {for-pay 300.0 ad-cost 45.67}}]
  (let [row (sync/finance->row {:rrd-id      rrd-id
                                :date-from   date-from
                                :date-to     date-from
                                :article     article
                                :operation   "sale"
                                :for-pay     for-pay
                                :ad-cost     ad-cost
                                :marketplace :ym})]
    (db/insert-batch! :finance sync/finance-columns [row])))

(defn- seed-ad-stats!
  "Insert an ad_stats row directly."
  [{:keys [campaign-id date nm-id spend]}]
  (db/execute! ["INSERT OR REPLACE INTO ad_stats
                   (campaign_id, date, nm_id, spend, synced_at)
                 VALUES (?, ?, ?, ?, ?)"
                campaign-id date (or nm-id 0) (double spend) "2026-04-22T00:00:00"]))

(defn- wb-ad-cost-by-article [article]
  (-> (db/query ["SELECT article, ad_cost, for_pay
                  FROM finance
                  WHERE marketplace='wb' AND article = ?"
                 article])
      first))

;; ---------------------------------------------------------------------------
;; T039 — materialize-wb-ad-cost! populates finance.ad_cost
;; ---------------------------------------------------------------------------

(deftest materialize-wb-ad-cost-direct-per-nm-id
  (testing "ad_stats rows with nm_id > 0 → direct attribution to finance.ad_cost"
    (seed-wb-finance-row! {:rrd-id 1 :article "ART-A" :nm-id 100
                           :date-from "2026-03-15"
                           :retail-amount 1000.0 :for-pay 800.0})
    (seed-wb-finance-row! {:rrd-id 2 :article "ART-B" :nm-id 200
                           :date-from "2026-03-15"
                           :retail-amount 2000.0 :for-pay 1700.0})
    (seed-ad-stats! {:campaign-id 11 :date "2026-03-15" :nm-id 100 :spend 50.0})
    (seed-ad-stats! {:campaign-id 11 :date "2026-03-15" :nm-id 200 :spend 120.0})

    (mat/materialize-wb-ad-cost! ["2026-03-01" "2026-03-31"])

    (is (= 50.0 (:ad-cost (wb-ad-cost-by-article "ART-A")))
        "ART-A direct attribution from its own nm_id")
    (is (= 120.0 (:ad-cost (wb-ad-cost-by-article "ART-B")))
        "ART-B direct attribution from its own nm_id")
    (is (= 800.0 (:for-pay (wb-ad-cost-by-article "ART-A")))
        "B-005: for_pay unchanged")))

(deftest materialize-wb-ad-cost-multiple-days-same-nm-id
  (testing "multiple (campaign, date) rows with same nm_id → SUM across dates"
    (seed-wb-finance-row! {:rrd-id 1 :article "ART-A" :nm-id 100
                           :date-from "2026-03-15"
                           :retail-amount 1000.0 :for-pay 800.0})
    (seed-wb-finance-row! {:rrd-id 2 :article "ART-A" :nm-id 100
                           :date-from "2026-03-20"
                           :retail-amount 1500.0 :for-pay 1300.0})
    (seed-ad-stats! {:campaign-id 11 :date "2026-03-15" :nm-id 100 :spend 40.0})
    (seed-ad-stats! {:campaign-id 11 :date "2026-03-20" :nm-id 100 :spend 60.0})
    (seed-ad-stats! {:campaign-id 22 :date "2026-03-20" :nm-id 100 :spend 25.0})

    (mat/materialize-wb-ad-cost! ["2026-03-01" "2026-03-31"])

    ;; Each ART-A finance row gets the spend for its own date
    ;; (40 for 03-15, 60+25=85 for 03-20), so SUM of ad_cost across the
    ;; article's rows = 40 + 85 = 125.
    (let [total (reduce + 0.0
                        (map :ad-cost
                             (db/query
                               ["SELECT ad_cost FROM finance
                                 WHERE marketplace='wb' AND article='ART-A'"])))]
      (is (= 125.0 total)
          "SUM across all ART-A rows = 40 (day 15) + 60 + 25 (day 20)"))))

(deftest materialize-wb-ad-cost-campaign-only-sentinel-allocation
  (testing "ad_stats rows with nm_id=0 sentinel → revenue-proportional allocation
            across WB articles with revenue on that date"
    ;; Two articles, same date, different revenue
    (seed-wb-finance-row! {:rrd-id 1 :article "ART-A" :nm-id 100
                           :date-from "2026-03-15"
                           :retail-amount 300.0 :for-pay 250.0})
    (seed-wb-finance-row! {:rrd-id 2 :article "ART-B" :nm-id 200
                           :date-from "2026-03-15"
                           :retail-amount 700.0 :for-pay 600.0})
    ;; Campaign-only row — no per-nm_id breakdown
    (seed-ad-stats! {:campaign-id 99 :date "2026-03-15" :nm-id 0 :spend 100.0})

    (mat/materialize-wb-ad-cost! ["2026-03-01" "2026-03-31"])

    ;; 100 × (300/1000) = 30; 100 × (700/1000) = 70
    (is (= 30.0 (:ad-cost (wb-ad-cost-by-article "ART-A"))))
    (is (= 70.0 (:ad-cost (wb-ad-cost-by-article "ART-B"))))

    ;; Conservation: sum of allocated = campaign spend
    (let [total (reduce + 0.0
                        (map :ad-cost
                             (db/query
                               ["SELECT ad_cost FROM finance
                                 WHERE marketplace='wb'"])))]
      (is (= 100.0 total) "allocation conserves campaign spend"))))

(deftest materialize-wb-ad-cost-mixed-per-nm-id-and-sentinel
  (testing "ad_stats with BOTH per-nm_id rows and a nm_id=0 row for the same date
            → per-nm_id wins for its articles; sentinel falls back to revenue-proportional
            across articles not covered by any per-nm_id row"
    (seed-wb-finance-row! {:rrd-id 1 :article "ART-A" :nm-id 100
                           :date-from "2026-03-15"
                           :retail-amount 400.0 :for-pay 350.0})
    (seed-wb-finance-row! {:rrd-id 2 :article "ART-B" :nm-id 200
                           :date-from "2026-03-15"
                           :retail-amount 600.0 :for-pay 500.0})
    ;; Campaign 11 has per-nm_id breakdown: 20 → ART-A (nm_id 100)
    (seed-ad-stats! {:campaign-id 11 :date "2026-03-15" :nm-id 100 :spend 20.0})
    ;; Campaign 22 only has campaign-total (nm_id=0), spend 50
    ;; → allocated by revenue: 50 × (400/1000)=20 → ART-A; 50 × (600/1000)=30 → ART-B
    (seed-ad-stats! {:campaign-id 22 :date "2026-03-15" :nm-id 0 :spend 50.0})

    (mat/materialize-wb-ad-cost! ["2026-03-01" "2026-03-31"])

    ;; ART-A: 20 (direct) + 20 (allocated) = 40
    ;; ART-B: 30 (allocated) = 30
    (is (= 40.0 (:ad-cost (wb-ad-cost-by-article "ART-A"))))
    (is (= 30.0 (:ad-cost (wb-ad-cost-by-article "ART-B"))))))

(deftest materialize-wb-ad-cost-zero-revenue-campaign-fallback
  (testing "campaign-only (nm_id=0) spend on a date with zero finance revenue
            → equal-split fallback across WB articles with any finance row on that date"
    ;; Both rows have 0 retail_amount
    (seed-wb-finance-row! {:rrd-id 1 :article "ART-A" :nm-id 100
                           :date-from "2026-03-15"
                           :retail-amount 0.0 :for-pay 0.0})
    (seed-wb-finance-row! {:rrd-id 2 :article "ART-B" :nm-id 200
                           :date-from "2026-03-15"
                           :retail-amount 0.0 :for-pay 0.0})
    (seed-ad-stats! {:campaign-id 99 :date "2026-03-15" :nm-id 0 :spend 40.0})

    (mat/materialize-wb-ad-cost! ["2026-03-01" "2026-03-31"])

    ;; Equal-split fallback: 40 / 2 = 20 per article
    (is (= 20.0 (:ad-cost (wb-ad-cost-by-article "ART-A"))))
    (is (= 20.0 (:ad-cost (wb-ad-cost-by-article "ART-B"))))))

(deftest materialize-wb-ad-cost-idempotent
  (testing "running materialize twice yields the same ad_cost (no double-adding)"
    (seed-wb-finance-row! {:rrd-id 1 :article "ART-A" :nm-id 100
                           :date-from "2026-03-15"
                           :retail-amount 1000.0 :for-pay 800.0})
    (seed-ad-stats! {:campaign-id 11 :date "2026-03-15" :nm-id 100 :spend 50.0})

    (mat/materialize-wb-ad-cost! ["2026-03-01" "2026-03-31"])
    (is (= 50.0 (:ad-cost (wb-ad-cost-by-article "ART-A")))
        "first run: 50")

    (mat/materialize-wb-ad-cost! ["2026-03-01" "2026-03-31"])
    (is (= 50.0 (:ad-cost (wb-ad-cost-by-article "ART-A")))
        "second run: STILL 50 (absolute SET, not additive)")))

(deftest materialize-wb-ad-cost-preserves-other-marketplaces
  (testing "YM and Ozon ad_cost values are NOT touched by WB materialize"
    (seed-wb-finance-row! {:rrd-id 1 :article "WB-A" :nm-id 100
                           :date-from "2026-03-15"
                           :retail-amount 1000.0 :for-pay 800.0})
    (seed-ozon-finance-row! {:rrd-id 2 :article "OZ-A" :date-from "2026-03-15"
                             :for-pay 500.0 :ad-cost 12.34})
    (seed-ym-finance-row! {:rrd-id 3 :article "YM-A" :date-from "2026-03-15"
                           :for-pay 300.0 :ad-cost 45.67})
    (seed-ad-stats! {:campaign-id 11 :date "2026-03-15" :nm-id 100 :spend 50.0})

    (mat/materialize-wb-ad-cost! ["2026-03-01" "2026-03-31"])

    (let [ozon (first (db/query
                        ["SELECT ad_cost FROM finance
                          WHERE marketplace='ozon' AND article='OZ-A'"]))
          ym   (first (db/query
                        ["SELECT ad_cost FROM finance
                          WHERE marketplace='ym' AND article='YM-A'"]))]
      (is (= 12.34 (:ad-cost ozon))
          "Ozon ad_cost untouched")
      (is (= 45.67 (:ad-cost ym))
          "YM ad_cost untouched")
      (is (= 50.0 (:ad-cost (wb-ad-cost-by-article "WB-A")))
          "WB ad_cost populated"))))

(deftest materialize-wb-ad-cost-no-ad-stats-clears-prior-population
  (testing "when ad_stats has NO rows in window but finance previously had ad_cost
            from earlier run → new run resets ad_cost to 0 (absolute SET)"
    (seed-wb-finance-row! {:rrd-id 1 :article "ART-A" :nm-id 100
                           :date-from "2026-03-15"
                           :retail-amount 1000.0 :for-pay 800.0})
    (seed-ad-stats! {:campaign-id 11 :date "2026-03-15" :nm-id 100 :spend 50.0})

    (mat/materialize-wb-ad-cost! ["2026-03-01" "2026-03-31"])
    (is (= 50.0 (:ad-cost (wb-ad-cost-by-article "ART-A"))))

    ;; Clear ad_stats
    (db/execute! ["DELETE FROM ad_stats"])
    (mat/materialize-wb-ad-cost! ["2026-03-01" "2026-03-31"])
    (is (= 0.0 (or (:ad-cost (wb-ad-cost-by-article "ART-A")) 0.0))
        "ad_cost resets to 0 when no ad_stats for the period")))

(deftest materialize-wb-ad-cost-period-scoped
  (testing "only WB rows whose date_from falls in [from..to] are affected"
    (seed-wb-finance-row! {:rrd-id 1 :article "ART-A" :nm-id 100
                           :date-from "2026-03-15"
                           :retail-amount 1000.0 :for-pay 800.0})
    (seed-wb-finance-row! {:rrd-id 2 :article "ART-A" :nm-id 100
                           :date-from "2026-02-15"
                           :retail-amount 500.0 :for-pay 400.0})
    (seed-ad-stats! {:campaign-id 11 :date "2026-03-15" :nm-id 100 :spend 50.0})
    (seed-ad-stats! {:campaign-id 11 :date "2026-02-15" :nm-id 100 :spend 30.0})

    ;; Only materialize March
    (mat/materialize-wb-ad-cost! ["2026-03-01" "2026-03-31"])

    (let [mar (first (db/query
                       ["SELECT ad_cost FROM finance
                         WHERE marketplace='wb' AND date_from='2026-03-15'"]))
          feb (first (db/query
                       ["SELECT ad_cost FROM finance
                         WHERE marketplace='wb' AND date_from='2026-02-15'"]))]
      (is (= 50.0 (:ad-cost mar))
          "March row populated")
      (is (or (nil? (:ad-cost feb)) (zero? (:ad-cost feb)))
          "February row NOT touched (outside period)"))))

;; ---------------------------------------------------------------------------
;; T040 — pnl/ad-spend-total prefers :ad-cost, falls back to legacy
;; ---------------------------------------------------------------------------

(deftest ad-spend-total-prefers-ad-cost-when-populated
  (testing "once finance.ad_cost is populated, ad-spend-total SUMs it (not legacy JOIN)"
    (seed-wb-finance-row! {:rrd-id 1 :article "ART-A" :nm-id 100
                           :date-from "2026-03-15"
                           :retail-amount 1000.0 :for-pay 800.0})
    (seed-ad-stats! {:campaign-id 11 :date "2026-03-15" :nm-id 100 :spend 50.0})
    (mat/materialize-wb-ad-cost! ["2026-03-01" "2026-03-31"])

    (let [fin-data (db/query
                     ["SELECT date_from, date_to, ad_cost, for_pay, marketplace
                       FROM finance"])
          pnl     (pnl/calculate fin-data :marketplace :wb)]
      (is (= 50.0 (:ad-spend pnl))
          "ad-spend = SUM(finance.ad_cost) via canonical path"))))

(deftest ad-spend-total-falls-back-to-legacy-when-ad-cost-empty
  (testing "when WB finance.ad_cost is all zero but ad_stats has data
            → fallback to legacy JOIN path (migration-friendly)"
    (seed-wb-finance-row! {:rrd-id 1 :article "ART-A" :nm-id 100
                           :date-from "2026-03-15"
                           :retail-amount 1000.0 :for-pay 800.0})
    (seed-ad-stats! {:campaign-id 11 :date "2026-03-15" :nm-id 100 :spend 77.0})
    ;; Deliberately DO NOT call materialize-wb-ad-cost!
    ;; → finance.ad_cost stays 0.

    (let [fin-data (db/query
                     ["SELECT date_from, date_to, ad_cost, for_pay, marketplace
                       FROM finance"])
          pnl     (pnl/calculate fin-data :marketplace :wb)]
      (is (= 77.0 (:ad-spend pnl))
          "legacy JOIN used when ad_cost is empty")
      (is (= :legacy (:ad-cost-source pnl))
          "source flag is :legacy when falling back to ad_stats JOIN"))))

(deftest ad-spend-total-ym-uses-ad-cost-only
  (testing "YM always uses :ad-cost (never legacy, since ad_stats is WB-only)"
    (seed-ym-finance-row! {:rrd-id 1 :article "YM-A" :date-from "2026-03-15"
                           :for-pay 300.0 :ad-cost 123.45})
    (let [fin-data (db/query
                     ["SELECT date_from, date_to, ad_cost, for_pay, marketplace
                       FROM finance"])
          pnl      (pnl/calculate fin-data :marketplace :ym)]
      (is (= 123.45 (:ad-spend pnl))
          "YM reads SUM(ad_cost) directly"))))

(deftest ad-spend-total-ozon-uses-ad-cost-only
  (testing "Ozon always uses :ad-cost (populated by US3 service-merge)"
    (seed-ozon-finance-row! {:rrd-id 1 :article "OZ-A" :date-from "2026-03-15"
                             :for-pay 500.0 :ad-cost 99.99})
    (let [fin-data (db/query
                     ["SELECT date_from, date_to, ad_cost, for_pay, marketplace
                       FROM finance"])
          pnl      (pnl/calculate fin-data :marketplace :ozon)]
      (is (= 99.99 (:ad-spend pnl))
          "Ozon reads SUM(ad_cost) directly"))))

;; ---------------------------------------------------------------------------
;; T041 — SC-009 validation: new ≥ legacy
;; ---------------------------------------------------------------------------

(deftest sc-009-new-vs-legacy-parity
  (testing "SC-009: SUM(finance.ad_cost WHERE marketplace='wb' AND period=P)
            ≥ legacy SUM(ad-spend-by-article), and within 1₽ rounding
            for the per-nm_id happy-path case"
    ;; Setup: single-article campaign with direct per-nm_id attribution →
    ;; both paths should agree exactly.
    (seed-wb-finance-row! {:rrd-id 1 :article "ART-A" :nm-id 100
                           :date-from "2026-03-15"
                           :retail-amount 1000.0 :for-pay 800.0})
    (seed-wb-finance-row! {:rrd-id 2 :article "ART-B" :nm-id 200
                           :date-from "2026-03-20"
                           :retail-amount 1500.0 :for-pay 1300.0})
    (seed-ad-stats! {:campaign-id 11 :date "2026-03-15" :nm-id 100 :spend 50.0})
    (seed-ad-stats! {:campaign-id 11 :date "2026-03-20" :nm-id 200 :spend 80.0})

    ;; Legacy path (pre-migration)
    (let [legacy (->> (db/ad-spend-by-article "2026-03-01" "2026-03-31"
                                              :marketplace :wb)
                      (map :ad-spend)
                      (reduce + 0.0))]
      (is (= 130.0 legacy) "legacy sees 50 + 80"))

    ;; Run new path
    (let [summary (mat/materialize-wb-ad-cost! ["2026-03-01" "2026-03-31"])
          new-sum (or (:sum (first (db/query
                                     ["SELECT SUM(ad_cost) AS sum
                                       FROM finance
                                       WHERE marketplace='wb'
                                         AND date_from >= ?
                                         AND date_from <= ?"
                                      "2026-03-01" "2026-03-31"])))
                      0.0)
          legacy (->> (db/ad-spend-by-article "2026-03-01" "2026-03-31"
                                              :marketplace :wb)
                      (map :ad-spend)
                      (reduce + 0.0))
          delta  (- new-sum legacy)]
      (is (<= -1.0 delta 1.0)
          (format "SC-009: new=%.2f legacy=%.2f delta=%.4f (should be within ±1₽)"
                  new-sum legacy delta))
      (is (>= new-sum (- legacy 1.0))
          "new ≥ legacy (legacy never captures MORE than new)")
      (is (= 130.0 (:total-spend-allocated summary))
          "summary includes :total-spend-allocated")
      (is (= 130.0 (:legacy-ad-spend summary))
          "summary includes :legacy-ad-spend for SC-009 comparison")
      (is (<= -1.0 (:delta summary) 1.0)
          "summary :delta within rounding for the parity case"))))

(deftest sc-009-new-captures-more-than-legacy-on-campaign-only-rows
  (testing "Scenario where legacy silently drops nm_id=0 rows but new captures them
            → new > legacy, and excess is 'newly captured multi-campaign allocation'"
    (seed-wb-finance-row! {:rrd-id 1 :article "ART-A" :nm-id 100
                           :date-from "2026-03-15"
                           :retail-amount 1000.0 :for-pay 800.0})
    ;; Campaign-only row (nm_id=0): legacy JOIN filters out, new path
    ;; allocates it.
    (seed-ad-stats! {:campaign-id 99 :date "2026-03-15" :nm-id 0 :spend 100.0})

    (mat/materialize-wb-ad-cost! ["2026-03-01" "2026-03-31"])

    ;; Inline the pre-2026-04-24 legacy ad_stats JOIN query (the old
    ;; behaviour of db/ad-spend-by-article before it was upgraded to
    ;; prefer canonical finance.ad_cost). This captures "what the old
    ;; path would have returned" for the SC-009 comparison.
    (let [legacy (->> (db/query
                        ["SELECT f.article, SUM(a.spend) AS ad_spend
                          FROM ad_stats a
                          JOIN (SELECT DISTINCT nm_id, article, marketplace FROM finance
                                WHERE article IS NOT NULL AND article != '') f
                            ON a.nm_id = f.nm_id
                          WHERE a.date >= ? AND a.date <= ?
                            AND f.marketplace = ?
                          GROUP BY f.article"
                         "2026-03-01" "2026-03-31" "wb"])
                      (map :ad-spend)
                      (reduce + 0.0))
          new-sum (or (:sum (first (db/query
                                     ["SELECT SUM(ad_cost) AS sum
                                       FROM finance
                                       WHERE marketplace='wb'
                                         AND date_from >= ?
                                         AND date_from <= ?"
                                      "2026-03-01" "2026-03-31"])))
                      0.0)
          canonical-by-article (->> (db/ad-spend-by-article
                                      "2026-03-01" "2026-03-31"
                                      :marketplace :wb)
                                    (map :ad-spend)
                                    (reduce + 0.0))]
      (is (zero? legacy)
          "legacy ad_stats JOIN drops campaign-only rows silently")
      (is (= 100.0 new-sum)
          "new path allocates campaign-only spend proportional to revenue → ART-A gets all 100")
      (is (> new-sum legacy)
          "new captures what legacy drops")
      (is (= 100.0 canonical-by-article)
          "db/ad-spend-by-article now prefers canonical finance.ad_cost and matches new-sum"))))

;; ---------------------------------------------------------------------------
;; End-to-end wiring: materialize-finance! for :wb auto-runs ad-cost
;; ---------------------------------------------------------------------------

(deftest materialize-finance-wb-auto-runs-ad-cost-when-ad-stats-present
  (testing "When ad_stats raw_data exists, materialize-finance! :wb automatically
            chains into materialize-wb-ad-stats! + materialize-wb-ad-cost!
            so the user gets ad_cost populated with a single command."
    ;; Simplest approach: seed the finance row directly (bypassing transform).
    ;; materialize-finance! :wb with no :finance raw_data is a no-op for the
    ;; first step; it preserves the seeded row and then chains ad-stats + ad-cost.
    (seed-wb-finance-row! {:rrd-id 1 :article "ART-A" :nm-id 100
                           :date-from "2026-03-15"
                           :retail-amount 1000.0 :for-pay 800.0})
    ;; Seed raw ad_stats response (shape per wb/fullstats).
    (db/insert-raw! :wb :ad_stats "2026-03-01" "2026-03-31"
                    [{:id 11
                      :days [{:date "2026-03-15"
                              :sum 50.0
                              :apps [{:nm_id 100 :sum 50.0}]}]}])

    (mat/materialize-finance! ["2026-03-01" "2026-03-31"] :marketplace :wb)

    (let [row (first (db/query
                       ["SELECT article, ad_cost, for_pay
                         FROM finance
                         WHERE marketplace='wb' AND article='ART-A'"]))]
      (is (= 50.0 (:ad-cost row))
          "ad_cost populated through the chained materialize pipeline")
      (is (= 800.0 (:for-pay row))
          "B-005: for_pay preserved through the full pipeline"))))

(deftest materialize-finance-wb-skips-ad-cost-when-no-ad-stats-raw
  (testing "When there's no ad_stats raw_data, materialize-finance! :wb skips
            the ad-cost step entirely → finance.ad_cost stays 0 (no errors)."
    (seed-wb-finance-row! {:rrd-id 1 :article "ART-A" :nm-id 100
                           :date-from "2026-03-15"
                           :retail-amount 1000.0 :for-pay 800.0})

    ;; No ad_stats raw_data seeded → pipeline should just no-op the extra step.
    (mat/materialize-finance! ["2026-03-01" "2026-03-31"] :marketplace :wb)

    (let [row (first (db/query
                       ["SELECT ad_cost FROM finance
                         WHERE marketplace='wb' AND article='ART-A'"]))]
      (is (or (nil? (:ad-cost row)) (zero? (:ad-cost row)))
          "ad_cost untouched when ad_stats raw is absent"))))
