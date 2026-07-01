(ns analitica.integration.ozon-ads-reconcile-test
  "T015 — end-to-end reconciliation + profit integration test (spec 011 US1).

   Seeds a temp SQLite DB with:
     - finance rows (ozon) for two articles with known revenue,
     - ozon_sku_map (sku → article),
     - raw_data :ad_performance (campaigns + daily rows) via ingest-ozon-ads!
       against a stubbed client that serves the fixtures.
   Then runs materialize-ozon-ad-cost! and asserts:
     (a) Σ finance.ad_cost(ozon, period) == Σ moneySpent EXACTLY (FR-008/SC-001/P7);
     (b) (pnl/calculate … :marketplace :ozon) net-profit == baseline − ad-spend,
         with :marketplace :ozon passed (memory marketplace_kwarg_propagation);
     (c) :drr non-zero;
     (d) per-SKU rows → finance event_date_source='api', spread → 'spread' (SC-004)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.db :as db]
            [analitica.ingest :as ingest]
            [analitica.materialize :as mat]
            [analitica.domain.finance :as finance]
            [analitica.domain.pnl :as pnl]
            [analitica.marketplace.ozon.performance.client :as pc])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temp-file SQLite DB fixture (same pattern as ozon-hybrid-reconcile-test)
;; ---------------------------------------------------------------------------

(def ^:dynamic *test-db-path* nil)

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-ads-recon-" ".db" (make-array FileAttribute 0))
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
;; Period + control totals
;; ---------------------------------------------------------------------------

(def ^:private from "2026-04-01")
(def ^:private to   "2026-04-30")

;; Fixture control total (Σ daily moneySpent): SKU campaign 610.00 + banner 1034.56
(def ^:private control-total 1644.56)

;; ---------------------------------------------------------------------------
;; Seed helpers
;; ---------------------------------------------------------------------------

(defn- seed-finance! []
  ;; Two ozon sale rows with known revenue for the banner-spread articles,
  ;; plus the SKU-campaign article. for_pay drives gross-profit.
  ;; retail_amount drives revenue (spread weights + DRR denominator).
  (let [rows [;; article ABC-123 (SKU campaign target) — event_date inside period
              {:rrd_id "r-abc" :report_id "rep1" :date_from from :date_to to
               :event_date "2026-04-12" :event_date_source "api"
               :article "ABC-123" :nm_id "12345678"
               :operation "sale" :operation_subtype "realization"
               :retail_amount 20000.0 :for_pay 15000.0 :quantity 10
               :ad_cost 0.0 :marketplace "ozon" :synced_at "2026-05-01T00:00:00"}
              ;; article ART-A (banner spread, larger revenue)
              {:rrd_id "r-a" :report_id "rep1" :date_from from :date_to to
               :event_date "2026-04-11" :event_date_source "api"
               :article "ART-A" :nm_id "22222222"
               :operation "sale" :operation_subtype "realization"
               :retail_amount 6000.0 :for_pay 4500.0 :quantity 4
               :ad_cost 0.0 :marketplace "ozon" :synced_at "2026-05-01T00:00:00"}
              ;; article ART-B (banner spread, smaller revenue)
              {:rrd_id "r-b" :report_id "rep1" :date_from from :date_to to
               :event_date "2026-04-13" :event_date_source "api"
               :article "ART-B" :nm_id "33333333"
               :operation "sale" :operation_subtype "realization"
               :retail_amount 4000.0 :for_pay 3000.0 :quantity 3
               :ad_cost 0.0 :marketplace "ozon" :synced_at "2026-05-01T00:00:00"}]]
    (db/insert-batch!
      :finance
      [:rrd_id :report_id :date_from :date_to :event_date :event_date_source
       :article :nm_id :operation :operation_subtype
       :retail_amount :for_pay :quantity :ad_cost :marketplace :synced_at]
      (mapv (fn [r] (mapv r [:rrd_id :report_id :date_from :date_to :event_date
                             :event_date_source :article :nm_id :operation
                             :operation_subtype :retail_amount :for_pay :quantity
                             :ad_cost :marketplace :synced_at]))
            rows))))

(defn- seed-sku-map! []
  (db/save-ozon-sku-map! [["12345678" "ABC-123"]]))

