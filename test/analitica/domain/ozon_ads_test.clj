(ns analitica.domain.ozon-ads-test
  "Spec 011 US3 (T034/T035) — Ozon campaign-efficiency analytics.

   T034 — efficiency formulas on an `ad_campaign_stats` fixture. The
          denominators are the FR-014a SoT (contracts/ingest-cli.edn
          :efficiency-report):
            CTR  = clicks / views       × 100
            CPC  = spend  / clicks
            CPM  = spend  / views       × 1000
            CPO  = spend  / orders
            CPS  = spend  / sales-units   (Σ ordered quantity — the :orders
                                           column, which Ozon reports as ordered
                                           units; distinct BASIS from CPO)
            CR   = orders / clicks      × 100
            ДРРз = spend  / orders-revenue × 100  (orders basis)
            ROAS = orders-revenue / spend         (NOT (rev-spend)/spend)
          A zero denominator yields N/A = nil (rendered «—», never 0/∞). An
          UNCOLLECTED counter (add-to-cart in the P0 slice) is distinguishable
          from a true 0.

   T035 — per-article spend in the efficiency report == Σ ad_spend.spend
          GROUP BY article from US1 attribution (FR-015, exact)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.db :as db]
            [analitica.domain.ozon-ads :as ozon-ads])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; T034 — pure efficiency formulas (no DB): campaign-metrics on a stat map.
;; ---------------------------------------------------------------------------

(def ^:private stat
  ;; One campaign-day stat. Chosen so every ratio is a clean, checkable value.
  {:campaign-id "78901" :campaign-type "SKU" :campaign-name "Search promo"
   :views 1000 :clicks 50 :add-to-cart 0 :orders 10
   :orders-revenue 9000.0 :spend 500.0 :bonus-spend 0.0})

(deftest efficiency-formulas-exact
  (testing "each ratio matches its FR-014a denominator exactly"
    (let [m (ozon-ads/campaign-metrics stat)]
      ;; CTR = clicks/views × 100 = 50/1000 × 100 = 5.0
      (is (= 5.0 (:CTR m)) "CTR = clicks/views × 100")
      ;; CPC = spend/clicks = 500/50 = 10.0
      (is (= 10.0 (:CPC m)) "CPC = spend/clicks")
      ;; CPM = spend/views × 1000 = 500/1000 × 1000 = 500.0
      (is (= 500.0 (:CPM m)) "CPM = spend/views × 1000")
      ;; CPO = spend/orders = 500/10 = 50.0
      (is (= 50.0 (:CPO m)) "CPO = spend/orders")
      ;; CPS = spend/sales-units = 500/10 = 50.0 (sales-units = :orders, ordered units)
      (is (= 50.0 (:CPS m)) "CPS = spend/sales-units")
      ;; CR = orders/clicks × 100 = 10/50 × 100 = 20.0
      (is (= 20.0 (:CR m)) "CR = orders/clicks × 100")
      ;; ДРРз = spend/orders-revenue × 100 = 500/9000 × 100 = 5.56
      (is (= 5.56 (:ДРРз m)) "ДРРз = spend/orders-revenue × 100 (orders basis)")
      ;; ROAS = orders-revenue/spend = 9000/500 = 18.0 (NOT (rev-spend)/spend)
      (is (= 18.0 (:ROAS m)) "ROAS = orders-revenue/spend"))))

