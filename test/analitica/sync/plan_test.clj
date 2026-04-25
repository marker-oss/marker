(ns analitica.sync.plan-test
  "Unit tests for analitica.sync.plan — pure planning logic."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.sync.registry :as reg]
            [analitica.sync.plan :as plan])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; DB fixture — mirrors runner_test.clj pattern
;; ---------------------------------------------------------------------------

(def ^:dynamic *test-db-path* nil)

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-plan-test-"
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

(defn- unique-run-id []
  (str "test-run-" (System/nanoTime)))

;; ---------------------------------------------------------------------------
;; 1. expand-plan-all-all
;;    :all × :all → 42 tasks (WB=8, Ozon=7, YM=6 pairs × 2 phases)
;; ---------------------------------------------------------------------------

(deftest expand-plan-all-all
  (testing ":what :all :marketplace :all yields 42 tasks total"
    (let [run-id (unique-run-id)
          tasks  (plan/expand-plan :run-id run-id
                                   :what :all
                                   :marketplace :all
                                   :period :last-30-days)]
      ;; WB: 8 pairs, Ozon: 7 pairs, YM: 6 pairs → 21 pairs × 2 phases = 42
      (is (= 42 (count tasks))
          (str "Expected 42 tasks, got " (count tasks)
               "\nTypes per mp: "
               {:wb   (count (filter #(= :wb (:marketplace %)) tasks))
                :ozon (count (filter #(= :ozon (:marketplace %)) tasks))
                :ym   (count (filter #(= :ym (:marketplace %)) tasks))}))
      ;; All tasks share the same run-id
      (is (every? #(= run-id (:run-id %)) tasks))
      ;; All period-from/to are populated
      (is (every? (comp some? :period-from) tasks))
      (is (every? (comp some? :period-to) tasks))
      ;; Exactly half are ingest, half materialize
      (let [by-phase (group-by :phase tasks)]
        (is (= 21 (count (:ingest by-phase))))
        (is (= 21 (count (:materialize by-phase))))))))

;; ---------------------------------------------------------------------------
;; 2. expand-plan-one-mp
;;    :all × :wb → 8 types × 2 phases = 16 tasks
;; ---------------------------------------------------------------------------

(deftest expand-plan-one-mp
  (testing ":what :all :marketplace :wb yields 16 tasks (8 types × 2 phases)"
    (let [run-id (unique-run-id)
          tasks  (plan/expand-plan :run-id run-id
                                   :what :all
                                   :marketplace :wb
                                   :period :last-30-days)]
      (is (= 16 (count tasks)))
      ;; All tasks are for :wb
      (is (every? #(= :wb (:marketplace %)) tasks))
      ;; 8 entity types present
      (let [types (->> tasks (map :entity-type) set)]
        (is (= #{:sales :orders :finance :stocks :prices :stats :storage :regions} types))))))

;; ---------------------------------------------------------------------------
;; 3. expand-plan-one-type
;;    :sales × :all → 3 mps × 2 phases = 6 tasks
;; ---------------------------------------------------------------------------

(deftest expand-plan-one-type
  (testing ":what :sales :marketplace :all yields 6 tasks (3 MPs × 2 phases)"
    (let [run-id (unique-run-id)
          tasks  (plan/expand-plan :run-id run-id
                                   :what :sales
                                   :marketplace :all
                                   :period :last-30-days)]
      (is (= 6 (count tasks)))
      ;; All are sales
      (is (every? #(= :sales (:entity-type %)) tasks))
      ;; All 3 MPs present
      (let [mps (->> tasks (map :marketplace) set)]
        (is (= #{:wb :ozon :ym} mps))))))

;; ---------------------------------------------------------------------------
;; 4. expand-plan-incompatible-skipped
;;    :storage × :ozon → 0 tasks (incompatible pair)
;; ---------------------------------------------------------------------------

(deftest expand-plan-incompatible-skipped
  (testing ":what :storage :marketplace :ozon yields empty plan (incompatible)"
    (let [run-id (unique-run-id)
          tasks  (plan/expand-plan :run-id run-id
                                   :what :storage
                                   :marketplace :ozon
                                   :period :last-30-days)]
      (is (empty? tasks))))

  (testing ":what :regions :marketplace :ozon yields empty plan (incompatible)"
    (let [run-id (unique-run-id)
          tasks  (plan/expand-plan :run-id run-id
                                   :what :regions
                                   :marketplace :ozon
                                   :period :last-30-days)]
      (is (empty? tasks))))

  (testing ":what :cashflow :marketplace :wb yields empty plan (incompatible)"
    (let [run-id (unique-run-id)
          tasks  (plan/expand-plan :run-id run-id
                                   :what :cashflow
                                   :marketplace :wb
                                   :period :last-30-days)]
      (is (empty? tasks))))

  (testing ":what :cashflow :marketplace :ym yields empty plan (incompatible)"
    (let [run-id (unique-run-id)
          tasks  (plan/expand-plan :run-id run-id
                                   :what :cashflow
                                   :marketplace :ym
                                   :period :last-30-days)]
      (is (empty? tasks)))))

;; ---------------------------------------------------------------------------
;; 5. expand-plan-materialize-deps-on-ingest
;;    Each materialize task's :depends-on contains exactly its ingest sibling.
;; ---------------------------------------------------------------------------

(deftest expand-plan-materialize-deps-on-ingest
  (testing "every materialize task depends on exactly its matching ingest task"
    (let [run-id (unique-run-id)
          tasks  (plan/expand-plan :run-id run-id
                                   :what :all
                                   :marketplace :all
                                   :period :last-30-days)
          mats   (filter #(= :materialize (:phase %)) tasks)]
      ;; Every materialize task has exactly one dep
      (is (every? #(= 1 (count (:depends-on %))) mats))
      ;; The dep is the matching ingest task id
      (doseq [mat mats]
        (let [mp        (:marketplace mat)
              etype     (:entity-type mat)
              ingest-id (str (name mp) "/" (name etype) "/ingest")
              dep-id    (first (:depends-on mat))]
          (is (= ingest-id dep-id)
              (str "Materialize task " (:id mat)
                   " should depend on " ingest-id
                   " but depends on " dep-id))))
      ;; All ingest tasks have no deps
      (let [ingest-tasks (filter #(= :ingest (:phase %)) tasks)]
        (is (every? #(empty? (:depends-on %)) ingest-tasks))))))

;; ---------------------------------------------------------------------------
;; 6. persist-plan-creates-rows
;;    persist-plan! inserts N rows with status='pending', items=nil, attempts=0
;; ---------------------------------------------------------------------------

(deftest persist-plan-creates-rows
  (testing "persist-plan! creates registry rows with expected defaults"
    (let [run-id   (unique-run-id)
          ;; Use a small plan for speed — just wb/sales
          tasks    (plan/expand-plan :run-id run-id
                                     :what :sales
                                     :marketplace :wb
                                     :period :last-30-days)
          returned (plan/persist-plan! tasks)
          rows     (reg/find-tasks-for-run run-id)]
      ;; persist-plan! returns the original plan unchanged
      (is (= tasks returned))
      ;; Correct number of rows created
      (is (= (count tasks) (count rows))
          (str "Expected " (count tasks) " rows, got " (count rows)))
      ;; All rows have status=pending
      (is (every? #(= "pending" (:status %)) rows))
      ;; All rows have items=nil (not yet run)
      (is (every? #(nil? (:items %)) rows))
      ;; All rows have attempts=0
      (is (every? #(= 0 (:attempts %)) rows))
      ;; period-from and period-to are populated
      (is (every? (comp some? :period-from) rows))
      (is (every? (comp some? :period-to) rows)))))
