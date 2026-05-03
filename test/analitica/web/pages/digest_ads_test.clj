(ns analitica.web.pages.digest-ads-test
  "Tests for the ads-traffic data assembly in digest/collect-page-data!.
   Bug #1: ad_stats query was computed but never wired into :pulse-data —
   these tests pin the orchestrator to actually surface the data."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.web.pages.digest :as digest]
            analitica.test-helpers)
  (:import [java.time YearMonth]))

(use-fixtures :once analitica.test-helpers/with-test-db)

(defn- this-month-day [day]
  (let [ym (YearMonth/now)]
    (str (.atDay ym day))))

(defn- insert-ad-row! [{:keys [campaign-id date views clicks spend nm-id]
                        :or {nm-id 0}}]
  (db/execute!
    ["INSERT OR REPLACE INTO ad_stats
        (campaign_id, date, views, clicks, ctr, cpc, spend, nm_id, synced_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, datetime('now'))"
     campaign-id date views clicks
     (if (pos? views) (* 100.0 (/ (double clicks) (double views))) 0.0)
     (if (pos? clicks) (/ (double spend) (double clicks)) 0.0)
     spend nm-id]))

(defn- clear! []
  (db/execute! ["DELETE FROM ad_stats"]))

(deftest collect-ads-traffic-aggregates-rows-in-period
  (testing "Aggregates SUM(views), SUM(clicks), SUM(spend) over period"
    (clear!)
    (insert-ad-row! {:campaign-id 1 :date (this-month-day 5)
                     :views 1000 :clicks 50 :spend 600.0})
    (insert-ad-row! {:campaign-id 2 :date (this-month-day 6)
                     :views 500  :clicks 20 :spend 240.0})
    (let [agg (#'digest/collect-ads-traffic
                (this-month-day 1) (this-month-day 28))]
      (is (== 1500 (:impressions agg)))
      (is (== 70   (:clicks      agg)))
      (is (some?   (:ctr-pct     agg)))
      (is (some?   (:cpc-rub     agg))))))

(deftest collect-ads-traffic-derives-ctr-and-cpc-from-totals
  (testing "CTR = clicks/impressions × 100; CPC = spend/clicks (totals, not row AVG)"
    (clear!)
    (insert-ad-row! {:campaign-id 1 :date (this-month-day 5)
                     :views 1000 :clicks 100 :spend 500.0})
    (let [agg (#'digest/collect-ads-traffic
                (this-month-day 1) (this-month-day 28))]
      (is (== 10.0 (:ctr-pct agg)) "100/1000 = 10%")
      (is (== 5.0  (:cpc-rub agg)) "500/100 = 5₽"))))

(deftest collect-ads-traffic-excludes-rows-outside-period
  (testing "Rows whose date is outside [from,to] are not included"
    (clear!)
    (insert-ad-row! {:campaign-id 1 :date "2025-01-15"
                     :views 99999 :clicks 9999 :spend 9999.0})
    (let [agg (#'digest/collect-ads-traffic
                (this-month-day 1) (this-month-day 28))]
      (is (zero? (:impressions agg)))
      (is (zero? (:clicks agg)))
      (is (nil?  (:ctr-pct agg)))
      (is (nil?  (:cpc-rub agg))))))

(deftest collect-ads-traffic-empty-state-returns-zeros
  (testing "No rows in period → impressions/clicks=0, ctr-pct/cpc-rub=nil"
    (clear!)
    (let [agg (#'digest/collect-ads-traffic
                (this-month-day 1) (this-month-day 28))]
      (is (zero? (:impressions agg)))
      (is (zero? (:clicks agg)))
      (is (nil?  (:ctr-pct agg)))
      (is (nil?  (:cpc-rub agg))))))
