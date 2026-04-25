(ns analitica.sync.executor-parallel-test
  "Phase 5 — DAG-aware parallel executor (run-parallel!)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.sync.registry :as reg]
            [analitica.sync.executor :as executor])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; DB fixture — mirrors executor_test.clj pattern
;; ---------------------------------------------------------------------------

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-executor-parallel-test-" ".db"
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

(def ^:private default-run-id "run-par-test")

(defn- make-task!
  ([id thunk] (make-task! id thunk []))
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
;; 1. trivial success
;; ---------------------------------------------------------------------------

(deftest run-parallel-success-trivial
  (testing "3 independent tasks, all return numbers, all end :ok"
    (let [tasks [(make-task! "t/p/a" (fn [] 1))
                 (make-task! "t/p/b" (fn [] 2))
                 (make-task! "t/p/c" (fn [] 3))]
          summary (executor/run-parallel! tasks)]
      (is (= 3 (:total summary)))
      (is (= 3 (:ok summary)))
      (is (= 0 (:failed summary)))
      (is (= 0 (:skipped summary)))
      (is (= "ok" (:status (reg/find-task "t/p/a"))))
      (is (= "ok" (:status (reg/find-task "t/p/b"))))
      (is (= "ok" (:status (reg/find-task "t/p/c")))))))

;; ---------------------------------------------------------------------------
;; 2. concurrency is real
;;    3 tasks each sleeping 200ms, with workers=4 → wall clock < 500ms
;; ---------------------------------------------------------------------------

(deftest run-parallel-witnessed-concurrency
  (testing "3 tasks × 200ms sleep with workers=4 finishes in < 500ms wall clock"
    (let [tasks [(make-task! "t/sleep/a" (fn [] (Thread/sleep 200) 1))
                 (make-task! "t/sleep/b" (fn [] (Thread/sleep 200) 1))
                 (make-task! "t/sleep/c" (fn [] (Thread/sleep 200) 1))]
          summary (executor/run-parallel! tasks :workers 4)]
      (is (= 3 (:ok summary)))
      ;; Sequential would be ≥600ms; parallel with 4 workers should finish
      ;; in roughly 200ms + overhead. Tolerant threshold of 500ms.
      (is (< (:duration-ms summary) 500)
          (str "Expected < 500ms wall clock for parallel execution, got "
               (:duration-ms summary) "ms — concurrency may be broken")))))

;; ---------------------------------------------------------------------------
;; 3. dep ordering — B depends on A; B must start after A finishes
;; ---------------------------------------------------------------------------

(deftest run-parallel-respects-deps
  (testing "B depends on A → B starts only after A's terminal status"
    (let [a-finished-at (atom nil)
          b-started-at  (atom nil)
          tasks [(make-task! "t/dep/a"
                              (fn []
                                (Thread/sleep 100)
                                (reset! a-finished-at (System/nanoTime))
                                1))
                 (make-task! "t/dep/b"
                              (fn []
                                (reset! b-started-at (System/nanoTime))
                                2)
                              ["t/dep/a"])]
          summary (executor/run-parallel! tasks :workers 4)]
      (is (= 2 (:ok summary)))
      (is (some? @a-finished-at) "A's thunk ran")
      (is (some? @b-started-at)  "B's thunk ran")
      (is (>= @b-started-at @a-finished-at)
          "B started AFTER A finished — DAG ordering enforced"))))

;; ---------------------------------------------------------------------------
;; 4. failed dep → dependent skipped
;; ---------------------------------------------------------------------------

(deftest run-parallel-skips-on-dep-failure
  (testing "A throws; B depends on A → B skipped, C independent → ok"
    (let [tasks [(make-task! "t/fail/a"
                              (fn [] (throw (ex-info "boom" {}))))
                 (make-task! "t/fail/b"
                              (fn [] 99)
                              ["t/fail/a"])
                 (make-task! "t/fail/c"
                              (fn [] 1))]
          summary (executor/run-parallel! tasks :workers 4)]
      (is (= "failed"  (:status (reg/find-task "t/fail/a"))))
      (is (= "skipped" (:status (reg/find-task "t/fail/b"))))
      (is (= "ok"      (:status (reg/find-task "t/fail/c"))))
      (is (= {:ok 1 :failed 1 :skipped 1}
             (select-keys summary [:ok :failed :skipped]))))))

;; ---------------------------------------------------------------------------
;; 5. summary counters with chained skip
;; ---------------------------------------------------------------------------

(deftest run-parallel-summary-counts
  (testing "5 tasks: 2 ok, 1 failed, 2 skipped (deps of failed)"
    (let [tasks [(make-task! "t/cnt/a" (fn [] 1))
                 (make-task! "t/cnt/b" (fn [] 2))
                 (make-task! "t/cnt/c" (fn [] (throw (ex-info "x" {}))))
                 (make-task! "t/cnt/d" (fn [] 3) ["t/cnt/c"])
                 (make-task! "t/cnt/e" (fn [] 4) ["t/cnt/c"])]
          summary (executor/run-parallel! tasks :workers 4)]
      (is (= {:total 5 :ok 2 :failed 1 :skipped 2}
             (select-keys summary [:total :ok :failed :skipped]))))))

;; ---------------------------------------------------------------------------
;; 6. empty plan
;; ---------------------------------------------------------------------------

(deftest run-parallel-empty-plan
  (testing "empty plan returns zero counters without error"
    (let [summary (executor/run-parallel! [])]
      (is (= {:total 0 :ok 0 :failed 0 :skipped 0}
             (select-keys summary [:total :ok :failed :skipped])))
      (is (>= (:duration-ms summary) 0)))))
