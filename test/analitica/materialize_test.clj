(ns analitica.materialize-test
  "Unit tests for `materialize-ozon-services!` — the Ozon hybrid merge
   path (US3B, T027).

   Covered scenarios:
     - T022 UPSERT idempotent: running materialize twice with the same
       fixtures yields the same cost fields, and never double-adds.
     - B-005 invariant: `for_pay` on the existing realization-derived
       finance-row MUST NOT change during service merge.
     - Orphan handling: service-rows whose sku isn't in article-lookup
       are dropped (no new INSERT happens).

   These are in-memory (temp-file) SQLite tests with a fresh DB per
   test via `with-temp-db`."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.db :as db]
            [analitica.materialize :as mat]
            [analitica.sync :as sync])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temp-file SQLite DB fixture (mirrors test/analitica/db_test.clj).
;; ---------------------------------------------------------------------------

(def ^:dynamic *test-db-path* nil)

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-mat-test-"
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
;; Fixtures / helpers
;; ---------------------------------------------------------------------------

(defn- seed-finance-row!
  "INSERT an Ozon realization-style finance-row for testing."
  [{:keys [rrd-id article date-from for-pay delivery-cost operation]
    :or   {operation "sale"
           for-pay   500.0
           delivery-cost 0}}]
  (let [row (sync/finance->row {:rrd-id        rrd-id
                                :date-from     date-from
                                :date-to       date-from
                                :article       article
                                :operation     operation
                                :for-pay       for-pay
                                :delivery-cost delivery-cost
                                :marketplace   :ozon})]
    (db/insert-batch! :finance sync/finance-columns [row])))

(defn- seed-realization-raw!
  "Minimal realization raw_data so that article-lookup can be built."
  [article sku date-from date-to]
  (db/insert-raw! :ozon :realization date-from date-to
                  {:header {:start_date date-from :stop_date date-to}
                   :rows   [{:rowNumber 1
                             :seller_price_per_instance 500
                             :item {:sku sku :offer_id article}
                             :delivery_commission {:quantity 1
                                                   :amount 500
                                                   :standard_fee 0}}]}))

(defn- seed-transactions-raw!
  "Insert transaction operations raw_data."
  [operations date-from date-to]
  (db/insert-raw! :ozon :transactions date-from date-to
                  {:operations operations}))

