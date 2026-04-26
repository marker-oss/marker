(ns analitica.marketplace.wb.ad-stats-test
  "Tests for WB ad_stats ingest + materialize.

   Covers:
     - wb-api/fullstats:
         * empty campaign list → no HTTP call, returns []
         * batches campaigns in chunks of ≤100
         * body shape per WB /adv/v2/fullstats spec
     - ingest/ingest-wb-ad-stats!:
         * calls wb-api/ad-campaigns → wb-api/fullstats
         * persists raw_data with entity_type=:ad_stats
     - materialize/materialize-wb-ad-stats!:
         * flattens response → one ad_stats row per (campaign, date, nm_id)
         * idempotent re-run (DELETE + INSERT)
         * multi-article campaigns → one row per distinct nm_id per day"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.db :as db]
            [analitica.ingest :as ingest]
            [analitica.materialize :as mat]
            [analitica.marketplace.wb.api :as wb-api]
            [analitica.marketplace.wb.client :as wb-client])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temp-file SQLite DB fixture
;; ---------------------------------------------------------------------------

(def ^:dynamic *test-db-path* nil)

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-wb-ad-stats-test-"
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
;; Fake client
;; ---------------------------------------------------------------------------

(defn- fake-client []
  (wb-client/->WBClient "test-token" {:advert 300}))

;; ---------------------------------------------------------------------------
;; fullstats (API wrapper) tests
;; ---------------------------------------------------------------------------

(deftest fullstats-empty-campaign-list
  (testing "empty campaign id list → returns [] without calling POST"
    (let [post-calls (atom 0)]
      (with-redefs [wb-client/post-request
                    (fn [& _args] (swap! post-calls inc) [])]
        (let [result (wb-api/fullstats (fake-client) [] "2026-03-01" "2026-03-31")]
          (is (= [] result))
          (is (zero? @post-calls)
              "no campaign ids → no network call"))))))

(deftest fullstats-query-shape
  (testing "GET /adv/v3/fullstats with ids=CSV&begin=YYYY-MM-DD&end=YYYY-MM-DD"
    (let [captured (atom nil)]
      (with-redefs [wb-client/get-request
                    (fn [_client _section _path & {:keys [query-params]}]
                      (reset! captured query-params)
                      [])]
        (wb-api/fullstats (fake-client) [1 2 3] "2026-03-01" "2026-03-07")
        (let [qp @captured]
          (is (= "1,2,3"      (get qp "ids"))    "ids is comma-separated csv")
          (is (= "2026-03-01" (get qp "begin")))
          (is (= "2026-03-07" (get qp "end"))))))))