(deftest zero-denominator-is-na-not-zero
  (testing "every metric with a zero DENOMINATOR → nil (N/A), never 0 or ∞"
    ;; All denominators zero (views/clicks/orders/orders-revenue), spend present.
    (let [m (ozon-ads/campaign-metrics
              {:campaign-id "z" :views 0 :clicks 0 :add-to-cart 0 :orders 0
               :orders-revenue 0.0 :spend 500.0 :bonus-spend 0.0})]
      (is (nil? (:CTR m))  "views=0 → CTR N/A")
      (is (nil? (:CPC m))  "clicks=0 → CPC N/A")
      (is (nil? (:CPM m))  "views=0 → CPM N/A")
      (is (nil? (:CPO m))  "orders=0 → CPO N/A")
      (is (nil? (:CPS m))  "sales-units=0 → CPS N/A")
      (is (nil? (:CR m))   "clicks=0 → CR N/A")
      (is (nil? (:ДРРз m)) "orders-revenue=0 → ДРРз N/A")
      ;; ROAS = orders-revenue/spend = 0/500 = 0.0 — a TRUE zero (denominator
      ;; 500 is present); the N/A case for ROAS is spend=0, tested below.
      (is (= 0.0 (:ROAS m)) "rev=0, spend present → ROAS is a TRUE 0.0")))

  (testing "ROAS N/A when spend=0 (its denominator is zero → undefined, not ∞)"
    (let [m (ozon-ads/campaign-metrics
              {:campaign-id "z2" :views 100 :clicks 10 :add-to-cart 0 :orders 2
               :orders-revenue 3000.0 :spend 0.0 :bonus-spend 0.0})]
      (is (nil? (:ROAS m)) "spend=0 → ROAS N/A (never ∞)")))

  (testing "true zero vs N/A: a computable metric that is 0.0 stays 0.0"
    ;; spend=0, clicks=10 → CPC = 0/10 = 0.0 (a TRUE zero, denominator present);
    ;; orders=0 → CPO N/A (denominator absent). The two are distinguishable.
    (let [m (ozon-ads/campaign-metrics
              {:campaign-id "z3" :views 100 :clicks 10 :add-to-cart 0 :orders 0
               :orders-revenue 0.0 :spend 0.0 :bonus-spend 0.0})]
      (is (= 0.0 (:CPC m)) "spend=0 with clicks>0 → CPC is a TRUE 0.0, not N/A")
      (is (nil? (:CPO m))  "orders=0 → CPO N/A (denominator absent)"))))

(deftest uncollected-counter-distinct-from-true-zero
  (testing "add-to-cart not collected in P0 → the counter renders N/A, not a misleading 0"
    ;; A stat row with no :add-to-cart key (the P0 slice does not populate it).
    (let [m (ozon-ads/campaign-metrics
              {:campaign-id "u" :views 1000 :clicks 50 :orders 10
               :orders-revenue 9000.0 :spend 500.0 :bonus-spend 0.0})]
      (is (nil? (:add-to-cart m))
          "missing add-to-cart → N/A (uncollected), distinguishable from a true 0"))
    ;; A stat row where add-to-cart WAS collected and is genuinely 0.
    (let [m (ozon-ads/campaign-metrics
              {:campaign-id "t" :views 1000 :clicks 50 :add-to-cart 0 :orders 10
               :orders-revenue 9000.0 :spend 500.0 :bonus-spend 0.0})]
      (is (= 0 (:add-to-cart m))
          "collected add-to-cart = 0 → a TRUE 0, distinct from the uncollected N/A"))))

(deftest campaign-metrics-carry-basis-hints
  (testing "each metric's basis is documented (P6) in the report's :hints"
    (let [hints ozon-ads/metric-hints]
      (is (every? #(contains? hints %)
                  [:CTR :CPC :CPM :CPO :CPS :CR :ДРРз :ROAS])
          "every ratio metric has a basis hint")
      (is (every? string? (vals hints))))))

;; ---------------------------------------------------------------------------
;; T035 — per-article spend == Σ ad_spend.spend GROUP BY article (FR-015).
;; DB-backed: seed ad_spend + ad_campaign_stats, read via efficiency-report.
;; ---------------------------------------------------------------------------

(def ^:dynamic *test-db-path* nil)

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-ozon-ads-" ".db" (make-array FileAttribute 0))
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

(def ^:private from "2026-04-01")
(def ^:private to   "2026-04-30")
(def ^:private synced "2026-05-01T00:00:00")

