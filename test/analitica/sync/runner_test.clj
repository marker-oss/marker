(ns analitica.sync.runner-test
  "Unit tests for analitica.sync.runner — Phase 2 task lifecycle envelope.
   Each test that touches the DB uses an isolated temp SQLite DB via with-temp-db."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.sync.registry :as reg]
            [analitica.sync.runner :as runner])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; DB fixture — mirrors registry_test.clj pattern exactly
;; ---------------------------------------------------------------------------

(def ^:dynamic *test-db-path* nil)

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-runner-test-"
                                   ".db"
                                   (make-array FileAttribute 0))
        f    (.toFile path)]
    (.delete f)
    (.getAbsolutePath f)))

(defn- delete-test-db! [path]
  (doseq [suffix ["" "-shm" "-wal"]]
    (let [f (File. (str path suffix))]
      (when (.exists f) (.delete f)))))

(defn with-temp-db [f]
  (let [path      (fresh-temp-db-path)
        orig-spec (deref #'db/db-spec)]
    (try
      (alter-var-root #'db/db-spec (constantly {:dbtype "sqlite" :dbname path}))
      (binding [*test-db-path* path]
        (db/init!)
        (f))
      (finally
        (alter-var-root #'db/db-spec (constantly orig-spec))
        (reset! @#'db/datasource nil)
        (delete-test-db! path)))))

(use-fixtures :each with-temp-db)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-task!
  "Create a minimal task row and return its id."
  [id]
  (reg/create-task! {:id          id
                     :run-id      "run-test"
                     :marketplace "wb"
                     :entity-type "sales"
                     :phase       "ingest"})
  id)

;; ---------------------------------------------------------------------------
;; 1. run-task-success
;; ---------------------------------------------------------------------------

(deftest run-task-success
  (testing "thunk returns 42 → status=ok, items=42, timing fields populated"
    (make-task! "wb/sales/ingest")
    (let [row (runner/run-task! "wb/sales/ingest" (fn [] 42))]
      (is (= "ok" (:status row)))
      (is (= 42 (:items row)))
      (is (some? (:started-at row)))
      (is (some? (:finished-at row)))
      ;; duration_ms may be 0 if same-second; just assert non-negative
      (is (>= (:duration-ms row) 0)))))

;; ---------------------------------------------------------------------------
;; 2. run-task-non-number-result
;; ---------------------------------------------------------------------------

(deftest run-task-non-number-result
  (testing "thunk returns a map → items defaults to 0"
    (make-task! "wb/orders/ingest")
    (let [row (runner/run-task! "wb/orders/ingest" (fn [] {:something :else}))]
      (is (= "ok" (:status row)))
      ;; Non-number → stored as 0 (documented in run-task! docstring)
      (is (= 0 (:items row))))))

;; ---------------------------------------------------------------------------
;; 3. run-task-throws-internal
;; ---------------------------------------------------------------------------

(deftest run-task-throws-internal
  (testing "thunk throws plain ex-info → status=failed, error_kind=internal"
    (make-task! "wb/finance/ingest")
    (let [row (runner/run-task! "wb/finance/ingest"
                                (fn [] (throw (ex-info "bug" {}))))]
      (is (= "failed" (:status row)))
      (is (= "internal" (:error-kind row)))
      (is (= "bug" (:error-msg row))))))

;; ---------------------------------------------------------------------------
;; 4. run-task-throws-http-4xx
;; ---------------------------------------------------------------------------

(deftest run-task-throws-http-4xx
  (testing "thunk throws ex-info with :status 400 → error_kind=http-4xx"
    (make-task! "wb/returns/ingest")
    (let [row (runner/run-task! "wb/returns/ingest"
                                (fn [] (throw (ex-info "bad request" {:status 400}))))]
      (is (= "failed" (:status row)))
      (is (= "http-4xx" (:error-kind row))))))

;; ---------------------------------------------------------------------------
;; 5. run-task-throws-http-429
;; ---------------------------------------------------------------------------

(deftest run-task-throws-http-429
  (testing "thunk throws ex-info with :status 429 → error_kind=http-429"
    (make-task! "wb/stocks/ingest")
    (let [row (runner/run-task! "wb/stocks/ingest"
                                (fn [] (throw (ex-info "rate limited" {:status 429}))))]
      (is (= "failed" (:status row)))
      (is (= "http-429" (:error-kind row))))))

;; ---------------------------------------------------------------------------
;; 6. run-task-throws-http-5xx
;; ---------------------------------------------------------------------------

(deftest run-task-throws-http-5xx
  (testing "thunk throws ex-info with :status 503 → error_kind=http-5xx"
    (make-task! "wb/prices/ingest")
    (let [row (runner/run-task! "wb/prices/ingest"
                                (fn [] (throw (ex-info "service unavailable" {:status 503}))))]
      (is (= "failed" (:status row)))
      (is (= "http-5xx" (:error-kind row))))))

;; ---------------------------------------------------------------------------
;; 7. run-task-throws-timeout
;; ---------------------------------------------------------------------------

(deftest run-task-throws-timeout
  (testing "SocketTimeoutException → error_kind=timeout"
    (make-task! "wb/storage/timeout1")
    (let [row (runner/run-task! "wb/storage/timeout1"
                                (fn [] (throw (java.net.SocketTimeoutException. "Read timed out"))))]
      (is (= "failed" (:status row)))
      (is (= "timeout" (:error-kind row)))))

  (testing "ex-info with message containing 'timed out' → error_kind=timeout"
    (make-task! "wb/storage/timeout2")
    (let [row (runner/run-task! "wb/storage/timeout2"
                                (fn [] (throw (ex-info "request timed out" {}))))]
      (is (= "failed" (:status row)))
      (is (= "timeout" (:error-kind row))))))

;; ---------------------------------------------------------------------------
;; 8. run-task-throws-validation
;; ---------------------------------------------------------------------------

(deftest run-task-throws-validation
  (testing "ex-info with :violations → error_kind=validation"
    (make-task! "wb/catalog/ingest")
    (let [row (runner/run-task! "wb/catalog/ingest"
                                (fn [] (throw (ex-info "schema mismatch"
                                                        {:violations [{:path :foo}]}))))]
      (is (= "failed" (:status row)))
      (is (= "validation" (:error-kind row))))))

;; ---------------------------------------------------------------------------
;; 9. run-task-task-missing
;; ---------------------------------------------------------------------------

(deftest run-task-task-missing
  (testing "missing task → throws ex-info with :task-id before thunk runs"
    (let [thunk-called? (atom false)
          caught        (try
                          (runner/run-task! "nonexistent/id"
                                            (fn [] (reset! thunk-called? true) 1))
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
      (is (some? caught) "should throw ex-info")
      (is (= "Task not found" (.getMessage caught)))
      (is (= "nonexistent/id" (:task-id (ex-data caught))))
      (is (false? @thunk-called?) "thunk must NOT be called for missing task"))))

;; ---------------------------------------------------------------------------
;; 10. classify-error-table (pure unit test — no DB)
;; ---------------------------------------------------------------------------

(deftest classify-error-table
  (testing "every classify-error branch is covered"
    (let [cases
          [;; HTTP 429 (must come before generic 4xx)
           [(ex-info "rate limited" {:status 429})          :http-429]
           ;; HTTP 4xx
           [(ex-info "bad request" {:status 400})           :http-4xx]
           [(ex-info "forbidden" {:status 403})             :http-4xx]
           [(ex-info "not found" {:status 404})             :http-4xx]
           [(ex-info "edge of 4xx" {:status 499})           :http-4xx]
           ;; HTTP 5xx
           [(ex-info "server error" {:status 500})          :http-5xx]
           [(ex-info "unavailable" {:status 503})           :http-5xx]
           [(ex-info "edge of 5xx" {:status 599})           :http-5xx]
           ;; Timeout via exception class
           [(java.net.SocketTimeoutException. "socket timeout")          :timeout]
           [(java.net.http.HttpTimeoutException. "http timeout")         :timeout]
           ;; Timeout via message
           [(ex-info "timeout occurred" {})                 :timeout]
           [(ex-info "request timed out" {})                :timeout]
           [(ex-info "TIMED OUT in caps" {})                :timeout]
           ;; Validation via :type
           [(ex-info "val err" {:type :validation-error})  :validation]
           [(ex-info "drift" {:type :schema-drift})         :validation]
           ;; Validation via :violations
           [(ex-info "violations" {:violations [{:p :x}]}) :validation]
           ;; Internal fallback
           [(ex-info "unknown error" {})                    :internal]
           [(RuntimeException. "plain java exception")      :internal]
           [(Exception. "another plain")                    :internal]]]
      (doseq [[throwable expected] cases]
        (is (= expected (runner/classify-error throwable))
            (str "classify-error for: " (.getMessage throwable)
                 " expected " expected))))))
