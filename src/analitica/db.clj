(ns analitica.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [jsonista.core :as j]
            [analitica.util.period :as period]))

(defonce ^:private datasource (atom nil))

(def ^:private db-spec
  {:dbtype "sqlite"
   :dbname (or (System/getenv "ANALITICA_DB") "analitica.db")})

(defn ds
  "Returns the datasource. Call (init!) first."
  []
  (or @datasource
      (throw (ex-info "Database not initialized. Call (analitica.db/init!) first." {}))))

;; ---------------------------------------------------------------------------
;; Schema
;; ---------------------------------------------------------------------------

(def ^:private ddl-statements
  ["CREATE TABLE IF NOT EXISTS sales (
      sale_id       TEXT PRIMARY KEY,
      date          TEXT NOT NULL,
      article       TEXT NOT NULL,
      nm_id         INTEGER,
      barcode       TEXT,
      tech_size     TEXT,
      subject       TEXT,
      category      TEXT,
      brand         TEXT,
      warehouse     TEXT,
      region        TEXT,
      type          TEXT NOT NULL,
      total_price   REAL,
      for_pay       REAL,
      finished_price REAL,
      price_with_disc REAL,
      marketplace   TEXT DEFAULT 'wb',
      synced_at     TEXT
    )"

   "CREATE TABLE IF NOT EXISTS orders (
      order_id      TEXT PRIMARY KEY,
      date          TEXT NOT NULL,
      article       TEXT NOT NULL,
      nm_id         INTEGER,
      barcode       TEXT,
      tech_size     TEXT,
      subject       TEXT,
      category      TEXT,
      brand         TEXT,
      warehouse     TEXT,
      region        TEXT,
      status        TEXT,
      price         REAL,
      price_with_disc REAL,
      marketplace   TEXT DEFAULT 'wb',
      synced_at     TEXT
    )"

   "CREATE TABLE IF NOT EXISTS finance (
      rrd_id             INTEGER NOT NULL,
      report_id          INTEGER,
      date_from          TEXT,
      date_to            TEXT,
      event_date         TEXT,
      article            TEXT,
      nm_id              INTEGER,
      barcode            TEXT,
      subject            TEXT,
      brand              TEXT,
      operation          TEXT,
      operation_kind     TEXT,
      operation_subtype  TEXT,
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
      return_logistics   REAL DEFAULT 0,
      dropoff_cost       REAL DEFAULT 0,
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
    )"

   "CREATE TABLE IF NOT EXISTS stocks (
      id            INTEGER PRIMARY KEY AUTOINCREMENT,
      article       TEXT NOT NULL,
      nm_id         INTEGER,
      barcode       TEXT,
      tech_size     TEXT,
      subject       TEXT,
      category      TEXT,
      brand         TEXT,
      warehouse     TEXT,
      quantity      INTEGER,
      quantity_full INTEGER,
      in_way_to     INTEGER,
      in_way_from   INTEGER,
      marketplace   TEXT DEFAULT 'wb',
      synced_at     TEXT
    )"

   ;; RFC-13 (closed 2026-04-28): per-day snapshot of `stocks` so we can
   ;; compute trends, velocity, and days-of-supply. Populated by
   ;; `analitica.materialize/snapshot-stocks-history!` (one row per
   ;; (snapshot_date, marketplace, article, warehouse) tuple). PK enforces
   ;; idempotent re-runs of the snapshot for the same day.
   "CREATE TABLE IF NOT EXISTS stocks_history (
      snapshot_date TEXT NOT NULL,
      marketplace   TEXT NOT NULL DEFAULT 'wb',
      article       TEXT NOT NULL,
      warehouse     TEXT NOT NULL DEFAULT '',
      quantity      INTEGER,
      quantity_full INTEGER,
      in_way_to     INTEGER,
      in_way_from   INTEGER,
      nm_id         INTEGER,
      barcode       TEXT,
      tech_size     TEXT,
      subject       TEXT,
      brand         TEXT,
      synced_at     TEXT,
      PRIMARY KEY (snapshot_date, marketplace, article, warehouse)
    )"

   "CREATE INDEX IF NOT EXISTS idx_stocks_history_article
      ON stocks_history(marketplace, article, snapshot_date)"

   "CREATE INDEX IF NOT EXISTS idx_stocks_history_date
      ON stocks_history(snapshot_date)"

   "CREATE TABLE IF NOT EXISTS cost_prices (
      article        TEXT NOT NULL,
      barcode        TEXT NOT NULL DEFAULT '',
      cost_price     REAL NOT NULL,
      nomenclature   TEXT,
      color          TEXT,
      characteristic TEXT,
      quantity_1c    REAL,
      updated_at     TEXT,
      PRIMARY KEY (article, barcode)
    )"

   ;; Phase 5 (2026-05-05): canonical item-event log. One row per
   ;; lifecycle event of one item-unit (1 unit = 1 row). Replaces the
   ;; lossy MP-specific semantics of orders/sales tables for
   ;; заказы/продажи/возвраты/выкуп counters. Per-MP normalizers
   ;; populate this from raw_data; domain reads from here, not from
   ;; orders/sales. See specs/004-canonical-item-events/data-model.md.
   "CREATE TABLE IF NOT EXISTS item_events (
      marketplace      TEXT    NOT NULL,
      posting_id       TEXT    NOT NULL,
      item_seq         INTEGER NOT NULL DEFAULT 0,
      sku              TEXT,
      article          TEXT,
      barcode          TEXT,
      event_type       TEXT    NOT NULL,
      event_date       TEXT    NOT NULL,
      event_ts         TEXT,
      quantity         INTEGER NOT NULL DEFAULT 1,
      related_event_id INTEGER,
      gross_price      REAL,
      status           TEXT,
      raw_data_id      INTEGER,
      ingested_at      TEXT    NOT NULL,
      PRIMARY KEY (marketplace, posting_id, item_seq, event_type)
    )"

   "CREATE INDEX IF NOT EXISTS idx_item_events_lookup
      ON item_events(marketplace, event_type, event_date)"

   "CREATE INDEX IF NOT EXISTS idx_item_events_article
      ON item_events(marketplace, article, event_date)"

   "CREATE TABLE IF NOT EXISTS product_stats (
      nm_id         INTEGER,
      article       TEXT NOT NULL,
      date_from     TEXT NOT NULL,
      date_to       TEXT NOT NULL,
      views         INTEGER,
      add_to_cart   INTEGER,
      orders        INTEGER,
      orders_sum    REAL,
      buyouts       INTEGER,
      buyouts_sum   REAL,
      cancel_count  INTEGER,
      cancel_sum    REAL,
      marketplace   TEXT NOT NULL DEFAULT 'wb',
      synced_at     TEXT,
      PRIMARY KEY (article, date_from, marketplace)
    )"

   "CREATE TABLE IF NOT EXISTS prices (
      nm_id         INTEGER,
      article       TEXT NOT NULL,
      price         REAL,
      discount      INTEGER,
      club_discount INTEGER,
      marketplace   TEXT NOT NULL DEFAULT 'wb',
      synced_at     TEXT,
      PRIMARY KEY (article, marketplace)
    )"

   ;; PK is (campaign_id, date, nm_id) so multi-article campaigns (one day,
   ;; multiple nm_ids) can all coexist — needed by spec 003 US5 ad-cost
   ;; allocation. `nm_id` defaults to 0 (instead of NULL) because SQLite
   ;; treats NULLs as distinct in PK comparisons, which would defeat
   ;; INSERT OR REPLACE idempotency when the campaign has no :apps. The
   ;; ad-spend-by-article JOIN filters out nm_id=0 naturally via the
   ;; finance sub-SELECT (article IS NOT NULL).
   "CREATE TABLE IF NOT EXISTS ad_stats (
      campaign_id   INTEGER NOT NULL,
      date          TEXT NOT NULL,
      views         INTEGER,
      clicks        INTEGER,
      ctr           REAL,
      cpc           REAL,
      spend         REAL,
      atbs          INTEGER,
      orders        INTEGER,
      cr            REAL,
      shks          INTEGER,
      sum_price     REAL,
      nm_id         INTEGER NOT NULL DEFAULT 0,
      synced_at     TEXT,
      PRIMARY KEY (campaign_id, date, nm_id)
    )"

   "CREATE TABLE IF NOT EXISTS region_sales (
      nm_id         INTEGER NOT NULL DEFAULT 0,
      article       TEXT NOT NULL DEFAULT '',
      region        TEXT NOT NULL DEFAULT '',
      city          TEXT NOT NULL DEFAULT '',
      country       TEXT,
      fo            TEXT,
      qty           INTEGER,
      sum_price     REAL,
      sum_price_prc REAL,
      date_from     TEXT NOT NULL DEFAULT '',
      date_to       TEXT NOT NULL DEFAULT '',
      synced_at     TEXT,
      PRIMARY KEY (date_from, date_to, nm_id, region, city)
    )"

   "CREATE TABLE IF NOT EXISTS paid_storage (
      date           TEXT NOT NULL,
      article        TEXT NOT NULL,
      nm_id          INTEGER,
      barcode        TEXT NOT NULL DEFAULT '',
      warehouse      TEXT NOT NULL DEFAULT '',
      cost           REAL NOT NULL,
      volume         REAL,
      barcodes_count INTEGER,
      marketplace    TEXT DEFAULT 'wb',
      synced_at      TEXT,
      PRIMARY KEY (date, barcode, warehouse, marketplace)
    )"

   "CREATE TABLE IF NOT EXISTS ozon_sku_map (
      sku        INTEGER PRIMARY KEY,
      offer_id   TEXT NOT NULL
    )"

   "CREATE TABLE IF NOT EXISTS cash_flow_periods (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      source TEXT NOT NULL,
      period_begin TEXT NOT NULL,
      period_end TEXT NOT NULL,
      orders_amount REAL DEFAULT 0,
      returns_amount REAL DEFAULT 0,
      commission_amount REAL DEFAULT 0,
      delivery_amount REAL DEFAULT 0,
      delivery_logistics REAL DEFAULT 0,
      return_amount REAL DEFAULT 0,
      return_logistics REAL DEFAULT 0,
      storage REAL DEFAULT 0,
      packaging REAL DEFAULT 0,
      warehouse_movement REAL DEFAULT 0,
      returns_cargo REAL DEFAULT 0,
      subscription REAL DEFAULT 0,
      fines REAL DEFAULT 0,
      other_services REAL DEFAULT 0,
      acquiring REAL DEFAULT 0,
      corrections REAL DEFAULT 0,
      compensation REAL DEFAULT 0,
      payment REAL DEFAULT 0,
      begin_balance REAL DEFAULT 0,
      end_balance REAL DEFAULT 0,
      invoice_transfer REAL DEFAULT 0,
      synced_at TEXT NOT NULL,
      UNIQUE(source, period_begin, period_end)
    )"

   "CREATE TABLE IF NOT EXISTS cost_prices_imports (
      id          INTEGER PRIMARY KEY AUTOINCREMENT,
      source      TEXT NOT NULL,
      imported_at TEXT NOT NULL,
      fetched     INTEGER NOT NULL DEFAULT 0,
      loaded      INTEGER NOT NULL DEFAULT 0,
      rejected    INTEGER NOT NULL DEFAULT 0,
      filename    TEXT,
      notes       TEXT
    )"

   "CREATE TABLE IF NOT EXISTS raw_data (
      id          INTEGER PRIMARY KEY AUTOINCREMENT,
      source      TEXT NOT NULL,
      entity_type TEXT NOT NULL,
      date_from   TEXT,
      date_to     TEXT,
      payload     TEXT NOT NULL,
      item_count  INTEGER NOT NULL DEFAULT 0,
      ingested_at TEXT NOT NULL,
      UNIQUE(source, entity_type, date_from, date_to)
    )"

   "CREATE TABLE IF NOT EXISTS accuracy_kpi_measurements (
      kpi_id                     TEXT PRIMARY KEY,
      captured_at                TEXT NOT NULL,
      captured_by                TEXT,
      marketplace                TEXT NOT NULL,
      period_from                TEXT NOT NULL,
      period_to                  TEXT NOT NULL,
      sku_list                   TEXT NOT NULL,
      sku_selection_method       TEXT NOT NULL,
      reference_bank_sum         REAL NOT NULL,
      reference_excel_sum        REAL,
      reference_excel_by_article TEXT,
      measured_value             REAL NOT NULL,
      delta_abs_rub              REAL NOT NULL,
      delta_rel_pct              REAL NOT NULL,
      verdict                    TEXT NOT NULL CHECK (verdict IN ('meets-kpi','misses-kpi')),
      breakdown                  TEXT,
      report_id                  TEXT NOT NULL
    )"

   ;; Indexes
   "CREATE INDEX IF NOT EXISTS idx_sales_date ON sales(date)"
   "CREATE INDEX IF NOT EXISTS idx_sales_article ON sales(article)"
   "CREATE INDEX IF NOT EXISTS idx_orders_date ON orders(date)"
   "CREATE INDEX IF NOT EXISTS idx_finance_article ON finance(article)"
   "CREATE INDEX IF NOT EXISTS idx_finance_report ON finance(report_id)"
   "CREATE INDEX IF NOT EXISTS idx_stocks_article ON stocks(article)"
   "CREATE INDEX IF NOT EXISTS idx_paid_storage_date ON paid_storage(date, marketplace)"
   "CREATE INDEX IF NOT EXISTS idx_paid_storage_barcode ON paid_storage(barcode)"
   "CREATE INDEX IF NOT EXISTS idx_finance_barcode ON finance(barcode)"
   "CREATE INDEX IF NOT EXISTS idx_finance_marketplace ON finance(marketplace)"
   "CREATE INDEX IF NOT EXISTS idx_raw_lookup ON raw_data(source, entity_type, date_from)"
   "CREATE INDEX IF NOT EXISTS idx_cashflow_lookup ON cash_flow_periods(source, period_begin)"
   "CREATE INDEX IF NOT EXISTS idx_kpi_mp_captured ON accuracy_kpi_measurements(marketplace, captured_at)"

   ;; sync_tasks — V4 Sync Task Registry (Phase 1)
   "CREATE TABLE IF NOT EXISTS sync_tasks (
      id            TEXT PRIMARY KEY,
      run_id        TEXT NOT NULL,
      marketplace   TEXT NOT NULL,
      entity_type   TEXT NOT NULL,
      phase         TEXT NOT NULL,
      chunk         TEXT,
      status        TEXT NOT NULL,
      attempts      INTEGER NOT NULL DEFAULT 0,
      max_attempts  INTEGER NOT NULL DEFAULT 1,
      items         INTEGER,
      error_msg     TEXT,
      error_kind    TEXT,
      started_at    TEXT,
      finished_at   TEXT,
      duration_ms   INTEGER,
      period_from   TEXT,
      period_to     TEXT,
      parent_id     TEXT,
      depends_on    TEXT
    )"
   "CREATE INDEX IF NOT EXISTS idx_sync_tasks_run_id ON sync_tasks (run_id)"
   "CREATE INDEX IF NOT EXISTS idx_sync_tasks_status ON sync_tasks (status)"
   "CREATE INDEX IF NOT EXISTS idx_sync_tasks_finished_at ON sync_tasks (finished_at)"

   ;; sync_schedule — V4 Phase 9: daily auto-refresh scheduler (singleton row)
   "CREATE TABLE IF NOT EXISTS sync_schedule (
      id              INTEGER PRIMARY KEY CHECK (id = 1),
      enabled         INTEGER NOT NULL DEFAULT 0,
      hour            INTEGER NOT NULL DEFAULT 6,
      minute          INTEGER NOT NULL DEFAULT 0,
      what            TEXT NOT NULL DEFAULT 'all',
      marketplace     TEXT NOT NULL DEFAULT 'all',
      period          TEXT NOT NULL DEFAULT 'last-7-days',
      last_run_at     TEXT,
      last_run_id     TEXT,
      next_run_at     TEXT,
      created_at      TEXT NOT NULL,
      updated_at      TEXT NOT NULL
    )"

   "CREATE TABLE IF NOT EXISTS monthly_plans (
      period_month TEXT NOT NULL,
      marketplace  TEXT NOT NULL,
      metric       TEXT NOT NULL,
      target_value REAL NOT NULL,
      updated_at   TEXT NOT NULL DEFAULT (datetime('now')),
      PRIMARY KEY (period_month, marketplace, metric)
    )"
   "CREATE INDEX IF NOT EXISTS idx_monthly_plans_period
      ON monthly_plans(period_month)"])

