(ns analitica.sync.plan-build-thunk-test
  "Unit tests for analitica.sync.plan/build-thunk-for-row."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.sync.plan :as plan]
            [analitica.ingest :as ingest]
            [analitica.materialize :as materialize]
            [analitica.db :as db])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; DB fixture (the fn under test does NOT use the DB but a few reqs load it)
;; ---------------------------------------------------------------------------

(defn- fresh-temp-db-path []
  (let [path (Files/createTempFile "analitica-build-thunk-test-" ".db"
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
;; Tests
;; ---------------------------------------------------------------------------

(deftest build-thunk-for-ingest-row
  (testing "phase=ingest → thunk calls ingest! with correct args"
    (let [called (atom nil)
          row    {:marketplace  "wb"
                  :entity-type  "sales"
                  :phase        "ingest"
                  :period-from  "2026-04-01"
                  :period-to    "2026-04-08"}
          thunk  (plan/build-thunk-for-row row)]
      (with-redefs [ingest/ingest! (fn [what & {:keys [marketplace period]}]
                                     (reset! called {:what what
                                                     :marketplace marketplace
                                                     :period period})
                                     42)]
        (let [result (thunk)]
          (is (= 42 result)
              "thunk returns the value from ingest!")
          (is (= {:what        :sales
                  :marketplace :wb
                  :period      ["2026-04-01" "2026-04-08"]}
                 @called)
              "ingest! called with correct keyword args"))))))

(deftest build-thunk-for-materialize-row
  (testing "phase=materialize → thunk calls materialize! with correct args"
    (let [called (atom nil)
          row    {:marketplace  "ozon"
                  :entity-type  "finance"
                  :phase        "materialize"
                  :period-from  "2026-03-01"
                  :period-to    "2026-03-31"}
          thunk  (plan/build-thunk-for-row row)]
      (with-redefs [materialize/materialize! (fn [what & {:keys [marketplace period]}]
                                               (reset! called {:what what
                                                               :marketplace marketplace
                                                               :period period})
                                               7)]
        (let [result (thunk)]
          (is (= 7 result)
              "thunk returns the value from materialize!")
          (is (= {:what        :finance
                  :marketplace :ozon
                  :period      ["2026-03-01" "2026-03-31"]}
                 @called)
              "materialize! called with correct keyword args"))))))

(deftest build-thunk-unknown-phase-throws
  (testing "unrecognised phase → throws ex-info"
    (let [row {:marketplace  "wb"
               :entity-type  "sales"
               :phase        "weird"
               :period-from  "2026-04-01"
               :period-to    "2026-04-08"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown phase"
                            (plan/build-thunk-for-row row))
          "should throw ExceptionInfo for unknown phase"))))
