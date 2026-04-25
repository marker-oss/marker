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
  (testing ":what :all :marketplace :all yields 21 (mp,type) groups, with WB storage chunked"
    (let [run-id (unique-run-id)
          tasks  (plan/expand-plan :run-id run-id
                                   :what :all
                                   :marketplace :all
                                   :period :last-30-days)
          by-phase (group-by :phase tasks)]
      ;; 21 (mp, type) groups × 1 materialize = 21 materialize tasks total
      (is (= 21 (count (:materialize by-phase))))
      ;; Ingest count = 20 non-storage + N storage-chunks (~5 for 30 days).
      ;; All tasks share the same run-id
      (is (every? #(= run-id (:run-id %)) tasks))
      (is (every? (comp some? :period-from) tasks))
      (is (every? (comp some? :period-to) tasks))
      ;; WB storage gets multiple ingest chunks; the materialize is single.
      (let [wb-storage-ingest (filter #(and (= :wb (:marketplace %))
                                            (= :storage (:entity-type %))
                                            (= :ingest (:phase %)))
                                      tasks)]
        (is (>= (count wb-storage-ingest) 4)
            "WB storage ingest is split into weekly chunks (≥4 for 30 days)")))))

;; ---------------------------------------------------------------------------
;; 2. expand-plan-one-mp
;;    :all × :wb → 8 types × 2 phases = 16 tasks
;; ---------------------------------------------------------------------------

