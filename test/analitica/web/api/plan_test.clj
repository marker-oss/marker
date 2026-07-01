(ns analitica.web.api.plan-test
  "Contract tests for spec 017 US4 per-SKU Plan/Fact HTTP API.

   Tests call handlers directly (no ring.mock), using a fresh temp-file SQLite
   DB per test (same pattern as tax-opex-test / feedback-api-test with-temp-db).

   Contract: specs/017-engagement-bot-planfact/contracts/plan-fact-sku.edn §5.

   Run focused:
     clojure -M:test --focus analitica.web.api.plan-test"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.domain.plan :as plan]
            [analitica.web.api.plan :as api])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.apache.poi.ss.usermodel WorkbookFactory]
           [org.apache.poi.xssf.usermodel XSSFWorkbook]))

;; ---------------------------------------------------------------------------
;; Fixture: fresh temp-file SQLite DB per test
;; ---------------------------------------------------------------------------

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "plan-api-test-" ".db"
                                   (make-array FileAttribute 0))
        f    (.toFile path)]
    (.delete f)
    (.getAbsolutePath f)))

(defn- delete-temp-db! [path]
  (doseq [suffix ["" "-shm" "-wal"]]
    (let [f (File. (str path suffix))]
      (when (.exists f) (.delete f)))))

