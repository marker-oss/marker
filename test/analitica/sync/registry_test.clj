(ns analitica.sync.registry-test
  "Unit tests for analitica.sync.registry — CRUD over sync_tasks.
   Each test is independent; uses a fresh temp-file SQLite DB via with-temp-db."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.sync.registry :as reg])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; DB fixture — mirrors materialize_test.clj pattern exactly
;; ---------------------------------------------------------------------------

(def ^:dynamic *test-db-path* nil)

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-reg-test-"
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

(defn- minimal-spec
  "Minimal valid task spec."
  [id]
  {:id          id
   :run-id      "run-001"
   :marketplace "wb"
   :entity-type "sales"
   :phase       "ingest"})

;; ---------------------------------------------------------------------------
;; Tests
;; db/query uses as-unqualified-kebab-maps, so column names are kebab-case:
;;   run_id -> :run-id, entity_type -> :entity-type, etc.
;; ---------------------------------------------------------------------------

(deftest create-task-roundtrip
  (testing "create then find returns all required fields with correct values"
    (let [spec {:id          "wb/sales/ingest"
                :run-id      "run-001"
                :marketplace "wb"
                :entity-type "sales"
                :phase       "ingest"
                :chunk       "2026-W14"
                :max-attempts 3
                :period-from "2026-04-01"
                :period-to   "2026-04-07"
                :parent-id   "wb/sales"
                :depends-on  ["wb/stocks/ingest"]}
          _    (reg/create-task! spec)
          row  (reg/find-task "wb/sales/ingest")]
      (is (some? row) "row should be found")
      (is (= "wb/sales/ingest" (:id row)))
      (is (= "run-001" (:run-id row)))
      (is (= "wb" (:marketplace row)))
      (is (= "sales" (:entity-type row)))
      (is (= "ingest" (:phase row)))
      (is (= "2026-W14" (:chunk row)))
      (is (= "pending" (:status row)))
      (is (= 0 (:attempts row)))
      (is (= 3 (:max-attempts row)))
      (is (= "2026-04-01" (:period-from row)))
      (is (= "2026-04-07" (:period-to row)))
      (is (= "wb/sales" (:parent-id row)))
      (is (= "wb/stocks/ingest" (:depends-on row))))))

(deftest create-with-defaults
  (testing "minimal spec yields correct defaults and nil optionals"
    (let [_   (reg/create-task! (minimal-spec "wb/orders/ingest"))
          row (reg/find-task "wb/orders/ingest")]
      (is (= "pending" (:status row)))
      (is (= 0 (:attempts row)))
      (is (= 1 (:max-attempts row)))
      (is (nil? (:chunk row)))
      (is (nil? (:items row)))
      (is (nil? (:error-msg row)))
      (is (nil? (:error-kind row)))
      (is (nil? (:started-at row)))
      (is (nil? (:finished-at row)))
      (is (nil? (:duration-ms row)))
      (is (nil? (:period-from row)))
      (is (nil? (:period-to row)))
      (is (nil? (:parent-id row)))
      (is (nil? (:depends-on row))))))

(deftest lifecycle-success
  (testing "create -> set-running -> record-success transitions"
    (reg/create-task! (minimal-spec "wb/finance/ingest"))
    (let [running (reg/set-running! "wb/finance/ingest")]
      (is (= "running" (:status running)))
      (is (= 1 (:attempts running)))
      (is (some? (:started-at running))))
    ;; sleep 1s so duration_ms > 0 (seconds-precision timestamps)
    (Thread/sleep 1100)
    (let [done (reg/record-success! "wb/finance/ingest" 42)]
      (is (= "ok" (:status done)))
      (is (= 42 (:items done)))
      (is (some? (:finished-at done)))
      (is (some? (:started-at done)))
      (is (pos? (:duration-ms done)) "duration_ms should be > 0")
      (is (nil? (:error-msg done)))
      (is (nil? (:error-kind done))))))

(deftest lifecycle-error
  (testing "create -> set-running -> record-error; error_msg truncated at 2000 chars"
    (reg/create-task! (minimal-spec "wb/storage/ingest"))
    (reg/set-running! "wb/storage/ingest")
    (Thread/sleep 1100)
    (let [long-msg (apply str (repeat 5000 "x"))
          done     (reg/record-error! "wb/storage/ingest" "http-5xx" long-msg)]
      (is (= "failed" (:status done)))
      (is (= "http-5xx" (:error-kind done)))
      (is (<= (count (:error-msg done)) 2000) "error_msg must be <= 2000 chars")
      (is (some? (:finished-at done)))
      (is (pos? (:duration-ms done))))))

(deftest find-tasks-for-run-ordering
  (testing "returns all tasks for run sorted by id ASC"
    (let [ids ["wb/c" "wb/a" "wb/e" "wb/b" "wb/d"]]
      (doseq [id ids]
        (reg/create-task! {:id id :run-id "run-sort" :marketplace "wb"
                           :entity-type "sales" :phase "ingest"}))
      (let [rows        (reg/find-tasks-for-run "run-sort")
            returned-ids (mapv :id rows)]
        (is (= 5 (count rows)))
        (is (= (sort ids) returned-ids) "rows must be sorted by id ASC")))))

(deftest find-pending-for-run
  (testing "returns only pending tasks, not ok ones"
    (doseq [id ["wb/p1" "wb/p2" "wb/p3" "wb/p4"]]
      (reg/create-task! {:id id :run-id "run-pending" :marketplace "wb"
                         :entity-type "sales" :phase "ingest"}))
    ;; mark two as ok
    (reg/set-running! "wb/p3")
    (reg/record-success! "wb/p3" 10)
    (reg/set-running! "wb/p4")
    (reg/record-success! "wb/p4" 5)
    (let [pending (reg/find-pending-for-run "run-pending")]
      (is (= 2 (count pending)))
      (is (every? #(= "pending" (:status %)) pending)))))

(deftest find-failed-since
  (testing "returns only failed tasks at or after the cutoff date"
    (doseq [id ["wb/old" "wb/cutoff" "wb/new"]]
      (reg/create-task! {:id id :run-id "run-fail" :marketplace "wb"
                         :entity-type "sales" :phase "ingest"})
      (reg/set-running! id)
      (reg/record-error! id "internal" "boom"))
    ;; Override finished_at for deterministic querying
    (db/execute! ["UPDATE sync_tasks SET finished_at = '2026-04-20T10:00:00' WHERE id = 'wb/old'"])
    (db/execute! ["UPDATE sync_tasks SET finished_at = '2026-04-22T00:00:00' WHERE id = 'wb/cutoff'"])
    (db/execute! ["UPDATE sync_tasks SET finished_at = '2026-04-23T12:00:00' WHERE id = 'wb/new'"])
    (let [results (reg/find-failed-since "2026-04-22T00:00:00")
          ids     (set (map :id results))]
      (is (= 2 (count results)))
      (is (contains? ids "wb/cutoff"))
      (is (contains? ids "wb/new"))
      (is (not (contains? ids "wb/old"))))))

(deftest reset-for-retry-preserves-attempts
  (testing "reset-for-retry clears error/timing but preserves attempt count"
    (reg/create-task! (minimal-spec "wb/prices/ingest"))
    (reg/set-running! "wb/prices/ingest")   ;; attempts -> 1
    (reg/record-error! "wb/prices/ingest" "timeout" "timed out")
    (let [reset-row (reg/reset-for-retry! "wb/prices/ingest")]
      (is (= "pending" (:status reset-row)) "status must be reset to pending")
      (is (= 1 (:attempts reset-row)) "attempts must be preserved (not cleared)")
      (is (nil? (:error-msg reset-row)))
      (is (nil? (:error-kind reset-row)))
      (is (nil? (:finished-at reset-row)))
      (is (nil? (:duration-ms reset-row)))
      (is (nil? (:started-at reset-row))))))
