(ns analitica.web.api.search-integration-test
  "^:integration — hit the full Ring stack via (server/app).
   Requires an isolated in-memory SQLite DB; no external service needed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.web.server :as server]
            [analitica.web.api.sync :as sync-api]
            [analitica.db :as db]
            [jsonista.core :as json]
            [next.jdbc :as next.jdbc])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temp DB fixture (identical pattern to sku-integration-test)
;; ---------------------------------------------------------------------------

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-search-int-test-" ".db"
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
    (reset! sync-api/sync-running? false)
    (reset! sync-api/progress-channel nil)
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
;; Helpers
;; ---------------------------------------------------------------------------

(defn- get-request
  "Build a minimal Ring GET request map."
  [uri query-string params]
  {:request-method :get
   :uri            uri
   :query-string   query-string
   :params         params
   :headers        {}})

(defn- parse-json [body]
  (json/read-value body json/keyword-keys-object-mapper))

;; ---------------------------------------------------------------------------
;; Integration tests
;; ---------------------------------------------------------------------------

(deftest ^:integration search-endpoint-empty-query
  (testing "GET /api/search?q= returns 200 with {:results []}"
    (let [app  (server/app)
          req  (get-request "/api/search" "q=" {:q ""})
          resp (app req)]
      (is (= 200 (:status resp)))
      (is (clojure.string/includes?
            (get-in resp [:headers "Content-Type"] "") "application/json"))
      (let [body (parse-json (:body resp))]
        (is (contains? body :results))
        (is (= [] (:results body)))))))

(deftest ^:integration search-endpoint-short-query
  (testing "GET /api/search?q=a returns {:results []} for single-char query"
    (let [app  (server/app)
          req  (get-request "/api/search" "q=a" {:q "a"})
          resp (app req)]
      (is (= 200 (:status resp)))
      (let [body (parse-json (:body resp))]
        (is (= [] (:results body)))))))

(deftest ^:integration search-endpoint-static-report-match
  (testing "GET /api/search?q=юнит returns the Юнит-экономика report entry"
    (let [app  (server/app)
          req  (get-request "/api/search" "q=%D1%8E%D0%BD%D0%B8%D1%82" {:q "юнит"})
          resp (app req)]
      (is (= 200 (:status resp)))
      (let [body    (parse-json (:body resp))
            results (:results body)]
        (is (vector? results))
        (is (seq results) "Should have at least one result for 'юнит'")
        (let [reports (filter #(= "report" (name (:type %))) results)]
          (is (seq reports) "Should have report-type results")
          (is (some #(clojure.string/includes?
                       (clojure.string/lower-case (:title %)) "юнит")
                    reports)))))))

(deftest ^:integration search-endpoint-page-match
  (testing "GET /api/search?q=главная returns the Главная page"
    (let [app  (server/app)
          req  (get-request "/api/search" "q=%D0%B3%D0%BB%D0%B0%D0%B2%D0%BD%D0%B0%D1%8F" {:q "главная"})
          resp (app req)]
      (is (= 200 (:status resp)))
      (let [body  (parse-json (:body resp))
            pages (filter #(= "page" (name (:type %))) (:results body))]
        (is (seq pages))
        (is (some #(= "/" (:route %)) pages))))))

(deftest ^:integration search-endpoint-sku-from-db
  (testing "GET /api/search?q=INT-SKU returns SKU seeded in DB"
    ;; Seed a row into sales
    (next.jdbc/execute!
     (db/ds)
     ["INSERT INTO sales (sale_id, date, article, nm_id, type, for_pay, marketplace)
       VALUES (?,?,?,?,?,?,?)"
      "search-int-1" "2026-04-10T10:00:00" "INT-SKU-001" 99001 "sale" 1500.0 "wb"])
    (let [app  (server/app)
          req  (get-request "/api/search" "q=INT-SKU" {:q "INT-SKU"})
          resp (app req)]
      (is (= 200 (:status resp)))
      (let [body (:results (parse-json (:body resp)))
            skus (filter #(= "sku" (name (:type %))) body)]
        (is (seq skus) "Should find at least one SKU result")
        (is (some #(clojure.string/includes? (:title %) "INT-SKU-001") skus))
        ;; Route must be URL-encoded (no bare slash in article query value)
        (is (every? #(clojure.string/starts-with? (:route %) "/reports/sales?article=")
                    skus))))))

(deftest ^:integration search-endpoint-results-shape
  (testing "Every result has :type :title :hint :route keys"
    (let [app  (server/app)
          req  (get-request "/api/search" "q=%D0%BF%D1%80%D0%BE%D0%B4%D0%B0%D0%B6" {:q "продаж"})
          resp (app req)]
      (is (= 200 (:status resp)))
      (let [results (:results (parse-json (:body resp)))]
        (doseq [r results]
          (is (contains? r :type)  (str "Missing :type in " r))
          (is (contains? r :title) (str "Missing :title in " r))
          (is (contains? r :hint)  (str "Missing :hint in " r))
          (is (contains? r :route) (str "Missing :route in " r)))))))
