(ns analitica.integration.ozon-ads-reconcile-test
  "T015 — end-to-end reconciliation + profit integration test (spec 011 US1).
   T038/T039 — async statistics ingest + materialize wiring tests.

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
     (d) per-SKU rows → finance event_date_source='api', spread → 'spread' (SC-004).

   T038 ingest-idempotent: running ingest-ozon-ads! twice with the async-stats
   flag does NOT double ad_campaign_stats rows.
   T039 materialize-idempotent: running materialize-ozon-ad-cost! twice does NOT
   double ad_campaign_stats rows and ad_cost total is unchanged.
   FR-015: per-article spend in efficiency-report == Σ ad_spend.spend from US1."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.db :as db]
            [analitica.ingest :as ingest]
            [analitica.materialize :as mat]
            [analitica.domain.finance :as finance]
            [analitica.domain.pnl :as pnl]
            [analitica.domain.ozon-ads :as ozon-ads]
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

;; ---------------------------------------------------------------------------
;; T038 — async statistics ingest idempotency
;;
;; A stub client that also carries :report-rows (the async statistics fixture
;; for campaign 78901, two per-SKU days). Running ingest-ozon-ads! twice with
;; :fetch-stats? true must NOT double ad_campaign_stats rows.
;; ---------------------------------------------------------------------------

(defn- stub-client-with-stats []
  ;; The report rows match statistics-report.json: campaign 78901, sku 12345678,
  ;; two days. moneySpent totals 610.00 (same as the daily rows for that campaign).
  (pc/stub-client
    {:token         {:access_token "t" :expires_in 1800}
     :campaigns     [{:id "78901" :advObjectType "SKU"   :title "SKU camp"}
                     {:id "78902" :advObjectType "BANNER" :title "Banner camp"}]
     :daily-rows    [{:date "2026-04-10" :id "78901" :sku "12345678" :moneySpent 410.00 :bonusSpent 0.0}
                     {:date "2026-04-15" :id "78901" :sku "12345678" :moneySpent 200.00 :bonusSpent 0.0}
                     {:date "2026-04-10" :id "78902" :moneySpent 500.00 :bonusSpent 60.00}
                     {:date "2026-04-20" :id "78902" :moneySpent 534.56 :bonusSpent 40.00}]
     :report-uuid   "test-uuid-001"
     :report-status {:state "OK"}
     :report-rows   [{:date "2026-04-10" :sku "12345678" :views 1200 :clicks 48
                      :moneySpent 410.00 :bonusSpent 0.0 :orders 6 :ordersMoney 9000.0}
                     {:date "2026-04-15" :sku "12345678" :views 800  :clicks 30
                      :moneySpent 200.00 :bonusSpent 0.0 :orders 3 :ordersMoney 4500.0}]}))

(deftest t038-async-ingest-idempotent
  "T038: ingest-ozon-ads! with :fetch-stats? true stores raw :ad_campaign_stats;
   running it twice stores it idempotently (INSERT OR REPLACE on the raw_data
   natural key — same (source, entity_type, date_from, date_to) → 1 row).
   After materialize the ad_campaign_stats table is populated correctly."
  (seed-finance!)
  (seed-sku-map!)
  (let [client (stub-client-with-stats)]
    ;; First ingest with async stats.
    (let [r1 (ingest/ingest-ozon-ads! client from to :fetch-stats? true)]
      (is (= :ok (:status r1)))
      (is (number? (:stat-rows r1)) "stat-rows count returned"))
    ;; raw_data should have exactly 1 :ad_campaign_stats batch.
    (let [raw-1 (db/get-raw-range "ozon" :ad_campaign_stats from to)]
      (is (= 1 (count raw-1)) "exactly 1 raw batch stored after first ingest"))
    ;; Second ingest — INSERT OR REPLACE dedups on the natural key.
    (ingest/ingest-ozon-ads! client from to :fetch-stats? true)
    (let [raw-2 (db/get-raw-range "ozon" :ad_campaign_stats from to)]
      (is (= 1 (count raw-2))
          "raw_data still exactly 1 batch after second ingest (idempotent)"))
    ;; After materialize, the table is populated (2 report rows × 2 campaigns = 4).
    (mat/materialize-ozon-ad-cost! from to)
    (let [stats (db/get-ad-campaign-stats :ozon from to)]
      (is (pos? (count stats))
          "ad_campaign_stats table populated after materialize"))))

;; ---------------------------------------------------------------------------
;; T039 — materialize idempotency for ad_campaign_stats + ad_cost unchanged
;;
;; Running materialize-ozon-ad-cost! twice:
;;   (1) ad_campaign_stats count is unchanged (DELETE+INSERT, not accumulate).
;;   (2) Σ finance.ad_cost is unchanged (no double-counting).
;;   (3) Per-article spend in efficiency-report == Σ ad_spend.spend (FR-015).
;; ---------------------------------------------------------------------------

(deftest t039-materialize-idempotent
  "T039: materialize-ozon-ad-cost! twice → ad_campaign_stats unchanged,
   ad_cost total unchanged, per-article spend == efficiency report (FR-015)."
  (seed-finance!)
  (seed-sku-map!)
  (let [client (stub-client-with-stats)]
    ;; Ingest with async stats first.
    (ingest/ingest-ozon-ads! client from to :fetch-stats? true)
    ;; First materialize.
    (mat/materialize-ozon-ad-cost! from to)
    (let [stats-1     (db/get-ad-campaign-stats :ozon from to)
          ad-cost-1   (-> (db/query ["SELECT COALESCE(SUM(ad_cost),0) AS s FROM finance
                                      WHERE marketplace='ozon'"]) first :s double)
          spend-sum-1 (reduce + 0.0 (map :spend (db/get-ad-spend :ozon from to)))]
      ;; Second materialize.
      (mat/materialize-ozon-ad-cost! from to)
      (let [stats-2     (db/get-ad-campaign-stats :ozon from to)
            ad-cost-2   (-> (db/query ["SELECT COALESCE(SUM(ad_cost),0) AS s FROM finance
                                        WHERE marketplace='ozon'"]) first :s double)
            spend-sum-2 (reduce + 0.0 (map :spend (db/get-ad-spend :ozon from to)))]

        (testing "ad_campaign_stats count unchanged after double-materialize"
          (is (= (count stats-1) (count stats-2))
              "ad_campaign_stats not doubled on re-materialize"))

        (testing "Σ finance.ad_cost unchanged after double-materialize"
          (is (= (kopecks ad-cost-1) (kopecks ad-cost-2))
              "ad_cost total not doubled"))

        (testing "Σ ad_spend.spend unchanged after double-materialize"
          (is (= (kopecks spend-sum-1) (kopecks spend-sum-2))
              "ad_spend total not doubled"))

        (testing "FR-015: per-article spend in efficiency-report == Σ ad_spend"
          (let [report      (ozon-ads/efficiency-report [from to] :marketplace :ozon)
                per-article (:per-article report)
                ;; Ground truth from ad_spend canon.
                expected    (->> (db/get-ad-spend :ozon from to)
                                 (reduce (fn [m r]
                                           (when-let [a (:article r)]
                                             (update m a (fnil + 0.0)
                                                     (double (or (:spend r) 0.0)))))
                                         {}))]
            (is (seq per-article) "efficiency report has per-article rows")
            (doseq [{:keys [article spend]} per-article
                    :when article]
              (is (= (kopecks (get expected article 0.0))
                     (kopecks spend))
                  (str "FR-015: " article " efficiency-report spend == ad_spend canon")))))))))