(defn- kopecks [x] (Math/round (* (double x) 100.0)))

(deftest per-article-spend-equals-us1-attribution
  (testing "per-article roll-up in the efficiency report == Σ ad_spend.spend GROUP BY article"
    ;; Seed US1-attributed ad_spend: two articles, :api + :spread rows.
    (db/insert-ad-spend!
      [{:marketplace :ozon :event-date "2026-04-10" :campaign-id "78901"
        :campaign-type "SKU" :article "ABC-123" :sku "12345678"
        :spend 410.00 :bonus-spend 0.0 :attribution-source :api
        :basis "test" :synced-at synced}
       {:marketplace :ozon :event-date "2026-04-15" :campaign-id "78901"
        :campaign-type "SKU" :article "ABC-123" :sku "12345678"
        :spend 200.00 :bonus-spend 0.0 :attribution-source :api
        :basis "test" :synced-at synced}
       {:marketplace :ozon :event-date "2026-04-10" :campaign-id "78902"
        :campaign-type "BANNER" :article "ART-B" :sku nil
        :spend 100.50 :bonus-spend 0.0 :attribution-source :spread
        :basis "test" :synced-at synced}])
    ;; Seed campaign stats (the efficiency counters).
    (db/insert-ad-campaign-stats!
      [{:marketplace :ozon :campaign-id "78901" :campaign-type "SKU"
        :campaign-name "Search promo" :stat-date "2026-04-10"
        :views 1000 :clicks 50 :add-to-cart 0 :orders 10
        :orders-revenue 9000.0 :spend 410.0 :bonus-spend 0.0 :synced-at synced}])
    (let [report      (ozon-ads/efficiency-report [from to] :marketplace :ozon)
          per-article (:per-article report)
          by-art      (into {} (map (juxt :article identity) per-article))
          ;; Ground truth straight from ad_spend (US1 attribution).
          expected    (->> (db/get-ad-spend :ozon from to)
                           (reduce (fn [m r]
                                     (update m (:article r) (fnil + 0.0)
                                             (double (or (:spend r) 0.0))))
                                   {}))]
      (is (seq per-article) "efficiency report has per-article rows")
      ;; ABC-123: 410 + 200 = 610.00 ; ART-B: 100.50.
      (is (= (kopecks (get expected "ABC-123"))
             (kopecks (:spend (get by-art "ABC-123"))))
          "ABC-123 per-article spend == Σ ad_spend.spend (610.00)")
      (is (= (kopecks (get expected "ART-B"))
             (kopecks (:spend (get by-art "ART-B"))))
          "ART-B per-article spend == Σ ad_spend.spend (100.50)")
      ;; Exact equality across ALL articles (FR-015).
      (is (= (into {} (map (fn [[a s]] [a (kopecks s)]) expected))
             (into {} (map (fn [r] [(:article r) (kopecks (:spend r))]) per-article)))
          "every article's report spend == its US1 ad_spend attribution"))))

(deftest per-campaign-report-has-metrics
  (testing "each campaign lists raw counters + derived ratio metrics"
    (db/insert-ad-campaign-stats!
      [{:marketplace :ozon :campaign-id "78901" :campaign-type "SKU"
        :campaign-name "Search promo" :stat-date "2026-04-10"
        :views 1000 :clicks 50 :add-to-cart 0 :orders 10
        :orders-revenue 9000.0 :spend 500.0 :bonus-spend 0.0 :synced-at synced}])
    (let [report      (ozon-ads/efficiency-report [from to] :marketplace :ozon)
          per-camp    (:per-campaign report)
          c           (first per-camp)]
      (is (= 1 (count per-camp)))
      (is (= "78901" (:campaign-id c)))
      (is (= 1000 (:views c)) "raw counter surfaced")
      (is (= 500.0 (:spend c)))
      (is (= 5.0 (:CTR c))  "derived CTR present")
      (is (= 18.0 (:ROAS c)) "derived ROAS present"))))