(deftest expand-plan-one-mp
  (testing ":what :all :marketplace :wb covers 8 types; storage ingest is chunked"
    (let [run-id (unique-run-id)
          tasks  (plan/expand-plan :run-id run-id
                                   :what :all
                                   :marketplace :wb
                                   :period :last-30-days)
          by-phase (group-by :phase tasks)]
      ;; All tasks are for :wb
      (is (every? #(= :wb (:marketplace %)) tasks))
      ;; 8 entity types present
      (let [types (->> tasks (map :entity-type) set)]
        (is (= #{:sales :orders :finance :stocks :prices :stats :storage :regions} types)))
      ;; 8 materialize tasks (one per type)
      (is (= 8 (count (:materialize by-phase))))
      ;; >=12 ingest tasks (7 single + ≥5 storage chunks)
      (is (>= (count (:ingest by-phase)) 12)))))

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
  (testing "every materialize task depends on its matching ingest task(s)"
    (let [run-id (unique-run-id)
          tasks  (plan/expand-plan :run-id run-id
                                   :what :all
                                   :marketplace :all
                                   :period :last-30-days)
          mats   (filter #(= :materialize (:phase %)) tasks)
          ingests (filter #(= :ingest (:phase %)) tasks)
          ingest-ids (set (map :id ingests))]
      ;; Most materialize tasks have exactly 1 dep; WB storage has many
      ;; (one per weekly chunk). Each dep must be a real ingest task.
      (doseq [mat mats]
        (is (pos? (count (:depends-on mat)))
            (str "Materialize " (:id mat) " should have at least one dep"))
        (doseq [dep-id (:depends-on mat)]
          (is (contains? ingest-ids dep-id)
              (str "Materialize " (:id mat) " has dep " dep-id
                   " that is not an ingest task in this plan"))))
      ;; Any (mp, type) pair listed in plan/chunk-days now produces multi-
      ;; dep materialize tasks when the period exceeds the chunk size.
      ;; WB storage is always chunked (7-day windows). Other chunked
      ;; pairs may or may not depending on the period length, so we
      ;; only assert about WB storage here.
      (let [storage-mat (first (filter #(and (= :storage (:entity-type %))
                                             (= :wb (:marketplace %)))
                                       mats))]
        (is (>= (count (:depends-on storage-mat)) 4)
            "WB storage materialize depends on ≥4 chunked ingest tasks"))
      ;; Pairs that are NEVER chunked still have exactly 1 dep.
      (let [never-chunked
            (filter (fn [m]
                      (let [k [(:marketplace m) (:entity-type m)]]
                        (not (contains? #{[:wb :storage] [:wb :finance]
                                          [:wb :regions] [:ym :finance]} k))))
                    mats)]
        (is (every? #(= 1 (count (:depends-on %))) never-chunked)))
      ;; All ingest tasks have no deps
      (is (every? #(empty? (:depends-on %)) ingests)))))

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

;; ---------------------------------------------------------------------------
;; 7. expand-plan-max-attempts
;;    Phase 6: ingest tasks get :max-attempts 3, materialize tasks get :max-attempts 1
;; ---------------------------------------------------------------------------

(deftest expand-plan-max-attempts
  (testing "ingest tasks carry :max-attempts 3; materialize tasks carry :max-attempts 1"
    (let [run-id (unique-run-id)
          tasks  (plan/expand-plan :run-id run-id
                                   :what :sales
                                   :marketplace :all
                                   :period :last-30-days)
          ingest-tasks     (filter #(= :ingest (:phase %)) tasks)
          materialize-tasks (filter #(= :materialize (:phase %)) tasks)]
      ;; All ingest tasks should have :max-attempts 3
      (is (pos? (count ingest-tasks)) "should have at least 1 ingest task")
      (is (every? #(= 3 (:max-attempts %)) ingest-tasks)
          "all ingest tasks must have :max-attempts 3")
      ;; All materialize tasks should have :max-attempts 1
      (is (pos? (count materialize-tasks)) "should have at least 1 materialize task")
      (is (every? #(= 1 (:max-attempts %)) materialize-tasks)
          "all materialize tasks must have :max-attempts 1")))

  (testing "persist-plan! writes max_attempts correctly to DB"
    (let [run-id (unique-run-id)
          tasks  (plan/expand-plan :run-id run-id
                                   :what :orders
                                   :marketplace :wb
                                   :period :last-30-days)
          _      (plan/persist-plan! tasks)
          rows   (reg/find-tasks-for-run run-id)]
      (let [ingest-rows     (filter #(= "ingest" (:phase %)) rows)
            materialize-rows (filter #(= "materialize" (:phase %)) rows)]
        (is (every? #(= 3 (:max-attempts %)) ingest-rows)
            "ingest rows in DB must have max_attempts=3")
        (is (every? #(= 1 (:max-attempts %)) materialize-rows)
            "materialize rows in DB must have max_attempts=1")))))

;; ---------------------------------------------------------------------------
;; 8. chunk-spec table per (mp, type)
;;    Phase 9 follow-up: WB finance/regions and YM finance get window-chunked
;;    so single-task duration stays bounded and the regions endpoint's
;;    30-day window cap is respected.
;; ---------------------------------------------------------------------------

(deftest chunk-spec-wb-finance-30-days
  (testing "WB finance: 90-day window splits into ~3 monthly chunks"
    (let [chunks (plan/chunk-spec :wb :finance "2026-02-01" "2026-04-30")]
      (is (>= (count chunks) 3)
          "≥3 chunks for ~90 days at 30-day chunk size")
      (is (every? vector? chunks))
      (is (every? #(= 2 (count %)) chunks)
          "each chunk is a [from to] pair"))))

(deftest chunk-spec-wb-regions-30-days
  (testing "WB regions: 84-day window splits — guards against the 400 we saw on long ranges"
    (let [chunks (plan/chunk-spec :wb :regions "2026-02-01" "2026-04-25")]
      (is (>= (count chunks) 3)))))

(deftest chunk-spec-ym-finance-30-days
  (testing "YM finance: long window splits to keep /stats/orders responses bounded"
    (let [chunks (plan/chunk-spec :ym :finance "2026-02-01" "2026-04-25")]
      (is (>= (count chunks) 3)))))

(deftest chunk-spec-non-chunked-pairs
  (testing "Pairs not in chunk-days table return single-element vector (no chunking)"
    (doseq [[mp etype] [[:wb :sales] [:wb :orders] [:wb :stocks] [:wb :prices]
                        [:wb :stats] [:ozon :sales] [:ozon :transactions]
                        [:ym :sales] [:ym :stocks]]]
      (is (= 1 (count (plan/chunk-spec mp etype "2026-02-01" "2026-04-25")))
          (str (name mp) "/" (name etype) " should not be chunked")))))

(deftest expand-plan-wb-finance-chunked-on-long-period
  (testing ":what :finance :marketplace :wb :period 90 days → multiple ingest chunks + 1 materialize"
    (let [run-id (unique-run-id)
          tasks  (plan/expand-plan :run-id run-id
                                   :what :finance
                                   :marketplace :wb
                                   :period {:from "2026-02-01" :to "2026-04-30"})
          ingest-tasks     (filter #(= :ingest (:phase %)) tasks)
          materialize-tasks (filter #(= :materialize (:phase %)) tasks)]
      (is (>= (count ingest-tasks) 3)
          "WB finance ingest splits into ≥3 monthly chunks")
      (is (= 1 (count materialize-tasks))
          "Single materialize task with multi-dep")
      (is (= (count ingest-tasks)
             (count (:depends-on (first materialize-tasks))))
          "Materialize depends on ALL ingest chunks"))))
