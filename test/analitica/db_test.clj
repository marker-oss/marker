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
