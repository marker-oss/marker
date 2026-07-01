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

;; ---------------------------------------------------------------------------
;; T004 (spec 015) — tax_config / opex_rows tables + indexes
;; ---------------------------------------------------------------------------

(defn- table-col-info [ds table-name]
  (jdbc/execute! ds [(str "PRAGMA table_info(" table-name ")")]
                 {:builder-fn rs/as-unqualified-maps}))

(defn- index-exists? [ds index-name]
  (let [rows (jdbc/execute! ds
               ["SELECT name FROM sqlite_master WHERE type='index' AND name=?", index-name]
               {:builder-fn rs/as-unqualified-maps})]
    (some? (seq rows))))

(deftest spec-015-tax-config-table-exists
  (testing "spec 015 T004: tax_config table is created by init! with correct columns and PK"
    (let [ds   (db/init!)
          info (table-col-info ds "tax_config")
          cols (set (map :name info))]
      (is (seq info) "tax_config table must exist after init!")
      (doseq [c ["year" "month" "taxation_type" "usn_rate" "vat_rate"
                 "official_cost_price" "updated_at"]]
        (is (contains? cols c) (str "tax_config missing column: " c)))
      ;; Verify composite PK: both year and month have pk > 0
      (let [pk-cols (->> info (filter #(pos? (:pk %))) (map :name) set)]
        (is (contains? pk-cols "year")  "year must be part of PK")
        (is (contains? pk-cols "month") "month must be part of PK")))))

(deftest spec-015-opex-rows-table-exists
  (testing "spec 015 T004: opex_rows table is created by init! with correct columns"
    (let [ds   (db/init!)
          info (table-col-info ds "opex_rows")
          cols (set (map :name info))]
      (is (seq info) "opex_rows table must exist after init!")
      (doseq [c ["id" "period_month" "category" "amount" "marketplace"
                 "note" "source" "rule_id" "created_at"]]
        (is (contains? cols c) (str "opex_rows missing column: " c)))
      ;; id is the sole PK
      (let [pk-col (->> info (filter #(= 1 (:pk %))) first)]
        (is (= "id" (:name pk-col)) "id must be the primary key of opex_rows")))))

(deftest spec-015-opex-rows-indexes-exist
  (testing "spec 015 T004: required indexes on opex_rows exist after init!"
    (let [ds (db/init!)]
      (is (index-exists? ds "idx_opex_period")
          "idx_opex_period on opex_rows(period_month) must exist")
      (is (index-exists? ds "idx_opex_period_mp")
          "idx_opex_period_mp on opex_rows(period_month, marketplace) must exist")
      (is (index-exists? ds "idx_opex_rule_period")
          "idx_opex_rule_period unique partial index on opex_rows must exist"))))

(deftest spec-015-tables-idempotent
  (testing "spec 015 T004: calling init! twice does not throw; tables present exactly once"
    (db/init!)
    (let [ds (db/init!)]
      (is (= 1 (count (jdbc/execute! ds
                        ["SELECT name FROM sqlite_master WHERE type='table' AND name='tax_config'"]
                        {:builder-fn rs/as-unqualified-maps})))
          "tax_config present exactly once after 2× init!")
      (is (= 1 (count (jdbc/execute! ds
                        ["SELECT name FROM sqlite_master WHERE type='table' AND name='opex_rows'"]
                        {:builder-fn rs/as-unqualified-maps})))
          "opex_rows present exactly once after 2× init!"))))

;; ---------------------------------------------------------------------------
;; T004 (spec 017) — bot_subscriptions + bot_deliveries tables
;;                   + monthly_plans.sku additive migration
;; ---------------------------------------------------------------------------

(deftest spec-017-bot-subscriptions-table-exists
  (testing "spec 017: bot_subscriptions table created by init! with all required columns"
    (let [ds   (db/init!)
          info (table-col-info ds "bot_subscriptions")
          cols (set (map :name info))]
      (is (seq info) "bot_subscriptions table must exist after init!")
      (doseq [c ["id" "chat_id" "label" "cadences" "metrics"
                 "show_movers" "marketplace" "gate_when_empty"
                 "status" "created_at" "updated_at"]]
        (is (contains? cols c) (str "bot_subscriptions missing column: " c)))))
  (testing "spec 017: bot_subscriptions UNIQUE(chat_id) constraint present"
    (let [ds (db/init!)]
      ;; Insert one row, then insert duplicate chat_id — must throw a unique constraint violation
      (jdbc/execute! ds ["INSERT INTO bot_subscriptions
                            (chat_id, cadences, metrics, show_movers, marketplace,
                             gate_when_empty, status, created_at, updated_at)
                          VALUES (?,?,?,?,?,?,?,?,?)"
                         "chat-999" "daily" "" 1 "all" "skip" "active"
                         "2026-07-01T00:00:00" "2026-07-01T00:00:00"])
      (is (thrown? Exception
            (jdbc/execute! ds ["INSERT INTO bot_subscriptions
                                  (chat_id, cadences, metrics, show_movers, marketplace,
                                   gate_when_empty, status, created_at, updated_at)
                                VALUES (?,?,?,?,?,?,?,?,?)"
                               "chat-999" "daily" "" 1 "all" "skip" "active"
                               "2026-07-01T00:00:00" "2026-07-01T00:00:00"]))
          "UNIQUE(chat_id) must reject duplicate chat_id"))))

(deftest spec-017-bot-subscriptions-index-exists
  (testing "spec 017: idx_bot_subs_status index exists after init!"
    (let [ds (db/init!)]
      (is (index-exists? ds "idx_bot_subs_status")
          "idx_bot_subs_status index on bot_subscriptions(status) must exist"))))

(deftest spec-017-bot-deliveries-table-exists
  (testing "spec 017: bot_deliveries table created by init! with all required columns"
    (let [ds   (db/init!)
          info (table-col-info ds "bot_deliveries")
          cols (set (map :name info))]
      (is (seq info) "bot_deliveries table must exist after init!")
      (doseq [c ["id" "chat_id" "cadence" "period" "outcome"
                 "detail" "fail_count" "sent_at"]]
        (is (contains? cols c) (str "bot_deliveries missing column: " c)))))
  (testing "spec 017: bot_deliveries UNIQUE(chat_id,cadence,period) constraint present"
    (let [ds (db/init!)]
      (jdbc/execute! ds ["INSERT INTO bot_deliveries
                            (chat_id, cadence, period, outcome, fail_count, sent_at)
                          VALUES (?,?,?,?,?,?)"
                         "chat-1" "daily" "2026-06-30" "delivered" 0 "2026-07-01T08:00:00"])
      (is (thrown? Exception
            (jdbc/execute! ds ["INSERT INTO bot_deliveries
                                  (chat_id, cadence, period, outcome, fail_count, sent_at)
                                VALUES (?,?,?,?,?,?)"
                               "chat-1" "daily" "2026-06-30" "delivered" 0 "2026-07-01T09:00:00"]))
          "UNIQUE(chat_id,cadence,period) must reject duplicate delivery key"))))

(deftest spec-017-bot-deliveries-index-exists
  (testing "spec 017: idx_bot_deliv_lookup index exists after init!"
    (let [ds (db/init!)]
      (is (index-exists? ds "idx_bot_deliv_lookup")
          "idx_bot_deliv_lookup index on bot_deliveries must exist"))))

(deftest spec-017-monthly-plans-sku-column-exists
  (testing "spec 017: monthly_plans.sku column present after init! with TEXT type and DEFAULT ''"
    (let [ds   (db/init!)
          info (table-col-info ds "monthly_plans")
          col  (find-column info "sku")]
      (is (some? col) "monthly_plans.sku column must exist after init!")
      (is (= "TEXT" (:type col)) "monthly_plans.sku must be TEXT")
      ;; SQLite stores default '' as '' or as empty-quoted string
      (let [dflt (:dflt_value col)]
        (is (or (= "''" dflt) (= "" dflt))
            (str "monthly_plans.sku default must be '' (got " (pr-str dflt) ")")))))
  (testing "spec 017: existing monthly_plans rows survive sku migration with sku=''"
    (let [pre-ds (jdbc/get-datasource {:dbtype "sqlite" :dbname *test-db-path*})]
      ;; Create monthly_plans WITHOUT sku, insert a row, then run init!
      (jdbc/execute! pre-ds
        ["CREATE TABLE IF NOT EXISTS monthly_plans (
            period_month TEXT NOT NULL,
            marketplace  TEXT NOT NULL,
            metric       TEXT NOT NULL,
            target_value REAL NOT NULL,
            updated_at   TEXT NOT NULL DEFAULT (datetime('now')),
            PRIMARY KEY (period_month, marketplace, metric)
          )"])
      (jdbc/execute! pre-ds
        ["INSERT INTO monthly_plans (period_month, marketplace, metric, target_value)
          VALUES (?,?,?,?)"
         "2026-06" "wb" "revenue" 500000.0])
      (let [ds  (db/init!)
            row (-> (jdbc/execute! ds
                      ["SELECT period_month, marketplace, metric, target_value, sku
                        FROM monthly_plans
                        WHERE period_month = ? AND marketplace = ? AND metric = ?"
                       "2026-06" "wb" "revenue"]
                      {:builder-fn rs/as-unqualified-maps})
                    first)]
        (is (some? row) "pre-existing monthly_plans row must still be present")
        (is (= 500000.0 (:target_value row)) "target_value must be unchanged")
        (is (= "" (:sku row)) "pre-existing row must have sku='' after migration")))))

(deftest spec-017-monthly-plans-sku-index-exists
  (testing "spec 017: uq_monthly_plans_sku unique index exists after init!"
    (let [ds (db/init!)]
      (is (index-exists? ds "uq_monthly_plans_sku")
          "uq_monthly_plans_sku unique index on monthly_plans(period_month,marketplace,metric,sku) must exist"))))

(deftest spec-017-init!-idempotent-bot-tables
  (testing "spec 017: double init! does not throw; bot tables and sku column present after 2× runs"
    (db/init!)
    (let [ds (db/init!)]
      (is (seq (table-col-info ds "bot_subscriptions"))
          "bot_subscriptions present after 2× init!")
      (is (seq (table-col-info ds "bot_deliveries"))
          "bot_deliveries present after 2× init!")
      (is (some? (find-column (table-col-info ds "monthly_plans") "sku"))
          "monthly_plans.sku present after 2× init!")
      ;; Existing non-bot tables must survive
      (is (seq (table-col-info ds "finance"))
          "finance table must survive 2× init! with bot tables added")
      (is (seq (table-col-info ds "monthly_plans"))
          "monthly_plans must survive 2× init!"))))

;; ---------------------------------------------------------------------------
;; T008/T009 (spec 019-treasury-ledger) — treasury_* tables + indexes
;;   treasury_accounts / treasury_counterparties / treasury_operations /
;;   treasury_auto_rules / treasury_obligations
;; All additive CREATE TABLE IF NOT EXISTS. Money columns are TEXT
;; (decimal-string "0.00"), NOT REAL — this is the whole point of the ledger
;; path (FR-019). data-model.md §5.
;; ---------------------------------------------------------------------------

(def ^:private treasury-tables
  ["treasury_accounts" "treasury_counterparties" "treasury_operations"
   "treasury_auto_rules" "treasury_obligations"])

(deftest spec-019-treasury-tables-exist
  (testing "spec 019 T009: all 5 treasury_* tables created by init! with a single-column id PK"
    (let [ds (db/init!)]
      (doseq [t treasury-tables]
        (let [info (table-info ds t)]
          (is (seq info) (str t " table must exist after init!"))
          (is (= ["id"] (pk-columns info))
              (str t " PRIMARY KEY = (id)")))))))

(deftest spec-019-treasury-accounts-columns
  (testing "spec 019: treasury_accounts columns"
    (let [ds   (db/init!)
          info (table-info ds "treasury_accounts")
          cols (set (map :name info))]
      (doseq [c ["id" "name" "marketplace" "kind" "currency" "archived_at" "created_at"]]
        (is (contains? cols c) (str "treasury_accounts missing column: " c))))))

(deftest spec-019-treasury-counterparties-columns
  (testing "spec 019: treasury_counterparties columns"
    (let [ds   (db/init!)
          info (table-info ds "treasury_counterparties")
          cols (set (map :name info))]
      (doseq [c ["id" "name" "kind" "archived_at" "created_at"]]
        (is (contains? cols c) (str "treasury_counterparties missing column: " c))))))

(deftest spec-019-treasury-operations-columns-money-is-text
  (testing "spec 019: treasury_operations columns present; amount is TEXT (NOT REAL) — FR-019"
    (let [ds   (db/init!)
          info (table-info ds "treasury_operations")
          cols (set (map :name info))]
      (doseq [c ["id" "op_date" "amount" "currency" "direction" "account_id"
                 "transfer_account_id" "counterparty_id" "category"
                 "category_source" "applied_rule_id" "confirmed" "regular"
                 "description" "source" "created_at"]]
        (is (contains? cols c) (str "treasury_operations missing column: " c)))
      (is (= "TEXT" (:type (find-column info "amount")))
          "treasury_operations.amount MUST be TEXT (decimal-string), never REAL (FR-019)"))))

(deftest spec-019-treasury-auto-rules-columns
  (testing "spec 019: treasury_auto_rules columns"
    (let [ds   (db/init!)
          info (table-info ds "treasury_auto_rules")
          cols (set (map :name info))]
      (doseq [c ["id" "match_field" "match_op" "match_value" "category"
                 "priority" "enabled" "created_at"]]
        (is (contains? cols c) (str "treasury_auto_rules missing column: " c))))))

(deftest spec-019-treasury-obligations-columns-money-is-text
  (testing "spec 019: treasury_obligations columns; amount + remaining_amount are TEXT (FR-019)"
    (let [ds   (db/init!)
          info (table-info ds "treasury_obligations")
          cols (set (map :name info))]
      (doseq [c ["id" "direction" "amount" "remaining_amount" "currency"
                 "counterparty_id" "issue_date" "due_date"
                 "settled_operation_id" "confirmed" "created_at"]]
        (is (contains? cols c) (str "treasury_obligations missing column: " c)))
      (is (= "TEXT" (:type (find-column info "amount")))
          "treasury_obligations.amount MUST be TEXT (decimal-string), never REAL")
      (is (= "TEXT" (:type (find-column info "remaining_amount")))
          "treasury_obligations.remaining_amount MUST be TEXT (decimal-string), never REAL"))))

(deftest spec-019-treasury-indexes-exist
  (testing "spec 019 T009: treasury indexes exist after init!"
    (let [ds (db/init!)]
      (is (index-exists? ds "idx_treasury_op_acc_date"))
      (is (index-exists? ds "idx_treasury_op_category"))
      (is (index-exists? ds "idx_treasury_op_confirmed"))
      (is (index-exists? ds "idx_treasury_obl_due")))))

(deftest spec-019-treasury-idempotent-and-nondestructive
  (testing "spec 019: 2× init! does not throw; each treasury table present exactly once"
    (db/init!)
    (let [ds (db/init!)]
      (doseq [t treasury-tables]
        (is (= 1 (count (jdbc/execute! ds
                          ["SELECT name FROM sqlite_master WHERE type='table' AND name=?" t]
                          {:builder-fn rs/as-unqualified-maps})))
            (str t " present exactly once after 2× init!")))))
  (testing "spec 019 (P5/SC-008): treasury migration does NOT touch analytics tables"
    (let [ds (db/init!)]
      ;; cash_flow_periods (seed source for treasury) must survive unchanged —
      ;; still REAL columns, not converted to TEXT.
      (is (seq (table-info ds "cash_flow_periods"))
          "cash_flow_periods must survive treasury migration (seed source, P&L.6)")
      (is (= "REAL" (:type (find-column (table-info ds "cash_flow_periods") "orders_amount")))
          "cash_flow_periods.orders_amount stays REAL — analytics path is FROZEN (SC-008)")
      (is (seq (table-info ds "finance")) "finance table must survive treasury migration")
      (is (seq (table-info ds "sales"))   "sales table must survive treasury migration")
      ;; 015 opex taxonomy source must survive (shared taxonomy, §3.A)
      (is (seq (table-info ds "opex_rows")) "opex_rows must survive treasury migration")
      (is (seq (table-info ds "opex_auto_rules"))
          "opex_auto_rules must survive treasury migration (015 classification seed, §3.A)"))))

;; ---------------------------------------------------------------------------
;; T050 (spec 015 US5) — opex_auto_rules table + idx_opex_auto_active
;;   Dedicated assertions for the auto-rule engine DDL (FR-020/FR-021).
;; ---------------------------------------------------------------------------

(deftest spec-015-us5-opex-auto-rules-table-exists
  (testing "spec 015 T050: opex_auto_rules table created by init! with all required columns"
    (let [ds   (db/init!)
          info (table-col-info ds "opex_auto_rules")
          cols (set (map :name info))]
      (is (seq info) "opex_auto_rules table must exist after init!")
      (doseq [c ["id" "category" "amount" "marketplace" "cadence"
                 "effective_from" "effective_to" "note" "created_at"]]
        (is (contains? cols c) (str "opex_auto_rules missing column: " c)))
      ;; id is the sole PK
      (let [pk-col (->> info (filter #(pos? (:pk %))) first)]
        (is (= "id" (:name pk-col)) "id must be the primary key of opex_auto_rules"))))
  (testing "spec 015 T050: opex_rows.source DEFAULT 'manual' and rule_id nullable columns exist"
    (let [ds   (db/init!)
          info (table-col-info ds "opex_rows")
          src  (find-column info "source")
          rid  (find-column info "rule_id")]
      (is (some? src) "opex_rows.source column must exist")
      (is (= "TEXT" (:type src)) "opex_rows.source must be TEXT")
      (is (some? rid) "opex_rows.rule_id column must exist"))))

(deftest spec-015-us5-idx-opex-auto-active-exists
  (testing "spec 015 T050: idx_opex_auto_active index exists on opex_auto_rules after init!"
    (let [ds (db/init!)]
      (is (index-exists? ds "idx_opex_auto_active")
          "idx_opex_auto_active index on opex_auto_rules must exist"))))

(deftest spec-015-us5-opex-auto-rules-idempotent
  (testing "spec 015 T050: double init! does not throw; opex_auto_rules present exactly once"
    (db/init!)
    (let [ds (db/init!)]
      (is (= 1 (count (jdbc/execute! ds
                        ["SELECT name FROM sqlite_master WHERE type='table' AND name='opex_auto_rules'"]
                        {:builder-fn rs/as-unqualified-maps})))
          "opex_auto_rules present exactly once after 2× init!")
      (is (index-exists? ds "idx_opex_auto_active")
          "idx_opex_auto_active still present after 2× init!"))))