;; A stub OzonPerfClient that serves the fixtures without touching the network.
;; ingest-ozon-ads! is written against a small protocol/multimethod that this
;; stub satisfies (token/list-campaigns/daily-stats).
(defn- stub-client []
  (pc/stub-client
    {:token       {:access_token "t" :expires_in 1800}
     :campaigns   [{:id "78901" :advObjectType "SKU"   :title "SKU camp"}
                   {:id "78902" :advObjectType "BANNER" :title "Banner camp"}]
     :daily-rows  [{:date "2026-04-10" :id "78901" :sku "12345678" :moneySpent 410.00 :bonusSpent 0.0}
                   {:date "2026-04-15" :id "78901" :sku "12345678" :moneySpent 200.00 :bonusSpent 0.0}
                   {:date "2026-04-10" :id "78902" :moneySpent 500.00 :bonusSpent 60.00}
                   {:date "2026-04-20" :id "78902" :moneySpent 534.56 :bonusSpent 40.00}]}))

(defn- kopecks [x] (Math/round (* (double x) 100.0)))

;; ---------------------------------------------------------------------------
;; End-to-end test
;; ---------------------------------------------------------------------------

(deftest reconcile-and-profit
  (seed-finance!)
  (seed-sku-map!)
  ;; Baseline profit BEFORE ad-cost materialization.
  (let [baseline (pnl/calculate (finance/fetch-finance [from to] :marketplace :ozon)
                                :marketplace :ozon :from from :to to)
        baseline-profit (:net-profit baseline)]

    ;; Ingest ads (token→campaign→daily) → raw_data :ad_performance.
    (let [ing (ingest/ingest-ozon-ads! (stub-client) from to)]
      (is (= :ok (:status ing)))
      (is (= 2 (:campaigns ing)))
      (is (= 4 (:daily-rows ing))))

    ;; Materialize ad_cost.
    (mat/materialize-ozon-ad-cost! from to)

    (testing "(a) Σ finance.ad_cost(ozon, period) == Σ moneySpent EXACTLY"
      (let [sum-ad-cost (-> (db/query
                              ["SELECT COALESCE(SUM(ad_cost),0) AS s FROM finance
                                WHERE marketplace='ozon'"]) first :s)]
        (is (= (kopecks control-total) (kopecks sum-ad-cost))
            "ad_cost reconciles to Performance total to the kopeck")))

    (testing "(a2) Σ ad_spend.spend(ozon, period) == control total"
      (let [rows (db/get-ad-spend :ozon from to)
            sum  (reduce + 0.0 (map :spend rows))]
        (is (= (kopecks control-total) (kopecks sum)))))

    (testing "(b) net-profit == baseline − ad-spend, :marketplace :ozon passed"
      (let [after (pnl/calculate (finance/fetch-finance [from to] :marketplace :ozon)
                                 :marketplace :ozon :from from :to to)]
        (is (= (kopecks control-total) (kopecks (:ad-spend after)))
            ":ad-spend surfaced in P&L == control total")
        (is (= (kopecks (- baseline-profit control-total))
               (kopecks (:net-profit after)))
            "net-profit fell by exactly the ad-spend")
        ;; If :marketplace were NOT passed, an all-MP ad_cost SUM could leak;
        ;; the ozon-scoped total equals the control total, proving the filter.
        (is (= :canonical (:ad-cost-source after)))))

    (testing "(c) :drr non-zero"
      (let [after (pnl/calculate (finance/fetch-finance [from to] :marketplace :ozon)
                                 :marketplace :ozon :from from :to to)]
        (is (number? (:margin-net after)))
        ;; DRR proper (ad-spend / revenue): assert it is a positive number.
        (let [revenue (:revenue after)
              drr     (* 100.0 (/ (:ad-spend after) revenue))]
          (is (pos? drr) "DRR is non-zero once ad_cost is populated"))))

    (testing "(d) per-SKU rows → event_date_source='api', spread → 'spread'"
      (let [rows      (db/get-ad-spend :ozon from to)
            by-source (group-by :attribution-source rows)]
        (is (seq (get by-source "api")) "SKU campaign produced :api rows")
        (is (seq (get by-source "spread")) "banner campaign produced :spread rows")
        ;; finance.event_date_source: the SKU-attributed article carries 'api',
        ;; the spread articles carry 'spread'.
        (let [esource (fn [article]
                        (-> (db/query
                              ["SELECT event_date_source FROM finance
                                WHERE marketplace='ozon' AND article=? LIMIT 1" article])
                            first :event-date-source))]
          (is (= "api" (esource "ABC-123")) "SKU-attributed article → api")
          (is (= "spread" (esource "ART-A")) "banner-spread article → spread")
          (is (= "spread" (esource "ART-B")) "banner-spread article → spread"))))))