;; ---------------------------------------------------------------------------
;; Init
;; ---------------------------------------------------------------------------

(defn init!
  "Initialize SQLite database and run migrations."
  []
  (let [ds (jdbc/get-datasource db-spec)]
    (reset! datasource ds)
    ;; Enable WAL mode for better concurrent read performance
    (jdbc/execute! ds ["PRAGMA journal_mode=WAL"])
    (doseq [ddl ddl-statements]
      (jdbc/execute! ds [ddl]))
    ;; Normalize operation names (one-time migration for existing WB data)
    (jdbc/execute! ds ["UPDATE finance SET operation = 'sale' WHERE operation = 'Продажа'"])
    (jdbc/execute! ds ["UPDATE finance SET operation = 'return' WHERE operation = 'Возврат'"])
    ;; Migrate product_stats: fix PRIMARY KEY from (nm_id, date_from) to (article, date_from, marketplace)
    (let [cols (map #(get % :name)
                    (jdbc/execute! ds ["PRAGMA table_info(product_stats)"]
                                   {:builder-fn rs/as-unqualified-maps}))]
      (when-not (some #{"marketplace"} cols)
        (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS product_stats_new (
                              nm_id         INTEGER,
                              article       TEXT NOT NULL,
                              date_from     TEXT NOT NULL,
                              date_to       TEXT NOT NULL,
                              views         INTEGER,
                              add_to_cart   INTEGER,
                              orders        INTEGER,
                              orders_sum    REAL,
                              buyouts       INTEGER,
                              buyouts_sum   REAL,
                              cancel_count  INTEGER,
                              cancel_sum    REAL,
                              marketplace   TEXT NOT NULL DEFAULT 'wb',
                              synced_at     TEXT,
                              PRIMARY KEY (article, date_from, marketplace)
                            )"])
        (jdbc/execute! ds ["INSERT INTO product_stats_new
                              SELECT nm_id, article, date_from, date_to,
                                     views, add_to_cart, orders, orders_sum,
                                     buyouts, buyouts_sum, cancel_count, cancel_sum,
                                     'wb', synced_at
                              FROM product_stats
                              WHERE article IS NOT NULL"])
        (jdbc/execute! ds ["DROP TABLE product_stats"])
        (jdbc/execute! ds ["ALTER TABLE product_stats_new RENAME TO product_stats"])
        (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_product_stats_marketplace ON product_stats(marketplace)"])
        (println "Migrated product_stats: new PRIMARY KEY (article, date_from, marketplace)")))
    ;; Migrate prices: fix PRIMARY KEY from nm_id to (article, marketplace)
    (let [cols (map #(get % :name)
                    (jdbc/execute! ds ["PRAGMA table_info(prices)"]
                                   {:builder-fn rs/as-unqualified-maps}))]
      (when-not (some #{"marketplace"} cols)
        (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS prices_new (
                              nm_id         INTEGER,
                              article       TEXT NOT NULL,
                              price         REAL,
                              discount      INTEGER,
                              club_discount INTEGER,
                              marketplace   TEXT NOT NULL DEFAULT 'wb',
                              synced_at     TEXT,
                              PRIMARY KEY (article, marketplace)
                            )"])
        (jdbc/execute! ds ["INSERT INTO prices_new
                              SELECT nm_id, article, price, discount, club_discount,
                                     'wb', synced_at
                              FROM prices
                              WHERE article IS NOT NULL"])
        (jdbc/execute! ds ["DROP TABLE prices"])
        (jdbc/execute! ds ["ALTER TABLE prices_new RENAME TO prices"])
        (println "Migrated prices: new PRIMARY KEY (article, marketplace)")))
    ;; Migrate finance: old PRIMARY KEY (rrd_id) let WB and Ozon rows collide
    ;; when they happened to produce the same rrd_id. Move to composite
    ;; (marketplace, rrd_id) so INSERT OR REPLACE in insert-batch! is scoped
    ;; per-marketplace and re-materialize stays idempotent.
    (let [info    (jdbc/execute! ds ["PRAGMA table_info(finance)"]
                                 {:builder-fn rs/as-unqualified-maps})
          rrd-row (first (filter #(= "rrd_id" (:name %)) info))
          mp-row  (first (filter #(= "marketplace" (:name %)) info))]
      (when (and (pos? (or (:pk rrd-row) 0))
                 (zero? (or (:pk mp-row) 0)))
        (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS finance_new (
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
                              operation_kind     TEXT,
                              operation_subtype  TEXT,
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
        (jdbc/execute! ds ["INSERT OR IGNORE INTO finance_new
                              SELECT rrd_id, report_id, date_from, date_to,
                                     article, nm_id, barcode, subject, brand,
                                     operation, doc_type, quantity,
                                     retail_price, retail_amount, sale_percent,
                                     commission_pct, mp_commission, wb_reward,
                                     wb_kvw_prc, spp_prc, price_with_disc,
                                     delivery_amount, return_amount, delivery_cost,
                                     for_pay, penalty, storage_fee, acceptance,
                                     additional_payment, deduction, acquiring_fee,
                                     COALESCE(marketplace, 'wb'),
                                     synced_at
                              FROM finance
                              WHERE rrd_id IS NOT NULL"])
        (jdbc/execute! ds ["DROP TABLE finance"])
        (jdbc/execute! ds ["ALTER TABLE finance_new RENAME TO finance"])
        (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_finance_article ON finance(article)"])
        (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_finance_report ON finance(report_id)"])
        (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_finance_barcode ON finance(barcode)"])
        (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_finance_marketplace ON finance(marketplace)"])
        (println "Migrated finance: new PRIMARY KEY (marketplace, rrd_id)")))
    ;; Migration (003-finance-row-completeness US0): add finance.ad_cost column
    ;; if absent. YM `item.bidFee` and future Ozon promotion services populate
    ;; this field; WB keeps it nil in MVP. Idempotent via PRAGMA check so
    ;; init! can run on every app start without raising "duplicate column".
    (let [info         (jdbc/execute! ds ["PRAGMA table_info(finance)"]
                                      {:builder-fn rs/as-unqualified-maps})
          has-ad-cost? (some #(= "ad_cost" (:name %)) info)]
      (when-not has-ad-cost?
        (jdbc/execute! ds ["ALTER TABLE finance ADD COLUMN ad_cost REAL DEFAULT 0"])
        (println "Migration: finance.ad_cost column added")))
    ;; Migration (RFC-6, 2026-04-28): rename finance.wb_commission →
    ;; finance.mp_commission. The field is cross-MP (Ozon and YM also use
    ;; it for MP commission RUB) — the WB-prefixed legacy name was misleading.
    ;; SQLite ≥ 3.25 supports ALTER TABLE ... RENAME COLUMN. Idempotent: only
    ;; runs when the old column is still present.
    (let [info         (jdbc/execute! ds ["PRAGMA table_info(finance)"]
                                      {:builder-fn rs/as-unqualified-maps})
          has-old?     (some #(= "wb_commission" (:name %)) info)
          has-new?     (some #(= "mp_commission" (:name %)) info)]
      (cond
        (and has-old? (not has-new?))
        (do (jdbc/execute! ds ["ALTER TABLE finance RENAME COLUMN wb_commission TO mp_commission"])
            (println "Migration: finance.wb_commission → mp_commission (RFC-6)"))
        (and has-old? has-new?)
        (println "Migration: both wb_commission and mp_commission exist — manual cleanup required")))
    ;; Migration (E-1, 2026-04-28): persist :operation-kind / :operation-subtype
    ;; introduced by RFC-3. Phase B added the fields to the Malli schema and
    ;; transform output, but they had no DB columns — so they were silently
    ;; dropped by the sync layer. Phase D audit caught this when the
    ;; :ozon-finance-vs-cashflow rule's `SELECT operation_kind ...` failed.
    ;; Idempotent via PRAGMA check.
    (let [info       (jdbc/execute! ds ["PRAGMA table_info(finance)"]
                                    {:builder-fn rs/as-unqualified-maps})
          has-kind?  (some #(= "operation_kind" (:name %)) info)
          has-subt?  (some #(= "operation_subtype" (:name %)) info)]
      (when-not has-kind?
        (jdbc/execute! ds ["ALTER TABLE finance ADD COLUMN operation_kind TEXT"])
        (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_finance_operation_kind
                            ON finance(marketplace, operation_kind)"])
        (println "Migration: finance.operation_kind column + index added (E-1)"))
      (when-not has-subt?
        (jdbc/execute! ds ["ALTER TABLE finance ADD COLUMN operation_subtype TEXT"])
        (println "Migration: finance.operation_subtype column added (E-1)")))
    ;; Migrate finance.event_date: per-event date extracted from raw at
    ;; transform time. Replaces overlap-on-date_from/date_to queries with
    ;; precise event-level filtering. Legacy rows have NULL event_date
    ;; until re-materialized; domain queries fall back to overlap when
    ;; event_date IS NULL to stay compatible during the rollout window.
    (let [info          (jdbc/execute! ds ["PRAGMA table_info(finance)"]
                                       {:builder-fn rs/as-unqualified-maps})
          has-event?    (some #(= "event_date" (:name %)) info)]
      (when-not has-event?
        (jdbc/execute! ds ["ALTER TABLE finance ADD COLUMN event_date TEXT"])
        (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_finance_event_date
                            ON finance(marketplace, event_date)"])
        (println "Migration: finance.event_date column + index added")))
    ;; D1 (2026-05-04): event_date_source distinguishes raw API event_date
    ;; from one synthesised by ozon-distribute write-time spread. 'api' for
    ;; values that came from the raw payload (or trivially from a daily
    ;; transaction-list row); 'spread' for daily children produced by
    ;; redistributing a month-stamped Ozon realization row. Used to (a)
    ;; skip already-spread rows on respread, (b) audit how a row's date
    ;; was derived.
    (let [info        (jdbc/execute! ds ["PRAGMA table_info(finance)"]
                                     {:builder-fn rs/as-unqualified-maps})
          has-source? (some #(= "event_date_source" (:name %)) info)]
      (when-not has-source?
        (jdbc/execute! ds ["ALTER TABLE finance ADD COLUMN event_date_source
                            TEXT NOT NULL DEFAULT 'api'"])
        (println "Migration: finance.event_date_source column added")))
    ;; Phase 4 (2026-05-05): split Ozon delivery_cost into separate cost
    ;; columns so finance rows align row-by-row with LK Накопления columns
    ;; «Логистика» / «Обратная логистика» / «Обработка отправления».
    ;;   return_logistics ← ReturnFlowLogistic + RedistributionReturnsPVZ
    ;;   dropoff_cost     ← DropoffSC + DropoffPVZ + RedistributionDropOffApvz
    ;; delivery_cost stays for forward delivery (DirectFlow + LastMile + …).
    (let [info        (jdbc/execute! ds ["PRAGMA table_info(finance)"]
                                     {:builder-fn rs/as-unqualified-maps})
          has-rl?     (some #(= "return_logistics" (:name %)) info)
          has-dc?     (some #(= "dropoff_cost"     (:name %)) info)]
      (when-not has-rl?
        (jdbc/execute! ds ["ALTER TABLE finance ADD COLUMN return_logistics REAL DEFAULT 0"])
        (println "Migration: finance.return_logistics column added (Phase 4)"))
      (when-not has-dc?
        (jdbc/execute! ds ["ALTER TABLE finance ADD COLUMN dropoff_cost REAL DEFAULT 0"])
        (println "Migration: finance.dropoff_cost column added (Phase 4)")))
    ;; Migrate cost_prices.characteristic: 1C characteristic string (size,
    ;; composition, etc.) retained per-barcode so future lookup fallbacks
    ;; can disambiguate variants. Empty for legacy rows; new imports fill
    ;; it via costsource ingest.
    (let [info         (jdbc/execute! ds ["PRAGMA table_info(cost_prices)"]
                                      {:builder-fn rs/as-unqualified-maps})
          has-char?    (some #(= "characteristic" (:name %)) info)]
      (when-not has-char?
        (jdbc/execute! ds ["ALTER TABLE cost_prices ADD COLUMN characteristic TEXT"])
        (println "Migration: cost_prices.characteristic column added")))
    ;; Migrate region_sales: old schema had no PK → every rerun duplicated rows.
    ;; Add composite PK so INSERT OR REPLACE folds reruns.
    (let [info    (jdbc/execute! ds ["PRAGMA table_info(region_sales)"]
                                 {:builder-fn rs/as-unqualified-maps})
          has-pk? (some #(pos? (or (:pk %) 0)) info)]
      (when-not has-pk?
        (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS region_sales_new (
                              nm_id         INTEGER NOT NULL DEFAULT 0,
                              article       TEXT NOT NULL DEFAULT '',
                              region        TEXT NOT NULL DEFAULT '',
                              city          TEXT NOT NULL DEFAULT '',
                              country       TEXT,
                              fo            TEXT,
                              qty           INTEGER,
                              sum_price     REAL,
                              sum_price_prc REAL,
                              date_from     TEXT NOT NULL DEFAULT '',
                              date_to       TEXT NOT NULL DEFAULT '',
                              synced_at     TEXT,
                              PRIMARY KEY (date_from, date_to, nm_id, region, city)
                            )"])
        (jdbc/execute! ds ["INSERT OR IGNORE INTO region_sales_new
                              SELECT COALESCE(nm_id, 0),
                                     COALESCE(article, ''),
                                     COALESCE(region, ''),
                                     COALESCE(city, ''),
                                     country, fo, qty, sum_price, sum_price_prc,
                                     COALESCE(date_from, ''),
                                     COALESCE(date_to, ''),
                                     synced_at
                              FROM region_sales"])
        (jdbc/execute! ds ["DROP TABLE region_sales"])
        (jdbc/execute! ds ["ALTER TABLE region_sales_new RENAME TO region_sales"])
        (println "Migrated region_sales: added PRIMARY KEY (date_from, date_to, nm_id, region, city)")))
    ;; Migrate ad_stats: widen PK from (campaign_id, date) to
    ;; (campaign_id, date, nm_id) so multi-article campaigns can store
    ;; per-nm_id daily rows. See spec 003 US5 — required for accurate
    ;; :ad-cost attribution. Idempotent: the migration is a no-op if
    ;; nm_id is already part of the PK.
    (let [info      (jdbc/execute! ds ["PRAGMA table_info(ad_stats)"]
                                   {:builder-fn rs/as-unqualified-maps})
          nm-id-row (first (filter #(= "nm_id" (:name %)) info))
          needs?    (and (seq info)
                         (zero? (or (:pk nm-id-row) 0)))]
      (when needs?
        (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS ad_stats_new (
                              campaign_id   INTEGER NOT NULL,
                              date          TEXT NOT NULL,
                              views         INTEGER,
                              clicks        INTEGER,
                              ctr           REAL,
                              cpc           REAL,
                              spend         REAL,
                              atbs          INTEGER,
                              orders        INTEGER,
                              cr            REAL,
                              shks          INTEGER,
                              sum_price     REAL,
                              nm_id         INTEGER NOT NULL DEFAULT 0,
                              synced_at     TEXT,
                              PRIMARY KEY (campaign_id, date, nm_id)
                            )"])
        (jdbc/execute! ds ["INSERT OR IGNORE INTO ad_stats_new
                              SELECT campaign_id, date,
                                     views, clicks, ctr, cpc, spend,
                                     atbs, orders, cr, shks, sum_price,
                                     COALESCE(nm_id, 0),
                                     synced_at
                              FROM ad_stats"])
        (jdbc/execute! ds ["DROP TABLE ad_stats"])
        (jdbc/execute! ds ["ALTER TABLE ad_stats_new RENAME TO ad_stats"])
        (println "Migrated ad_stats: new PRIMARY KEY (campaign_id, date, nm_id)")))
    ;; Ensure index exists (for both migrated and fresh DBs)
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_product_stats_marketplace ON product_stats(marketplace)"])
    ;; Seed the sync_schedule singleton row if not yet present.
    ;; INSERT OR IGNORE is idempotent — safe to run on every startup.
    (let [now-str (.format (java.time.LocalDateTime/now)
                           (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))]
      (jdbc/execute! ds
                     ["INSERT OR IGNORE INTO sync_schedule (id, created_at, updated_at)
                       VALUES (1, ?, ?)"
                      now-str now-str]))
    (println "SQLite database initialized: analitica.db (WAL mode enabled)")
    ds))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn execute! [sql-params]
  (jdbc/execute! (ds) sql-params))

(defn query
  "Execute a query and return results as unqualified maps."
  [sql-params]
  (jdbc/execute! (ds) sql-params
                 {:builder-fn rs/as-unqualified-kebab-maps}))

(defn orders-by-article
  "Group rows of `orders` for a date range by `article`. Returns a vector of
   maps `{:article :placed :cancelled}` where `:placed` is the total order
   count and `:cancelled` is the count of orders whose status canonicalizes
   to `:cancelled` (per `domain.order-status/canonicalize`). Marketplace
   filter is applied in SQL.

   Powering metric: `:true-buyout-rate = sales.sold / orders.placed` —
   counts cancellations that never reach the `sales` table."
  [from to & {:keys [marketplace]}]
  (let [mp-clause (when marketplace " AND marketplace = ?")
        params    (cond-> [from (str to "T23:59:59")]
                    marketplace (conj (name marketplace)))
        rows      (query (into [(str "SELECT article, status, COUNT(*) AS n
                                      FROM orders
                                      WHERE article IS NOT NULL AND article != ''
                                        AND date >= ? AND date <= ?" mp-clause "
                                      GROUP BY article, status")]
                               params))
        canonicalize (requiring-resolve 'analitica.domain.order-status/canonicalize)]
    (->> rows
         (group-by :article)
         (mapv (fn [[art rs]]
                 (reduce (fn [acc r]
                           (let [n (or (:n r) 0)]
                             (cond-> (update acc :placed + n)
                               (= :cancelled (canonicalize (:status r)))
                               (update :cancelled + n))))
                         {:article art :placed 0 :cancelled 0}
                         rs))))))

(defn storage-by-article
  "Sum paid storage costs by article for period.
   Maps barcode→article via finance table, falls back to paid_storage.article."
  [from to & {:keys [marketplace]}]
  (let [mp-clause (when marketplace " AND ps.marketplace = ?")
        params    (cond-> [from to] marketplace (conj (name marketplace)))]
    (query (into [(str "SELECT COALESCE(fm.article, ps.article) AS article,
                         SUM(ps.cost) AS storage_cost
                  FROM paid_storage ps
                  LEFT JOIN (SELECT DISTINCT barcode, article FROM finance
                             WHERE barcode IS NOT NULL AND barcode != ''
                               AND article IS NOT NULL AND article != '') fm
                    ON ps.barcode = fm.barcode
                  WHERE ps.date >= ? AND ps.date <= ?" mp-clause
                        " GROUP BY COALESCE(fm.article, ps.article)")]
                 params))))

(defn ad-spend-by-article
  "Sum ad spend by article for period. Prefers the canonical
   `finance.ad_cost` path (populated by Ozon / YM ingest and — since
   spec 003 US5 — by WB materialize too); falls back to the legacy
   `ad_stats` JOIN when canonical returns empty (pre-migration WB
   periods).

   Date filter uses per-event `event_date` when populated (post
   2026-04-23 migration), falling back to weekly-report overlap for
   legacy rows. `:marketplace` scopes both paths."
  [from to & {:keys [marketplace]}]
  (let [mp-clause-f  (when marketplace " AND marketplace = ?")
        canonical-sql (str "SELECT article, SUM(ad_cost) AS ad_spend
                            FROM finance
                            WHERE ad_cost IS NOT NULL AND ad_cost > 0
                              AND article IS NOT NULL AND article != ''
                              AND ((event_date IS NOT NULL AND event_date BETWEEN ? AND ?)
                                   OR (event_date IS NULL AND date_from <= ? AND date_to >= ?))"
                           mp-clause-f
                           " GROUP BY article")
        canonical-params (cond-> [from to to from] marketplace (conj (name marketplace)))
        canonical    (query (into [canonical-sql] canonical-params))]
    (if (seq canonical)
      canonical
      ;; Legacy fallback — ad_stats JOIN on nm_id. Kept for pre-spec-003
      ;; WB periods where ad_cost is 0 on finance rows.
      (let [mp-clause-a (when marketplace " AND f.marketplace = ?")
            params     (cond-> [from to] marketplace (conj (name marketplace)))]
        (query (into [(str "SELECT f.article, SUM(a.spend) AS ad_spend
                            FROM ad_stats a
                            JOIN (SELECT DISTINCT nm_id, article, marketplace FROM finance
                                  WHERE article IS NOT NULL AND article != '') f
                              ON a.nm_id = f.nm_id
                            WHERE a.date >= ? AND a.date <= ?" mp-clause-a
                            " GROUP BY f.article")]
                     params))))))

(defn insert-batch!
  "Insert multiple rows using multi-row INSERT OR REPLACE.
   Chunks rows to stay within SQLite's 999-parameter limit."
  [table columns rows]
  (when (seq rows)
    (let [col-count  (count columns)
          col-names  (clojure.string/join "," (map name columns))
          row-ph     (str "(" (clojure.string/join "," (repeat col-count "?")) ")")
          chunk-size (max 1 (quot 999 col-count))]
      (jdbc/with-transaction [tx (ds)]
        (doseq [chunk (partition-all chunk-size rows)]
          (let [sql    (str "INSERT OR REPLACE INTO " (name table)
                            " (" col-names ") VALUES "
                            (clojure.string/join "," (repeat (count chunk) row-ph)))
                params (into [sql] (mapcat identity chunk))]
            (jdbc/execute! tx params))))
      (count rows))))

;; ---------------------------------------------------------------------------
;; Raw data helpers
;; ---------------------------------------------------------------------------

(def ^:private raw-json-mapper (j/object-mapper {:decode-key-fn true}))

(defn serialize-json [data]
  (j/write-value-as-string data))

(defn parse-json [s]
  (j/read-value s raw-json-mapper))

(defn insert-raw!
  "Save raw API data to raw_data table. Data is serialized to JSON.
   Replaces existing entry for the same (source, entity_type, date_from, date_to)."
  [source entity-type date-from date-to data]
  (let [json    (serialize-json data)
        cnt     (if (sequential? data) (count data) 1)
        now     (.format (java.time.LocalDateTime/now)
                         (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))]
    (execute! ["INSERT OR REPLACE INTO raw_data (source, entity_type, date_from, date_to, payload, item_count, ingested_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)"
               (name source) (name entity-type) date-from date-to json cnt now])
    cnt))

(defn get-raw
  "Get raw data for exact (source, entity_type, date_from, date_to) match.
   Returns parsed Clojure data."
  [source entity-type date-from date-to]
  (when-let [row (first (query ["SELECT payload FROM raw_data
                                 WHERE source = ? AND entity_type = ? AND date_from = ? AND date_to = ?"
                                (name source) (name entity-type) date-from date-to]))]
    (parse-json (:payload row))))

(defn get-raw-range
  "Get all raw data rows for source/entity_type whose period overlaps [from..to].
   Returns seq of {:date-from :date-to :data} maps.

   Overlap semantics (`date_from <= to AND date_to >= from`) — not strict
   containment — so monthly batches (e.g. Ozon realization, one row per
   calendar month) still get materialized when the caller passes a
   shorter window like last-7-days. Strict containment was silently
   dropping April realization during routine syncs because the April
   batch (Apr 1..Apr 30) didn't fit the Apr 26..May 3 window."
  [source entity-type from to]
  (->> (query ["SELECT date_from, date_to, payload FROM raw_data
                WHERE source = ? AND entity_type = ?
                  AND date_from <= ? AND date_to >= ?
                ORDER BY date_from"
               (name source) (name entity-type) to from])
       (mapv (fn [row]
               {:date-from (:date-from row)
                :date-to   (:date-to row)
                :data      (parse-json (:payload row))}))))

(defn raw-status
  "Summary of raw_data: counts grouped by source and entity_type."
  []
  (query ["SELECT source, entity_type,
                  COUNT(*) AS batch_count,
                  SUM(item_count) AS total_items,
                  MIN(date_from) AS min_date,
                  MAX(date_to) AS max_date
           FROM raw_data
           GROUP BY source, entity_type
           ORDER BY source, entity_type"]))

;; ---------------------------------------------------------------------------
;; Table stats
;; ---------------------------------------------------------------------------

(defn count-rows [table]
  (-> (query [(str "SELECT count(*) as cnt FROM " (name table))])
      first :cnt))

(defn clear-table! [table]
  (let [db-name (:dbname db-spec)]
    (when (= db-name "analitica.db")
      (throw (ex-info "Cannot clear production database! Use test database."
                      {:database db-name
                       :table table}))))
  (execute! [(str "DELETE FROM " (name table))]))

(defn clear-marketplace-rows! [table marketplace-key]
  (execute! [(str "DELETE FROM " (name table) " WHERE marketplace = ?")
             (name marketplace-key)]))

(defn ozon-sku-map
  "Load Ozon {sku → offer_id} mapping from DB."
  []
  (into {} (map (juxt :sku :offer-id)
                (query ["SELECT sku, offer_id FROM ozon_sku_map"]))))

(def ^:private cash-flow-adjustment-keys
  [:subscription :warehouse-movement :returns-cargo :fines
   :corrections :compensation :other-services :packaging])

(defn cash-flow-adjustments
  "Sum cash flow period costs for the given source and date range.
   Cash-flow rows are weekly buckets that rarely align with arbitrary
   UI windows (e.g. ISO weeks vs Mon-Sun reporting weeks). Each
   overlapping row is pro-rated by day-overlap before summing so that
   Σ(weekly slices) reconciles with the full period.

   Returns a map with summed values (signs preserved: costs are negative)."
  [source from to]
  (let [rows (query ["SELECT period_begin, period_end,
                             subscription, warehouse_movement, returns_cargo,
                             fines, corrections, compensation,
                             other_services, packaging
                      FROM cash_flow_periods
                      WHERE source = ?
                        AND period_begin <= ? AND period_end >= ?"
                     source to from])
        prorated (period/pro-rate-rows
                   rows
                   {:from from :to to
                    :numeric-keys cash-flow-adjustment-keys})]
    (reduce (fn [acc k]
              (assoc acc k (reduce + 0.0 (keep k prorated))))
            {}
            cash-flow-adjustment-keys)))

(defn save-ozon-sku-map!
  "Save Ozon SKU→offer_id mappings to DB. Input: seq of [sku offer-id] pairs."
  [pairs]
  (when (seq pairs)
    (insert-batch! :ozon_sku_map [:sku :offer_id]
                   (mapv (fn [[sku offer-id]] [sku offer-id]) pairs))))
