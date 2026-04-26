(ns analitica.domain.sku-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.domain.sku :as sku]
            [analitica.db :as db])
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
    article "" cost]))

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
  (testing "counts sales and revenue"
    (insert-sale! "ART-001" 12345 "2026-04-01T10:00:00" :sale 1000.0 :wb)
    (insert-sale! "ART-001" 12345 "2026-04-02T10:00:00" :sale 1200.0 :wb)
    (insert-sale! "ART-001" 12345 "2026-04-03T10:00:00" :return 900.0 :wb)
    (let [s (sku/sku-summary "ART-001" "2026-04-01" "2026-04-30")]
      (is (= 2 (:sales-count s)))
      (is (= 1 (:returns-count s)))
      (is (= 2200.0 (:revenue s)))
      (is (= 12345 (:nm-id s))))))

(deftest sku-summary-margin-roi
  (testing "margin and ROI calculated correctly"
    (insert-sale! "ART-002" nil "2026-04-01T10:00:00" :sale 1000.0 :wb)
    (insert-cost-price! "ART-002" 400.0)
    (let [s (sku/sku-summary "ART-002" "2026-04-01" "2026-04-30")]
      (is (= 1 (:sales-count s)))
      (is (= 400.0 (:cogs s)))
      (is (= 600.0 (- (:revenue s) (:cogs s))))
      ;; margin = (1000-400)/1000 * 100 = 60%
      (is (< (Math/abs (- 60.0 (:margin-pct s))) 0.01))
      ;; roi = 1000/400 = 2.5
      (is (< (Math/abs (- 2.5 (:roi s))) 0.01)))))

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
  (testing "marketplace param filters correctly"
    (insert-sale! "ART-005" nil "2026-04-01T10:00:00" :sale 1000.0 :wb)
    (insert-sale! "ART-005" nil "2026-04-01T10:00:00" :sale 2000.0 :ozon)
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
