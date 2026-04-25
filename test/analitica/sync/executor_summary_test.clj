(ns analitica.sync.executor-summary-test
  "Unit tests for analitica.sync.executor/run-summary and recent-runs (Phase 4)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.sync.registry :as reg]
            [analitica.sync.executor :as executor])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; DB fixture (mirrors executor_test.clj)
;; ---------------------------------------------------------------------------

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-exec-summary-test-"
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
      (db/init!)
      (f)
      (finally
        (alter-var-root #'db/db-spec (constantly orig-spec))
        (reset! @#'db/datasource nil)
        (delete-test-db! path)))))

(use-fixtures :each with-temp-db)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-task!
  "Create a task row in the registry for tests."
  [id run-id & {:keys [marketplace entity-type phase]
                :or   {marketplace "wb" entity-type "sales" phase "ingest"}}]
  (reg/create-task! {:id          id
                     :run-id      run-id
                     :marketplace marketplace
                     :entity-type entity-type
                     :phase       phase}))

;; ---------------------------------------------------------------------------
;; 1. run-summary-empty-run — unknown run-id returns nil
;; ---------------------------------------------------------------------------

(deftest run-summary-empty-run
  (testing "unknown run-id returns nil (so route can 404)"
    (is (nil? (executor/run-summary "nonexistent-run-id-xyz")))))

;; ---------------------------------------------------------------------------
;; 2. run-summary-all-pending — status="running" while tasks are pending
;; ---------------------------------------------------------------------------

(deftest run-summary-all-pending
  (testing "all-pending tasks → top-level status 'running'"
    (let [run-id "run-pending-001"]
      (make-task! "run-pending-001/wb/sales/ingest"   run-id)
      (make-task! "run-pending-001/wb/orders/ingest"  run-id)
      (make-task! "run-pending-001/wb/finance/ingest" run-id)
      (let [summary (executor/run-summary run-id)]
        (is (some? summary))
        (is (= 3 (:total summary)))
        (is (= "running" (:status summary))
            "pending tasks mean the run can still progress → 'running'")
        (is (= 3 (count (:tasks summary))))))))

;; ---------------------------------------------------------------------------
;; 3. run-summary-all-ok — all ok → status="done", finished-at = max
;; ---------------------------------------------------------------------------

(deftest run-summary-all-ok
  (testing "all tasks ok → status='done', finished-at is populated"
    (let [run-id "run-ok-001"]
      (doseq [id ["run-ok-001/a/ingest" "run-ok-001/b/ingest" "run-ok-001/c/ingest"]]
        (make-task! id run-id)
        (reg/set-running! id)
        (reg/record-success! id 42))
      (let [summary (executor/run-summary run-id)]
        (is (= "done" (:status summary)))
        (is (= 3 (:total summary)))
        (is (some? (:finished-at summary))
            "finished-at should be set when all tasks are terminal")
        (is (every? #(= "ok" (:status %)) (:tasks summary)))))))

;; ---------------------------------------------------------------------------
;; 4. run-summary-mixed-failure — 2 ok + 1 failed → status="failed"
;; ---------------------------------------------------------------------------

(deftest run-summary-mixed-failure
  (testing "2 ok + 1 failed → top-level status 'failed'"
    (let [run-id "run-fail-001"]
      (doseq [id ["run-fail-001/a/ingest" "run-fail-001/b/ingest"]]
        (make-task! id run-id)
        (reg/set-running! id)
        (reg/record-success! id 10))
      (let [fail-id "run-fail-001/c/ingest"]
        (make-task! fail-id run-id)
        (reg/set-running! fail-id)
        (reg/record-error! fail-id "test-error" "boom"))
      (let [summary (executor/run-summary run-id)]
        (is (= "failed" (:status summary)))
        (is (= 3 (:total summary)))
        (is (some? (:finished-at summary)))))))

;; ---------------------------------------------------------------------------
;; 5. run-summary-started-at-earliest — started-at = min across all tasks
;; ---------------------------------------------------------------------------

(deftest run-summary-started-at-earliest
  (testing "top-level started-at is the earliest among tasks"
    (let [run-id "run-start-001"]
      ;; Insert three tasks and manually set started_at values
      (doseq [id ["run-start-001/a" "run-start-001/b" "run-start-001/c"]]
        (make-task! id run-id))
      ;; Drive them to running (sets started_at to now) then success
      ;; We use Thread/sleep 1s between to get distinct timestamps, but
      ;; that's too slow for a unit test. Instead we manipulate directly:
      (db/execute! ["UPDATE sync_tasks SET status='running', started_at='2026-04-24T10:01:00', attempts=1 WHERE id='run-start-001/b'"])
      (db/execute! ["UPDATE sync_tasks SET status='ok', started_at='2026-04-24T10:00:00', finished_at='2026-04-24T10:00:05', attempts=1 WHERE id='run-start-001/a'"])
      (db/execute! ["UPDATE sync_tasks SET status='ok', started_at='2026-04-24T10:02:00', finished_at='2026-04-24T10:02:05', attempts=1 WHERE id='run-start-001/c'"])
      (let [summary (executor/run-summary run-id)]
        (is (= "2026-04-24T10:00:00" (:started-at summary))
            "started-at should be the earliest among all tasks")))))

;; ---------------------------------------------------------------------------
;; 6. run-summary-tasks-ordered-by-id — returned tasks ordered by id ASC
;; ---------------------------------------------------------------------------

(deftest run-summary-tasks-ordered-by-id
  (testing "tasks in summary are ordered by id ASC regardless of insert order"
    (let [run-id "run-order-001"]
      ;; Insert in reverse order
      (make-task! "run-order-001/wb/stocks/ingest"   run-id)
      (make-task! "run-order-001/wb/finance/ingest"  run-id)
      (make-task! "run-order-001/wb/orders/ingest"   run-id)
      (let [summary  (executor/run-summary run-id)
            task-ids (mapv :id (:tasks summary))]
        (is (= task-ids (sort task-ids))
            "task list must be sorted by id ASC")))))
