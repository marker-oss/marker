(ns analitica.sync.executor-test
  "Unit tests for analitica.sync.executor — sequential execution with dep-skipping."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.sync.registry :as reg]
            [analitica.sync.executor :as executor])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; DB fixture — mirrors registry_test.clj pattern
;; ---------------------------------------------------------------------------

(def ^:dynamic *test-db-path* nil)

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-executor-test-"
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

(def ^:private default-run-id "run-exec-test")

(defn- make-task!
  "Create a minimal task row in the registry and return the descriptor map
   with a :thunk for use by the executor."
  ([id thunk]
   (make-task! id thunk []))
  ([id thunk depends-on]
   (reg/create-task! {:id          id
                      :run-id      default-run-id
                      :marketplace "wb"
                      :entity-type "sales"
                      :phase       "ingest"
                      :depends-on  (seq depends-on)})
   {:id          id
    :run-id      default-run-id
    :marketplace :wb
    :entity-type :sales
    :phase       :ingest
    :depends-on  depends-on
    :thunk       thunk}))

;; ---------------------------------------------------------------------------
;; 1. run-sequential-success
;;    Two tasks, both return numbers → :ok 2, :failed 0, :skipped 0
;; ---------------------------------------------------------------------------

(deftest run-sequential-success
  (testing "two successful tasks → summary ok=2, failed=0, skipped=0"
    (let [task-a (make-task! "t/a/ingest" (fn [] 10))
          task-b (make-task! "t/b/ingest" (fn [] 20))
          summary (executor/run-sequential! [task-a task-b])]
      (is (= 2 (:total summary)))
      (is (= 2 (:ok summary)))
      (is (= 0 (:failed summary)))
      (is (= 0 (:skipped summary)))
      ;; Registry rows should be ok
      (is (= "ok" (:status (reg/find-task "t/a/ingest"))))
      (is (= "ok" (:status (reg/find-task "t/b/ingest")))))))

;; ---------------------------------------------------------------------------
;; 2. run-sequential-one-fails
;;    Three tasks: A ok, B throws, C ok → ok=2, failed=1
;; ---------------------------------------------------------------------------

(deftest run-sequential-one-fails
  (testing "plan with one failing task → summary ok=2, failed=1"
    (let [task-a (make-task! "t/a2/ingest" (fn [] 5))
          task-b (make-task! "t/b2/ingest" (fn [] (throw (ex-info "boom" {}))))
          task-c (make-task! "t/c2/ingest" (fn [] 7))
          summary (executor/run-sequential! [task-a task-b task-c])]
      (is (= 3 (:total summary)))
      (is (= 2 (:ok summary)))
      (is (= 1 (:failed summary)))
      (is (= 0 (:skipped summary)))
      ;; All rows should have terminal status
      (is (= "ok"     (:status (reg/find-task "t/a2/ingest"))))
      (is (= "failed" (:status (reg/find-task "t/b2/ingest"))))
      (is (= "ok"     (:status (reg/find-task "t/c2/ingest")))))))

;; ---------------------------------------------------------------------------
;; 3. run-sequential-skip-on-dep-failure
;;    A throws, B depends on A → B skipped with reason, C independent → ok
;; ---------------------------------------------------------------------------

(deftest run-sequential-skip-on-dep-failure
  (testing "dep failure causes dependent task to be skipped"
    (let [task-a (make-task! "t/a3/ingest" (fn [] (throw (ex-info "upstream error" {}))))
          task-b (make-task! "t/b3/materialize" (fn [] 99) ["t/a3/ingest"])
          task-c (make-task! "t/c3/ingest" (fn [] 1))
          summary (executor/run-sequential! [task-a task-b task-c])]
      (is (= 3 (:total summary)))
      (is (= 1 (:ok summary)))
      (is (= 1 (:failed summary)))
      (is (= 1 (:skipped summary)))
      ;; A should be failed
      (is (= "failed" (:status (reg/find-task "t/a3/ingest"))))
      ;; B should be skipped with "dependency failed:" reason
      (let [b-row (reg/find-task "t/b3/materialize")]
        (is (= "skipped" (:status b-row)))
        (is (some? (:error-msg b-row)))
        (is (.contains (:error-msg b-row) "dependency failed")))
      ;; C (independent) should be ok
      (is (= "ok" (:status (reg/find-task "t/c3/ingest")))))))

;; ---------------------------------------------------------------------------
;; 4. run-sequential-summary-duration
;;    Plan with a task that sleeps 100ms → :duration-ms >= 100
;; ---------------------------------------------------------------------------

(deftest run-sequential-summary-duration
  (testing "summary :duration-ms reflects actual wall-clock time"
    (let [task-a (make-task! "t/a4/ingest" (fn [] (Thread/sleep 100) 1))
          summary (executor/run-sequential! [task-a])]
      (is (>= (:duration-ms summary) 100)
          (str "Expected duration >= 100ms, got " (:duration-ms summary))))))

;; ---------------------------------------------------------------------------
;; 5. run-sequential-honors-existing-status
;;    Task already has status='ok' → not re-run (thunk not invoked)
;; ---------------------------------------------------------------------------

(deftest run-sequential-honors-existing-status
  (testing "task with terminal status is not re-run"
    (let [call-count (atom 0)
          ;; Pre-create the task and transition it to ok directly
          task-id    "t/a5/ingest"
          _          (reg/create-task! {:id          task-id
                                        :run-id      default-run-id
                                        :marketplace "wb"
                                        :entity-type "sales"
                                        :phase       "ingest"})
          ;; Manually drive it to ok state
          _          (reg/set-running! task-id)
          _          (reg/record-success! task-id 42)
          ;; Build plan descriptor with a side-effect thunk
          task       {:id          task-id
                      :run-id      default-run-id
                      :marketplace :wb
                      :entity-type :sales
                      :phase       :ingest
                      :depends-on  []
                      :thunk       (fn [] (swap! call-count inc) 99)}
          summary    (executor/run-sequential! [task])]
      ;; Thunk should NOT have been called
      (is (= 0 @call-count) "thunk must not be invoked for already-terminal task")
      ;; Row should still be 'ok' (not re-run)
      (is (= "ok" (:status (reg/find-task task-id))))
      ;; Summary counts: task is neither ok/failed/skipped in this run
      ;; (it was skipped by the idempotency guard, so total=1, ok=0, failed=0, skipped=0)
      (is (= 1 (:total summary))))))

;; ---------------------------------------------------------------------------
;; 6. run-sequential-empty-plan
;;    Empty plan returns valid summary with zeros, no errors
;; ---------------------------------------------------------------------------

(deftest run-sequential-empty-plan
  (testing "empty plan returns valid summary with all zeros"
    (let [summary (executor/run-sequential! [])]
      (is (= 0 (:total summary)))
      (is (= 0 (:ok summary)))
      (is (= 0 (:failed summary)))
      (is (= 0 (:skipped summary)))
      (is (>= (:duration-ms summary) 0)))))
