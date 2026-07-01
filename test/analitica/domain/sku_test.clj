(ns analitica.domain.sku-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.domain.sku :as sku]
            [analitica.domain.cost-price :as cost-price]
            [analitica.db :as db]
            [next.jdbc])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temp DB fixture
;; ---------------------------------------------------------------------------

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-sku-test-" ".db"
                                   (make-array FileAttribute 0))
        f    (.toFile path)]
    (.delete f)
    (.getAbsolutePath f)))

(defn- delete-test-db! [path]
  (doseq [suffix ["" "-shm" "-wal"]]
    (let [f (File. (str path suffix))]
      (when (.exists f) (.delete f)))))

(use-fixtures :each
  (fn [f]
    (let [path     (fresh-temp-db-path)
          orig     (deref #'db/db-spec)]
      (try
        (alter-var-root #'db/db-spec (constantly {:dbtype "sqlite" :dbname path}))
        (db/init!)
        ;; cost-price/get-price reads a GLOBAL in-memory atom, not the DB. The
        ;; fixture rebinds db-spec/datasource per test but the atom persists —
        ;; a prior test's cost-price (inserted with barcode "") would otherwise
        ;; bleed into a later test via barcode fallback, making roi-zero-cost
        ;; flaky by test order. Reset the atom to this fresh (empty) temp DB.
        (cost-price/load-from-db!)
        (f)
        (finally
          (alter-var-root #'db/db-spec (constantly orig))
          (reset! @#'db/datasource nil)
          (delete-test-db! path))))))

;; ---------------------------------------------------------------------------
;; Seed helpers
;; ---------------------------------------------------------------------------

(defn- insert-sale! [article nm-id date type for-pay marketplace]
  (next.jdbc/execute!
   (db/ds)
   [(str "INSERT INTO sales (sale_id, date, article, nm_id, type, for_pay, marketplace)"
         " VALUES (?,?,?,?,?,?,?)")
    (str (java.util.UUID/randomUUID)) date article nm-id (name type) for-pay (name marketplace)]))

(defn- insert-cost-price! [article cost]
  (next.jdbc/execute!
   (db/ds)
   ["INSERT INTO cost_prices (article, barcode, cost_price) VALUES (?,?,?)"
    article "" cost])
  ;; cost-price/get-price reads an in-memory atom, not the DB. Refresh
  ;; after every INSERT so finance/by-article sees the new price.
  (cost-price/load-from-db!))

(defn- insert-finance!
  "Seed one finance row. operation: 'sale' or 'return'. quantity may be
   fractional (Ozon spread rows). retail = retail-amount (gross), pay =
   for-pay (seller payout)."
  [{:keys [article marketplace operation quantity retail pay event-date
           rrd-id from to]
    :or   {operation  "sale"
           quantity   1
           retail     0.0
           pay        0.0
           event-date "2026-04-15"
           rrd-id     1
           from       "2026-04-01"
           to         "2026-04-30"}}]
  (next.jdbc/execute!
   (db/ds)
   [(str "INSERT INTO finance (rrd_id, marketplace, article, operation, quantity, "
         "retail_amount, for_pay, event_date, date_from, date_to) "
         "VALUES (?,?,?,?,?,?,?,?,?,?)")
    rrd-id (name marketplace) article operation quantity
    retail pay event-date from to]))

;; ---------------------------------------------------------------------------
;; B4: sku-summary now reads cogs/revenue/quantities from canonical
;; finance via finance/by-article. The previous sales-table path
;; counted rows (not units) for cogs and used for_pay (not retail) for
;; revenue — both broken for Ozon multi-unit and spread rows.
;; ---------------------------------------------------------------------------

(deftest sku-summary-uses-finance-units-not-row-count
  (testing "Ozon multi-unit row: cogs scales with quantity, not row count"
    (insert-finance! {:article "OZ-X" :marketplace :ozon
                      :quantity 5 :retail 1000.0 :pay 800.0})
    (insert-cost-price! "OZ-X" 100.0)
    (let [s (sku/sku-summary "OZ-X" "2026-04-01" "2026-04-30" :marketplace :ozon)]
      (testing ":sales-count = 5 (units), not 1 (rows)"
        (is (= 5 (:sales-count s))))
      (testing ":cogs linear: 100 × 5 = 500 (was 100 × 1)"
        (is (= 500.0 (:cogs s))))
      (testing ":revenue from finance retail-amount, not sales for_pay"
        (is (= 1000.0 (:revenue s))))
      (testing ":margin-pct = (1000-500)/1000 × 100 = 50.0"
        (is (< (Math/abs (- 50.0 (:margin-pct s))) 0.01)))
      (testing ":roi = (800-500)/500*100 = 60.0 (net-profit/cogs*100)"
        (is (< (Math/abs (- 60.0 (:roi s))) 0.01))))))

(deftest sku-summary-fractional-quantity-spread-row
  (testing "Ozon spread row qty=1/30 should not 30× cogs"
    (dotimes [i 30]
      (insert-finance! {:article "OZ-Y" :marketplace :ozon
                        :rrd-id i
                        :quantity (/ 1.0 30.0)
                        :retail (/ 1000.0 30.0)
                        :pay    (/ 800.0 30.0)
                        :event-date (str "2026-04-" (format "%02d" (inc i)))}))
    (insert-cost-price! "OZ-Y" 200.0)
    (let [s (sku/sku-summary "OZ-Y" "2026-04-01" "2026-04-30" :marketplace :ozon)]
      (testing "cogs ≈ 200 (not ~6000 from the (max 1 quantity) clamp)"
        (is (< 199.5 (:cogs s) 200.5))))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest sku-summary-empty
  (testing "unknown article returns zeros"
    (let [s (sku/sku-summary "NONEXIST" "2026-01-01" "2026-12-31")]
      (is (= 0 (:sales-count s)))
      (is (= 0 (:returns-count s)))
      (is (= 0.0 (:revenue s)))
      (is (= 0.0 (:margin-pct s)))
      (is (= 0.0 (:roi s)))
      (is (empty? (:daily-revenue s)))
      (is (empty? (:recent-ops s))))))

(deftest sku-summary-sales
  (testing "counts unit-quantities and revenue from finance"
    ;; Sales seed (used by recent-ops + sparkline + nm-id resolution)
    (insert-sale! "ART-001" 12345 "2026-04-01T10:00:00" :sale 1000.0 :wb)
    (insert-sale! "ART-001" 12345 "2026-04-02T10:00:00" :sale 1200.0 :wb)
    (insert-sale! "ART-001" 12345 "2026-04-03T10:00:00" :return 900.0 :wb)
    ;; Finance seed (canonical source of cogs / revenue / qty)
    (insert-finance! {:article "ART-001" :marketplace :wb :rrd-id 1
                      :quantity 1 :retail 1000.0 :pay 800.0
                      :event-date "2026-04-01"})
    (insert-finance! {:article "ART-001" :marketplace :wb :rrd-id 2
                      :quantity 1 :retail 1200.0 :pay 1000.0
                      :event-date "2026-04-02"})
    (insert-finance! {:article "ART-001" :marketplace :wb :rrd-id 3
                      :operation "return" :quantity 1
                      :retail 900.0 :pay 700.0
                      :event-date "2026-04-03"})
    (let [s (sku/sku-summary "ART-001" "2026-04-01" "2026-04-30")]
      (is (= 2 (:sales-count s)))
      (is (= 1 (:returns-count s)))
      (is (= 2200.0 (:revenue s)))
      (is (= 12345 (:nm-id s))))))

(deftest sku-summary-margin-roi
  (testing "margin and ROI calculated from finance"
    (insert-sale!     "ART-002" nil "2026-04-01T10:00:00" :sale 1000.0 :wb)
    (insert-finance!  {:article "ART-002" :marketplace :wb
                       :quantity 1 :retail 1000.0 :pay 800.0
                       :event-date "2026-04-01"})
    (insert-cost-price! "ART-002" 400.0)
    (let [s (sku/sku-summary "ART-002" "2026-04-01" "2026-04-30")]
      (is (= 1 (:sales-count s)))
      (is (= 400.0 (:cogs s)))
      (is (= 600.0 (- (:revenue s) (:cogs s))))
      ;; margin = (1000-400)/1000 * 100 = 60%
      (is (< (Math/abs (- 60.0 (:margin-pct s))) 0.01))
      ;; roi = (800-400)/400*100 = 100.0 (net-profit/cogs*100)
      (is (< (Math/abs (- 100.0 (:roi s))) 0.01)))))

(deftest sku-summary-recent-ops
  (testing "recent-ops at most 10 rows, newest first"
    (dotimes [i 15]
      (insert-sale! "ART-003" nil (str "2026-04-" (format "%02d" (inc i)) "T10:00:00") :sale (* (inc i) 100.0) :wb))
    (let [s (sku/sku-summary "ART-003" "2026-04-01" "2026-04-30")]
      (is (= 10 (count (:recent-ops s)))))))

(deftest sku-summary-daily-revenue
  (testing "daily-revenue has one entry per day with sales"
    (insert-sale! "ART-004" nil "2026-04-01T10:00:00" :sale 500.0 :wb)
    (insert-sale! "ART-004" nil "2026-04-01T15:00:00" :sale 300.0 :wb)
    (insert-sale! "ART-004" nil "2026-04-02T10:00:00" :sale 400.0 :wb)
    (let [s    (sku/sku-summary "ART-004" "2026-04-01" "2026-04-30")
          days (mapv :date (:daily-revenue s))]
      ;; two distinct days
      (is (= 2 (count days)))
      ;; April 1 total = 800
      (let [d1 (first (filter #(= "2026-04-01" (:date %)) (:daily-revenue s)))]
        (is (< (Math/abs (- 800.0 (:revenue d1))) 0.01))))))

(deftest sku-summary-marketplace-filter
  (testing "marketplace param filters finance correctly"
    (insert-sale!    "ART-005" nil "2026-04-01T10:00:00" :sale 1000.0 :wb)
    (insert-sale!    "ART-005" nil "2026-04-01T10:00:00" :sale 2000.0 :ozon)
    (insert-finance! {:article "ART-005" :marketplace :wb :rrd-id 1
                      :quantity 1 :retail 1000.0 :pay 800.0
                      :event-date "2026-04-01"})
    (insert-finance! {:article "ART-005" :marketplace :ozon :rrd-id 2
                      :quantity 1 :retail 2000.0 :pay 1700.0
                      :event-date "2026-04-01"})
    (let [s-wb   (sku/sku-summary "ART-005" "2026-04-01" "2026-04-30" :marketplace :wb)
          s-all  (sku/sku-summary "ART-005" "2026-04-01" "2026-04-30")]
      (is (= 1 (:sales-count s-wb)))
      (is (= 1000.0 (:revenue s-wb)))
      (is (= 2 (:sales-count s-all))))))

(deftest sku-summary-default-dates
  (testing "nil from/to defaults to last 30 days without throwing"
    (let [s (sku/sku-summary "NONE" nil nil)]
      (is (map? s))
      (is (= 0 (:sales-count s))))))

;; ---------------------------------------------------------------------------
;; FR-001: ROI = (for-pay - cogs) / cogs * 100
;; ---------------------------------------------------------------------------

(deftest roi-is-net-profit-over-cost
  (testing "roi = (for-pay - cogs) / cogs * 100"
    ;; revenue=1000, for-pay=800, cogs=500 => (800-500)/500*100 = 60.0
    ;; before-would-be (revenue/cogs) = 1000/500 = 2.0 (no *100, markup not ROI)
    (insert-finance! {:article "ROI-001" :marketplace :wb :rrd-id 901
                      :quantity 1 :retail 1000.0 :pay 800.0
                      :event-date "2026-04-15"})
    (insert-cost-price! "ROI-001" 500.0)
    (let [s (sku/sku-summary "ROI-001" "2026-04-01" "2026-04-30")]
      (is (< (Math/abs (- 60.0 (:roi s))) 0.01)
          (str "expected 60.0 got " (:roi s))))))

(deftest roi-zero-cost-no-divide
  (testing "roi = 0.0 when cogs = 0 (no cost-price seeded), no exception"
    ;; cogs=0.0 => guarded => 0.0; before-would-be same guard, so this pins behaviour
    (insert-finance! {:article "ROI-002" :marketplace :wb :rrd-id 902
                      :quantity 1 :retail 1000.0 :pay 800.0
                      :event-date "2026-04-15"})
    ;; intentionally no insert-cost-price! => cogs = 0.0
    (let [s (sku/sku-summary "ROI-002" "2026-04-01" "2026-04-30")]
      (is (= 0.0 (:roi s))
          (str "expected 0.0 got " (:roi s))))))
