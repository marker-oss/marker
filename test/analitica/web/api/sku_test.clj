(ns analitica.web.api.sku-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.web.api.sku :as sku-api]
            [analitica.db :as db])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temp DB fixture
;; ---------------------------------------------------------------------------

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-sku-api-test-" ".db"
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
    (let [path (fresh-temp-db-path)
          orig (deref #'db/db-spec)]
      (try
        (alter-var-root #'db/db-spec (constantly {:dbtype "sqlite" :dbname path}))
        (db/init!)
        (f)
        (finally
          (alter-var-root #'db/db-spec (constantly orig))
          (reset! @#'db/datasource nil)
          (delete-test-db! path))))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest handler-missing-id
  (testing "empty identifier returns 404 with text/html"
    (let [resp (sku-api/handler {:params {:identifier ""}})]
      (is (= 404 (:status resp)))
      (is (= "text/html; charset=utf-8" (get-in resp [:headers "Content-Type"]))))))

(deftest handler-unknown-sku
  (testing "unknown article returns 200 with 'не найден' fragment"
    (let [resp (sku-api/handler {:params {:identifier "NONEXIST-999"
                                           :from "2026-04-01"
                                           :to   "2026-04-30"}})]
      (is (= 200 (:status resp)))
      (is (= "text/html; charset=utf-8" (get-in resp [:headers "Content-Type"])))
      (is (re-find #"не найден" (:body resp))))))

(deftest handler-text-html-content-type
  (testing "response always returns text/html content-type"
    (let [resp (sku-api/handler {:params {:identifier "ART-XYZ"
                                           :from "2026-04-01"
                                           :to   "2026-04-30"}})]
      (is (string? (:body resp)))
      (is (= "text/html; charset=utf-8" (get-in resp [:headers "Content-Type"]))))))

(deftest handler-with-sales-data
  (testing "article with sales returns 200 with KPI content"
    ;; Seed a sale row
    (next.jdbc/execute!
     (db/ds)
     ["INSERT INTO sales (sale_id, date, article, nm_id, type, for_pay, marketplace)
       VALUES (?,?,?,?,?,?,?)"
      "test-sale-1" "2026-04-10T10:00:00" "ART-TEST" 99999 "sale" 1500.0 "wb"])
    (let [resp (sku-api/handler {:params {:identifier "ART-TEST"
                                           :from "2026-04-01"
                                           :to   "2026-04-30"}})]
      (is (= 200 (:status resp)))
      (is (re-find #"ART-TEST" (:body resp)))
      (is (re-find #"Продажи" (:body resp))))))

(deftest handler-no-from-to-defaults
  (testing "missing from/to params do not crash — defaults to last 30 days"
    (let [resp (sku-api/handler {:params {:identifier "ART-DEFAULT"}})]
      (is (contains? #{200 404} (:status resp)))
      (is (string? (:body resp))))))

(deftest handler-invalid-marketplace-ignored
  (testing "invalid marketplace param is ignored (treated as nil)"
    (let [resp (sku-api/handler {:params {:identifier "ART-MP"
                                           :marketplace "invalid"
                                           :from "2026-04-01"
                                           :to   "2026-04-30"}})]
      ;; Should not throw — unknown mp is just nil
      (is (contains? #{200 404} (:status resp))))))