(defn- finance-row-by-article [article]
  (first
    (db/query
      ["SELECT article, for_pay, delivery_cost, acquiring_fee,
               acceptance, storage_fee, additional_payment, ad_cost
        FROM finance WHERE marketplace='ozon' AND article = ?" article])))

;; ---------------------------------------------------------------------------
;; T022 — UPSERT idempotency (sale scenario)
;; ---------------------------------------------------------------------------

(deftest materialize-services-updates-delivery-cost
  (testing "single-service operation merges into the existing finance-row"
    (seed-finance-row! {:rrd-id 1 :article "ART-A"
                        :date-from "2026-03-01"
                        :for-pay 500.0
                        :delivery-cost 0})
    (seed-realization-raw! "ART-A" 10 "2026-03-01" "2026-03-31")
    (seed-transactions-raw!
      [{:operation_id 101
        :operation_type "OperationAgentDeliveredToCustomer"
        :operation_date "2026-03-15 10:00:00"
        :type "services"
        :amount -50
        :items [{:sku 10 :name "A" :price 500 :quantity 1}]
        :services [{:name "MarketplaceServiceItemDirectFlowLogistic" :price -50}]
        :posting {:posting_number "P1" :delivery_schema "FBO"}}]
      "2026-03-01" "2026-03-31")

    (mat/materialize-ozon-services! ["2026-03-01" "2026-03-31"])

    (let [row (finance-row-by-article "ART-A")]
      (is (some? row))
      (is (= 50.0 (:delivery-cost row))
          "delivery_cost accumulated from the service")
      (is (= 500.0 (:for-pay row))
          "B-005 invariant: for_pay unchanged"))))

(deftest materialize-services-is-idempotent
  (testing "running materialize-ozon-services! twice does NOT double-add"
    (seed-finance-row! {:rrd-id 1 :article "ART-A"
                        :date-from "2026-03-01"
                        :for-pay 500.0
                        :delivery-cost 0})
    (seed-realization-raw! "ART-A" 10 "2026-03-01" "2026-03-31")
    (seed-transactions-raw!
      [{:operation_id 101
        :operation_type "OperationAgentDeliveredToCustomer"
        :operation_date "2026-03-15 10:00:00"
        :type "services"
        :amount -50
        :items [{:sku 10 :name "A" :price 500 :quantity 1}]
        :services [{:name "MarketplaceServiceItemDirectFlowLogistic" :price -50}]
        :posting {:posting_number "P1" :delivery_schema "FBO"}}]
      "2026-03-01" "2026-03-31")

    ;; First materialize
    (mat/materialize-ozon-services! ["2026-03-01" "2026-03-31"])
    (let [row1 (finance-row-by-article "ART-A")]
      (is (= 50.0 (:delivery-cost row1))
          "first run: delivery_cost = 50"))

    ;; Second materialize — identical input, idempotent
    (mat/materialize-ozon-services! ["2026-03-01" "2026-03-31"])
    (let [row2 (finance-row-by-article "ART-A")]
      (is (= 50.0 (:delivery-cost row2))
          "second run: delivery_cost STILL 50 (no double-adding)")
      (is (= 500.0 (:for-pay row2))
          "B-005: for_pay still untouched after second run"))))

(deftest materialize-services-multi-field-merge
  (testing "multiple distinct services map to distinct cost fields"
    (seed-finance-row! {:rrd-id 1 :article "ART-A"
                        :date-from "2026-03-01"
                        :for-pay 500.0
                        :delivery-cost 0})
    (seed-realization-raw! "ART-A" 10 "2026-03-01" "2026-03-31")
    (seed-transactions-raw!
      [{:operation_id 201
        :operation_type "OperationAgentDeliveredToCustomer"
        :operation_date "2026-03-15 10:00:00"
        :type "services"
        :amount -60
        :items [{:sku 10 :name "A" :price 500 :quantity 1}]
        :services [{:name "MarketplaceServiceItemDirectFlowLogistic" :price -30}
                   {:name "MarketplaceRedistributionOfAcquiringOperation" :price -10}
                   {:name "MarketplaceServiceItemTemporaryStorageRedistribution" :price -5}
                   {:name "MarketplaceServiceItemPackageMaterialsProvision" :price -15}]
        :posting {:posting_number "P1" :delivery_schema "FBO"}}]
      "2026-03-01" "2026-03-31")

    (mat/materialize-ozon-services! ["2026-03-01" "2026-03-31"])

    (let [row (finance-row-by-article "ART-A")]
      (is (= 30.0 (:delivery-cost row)))
      (is (= 10.0 (:acquiring-fee row)))
      (is (= 5.0  (:storage-fee row)))
      (is (= 15.0 (:acceptance row)))
      (is (= 500.0 (:for-pay row))
          "B-005: for_pay untouched"))))

(deftest materialize-services-orphan-skipped
  (testing "service-rows whose sku doesn't resolve skip with no INSERT"
    (seed-finance-row! {:rrd-id 1 :article "ART-A"
                        :date-from "2026-03-01"
                        :for-pay 500.0
                        :delivery-cost 0})
    (seed-realization-raw! "ART-A" 10 "2026-03-01" "2026-03-31")
    (seed-transactions-raw!
      [{:operation_id 401
        :operation_type "OperationAgentDeliveredToCustomer"
        :operation_date "2026-03-15 10:00:00"
        :type "services"
        :amount -50
        :items [{:sku 9999 :name "GHOST" :price 500 :quantity 1}]
        :services [{:name "MarketplaceServiceItemDirectFlowLogistic" :price -50}]
        :posting {:posting_number "P99" :delivery_schema "FBO"}}]
      "2026-03-01" "2026-03-31")

    (mat/materialize-ozon-services! ["2026-03-01" "2026-03-31"])

    (let [count-rows (-> (db/query
                           ["SELECT COUNT(*) AS cnt FROM finance
                             WHERE marketplace='ozon'"])
                         first :cnt)
          row        (finance-row-by-article "ART-A")]
      (is (= 1 count-rows)
          "no new row inserted for the orphan sku")
      (is (or (nil? (:delivery-cost row))
              (zero? (:delivery-cost row)))
          "existing row's delivery_cost remains 0 (no orphan contribution)"))))

(deftest materialize-services-missing-finance-row-skips
  (testing "service-row for an article with no existing finance-row is skipped"
    ;; lookup resolves sku → article, but there's no finance-row for that article.
    (seed-realization-raw! "ART-ORPHAN" 77 "2026-03-01" "2026-03-31")
    (seed-transactions-raw!
      [{:operation_id 501
        :operation_type "OperationAgentDeliveredToCustomer"
        :operation_date "2026-03-10 00:00:00"
        :type "services"
        :amount -50
        :items [{:sku 77 :name "G" :price 500 :quantity 1}]
        :services [{:name "MarketplaceServiceItemDirectFlowLogistic" :price -50}]
        :posting {:posting_number "P77" :delivery_schema "FBO"}}]
      "2026-03-01" "2026-03-31")

    ;; Nothing pre-seeded in finance table
    (mat/materialize-ozon-services! ["2026-03-01" "2026-03-31"])

    (let [count-rows (-> (db/query
                           ["SELECT COUNT(*) AS cnt FROM finance
                             WHERE marketplace='ozon'"])
                         first :cnt)]
      (is (zero? count-rows)
          "orphan posting (no target finance-row) → no INSERT"))))

;; ---------------------------------------------------------------------------
;; D1 — write-time daily spread for Ozon realization rows.
;;
;; Before D1, materialize-ozon-finance-from-realization! wrote each
;; realization row as a single month-stamped finance row. ozon-distribute
;; spread it to days at READ time only (in fetch-finance). Any direct SQL
;; — audit, future report, ad-hoc admin query — saw month-stamped data
;; and silently undercounted weekly slices. After D1 the spread runs at
;; write time as the final step of materialize-finance Ozon pipeline.
;; ---------------------------------------------------------------------------

(defn- seed-sale-row!
  "Insert one row into the sales table at a specific day. Used to seed
   spread weights for the Ozon spreader."
  [{:keys [article sku date marketplace total-price]
    :or   {marketplace :ozon
           total-price 100.0}}]
  (next.jdbc/execute!
    (db/ds)
    [(str "INSERT INTO sales (sale_id, date, article, nm_id, type, "
          "total_price, marketplace) VALUES (?,?,?,?,?,?,?)")
     (str (java.util.UUID/randomUUID)) date article sku "sale"
     total-price (name marketplace)]))

(defn- ozon-finance-rows []
  (db/query
    ["SELECT rrd_id, event_date, event_date_source, retail_amount,
             for_pay, quantity, operation_subtype
      FROM finance
      WHERE marketplace = 'ozon'
      ORDER BY event_date, rrd_id"]))

(deftest materialize-ozon-finance-spreads-realization-on-write
  (testing "month-stamped realization rows are spread to daily children at write time"
    ;; Three days of sales activity for ART-A in April → spread weights.
    (seed-sale-row! {:article "ART-A" :sku 10 :date "2026-04-05T10:00:00"
                     :total-price 100.0})
    (seed-sale-row! {:article "ART-A" :sku 10 :date "2026-04-15T10:00:00"
                     :total-price 200.0})
    (seed-sale-row! {:article "ART-A" :sku 10 :date "2026-04-25T10:00:00"
                     :total-price 300.0})
    ;; One realization batch covering the month.
    (seed-realization-raw! "ART-A" 10 "2026-04-01" "2026-04-30")

    (mat/materialize-finance! ["2026-04-01" "2026-04-30"] :marketplace :ozon)

    (let [rows         (ozon-finance-rows)
          dates        (set (map :event-date rows))
          sources      (set (map :event-date-source rows))
          sum-for-pay  (reduce + 0.0 (keep :for-pay rows))]
      (testing "spread to 3 daily children (one per sales-day)"
        (is (= 3 (count rows))
            (str "Expected 3 rows, got " (count rows) ": "
                 (mapv (juxt :event-date :event-date-source :for-pay) rows))))
      (testing "event_date matches sales days, not month-start"
        (is (= #{"2026-04-05" "2026-04-15" "2026-04-25"} dates)))
      (testing "all daily children tagged event_date_source = 'spread'"
        (is (= #{"spread"} sources)))
      (testing "for_pay sum preserved (within rounding) — original was 500"
        (is (< (Math/abs (- 500.0 sum-for-pay)) 1.0)
            (str "Sum preserved? got " sum-for-pay))))))

(deftest materialize-ozon-finance-no-coverage-fallback
  ;; D1 Phase D semantics: no sales/orders coverage → flat distribution
  ;; across all days in the realization period (was: keep month-stamped).
  ;; Sums are still preserved; the 'flat' tag distinguishes guess-
  ;; distributed rows from real-coverage 'spread' rows in audits.
  (testing "article without sales/orders coverage flat-spreads across the period"
    (seed-realization-raw! "ART-NOCOVER" 99 "2026-04-01" "2026-04-30")

    (mat/materialize-finance! ["2026-04-01" "2026-04-30"] :marketplace :ozon)

    (let [rows (ozon-finance-rows)]
      (testing "30 children, one per day"
        (is (= 30 (count rows))))
      (testing "event_date_source = 'flat' on every child"
        (is (= #{"flat"} (set (map :event-date-source rows)))))
      (testing "first and last day present"
        (let [dates (set (map :event-date rows))]
          (is (contains? dates "2026-04-01"))
          (is (contains? dates "2026-04-30")))))))

(deftest materialize-ozon-finance-respread-is-idempotent
  (testing "running materialize-finance twice produces the same row count"
    (seed-sale-row! {:article "ART-IDEM" :sku 11 :date "2026-04-10T10:00:00"
                     :total-price 100.0})
    (seed-sale-row! {:article "ART-IDEM" :sku 11 :date "2026-04-20T10:00:00"
                     :total-price 200.0})
    (seed-realization-raw! "ART-IDEM" 11 "2026-04-01" "2026-04-30")

    (mat/materialize-finance! ["2026-04-01" "2026-04-30"] :marketplace :ozon)
    (let [count-1 (count (ozon-finance-rows))]
      (mat/materialize-finance! ["2026-04-01" "2026-04-30"] :marketplace :ozon)
      (let [count-2 (count (ozon-finance-rows))]
        (testing "second run does not multiply rows"
          (is (= count-1 count-2)
              (str "First run: " count-1 " rows; second run: " count-2)))))))

;; ---------------------------------------------------------------------------
;; Ozon postings overlap dedup
;;
;; Production hit: 6 weekly Ozon raw postings batches with overlapping
;; date ranges → mapcat over batches multiplies the same posting_number
;; → ozon/->sales emits N copies of the same sale-id → INSERT OR REPLACE
;; silently collapses N duplicates into 1 → 28% of postings vanish
;; from the sales table. Fix: dedupe by posting_number before transform,
;; keeping the latest-ingested copy.
;; ---------------------------------------------------------------------------

(defn- seed-postings-raw!
  "Seed a raw_data postings batch with N postings."
  [date-from date-to postings]
  (db/insert-raw! :ozon :postings date-from date-to postings))

(deftest materialize-ozon-sales-dedupes-overlapping-batches
  (testing "same posting_number across overlapping batches counts ONCE"
    (let [;; A delivered posting present in both batches.
          shared {:posting_number "P-001"
                  :status         "delivered"
                  :in_process_at  "2026-04-10T10:00:00Z"
                  :products       [{:offer_id "ART-A"
                                    :sku      10
                                    :quantity 1
                                    :price    "100"}]
                  :financial_data {:products [{:product_id 10
                                               :payout 80}]}
                  :analytics_data {:warehouse "WH" :region "RU"}}
          batch-1-only {:posting_number "P-002"
                        :status         "delivered"
                        :in_process_at  "2026-04-12T10:00:00Z"
                        :products       [{:offer_id "ART-B"
                                          :sku      11
                                          :quantity 1
                                          :price    "200"}]
                        :financial_data {:products [{:product_id 11
                                                     :payout 160}]}
                        :analytics_data {:warehouse "WH" :region "RU"}}
          batch-2-only {:posting_number "P-003"
                        :status         "delivered"
                        :in_process_at  "2026-04-15T10:00:00Z"
                        :products       [{:offer_id "ART-C"
                                          :sku      12
                                          :quantity 1
                                          :price    "300"}]
                        :financial_data {:products [{:product_id 12
                                                     :payout 240}]}
                        :analytics_data {:warehouse "WH" :region "RU"}}]
      (seed-postings-raw! "2026-04-01" "2026-04-30" [shared batch-1-only])
      (seed-postings-raw! "2026-04-13" "2026-04-30" [shared batch-2-only]))

    (mat/materialize-sales! ["2026-04-01" "2026-04-30"] :marketplace :ozon)

    (let [rows (db/query
                 ["SELECT article, COUNT(*) AS n
                   FROM sales WHERE marketplace = 'ozon'
                   GROUP BY article ORDER BY article"])
          total (reduce + 0 (map :n rows))]
      (testing "shared posting written once, two batch-only postings each once"
        (is (= 3 total)
            (str "Expected 3 sales rows (1 shared + 2 unique). Got: " rows)))
      (testing "all 3 articles present"
        (is (= #{"ART-A" "ART-B" "ART-C"}
               (set (map :article rows))))))))
