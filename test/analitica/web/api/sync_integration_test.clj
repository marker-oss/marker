(ns analitica.web.api.sync-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.web.api.sync :as sync-api])
  (:import [java.util.concurrent TimeUnit]))

(use-fixtures :each
  (fn [f]
    ;; Reset state before each test
    (reset! sync-api/sync-running? false)
    (reset! sync-api/progress-channel nil)
    (f)
    ;; Wait for any background threads to complete
    (Thread/sleep 300)
    ;; Reset state after each test
    (reset! sync-api/sync-running? false)
    (reset! sync-api/progress-channel nil)))

(deftest test-sync-output-capture
  (testing "Sync creates progress channel and captures events"
    (sync-api/start-sync! :1c)
    
    ;; Wait a bit for sync to start
    (Thread/sleep 200)
    
    (let [queue @sync-api/progress-channel]
      (is (some? queue) "Progress channel should be created")
      (is (instance? java.util.concurrent.LinkedBlockingQueue queue)
          "Progress channel should be a LinkedBlockingQueue")
      
      ;; Try to read events from queue
      (when queue
        (let [events (loop [acc []]
                       (if-let [event (.poll queue 100 TimeUnit/MILLISECONDS)]
                         (recur (conj acc event))
                         acc))]
          ;; Should have at least one event (either message, done, or error)
          (is (seq events) "Should have captured some events")
          (is (every? #(contains? #{:message :done :error} (:type %)) events)
              "All events should have valid types"))))))

(deftest test-sync-error-handling
  (testing "Sync with unrecognised marketplace produces empty plan and completes cleanly"
    ;; Phase 8: :invalid-mp is not in mp-entity-matrix → expand-plan returns [] →
    ;; executor is called with empty plan → :done event is emitted (not :error).
    ;; The test previously expected :error because the old code called ingest! directly
    ;; which threw. The new plan-based path silently produces an empty plan instead.
    (sync-api/start-sync! :stocks :marketplace :invalid-mp)

    ;; Wait for the empty run to complete
    (Thread/sleep 500)

    (let [queue @sync-api/progress-channel]
      (when queue
        (let [events (loop [acc []]
                       (if-let [event (.poll queue 100 TimeUnit/MILLISECONDS)]
                         (recur (conj acc event))
                         acc))]
          ;; Empty plan → :done event, no :error
          (is (some #(= :done (:type %)) events) "Should have a :done event for empty plan")
          (is (not (some #(= :error (:type %)) events)) "Should NOT have an :error event for empty plan"))))

    ;; Verify running flag is reset after completion
    (is (false? @sync-api/sync-running?)
        "sync-running? should be false after completion")))

(deftest test-sync-with-different-parameters
  (testing "Sync accepts various parameter combinations"
    ;; Test 1: Just what
    (let [r1 (sync-api/start-sync! :1c)]
      (is (= {:ok true} r1) "Should accept just 'what' parameter"))
    (Thread/sleep 300)
    (reset! sync-api/sync-running? false)
    
    ;; Test 2: what + period (note: :1c doesn't use period, but API should accept it)
    (let [r2 (sync-api/start-sync! :1c :last-7-days)]
      (is (= {:ok true} r2) "Should accept 'what' + period"))
    (Thread/sleep 300)
    (reset! sync-api/sync-running? false)
    
    ;; Test 3: what + marketplace (note: :1c doesn't use marketplace, but API should accept it)
    (let [r3 (sync-api/start-sync! :1c :marketplace :wb)]
      (is (= {:ok true} r3) "Should accept 'what' + marketplace"))
    (Thread/sleep 300)
    (reset! sync-api/sync-running? false)))