(deftest fullstats-chunks-large-campaign-list
  (testing ">100 campaign ids → split into multiple GETs of ≤100 ids each"
    (let [calls (atom [])]
      (with-redefs [wb-client/get-request
                    (fn [_client _section _path & {:keys [query-params]}]
                      (let [ids-csv (get query-params "ids")
                            ids     (mapv #(Long/parseLong %)
                                          (clojure.string/split ids-csv #","))]
                        (swap! calls conj ids)
                        (mapv (fn [id] {:advertId id :days []}) ids)))]
        ;; (range 1 251) yields 250 campaign ids → 100 + 100 + 50
        (wb-api/fullstats (fake-client) (vec (range 1 251)) "2026-03-01" "2026-03-07")
        (is (= 3 (count @calls))
            "250 campaigns → 3 batches (100+100+50)")
        (is (= 100 (count (first @calls))))
        (is (= 100 (count (second @calls))))
        (is (= 50  (count (nth @calls 2))))))))

(deftest fullstats-concats-results
  (testing "results from multiple batches are concatenated"
    (with-redefs [wb-client/get-request
                  (fn [_client _section _path & {:keys [query-params]}]
                    (let [ids (mapv #(Long/parseLong %)
                                    (clojure.string/split (get query-params "ids") #","))]
                      (mapv (fn [id] {:advertId id :days []}) ids)))]
      (let [result (wb-api/fullstats (fake-client)
                                     (vec (range 1 151))
                                     "2026-03-01" "2026-03-07")]
        (is (= 150 (count result)))
        (is (= (set (range 1 151)) (set (map :advertId result))))))))

;; ---------------------------------------------------------------------------
;; ingest-wb-ad-stats! tests
;; ---------------------------------------------------------------------------

(def ^:private synth-campaigns
  [{:advertId 111 :status 9 :type 8}
   {:advertId 222 :status 11 :type 8}])

(def ^:private synth-fullstats-response
  [{:id 111
    :views 1000 :clicks 50 :ctr 5.0 :cpc 10.0 :sum 500.0 :atbs 5
    :orders 2 :cr 4.0 :shks 2 :sum_price 2000.0
    :days [{:date "2026-03-01"
            :views 600 :clicks 30 :ctr 5.0 :cpc 10.0 :sum 300.0 :atbs 3
            :orders 1 :cr 3.33 :shks 1
            :apps [{:nm_id 100500 :views 600 :clicks 30
                    :ctr 5.0 :cpc 10.0 :sum 300.0 :atbs 3
                    :orders 1 :cr 3.33 :shks 1}]}
           {:date "2026-03-02"
            :views 400 :clicks 20 :ctr 5.0 :cpc 10.0 :sum 200.0 :atbs 2
            :orders 1 :cr 5.0 :shks 1
            :apps [{:nm_id 100500 :views 400 :clicks 20
                    :ctr 5.0 :cpc 10.0 :sum 200.0 :atbs 2
                    :orders 1 :cr 5.0 :shks 1}]}]}
   {:id 222
    :views 2000 :clicks 100 :ctr 5.0 :cpc 10.0 :sum 1000.0 :atbs 10
    :orders 5 :cr 5.0 :shks 5 :sum_price 5000.0
    :days [{:date "2026-03-01"
            :views 2000 :clicks 100 :ctr 5.0 :cpc 10.0 :sum 1000.0 :atbs 10
            :orders 5 :cr 5.0 :shks 5
            ;; Multi-article: two distinct nm_ids within one campaign-day
            :apps [{:nm_id 200100 :views 1200 :clicks 60
                    :ctr 5.0 :cpc 10.0 :sum 600.0 :atbs 6
                    :orders 3 :cr 5.0 :shks 3}
                   {:nm_id 200200 :views 800 :clicks 40
                    :ctr 5.0 :cpc 10.0 :sum 400.0 :atbs 4
                    :orders 2 :cr 5.0 :shks 2}]}]}])

(deftest ingest-wb-ad-stats-persists-raw
  (testing "ingest calls ad-campaigns + fullstats → writes raw_data with entity_type=ad_stats"
    (with-redefs [wb-api/ad-campaigns
                  (fn [_client & _]
                    ;; Shape mirrors /adv/v1/promotion/adverts response.
                    synth-campaigns)
                  wb-api/fullstats
                  (fn [_client ids from to]
                    (is (= [111 222] (vec ids)))
                    (is (= "2026-03-01" from))
                    (is (= "2026-03-07" to))
                    synth-fullstats-response)]
      (let [cnt (ingest/ingest-wb-ad-stats! (fake-client)
                                            "2026-03-01" "2026-03-07")]
        (is (= 2 cnt) "2 campaigns ingested")
        (let [raw (db/get-raw :wb :ad_stats "2026-03-01" "2026-03-07")]
          (is (some? raw) "raw_data row present")
          (is (= 2 (count raw)))
          (is (= 111 (:id (first raw)))
              "first campaign preserved"))))))

(deftest ingest-wb-ad-stats-empty-campaigns
  (testing "no campaigns → raw_data still written (as empty vector) and fullstats skipped"
    (let [fullstats-calls (atom 0)]
      (with-redefs [wb-api/ad-campaigns (fn [& _] [])
                    wb-api/fullstats
                    (fn [& _]
                      (swap! fullstats-calls inc)
                      [])]
        (let [cnt (ingest/ingest-wb-ad-stats! (fake-client)
                                              "2026-03-01" "2026-03-07")]
          (is (zero? cnt))
          (is (zero? @fullstats-calls)
              "no campaigns → fullstats not called")
          (is (= [] (db/get-raw :wb :ad_stats "2026-03-01" "2026-03-07"))))))))

;; ---------------------------------------------------------------------------
;; materialize-wb-ad-stats! tests
;; ---------------------------------------------------------------------------

(defn- seed-raw-ad-stats! [from to data]
  (db/insert-raw! :wb :ad_stats from to data))

(defn- ad-stats-rows []
  (db/query ["SELECT campaign_id, date, nm_id, views, clicks, spend, orders, atbs, ctr, cpc
              FROM ad_stats
              ORDER BY campaign_id, date, nm_id"]))

(deftest materialize-wb-ad-stats-flattens-structure
  (testing "response flattens to (campaign_id × date × nm_id) rows in ad_stats"
    (seed-raw-ad-stats! "2026-03-01" "2026-03-07" synth-fullstats-response)
    (mat/materialize-wb-ad-stats! ["2026-03-01" "2026-03-07"])
    (let [rows (ad-stats-rows)]
      ;; Campaign 111: 2 days × 1 app = 2 rows
      ;; Campaign 222: 1 day × 2 apps = 2 rows
      ;; Total = 4 rows
      (is (= 4 (count rows)))
      ;; Verify campaign 111, day 1, nm_id 100500
      (let [r (first (filter #(and (= 111 (:campaign-id %))
                                   (= "2026-03-01" (:date %)))
                             rows))]
        (is (= 100500 (:nm-id r)))
        (is (= 600    (:views r)))
        (is (= 30     (:clicks r)))
        (is (= 300.0  (:spend r))
            "spend mapped from apps[].sum"))
      ;; Verify multi-article split for campaign 222
      (let [r1 (first (filter #(and (= 222 (:campaign-id %))
                                    (= 200100 (:nm-id %)))
                              rows))
            r2 (first (filter #(and (= 222 (:campaign-id %))
                                    (= 200200 (:nm-id %)))
                              rows))]
        (is (= 600.0 (:spend r1)))
        (is (= 400.0 (:spend r2))
            "second article of the multi-article campaign gets its own row")))))

(deftest materialize-wb-ad-stats-idempotent
  (testing "running materialize twice yields identical state (DELETE + INSERT)"
    (seed-raw-ad-stats! "2026-03-01" "2026-03-07" synth-fullstats-response)
    (mat/materialize-wb-ad-stats! ["2026-03-01" "2026-03-07"])
    (let [rows1 (ad-stats-rows)]
      (is (= 4 (count rows1))))
    ;; second run
    (mat/materialize-wb-ad-stats! ["2026-03-01" "2026-03-07"])
    (let [rows2 (ad-stats-rows)]
      (is (= 4 (count rows2))
          "no duplication after second run"))))

(deftest materialize-wb-ad-stats-multi-article
  (testing "campaign with 3 distinct nm_ids on one day → 3 rows that day"
    (let [resp [{:id 333
                 :views 3000 :clicks 150 :sum 1500.0
                 :days [{:date "2026-03-05"
                         :views 3000 :clicks 150 :sum 1500.0
                         :apps [{:nm_id 1 :views 1000 :clicks 50 :sum 500.0
                                 :atbs 5 :orders 2 :cr 4.0 :shks 2
                                 :ctr 5.0 :cpc 10.0}
                                {:nm_id 2 :views 1500 :clicks 75 :sum 750.0
                                 :atbs 7 :orders 3 :cr 4.0 :shks 3
                                 :ctr 5.0 :cpc 10.0}
                                {:nm_id 3 :views 500 :clicks 25 :sum 250.0
                                 :atbs 2 :orders 1 :cr 4.0 :shks 1
                                 :ctr 5.0 :cpc 10.0}]}]}]]
      (seed-raw-ad-stats! "2026-03-01" "2026-03-07" resp)
      (mat/materialize-wb-ad-stats! ["2026-03-01" "2026-03-07"])
      (let [rows (ad-stats-rows)]
        (is (= 3 (count rows)))
        (is (= #{1 2 3} (set (map :nm-id rows))))
        (is (= 1500.0 (reduce + 0.0 (keep :spend rows)))
            "sum across nm_ids equals campaign total")))))

(deftest materialize-wb-ad-stats-no-apps-fallback
  (testing "day with no apps vector → one row with nm_id=0 sentinel"
    (let [resp [{:id 444
                 :views 100 :clicks 5 :sum 50.0
                 :days [{:date "2026-03-06"
                         :views 100 :clicks 5 :sum 50.0
                         :atbs 1 :orders 0 :cr 0.0 :shks 0
                         :ctr 5.0 :cpc 10.0
                         :apps []}]}]]
      (seed-raw-ad-stats! "2026-03-01" "2026-03-07" resp)
      (mat/materialize-wb-ad-stats! ["2026-03-01" "2026-03-07"])
      (let [rows (ad-stats-rows)]
        (is (= 1 (count rows)))
        (is (= 0 (:nm-id (first rows)))
            "nm_id sentinel 0 since ad_stats PK NOT NULL requires deterministic value")
        (is (= 50.0 (:spend (first rows))))))))
