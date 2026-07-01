(ns analitica.db-test
  "Tests for analitica.db init! idempotency + migrations.

   US0 (spec 003-finance-row-completeness) adds an `ad_cost REAL DEFAULT 0`
   column to the `finance` table via an idempotent PRAGMA-guarded
   migration. These tests exercise init! twice against a fresh
   temp-file SQLite DB and assert:

     - first run creates `finance.ad_cost` with type REAL and default 0
     - second run does NOT throw (idempotent)
     - rows inserted BEFORE the ALTER acquire `ad_cost = 0` from DEFAULT"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [analitica.db :as db])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temp-file SQLite DB helpers — we need file-based storage because
;; `db/init!` runs several statements that each open their own connection
;; via next.jdbc's datasource (not a single connection). An in-memory
;; `:memory:` DB would give each statement its own empty database.
;; ---------------------------------------------------------------------------

(def ^:dynamic *test-db-path* nil)

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-db-test-"
                                   ".db"
                                   (make-array FileAttribute 0))
        f    (.toFile path)]
    ;; createTempFile creates the empty file; delete so sqlite-jdbc can
    ;; initialize a clean DB (SQLite opens fine either way, but this
    ;; avoids any ambiguity from a zero-byte pre-existing file).
    (.delete f)
    (.getAbsolutePath f)))

(defn- delete-test-db! [path]
  (doseq [suffix ["" "-shm" "-wal"]]
    (let [f (File. (str path suffix))]
      (when (.exists f) (.delete f)))))

