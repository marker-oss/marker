(ns analitica.test-helpers
  "Test helpers and fixtures for database isolation.
   Ensures tests use a separate test database instead of production database."
  (:require [analitica.db :as db]
            [next.jdbc :as jdbc])
  (:import [java.io File]))

(defn- set-test-db-env!
  "Set ANALITICA_DB environment variable to test database.
   Uses reflection to modify the environment map at runtime."
  []
  (try
    (let [env-class (Class/forName "java.lang.ProcessEnvironment")
          env-field (.getDeclaredField env-class "theEnvironment")]
      (.setAccessible env-field true)
      (let [env-map (.get env-field nil)]
        (.put env-map "ANALITICA_DB" "test-analitica.db")))
    (catch Exception e
      (println "Warning: Could not set ANALITICA_DB via reflection:" (.getMessage e))
      ;; Fallback: rely on external environment variable
      nil)))

(defn- clear-test-db-env!
  "Clear ANALITICA_DB environment variable."
  []
  (try
    (let [env-class (Class/forName "java.lang.ProcessEnvironment")
          env-field (.getDeclaredField env-class "theEnvironment")]
      (.setAccessible env-field true)
      (let [env-map (.get env-field nil)]
        (.remove env-map "ANALITICA_DB")))
    (catch Exception e
      (println "Warning: Could not clear ANALITICA_DB via reflection:" (.getMessage e))
      nil)))

(defn- init-test-db!
  "Initialize test database with proper db-spec.
   This creates a new datasource with the test database name."
  []
  (let [test-db-spec {:dbtype "sqlite"
                      :dbname "test-analitica.db"}
        ds (jdbc/get-datasource test-db-spec)]
    ;; Reset the datasource atom to use test database
    (reset! @#'db/datasource ds)
    ;; Enable WAL mode
    (jdbc/execute! ds ["PRAGMA journal_mode=WAL"])
    ;; Run DDL statements to create tables
    (doseq [ddl @#'db/ddl-statements]
      (jdbc/execute! ds [ddl]))
    (println "Test database initialized: test-analitica.db")))

(defn- delete-test-db-files!
  "Delete test database files (db, shm, wal)."
  []
  (doseq [filename ["test-analitica.db" 
                    "test-analitica.db-shm" 
                    "test-analitica.db-wal"]]
    (let [file (File. filename)]
      (when (.exists file)
        (.delete file)))))

(defn- clear-all-tables!
  "Clear all tables in the test database."
  []
  (doseq [table [:sales :orders :finance :paid_storage :stocks
                 :product_stats :region_sales :cost_prices :prices :ad_stats
                 :monthly_plans]]
    (try
      (db/clear-table! table)
      (catch Exception e
        ;; Ignore errors if table doesn't exist yet
        nil))))

(defn with-test-db
  "Test fixture that ensures database isolation.
   
   Sets up a test database before tests run and cleans up after.
   
   Usage:
     (use-fixtures :once with-test-db)
   
   This fixture:
   1. Sets ANALITICA_DB environment variable to 'test-analitica.db'
   2. Initializes the test database with proper schema
   3. Clears all tables to ensure clean state
   4. Runs the tests
   5. Cleans up test database files after tests complete"
  [f]
  (try
    ;; Setup: Set environment variable and initialize test database
    (set-test-db-env!)
    (init-test-db!)
    
    ;; Clear all tables to ensure clean state
    (clear-all-tables!)
    
    ;; Run the tests
    (f)
    
    (finally
      ;; Cleanup: Remove test database files and clear environment variable
      (delete-test-db-files!)
      (clear-test-db-env!))))

(defn strip-ns
  "Remove the namespace from every key in a flat map. Used by schema-
   conformance tests to normalize `next.jdbc.result-set/as-kebab-maps`
   output — which produces table-namespaced keys like `:finance/rrd-id`
   — into unqualified kebab-case keys that Malli schemas expect."
  [m]
  (into {} (map (fn [[k v]] [(keyword (name k)) v]) m)))

(defn db-or-skip
  "Returns a datasource for the configured test DB, or nil to signal
   the schema conformance test should skip. Reads ANALITICA_TEST_DB
   first, then ANALITICA_DB."
  []
  (when-let [db-path (or (System/getenv "ANALITICA_TEST_DB")
                         (System/getenv "ANALITICA_DB"))]
    (next.jdbc/get-datasource {:dbtype "sqlite" :dbname db-path})))
