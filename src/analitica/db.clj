(ns analitica.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defonce ^:private datasource (atom nil))

(def ^:private db-spec
  {:dbtype "sqlite"
   :dbname "analitica.db"})

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
      rrd_id             INTEGER PRIMARY KEY,
      report_id          INTEGER,
      date_from          TEXT,
      date_to            TEXT,
      article            TEXT,
      nm_id              INTEGER,
      barcode            TEXT,
      subject            TEXT,
      brand              TEXT,
      operation          TEXT,
      quantity           INTEGER,
      retail_amount      REAL,
      delivery_cost      REAL,
      for_pay            REAL,
      penalty            REAL,
      storage_fee        REAL,
      acceptance         REAL,
      additional_payment REAL,
      commission_pct     REAL,
      price_with_disc    REAL,
      marketplace        TEXT DEFAULT 'wb',
      synced_at          TEXT
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

   "CREATE TABLE IF NOT EXISTS cost_prices (
      article       TEXT NOT NULL,
      barcode       TEXT NOT NULL DEFAULT '',
      cost_price    REAL NOT NULL,
      nomenclature  TEXT,
      color         TEXT,
      quantity_1c   REAL,
      updated_at    TEXT,
      PRIMARY KEY (article, barcode)
    )"

   ;; Indexes
   "CREATE INDEX IF NOT EXISTS idx_sales_date ON sales(date)"
   "CREATE INDEX IF NOT EXISTS idx_sales_article ON sales(article)"
   "CREATE INDEX IF NOT EXISTS idx_orders_date ON orders(date)"
   "CREATE INDEX IF NOT EXISTS idx_finance_article ON finance(article)"
   "CREATE INDEX IF NOT EXISTS idx_finance_report ON finance(report_id)"
   "CREATE INDEX IF NOT EXISTS idx_stocks_article ON stocks(article)"])

;; ---------------------------------------------------------------------------
;; Init
;; ---------------------------------------------------------------------------

(defn init!
  "Initialize SQLite database and run migrations."
  []
  (let [ds (jdbc/get-datasource db-spec)]
    (reset! datasource ds)
    (doseq [ddl ddl-statements]
      (jdbc/execute! ds [ddl]))
    (println "SQLite database initialized: analitica.db")
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

(defn insert-batch!
  "Insert multiple rows. Uses INSERT OR REPLACE for upsert semantics."
  [table columns rows]
  (when (seq rows)
    (let [placeholders (str "(" (clojure.string/join "," (repeat (count columns) "?")) ")")
          col-names    (clojure.string/join "," (map name columns))
          sql          (str "INSERT OR REPLACE INTO " (name table)
                           " (" col-names ") VALUES " placeholders)]
      (jdbc/with-transaction [tx (ds)]
        (doseq [row rows]
          (jdbc/execute! tx (into [sql] row))))
      (count rows))))

(defn count-rows [table]
  (-> (query [(str "SELECT count(*) as cnt FROM " (name table))])
      first :cnt))

(defn clear-table! [table]
  (execute! [(str "DELETE FROM " (name table))]))
