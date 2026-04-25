(ns analitica.web.api.sku-integration-test
  "^:integration smoke tests — hit the full Ring stack via (app)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.web.server :as server]
            [analitica.web.api.sync :as sync-api]
            [analitica.db :as db])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temp DB fixture
;; ---------------------------------------------------------------------------

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-sku-int-test-" ".db"
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
;; Helper: build a minimal GET Ring request map
;; ---------------------------------------------------------------------------

(defn- get-request
  "Build a minimal Ring GET request map for the given URI."
  [uri]
  {:request-method :get
   :uri            uri
   :params         {}
   :headers        {}})

;; ---------------------------------------------------------------------------
;; Integration tests
;; ---------------------------------------------------------------------------

(deftest ^:integration sku-endpoint-smoke
  (testing "GET /api/sku/123?from=2026-04-01&to=2026-04-30 returns 200 text/html"
    (let [app  (server/app)
          req  {:request-method :get
                :uri            "/api/sku/123"
                :query-string   "from=2026-04-01&to=2026-04-30"
                :params         {:identifier "123" :from "2026-04-01" :to "2026-04-30"}
                :headers        {}}
          resp (app req)]
      (is (= 200 (:status resp)))
      (is (= "text/html; charset=utf-8" (get-in resp [:headers "Content-Type"])))
      (is (string? (:body resp))))))

(deftest ^:integration sku-endpoint-article-string
  (testing "GET /api/sku/DRESS-3452 returns 200"
    (let [app  (server/app)
          req  {:request-method :get
                :uri            "/api/sku/DRESS-3452"
                :query-string   "from=2026-04-01&to=2026-04-30"
                :params         {:identifier "DRESS-3452" :from "2026-04-01" :to "2026-04-30"}
                :headers        {}}
          resp (app req)]
      (is (= 200 (:status resp)))
      (is (string? (:body resp))))))

(deftest ^:integration sku-endpoint-with-data
  (testing "seeded article returns fragment with article name"
    (next.jdbc/execute!
     (db/ds)
     ["INSERT INTO sales (sale_id, date, article, nm_id, type, for_pay, marketplace)
       VALUES (?,?,?,?,?,?,?)"
      "int-sale-1" "2026-04-10T10:00:00" "INT-ART-001" 77777 "sale" 2000.0 "wb"])
    (let [app  (server/app)
          req  {:request-method :get
                :uri            "/api/sku/INT-ART-001"
                :query-string   "from=2026-04-01&to=2026-04-30"
                :params         {:identifier "INT-ART-001" :from "2026-04-01" :to "2026-04-30"}
                :headers        {}}
          resp (app req)]
      (is (= 200 (:status resp)))
      (is (re-find #"INT-ART-001" (:body resp)))
      (is (re-find #"Продажи" (:body resp))))))

(deftest ^:integration sku-endpoint-marketplace-param
  (testing "?marketplace=wb works without error"
    (let [app  (server/app)
          req  {:request-method :get
                :uri            "/api/sku/SOME-ART"
                :query-string   "from=2026-04-01&to=2026-04-30&marketplace=wb"
                :params         {:identifier "SOME-ART" :from "2026-04-01" :to "2026-04-30"
                                 :marketplace "wb"}
                :headers        {}}
          resp (app req)]
      (is (contains? #{200 404} (:status resp)))
      (is (string? (:body resp))))))
