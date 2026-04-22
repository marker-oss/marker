(ns analitica.audit.test-helpers
  "Test helpers and fixtures for isolated audit testing.
   Provides per-test DB isolation, synthetic row factories, and batch insert helpers."
  (:require [analitica.db :as db]
            [next.jdbc :as jdbc]
            [clojure.string :as str])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Auto-ID counters
;; ---------------------------------------------------------------------------

(defonce ^:private rrd-counter (atom 0))
(defonce ^:private sale-counter (atom 0))
(defonce ^:private order-counter (atom 0))

(defn reset-counters!
  "Reset all auto-ID counters to 0. Call at start of test to ensure deterministic IDs."
  []
  (reset! rrd-counter 0)
  (reset! sale-counter 0)
  (reset! order-counter 0))

;; ---------------------------------------------------------------------------
;; Isolated DB fixture
;; ---------------------------------------------------------------------------

(defn with-isolated-db
  "Test fixture providing per-test isolated SQLite DB (temp file).

   Sets up a unique temp DB file, resets datasource atom, loads schema,
   and restores original datasource in finally block.

   Usage:
     (use-fixtures :each with-isolated-db)

   or programmatically:
     (with-isolated-db (fn [] (test-code-here)))"
  [f]
  (let [;; Generate unique temp filename
        timestamp (System/currentTimeMillis)
        rand-suffix (rand-int 99999)
        temp-db-file (str "test-audit-" timestamp "-" rand-suffix ".db")

        ;; Save original datasource
        original-ds (deref @#'db/datasource)

        ;; Create temp datasource
        temp-db-spec {:dbtype "sqlite" :dbname temp-db-file}
        temp-ds (jdbc/get-datasource temp-db-spec)]

    (try
      ;; Setup: point datasource atom to temp DB
      (reset! @#'db/datasource temp-ds)

      ;; Enable WAL mode
      (jdbc/execute! temp-ds ["PRAGMA journal_mode=WAL"])

      ;; Load schema from ddl-statements
      (doseq [ddl @#'db/ddl-statements]
        (jdbc/execute! temp-ds [ddl]))

      ;; Reset counters for deterministic IDs
      (reset-counters!)

      ;; Run the test
      (f)

      (finally
        ;; Restore original datasource
        (reset! @#'db/datasource original-ds)

        ;; Delete temp DB files
        (doseq [filename [temp-db-file
                         (str temp-db-file "-shm")
                         (str temp-db-file "-wal")]]
          (let [file (File. filename)]
            (when (.exists file)
              (.delete file))))))))

;; ---------------------------------------------------------------------------
;; Row factories (kebab-case keys, sensible defaults)
;; ---------------------------------------------------------------------------

(defn finance-row
  "Factory for synthetic finance row. Returns kebab-case map.

   Defaults mimic a real WB sale.
   Usage:
     (finance-row)
     (finance-row :article \"ART-002\" :quantity 5)"
  [& {:as overrides}]
  (merge
    {:rrd-id          (swap! rrd-counter inc)
     :report-id       1
     :date-from       "2026-03-01"
     :date-to         "2026-03-07"
     :article         "ART-001"
     :nm-id           100001
     :barcode         "1000000000001"
     :subject         "Электроника"
     :brand           "TestBrand"
     :operation       "sale"
     :doc-type        nil
     :quantity        1
     :retail-price    1000.0
     :retail-amount   1000.0
     :sale-percent    0.0
     :commission-pct  12.0
     :wb-commission   120.0
     :wb-reward       0.0
     :wb-kvw-prc      0.0
     :spp-prc         0.0
     :price-with-disc 1000.0
     :delivery-amount 0
     :return-amount   0
     :delivery-cost   30.0
     :for-pay         850.0
     :penalty         0.0
     :storage-fee     0.0
     :acceptance      0.0
     :additional-payment 0.0
     :deduction       0.0
     :acquiring-fee   0.0
     :marketplace     "wb"
     :synced-at       nil}
    overrides))

(defn sales-row
  "Factory for synthetic sales row. Returns kebab-case map.

   Defaults: typical WB sale.
   Usage:
     (sales-row)
     (sales-row :article \"ART-002\" :total-price 2000.0)"
  [& {:as overrides}]
  (merge
    {:sale-id (str "SALE-" (swap! sale-counter inc))
     :date "2026-03-01"
     :article "ART-001"
     :nm-id 100001
     :barcode "1000000000001"
     :tech-size nil
     :subject "Электроника"
     :category nil
     :brand "TestBrand"
     :warehouse nil
     :region nil
     :type "S"
     :total-price 1000.0
     :for-pay 850.0
     :finished-price 1000.0
     :price-with-disc 1000.0
     :marketplace "wb"
     :synced-at nil}
    overrides))

(defn orders-row
  "Factory for synthetic orders row. Returns kebab-case map.

   Defaults: typical WB order.
   Usage:
     (orders-row)
     (orders-row :article \"ART-002\" :price 2500.0)"
  [& {:as overrides}]
  (merge
    {:order-id (str "ORD-" (swap! order-counter inc))
     :date "2026-03-01"
     :article "ART-001"
     :nm-id 100001
     :barcode "1000000000001"
     :tech-size nil
     :subject "Электроника"
     :category nil
     :brand "TestBrand"
     :warehouse nil
     :region nil
     :status "waiting"
     :price 1000.0
     :price-with-disc 1000.0
     :marketplace "wb"
     :synced-at nil}
    overrides))

;; ---------------------------------------------------------------------------
;; Insert helpers (kebab→snake conversion + batch insert)
;; ---------------------------------------------------------------------------

(defn- kebab->snake
  "Convert kebab-case keyword to snake_case keyword."
  [kw]
  (keyword (str/replace (name kw) "-" "_")))

(defn- snake->kebab
  "Convert snake_case keyword to kebab-case keyword."
  [kw]
  (keyword (str/replace (name kw) "_" "-")))

(defn- insert-rows!
  "Convert kebab-case row maps to snake_case columns and batch-insert into `table`."
  [table rows]
  (when (seq rows)
    (let [cols     (mapv kebab->snake (keys (first rows)))
          row-vecs (mapv (fn [row]
                           (mapv #(get row (snake->kebab %)) cols))
                         rows)]
      (db/insert-batch! table cols row-vecs))))

(defn insert-finance! "Insert finance rows into DB." [rows] (insert-rows! :finance rows))
(defn insert-sales! "Insert sales rows into DB." [rows] (insert-rows! :sales rows))
(defn insert-orders! "Insert orders rows into DB." [rows] (insert-rows! :orders rows))

(defn insert-raw-finance!
  "Insert raw finance data into raw_data table.

   Args:
     source - keyword, e.g. :wb
     date-from - string date, e.g. \"2026-03-01\"
     date-to - string date
     rows - collection of row-maps (kebab-case)"
  [source date-from date-to rows]
  (when (seq rows)
    ;; Convert kebab→snake for the payload
    (let [snake-rows (mapv (fn [row]
                             (into {} (mapv (fn [[k v]]
                                              [(kebab->snake k) v])
                                           row)))
                           rows)]
      (db/insert-raw! source :finance date-from date-to snake-rows))))