(defn with-temp-db
  "Test fixture: for each test, rebinds the private `db-spec` to a fresh
   temp-file sqlite DB so `db/init!` can run against it without touching
   the production `analitica.db` file."
  [f]
  (let [path      (fresh-temp-db-path)
        orig-spec (deref #'db/db-spec)]
    (try
      (alter-var-root #'db/db-spec (constantly {:dbtype "sqlite" :dbname path}))
      (binding [*test-db-path* path]
        (f))
      (finally
        (alter-var-root #'db/db-spec (constantly orig-spec))
        (reset! @#'db/datasource nil)
        (delete-test-db! path)))))

(use-fixtures :each with-temp-db)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- finance-column-info
  "Return PRAGMA table_info(finance) rows as a vector of unqualified maps."
  [ds]
  (jdbc/execute! ds ["PRAGMA table_info(finance)"]
                 {:builder-fn rs/as-unqualified-maps}))

(defn- find-column [info col-name]
  (first (filter #(= col-name (:name %)) info)))

;; ---------------------------------------------------------------------------
;; T004 — idempotent :ad-cost migration
;; ---------------------------------------------------------------------------

(deftest init!-creates-ad-cost-column
  (testing "finance.ad_cost exists after init!, is REAL and DEFAULT 0"
    (let [ds  (db/init!)
          info (finance-column-info ds)
          col  (find-column info "ad_cost")]
      (is (some? col) "ad_cost column should exist in finance table")
      (is (= "REAL" (:type col)) "ad_cost column should be of type REAL")
      ;; sqlite stores the default as the literal text "0" (sometimes
      ;; wrapped in quotes depending on the engine version) — accept
      ;; either bare "0", "'0'", or numeric 0.
      (let [dflt (:dflt_value col)]
        (is (or (= "0" dflt)
                (= "'0'" dflt)
                (= 0 dflt)
                (= 0.0 dflt))
            (str "ad_cost default should be 0 (got " (pr-str dflt) ")"))))))

(deftest init!-creates-net-sales-column
  (testing "spec 012: finance.net_sales exists after init!, type REAL"
    (let [ds  (db/init!)
          col (find-column (finance-column-info ds) "net_sales")]
      (is (some? col) "net_sales column should exist in finance after init!")
      (is (= "REAL" (:type col)) "net_sales column should be REAL")))
  (testing "spec 012: net_sales migration is idempotent (2× init! → column present exactly once)"
    (db/init!)
    (let [ds   (db/init!)
          n    (count (filter #(= "net_sales" (:name %)) (finance-column-info ds)))]
      (is (= 1 n) "net_sales present exactly once after repeated init!"))))

(deftest init!-creates-item-events-table
  (testing "Phase 5a: canonical event log table exists after init!.
            Each row = one lifecycle event of one item-unit; replaces
            split-by-MP semantics of orders/sales/finance for counting."
    (let [ds (db/init!)
          info (jdbc/execute! ds ["PRAGMA table_info(item_events)"]
                              {:builder-fn rs/as-unqualified-maps})
          col-names (set (map :name info))]
      (is (seq info) "item_events table must exist")
      (doseq [c ["marketplace" "posting_id" "item_seq" "article" "sku" "barcode"
                 "event_type" "event_date" "event_ts" "quantity"
                 "related_event_id" "gross_price" "status" "raw_data_id"
                 "ingested_at"]]
        (is (contains? col-names c) (str "column missing: " c))))))

(deftest init!-creates-return-logistics-and-dropoff-cost-columns
  (testing "Phase 4 split adds finance.return_logistics and finance.dropoff_cost
            columns so Ozon return/dropoff services can be tracked separately
            from forward delivery_cost (LK Накопления sverka)."
    (let [ds   (db/init!)
          info (finance-column-info ds)]
      (is (some? (find-column info "return_logistics"))
          "finance.return_logistics column should exist after init!")
      (is (some? (find-column info "dropoff_cost"))
          "finance.dropoff_cost column should exist after init!")
      (let [rl (find-column info "return_logistics")
            dc (find-column info "dropoff_cost")]
        (is (= "REAL" (:type rl)))
        (is (= "REAL" (:type dc)))))))

(deftest init!-is-idempotent
  (testing "calling init! twice in a row does not throw duplicate-column error"
    (let [ds1 (db/init!)]
      (is (some? ds1))
      ;; Second call must not raise "duplicate column name: ad_cost"
      (is (some? (db/init!)))
      (let [info (finance-column-info (db/ds))
            matches (filter #(= "ad_cost" (:name %)) info)]
        (is (= 1 (count matches))
            "ad_cost column should be present exactly once after 2 init! calls")))))

(deftest existing-rows-get-default-zero-after-migration
  (testing "rows inserted BEFORE the ad_cost migration acquire ad_cost=0"
    ;; Simulate a pre-migration production DB: create the full finance
    ;; schema EXCEPT the ad_cost column, insert a row, then call db/init!.
    ;; The idempotent migration should add ad_cost with DEFAULT 0, and
    ;; the pre-existing row should acquire ad_cost=0 via the DEFAULT.
    (let [pre-ds (jdbc/get-datasource {:dbtype "sqlite" :dbname *test-db-path*})]
      (jdbc/execute! pre-ds
        ["CREATE TABLE finance (
            rrd_id             INTEGER NOT NULL,
            report_id          INTEGER,
            date_from          TEXT,
            date_to            TEXT,
            article            TEXT,
            nm_id              INTEGER,
            barcode            TEXT,
            subject            TEXT,
            brand              TEXT,
            operation          TEXT,
            doc_type           TEXT,
            quantity           INTEGER,
            retail_price       REAL,
            retail_amount      REAL,
            sale_percent       REAL,
            commission_pct     REAL,
            mp_commission      REAL,
            wb_reward          REAL,
            wb_kvw_prc         REAL,
            spp_prc            REAL,
            price_with_disc    REAL,
            delivery_amount    INTEGER,
            return_amount      INTEGER,
            delivery_cost      REAL,
            for_pay            REAL,
            penalty            REAL,
            storage_fee        REAL,
            acceptance         REAL,
            additional_payment REAL,
            deduction          REAL,
            acquiring_fee      REAL,
            marketplace        TEXT NOT NULL DEFAULT 'wb',
            synced_at          TEXT,
            PRIMARY KEY (marketplace, rrd_id)
          )"])
      (jdbc/execute! pre-ds
        ["INSERT INTO finance (rrd_id, date_from, date_to, article,
                               operation, for_pay, marketplace, synced_at)
          VALUES (?,?,?,?,?,?,?,?)"
         42 "2026-03-01" "2026-03-07" "art-1"
         "sale" 100.0 "wb" "2026-03-07T00:00:00"])
      ;; Step 2: run init! — this applies the ad_cost migration
      (let [ds (db/init!)
            row (-> (jdbc/execute! ds
                     ["SELECT rrd_id, ad_cost FROM finance WHERE rrd_id = ?" 42]
                     {:builder-fn rs/as-unqualified-maps})
                    first)]
        (is (some? row) "pre-existing row should still be present")
        (is (= 42 (:rrd_id row)))
        (is (or (= 0 (:ad_cost row))
                (= 0.0 (:ad_cost row)))
            (str "pre-existing row should have ad_cost=0 (got "
                 (pr-str (:ad_cost row)) ")"))))))

;; ---------------------------------------------------------------------------
;; T004 (spec 011-ozon-performance-ads) — new advertising canon tables
;;   ad_spend            (attributed spend → feeds finance.ad_cost)
;;   ad_campaign_stats   (per-campaign/per-day raw efficiency data)
;; Both are additive CREATE TABLE IF NOT EXISTS migrations (P5 — no ALTER on
;; existing product tables). See data-model.md §2.1 / §2.2.
;; ---------------------------------------------------------------------------

(defn- table-info
  "Return PRAGMA table_info(<table>) rows as a vector of unqualified maps."
  [ds table]
  (jdbc/execute! ds [(str "PRAGMA table_info(" table ")")]
                 {:builder-fn rs/as-unqualified-maps}))

(defn- pk-columns
  "Return the set of column names participating in the PRIMARY KEY, in
   declared order (PRAGMA table_info :pk is the 1-based position within
   the PK, 0 = not part of it)."
  [info]
  (->> info
       (filter #(pos? (or (:pk %) 0)))
       (sort-by :pk)
       (mapv :name)))

(deftest init!-creates-ad-spend-table
  (testing "spec 011 §2.1: ad_spend table exists with the canonical columns + PK"
    (let [ds        (db/init!)
          info      (table-info ds "ad_spend")
          col-names (set (map :name info))]
      (is (seq info) "ad_spend table must exist after init!")
      (doseq [c ["marketplace" "event_date" "campaign_id" "campaign_type"
                 "article" "sku" "spend" "bonus_spend" "attribution_source"
                 "basis" "synced_at"]]
        (is (contains? col-names c) (str "ad_spend column missing: " c)))
      (is (= ["marketplace" "event_date" "campaign_id" "article"]
             (pk-columns info))
          "ad_spend PRIMARY KEY = (marketplace, event_date, campaign_id, article)")
      (is (= "REAL" (:type (find-column info "spend"))))
      (is (= "REAL" (:type (find-column info "bonus_spend")))))))

(deftest init!-creates-ad-campaign-stats-table
  (testing "spec 011 §2.2: ad_campaign_stats table exists with columns + PK"
    (let [ds        (db/init!)
          info      (table-info ds "ad_campaign_stats")
          col-names (set (map :name info))]
      (is (seq info) "ad_campaign_stats table must exist after init!")
      (doseq [c ["marketplace" "campaign_id" "campaign_type" "campaign_name"
                 "stat_date" "views" "clicks" "add_to_cart" "orders"
                 "orders_revenue" "spend" "bonus_spend" "synced_at"]]
        (is (contains? col-names c) (str "ad_campaign_stats column missing: " c)))
      (is (= ["marketplace" "campaign_id" "stat_date"]
             (pk-columns info))
          "ad_campaign_stats PRIMARY KEY = (marketplace, campaign_id, stat_date)"))))

(deftest init!-ad-tables-are-idempotent
  (testing "spec 011: double init! creates each ad table exactly once, no throw"
    (db/init!)
    (let [ds (db/init!)]
      (is (seq (table-info ds "ad_spend")) "ad_spend still present after 2× init!")
      (is (seq (table-info ds "ad_campaign_stats"))
          "ad_campaign_stats still present after 2× init!"))))

(deftest init!-preserves-existing-finance-columns
  (testing "spec 011: adding ad tables does NOT drop existing finance columns
            (ad_cost / event_date_source must survive — no ALTER regression)"
    (let [ds   (db/init!)
          info (finance-column-info ds)]
      (is (some? (find-column info "ad_cost"))
          "finance.ad_cost must still exist alongside new ad tables")
      (is (some? (find-column info "event_date_source"))
          "finance.event_date_source must still exist alongside new ad tables"))))

;; ---------------------------------------------------------------------------
;; T007 (spec 011) — ad_spend / ad_campaign_stats DB helpers
;; ---------------------------------------------------------------------------

(deftest ad-spend-insert-delete-get-roundtrip
  (testing "spec 011 §2.1: insert-ad-spend! → get-ad-spend → delete-ad-spend!"
    (db/init!)
    (let [rows [{:marketplace "ozon" :event-date "2026-04-15"
                 :campaign-id "78901" :campaign-type "SEARCH_PROMO"
                 :article "ABC-123" :sku "12345678"
                 :spend 1234.56 :bonus-spend 100.0
                 :attribution-source "api"
                 :basis "test" :synced-at "2026-04-30T00:00:00"}
                {:marketplace "ozon" :event-date "2026-04-16"
                 :campaign-id nil :campaign-type nil
                 :article nil :sku nil
                 :spend 50.0 :bonus-spend 0.0
                 :attribution-source "spread"
                 :basis "test" :synced-at "2026-04-30T00:00:00"}]]
      (db/insert-ad-spend! rows)
      (let [got (db/get-ad-spend "ozon" "2026-04-01" "2026-04-30")]
        (is (= 2 (count got)) "both ad_spend rows retrievable by mp+period"))
      ;; period-scoped delete removes only Ozon rows in the window
      (db/delete-ad-spend! "ozon" "2026-04-01" "2026-04-30")
      (is (= 0 (count (db/get-ad-spend "ozon" "2026-04-01" "2026-04-30")))
          "delete-ad-spend! clears the window (re-materialize semantics)"))))

(deftest ad-campaign-stats-insert-get-roundtrip
  (testing "spec 011 §2.2: insert-ad-campaign-stats! → get-ad-campaign-stats,
            natural-key PK folds a re-insert (INSERT OR REPLACE, idempotent)"
    (db/init!)
    (let [row {:marketplace "ozon" :campaign-id "78901"
               :campaign-type "SEARCH_PROMO" :campaign-name "Autumn"
               :stat-date "2026-04-15" :views 1200 :clicks 48
               :add-to-cart 10 :orders 6 :orders-revenue 9000.0
               :spend 1234.56 :bonus-spend 100.0
               :synced-at "2026-04-30T00:00:00"}]
      (db/insert-ad-campaign-stats! [row])
      (db/insert-ad-campaign-stats! [row]) ;; re-insert same PK
      (let [got (db/get-ad-campaign-stats "ozon" "2026-04-01" "2026-04-30")]
        (is (= 1 (count got)) "PK (marketplace, campaign_id, stat_date) dedups reruns")
        (is (= 1200 (:views (first got))))))))