(defn with-temp-db [f]
  (let [path      (fresh-temp-db-path)
        orig-spec (deref #'db/db-spec)]
    (try
      (alter-var-root #'db/db-spec (constantly {:dbtype "sqlite" :dbname path}))
      (db/init!)
      (f)
      (finally
        (alter-var-root #'db/db-spec (constantly orig-spec))
        (reset! @#'db/datasource nil)
        (delete-temp-db! path)))))

(use-fixtures :each with-temp-db)

;; ---------------------------------------------------------------------------
;; Helpers: seed sales rows (per-article actuals source) + multipart parts
;; ---------------------------------------------------------------------------

(defn- insert-sale!
  "Insert a minimal `sale` row so sales/by-article picks it up.
   revenue := SUM(for_pay) on :sale rows (canon Sales.3)."
  [{:keys [sale-id date article for-pay marketplace type]
    :or   {marketplace "wb" type "sale"}}]
  (db/execute!
    ["INSERT INTO sales (sale_id, date, article, type, for_pay, marketplace)
      VALUES (?, ?, ?, ?, ?, ?)"
     sale-id date article type (double for-pay) marketplace]))

(defn- csv-part
  "Build a multipart file-part map (as ring's multipart middleware produces)
   whose tempfile holds the given text with the given extension."
  [text ext filename]
  (let [f (File/createTempFile "plan-import-" ext)]
    (spit f text)
    {:filename     filename
     :content-type "text/csv"
     :size         (.length f)
     :tempfile     f}))

(defn- xlsx-part
  "Build a multipart file-part map whose tempfile is an .xlsx workbook with the
   given rows (vector of vectors). First row is the header."
  [rows filename]
  (let [f  (File/createTempFile "plan-import-" ".xlsx")
        wb (XSSFWorkbook.)
        sh (.createSheet wb "plan")]
    (doseq [[ri row] (map-indexed vector rows)]
      (let [r (.createRow sh (int ri))]
        (doseq [[ci v] (map-indexed vector row)]
          (.setCellValue (.createCell r (int ci)) (str v)))))
    (with-open [out (java.io.FileOutputStream. f)]
      (.write wb out))
    (.close wb)
    {:filename     filename
     :content-type "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
     :size         (.length f)
     :tempfile     f}))

;; ===========================================================================
;; GET /api/v1/plan/sku
;; ===========================================================================

(deftest get-sku-empty-returns-empty-rows-and-zero-totals
  (testing "No plans, no sales → {:rows [] :totals {:plan 0 :actual 0}}"
    (let [resp (api/get-plan-sku {:params {:period_month "2026-05" :marketplace "wb"}})]
      (is (= 200 (:status resp)))
      (is (vector? (get-in resp [:body :rows])))
      (is (empty? (get-in resp [:body :rows])))
      (is (map? (get-in resp [:body :totals])))
      (is (contains? (get-in resp [:body :totals]) :plan))
      (is (contains? (get-in resp [:body :totals]) :actual)))))

(deftest get-sku-missing-period-returns-422
  (testing "Missing period_month → 422"
    (let [resp (api/get-plan-sku {:params {:marketplace "wb"}})]
      (is (= 422 (:status resp)))
      (is (false? (get-in resp [:body :ok]))))))

(deftest get-sku-invalid-period-returns-422
  (testing "Bad period_month (month 13) → 422"
    (let [resp (api/get-plan-sku {:params {:period_month "2026-13" :marketplace "wb"}})]
      (is (= 422 (:status resp))))))

(deftest get-sku-returns-plan-and-actual-with-variance
  (testing "Per-SKU plan + seeded sales → PlanFactRow with variance"
    ;; Plan: revenue target 100000 for article A-1 on wb
    (plan/save-plan! {:period-month "2026-05" :marketplace "wb"
                      :metric :revenue :sku "A-1" :target-value 100000.0})
    ;; Actuals: two sales for A-1 in May → revenue 80000
    (insert-sale! {:sale-id "s1" :date "2026-05-03" :article "A-1" :for-pay 50000.0})
    (insert-sale! {:sale-id "s2" :date "2026-05-20" :article "A-1" :for-pay 30000.0})
    (let [resp  (api/get-plan-sku {:params {:period_month "2026-05" :marketplace "wb"}})
          rows  (get-in resp [:body :rows])
          row   (first (filter #(= "A-1" (:sku %)) rows))]
      (is (= 200 (:status resp)))
      (is (some? row) "row for A-1 present")
      (is (= :revenue (:metric row)))
      (is (== 100000.0 (:plan row)))
      (is (== 80000.0 (:actual row)))
      (is (== -20000.0 (:variance-abs row)))
      (is (== -20.0 (:variance-pct row))))))

(deftest get-sku-actual-without-plan-has-nil-variance
  (testing "Sales exist but no per-SKU plan → variance nil (NOT -100%)"
    (insert-sale! {:sale-id "s1" :date "2026-05-03" :article "B-2" :for-pay 40000.0})
    (let [resp (api/get-plan-sku {:params {:period_month "2026-05" :marketplace "wb"}})
          row  (first (filter #(= "B-2" (:sku %)) (get-in resp [:body :rows])))]
      (is (some? row))
      (is (nil? (:plan row)))
      (is (== 40000.0 (:actual row)))
      (is (nil? (:variance-abs row)))
      (is (nil? (:variance-pct row)) "must be nil, not -100"))))

(deftest get-sku-plan-with-zero-actual-is-minus-100
  (testing "plan>0, actual=0 → variance-abs=-plan, variance-pct=-100.0"
    (plan/save-plan! {:period-month "2026-05" :marketplace "wb"
                      :metric :revenue :sku "C-3" :target-value 50000.0})
    (let [resp (api/get-plan-sku {:params {:period_month "2026-05" :marketplace "wb"}})
          row  (first (filter #(= "C-3" (:sku %)) (get-in resp [:body :rows])))]
      (is (some? row))
      (is (== 50000.0 (:plan row)))
      (is (== 0.0 (:actual row)))
      (is (== -50000.0 (:variance-abs row)))
      (is (== -100.0 (:variance-pct row))))))

(deftest get-sku-totals-reconcile-with-rows
  (testing "Σ plan of rows = totals.plan ; Σ actual = totals.actual (SC-007)"
    (plan/save-plan! {:period-month "2026-05" :marketplace "wb"
                      :metric :revenue :sku "A-1" :target-value 100000.0})
    (plan/save-plan! {:period-month "2026-05" :marketplace "wb"
                      :metric :revenue :sku "C-3" :target-value 50000.0})
    (insert-sale! {:sale-id "s1" :date "2026-05-03" :article "A-1" :for-pay 80000.0})
    (let [resp   (api/get-plan-sku {:params {:period_month "2026-05" :marketplace "wb"}})
          rows   (get-in resp [:body :rows])
          totals (get-in resp [:body :totals])
          sum-plan   (reduce + 0.0 (keep :plan rows))
          sum-actual (reduce + 0.0 (map #(or (:actual %) 0.0) rows))]
      (is (== sum-plan (:plan totals)))
      (is (== sum-actual (:actual totals))))))

(deftest get-sku-filters-by-marketplace
  (testing "Sales on ozon do not leak into wb view"
    (insert-sale! {:sale-id "s-wb" :date "2026-05-03" :article "A-1" :for-pay 10000.0 :marketplace "wb"})
    (insert-sale! {:sale-id "s-oz" :date "2026-05-03" :article "Z-9" :for-pay 99999.0 :marketplace "ozon"})
    (let [resp (api/get-plan-sku {:params {:period_month "2026-05" :marketplace "wb"}})
          skus (set (map :sku (get-in resp [:body :rows])))]
      (is (contains? skus "A-1"))
      (is (not (contains? skus "Z-9")) "ozon article must be excluded from wb view"))))

;; ===========================================================================
;; POST /api/v1/plan/sku/preview  (multipart; NO DB write; cap 200)
;; ===========================================================================

(deftest preview-csv-valid-rows-no-db-write
  (testing "Preview parses CSV, returns ImportOutcome, writes nothing"
    ;; Seed the catalogue via sales so A-1/C-3 are known SKUs
    (insert-sale! {:sale-id "s1" :date "2026-05-01" :article "A-1" :for-pay 1.0})
    (insert-sale! {:sale-id "s2" :date "2026-05-01" :article "C-3" :for-pay 1.0})
    (let [csv  (str "sku,metric,target_value\n"
                    "A-1,revenue,100000\n"
                    "C-3,orders,500\n")
          part (csv-part csv ".csv" "plan.csv")
          resp (api/preview-plan-sku
                 {:multipart-params {"file"         part
                                     "period_month" "2026-05"
                                     "marketplace"  "wb"}})]
      (is (= 200 (:status resp)))
      (is (= 2 (get-in resp [:body :total])))
      (is (= 2 (get-in resp [:body :loaded])))
      (is (= 0 (get-in resp [:body :rejected])))
      (is (vector? (get-in resp [:body :rows])))
      (is (vector? (get-in resp [:body :errors])))
      ;; NO DB write on preview
      (is (empty? (plan/fetch-plans "2026-05")) "preview must not persist"))))

(deftest preview-csv-reports-rejects-never-silently-dropped
  (testing "Unknown SKU / unknown metric / bad target all reported in :errors"
    (insert-sale! {:sale-id "s1" :date "2026-05-01" :article "A-1" :for-pay 1.0})
    (let [csv  (str "sku,metric,target_value\n"
                    "A-1,revenue,100000\n"        ; ok
                    "GHOST,revenue,5\n"           ; unknown SKU
                    "A-1,not_a_metric,5\n"        ; unknown metric
                    "A-1,revenue,-3\n")           ; non-positive
          part (csv-part csv ".csv" "plan.csv")
          resp (api/preview-plan-sku
                 {:multipart-params {"file"         part
                                     "period_month" "2026-05"
                                     "marketplace"  "wb"}})]
      (is (= 200 (:status resp)))
      (is (= 4 (get-in resp [:body :total])))
      (is (= 1 (get-in resp [:body :loaded])))
      (is (= 3 (get-in resp [:body :rejected])))
      (is (= 3 (count (get-in resp [:body :errors]))))
      (is (every? #(and (contains? % :line) (contains? % :sku) (contains? % :reason))
                  (get-in resp [:body :errors]))))))

(deftest preview-xlsx-parses
  (testing "Preview accepts an .xlsx upload via docjure"
    (insert-sale! {:sale-id "s1" :date "2026-05-01" :article "A-1" :for-pay 1.0})
    (let [part (xlsx-part [["sku" "metric" "target_value"]
                           ["A-1" "revenue" "100000"]]
                          "plan.xlsx")
          resp (api/preview-plan-sku
                 {:multipart-params {"file"         part
                                     "period_month" "2026-05"
                                     "marketplace"  "wb"}})]
      (is (= 200 (:status resp)))
      (is (= 1 (get-in resp [:body :total])))
      (is (= 1 (get-in resp [:body :loaded]))))))

(deftest preview-caps-rows-at-200
  (testing "Preview caps returned rows at 200 even for a larger file"
    ;; one known SKU used on every line
    (insert-sale! {:sale-id "s1" :date "2026-05-01" :article "A-1" :for-pay 1.0})
    (let [body  (apply str "sku,metric,target_value\n"
                       (repeat 250 "A-1,revenue,1000\n"))
          part  (csv-part body ".csv" "big.csv")
          resp  (api/preview-plan-sku
                  {:multipart-params {"file"         part
                                      "period_month" "2026-05"
                                      "marketplace"  "wb"}})]
      (is (= 200 (:status resp)))
      (is (= 250 (get-in resp [:body :total])))
      (is (<= (count (get-in resp [:body :rows])) 200) "rows preview capped at 200"))))

(deftest preview-no-file-returns-400
  (testing "Missing file part → 400"
    (let [resp (api/preview-plan-sku
                 {:multipart-params {"period_month" "2026-05" "marketplace" "wb"}})]
      (is (= 400 (:status resp))))))

(deftest preview-missing-period-returns-422
  (testing "Missing period_month form field → 422"
    (let [part (csv-part "sku,metric,target_value\nA-1,revenue,1\n" ".csv" "p.csv")
          resp (api/preview-plan-sku
                 {:multipart-params {"file" part "marketplace" "wb"}})]
      (is (= 422 (:status resp))))))

;; ===========================================================================
;; POST /api/v1/plan/sku/import  (multipart; writes valid rows atomically)
;; ===========================================================================

(deftest import-writes-valid-rows
  (testing "Import persists valid rows via save-plan!"
    (insert-sale! {:sale-id "s1" :date "2026-05-01" :article "A-1" :for-pay 1.0})
    (insert-sale! {:sale-id "s2" :date "2026-05-01" :article "C-3" :for-pay 1.0})
    (let [csv  (str "sku,metric,target_value\n"
                    "A-1,revenue,100000\n"
                    "C-3,orders,500\n")
          part (csv-part csv ".csv" "plan.csv")
          resp (api/import-plan-sku
                 {:multipart-params {"file"         part
                                     "period_month" "2026-05"
                                     "marketplace"  "wb"}})]
      (is (= 200 (:status resp)))
      (is (= 2 (get-in resp [:body :loaded])))
      (is (= 0 (get-in resp [:body :rejected])))
      (let [saved (plan/fetch-plans "2026-05")]
        (is (= 2 (count saved)) "both rows persisted")
        (is (= 100000.0 (plan/lookup-plan-sku saved
                          {:period-month "2026-05" :marketplace "wb"
                           :metric :revenue :sku "A-1"})))))))

(deftest import-rejects-bad-rows-writes-only-good
  (testing "Invalid rows rejected; only valid rows written"
    (insert-sale! {:sale-id "s1" :date "2026-05-01" :article "A-1" :for-pay 1.0})
    (let [csv  (str "sku,metric,target_value\n"
                    "A-1,revenue,100000\n"    ; ok
                    "GHOST,revenue,5\n")      ; unknown SKU → reject
          part (csv-part csv ".csv" "plan.csv")
          resp (api/import-plan-sku
                 {:multipart-params {"file"         part
                                     "period_month" "2026-05"
                                     "marketplace"  "wb"}})]
      (is (= 200 (:status resp)))
      (is (= 1 (get-in resp [:body :loaded])))
      (is (= 1 (get-in resp [:body :rejected])))
      (is (= 1 (count (plan/fetch-plans "2026-05")))))))

(deftest import-last-wins-on-duplicate-sku
  (testing "Duplicate (period,mp,metric,sku) within file → last-wins upsert"
    (insert-sale! {:sale-id "s1" :date "2026-05-01" :article "A-1" :for-pay 1.0})
    (let [csv  (str "sku,metric,target_value\n"
                    "A-1,revenue,100\n"
                    "A-1,revenue,999\n")
          part (csv-part csv ".csv" "plan.csv")
          resp (api/import-plan-sku
                 {:multipart-params {"file"         part
                                     "period_month" "2026-05"
                                     "marketplace"  "wb"}})]
      (is (= 200 (:status resp)))
      (let [saved (plan/fetch-plans "2026-05")]
        (is (= 1 (count saved)) "single row after upsert")
        (is (== 999.0 (plan/lookup-plan-sku saved
                        {:period-month "2026-05" :marketplace "wb"
                         :metric :revenue :sku "A-1"})) "last value wins")))))

(deftest import-no-file-returns-400
  (testing "Missing file part → 400"
    (let [resp (api/import-plan-sku
                 {:multipart-params {"period_month" "2026-05" "marketplace" "wb"}})]
      (is (= 400 (:status resp))))))

(deftest import-missing-period-returns-422
  (testing "Missing period_month form field → 422"
    (let [part (csv-part "sku,metric,target_value\nA-1,revenue,1\n" ".csv" "p.csv")
          resp (api/import-plan-sku
                 {:multipart-params {"file" part "marketplace" "wb"}})]
      (is (= 422 (:status resp))))))
