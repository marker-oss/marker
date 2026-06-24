(ns analitica.web.api.sync-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.web.api.sync :as sync-api]
            [analitica.sync.plan :as plan]
            [analitica.sync.executor :as executor])
  (:import [java.time LocalDateTime]))

(use-fixtures :each
  (fn [f]
    ;; Reset state before each test
    (reset! sync-api/sync-running? false)
    (reset! sync-api/progress-channel nil)
    (f)
    ;; Wait for any background threads to complete
    (Thread/sleep 200)
    ;; Reset state after each test
    (reset! sync-api/sync-running? false)
    (reset! sync-api/progress-channel nil)))

(deftest test-sync-state-initialization
  (testing "Sync state atoms exist and can be accessed"
    (is (boolean? @sync-api/sync-running?)
        "sync-running? should be a boolean")
    (is (or (nil? @sync-api/progress-channel)
            (instance? java.util.concurrent.LinkedBlockingQueue @sync-api/progress-channel))
        "progress-channel should be nil or a LinkedBlockingQueue")))

(deftest test-start-sync-returns-ok
  (testing "start-sync! returns {:ok true} when starting"
    (let [result (sync-api/start-sync! :1c)]
      (is (= {:ok true} result)
          "Sync should start successfully and return {:ok true}"))))

(deftest test-start-sync-concurrent-protection
  (testing "start-sync! prevents concurrent execution"
    ;; First call should succeed
    (let [result1 (sync-api/start-sync! :1c)]
      (is (= {:ok true} result1)
          "First sync should start successfully"))
    
    ;; Immediately try second call - should fail while first is running
    (let [result2 (sync-api/start-sync! :1c)]
      (is (= {:error "already running"} result2)
          "Second sync should be rejected with error"))))

(deftest test-start-sync-creates-progress-channel
  (testing "start-sync! creates a progress channel"
    (sync-api/start-sync! :1c)
    (is (instance? java.util.concurrent.LinkedBlockingQueue @sync-api/progress-channel)
        "progress-channel should be a LinkedBlockingQueue after starting sync")))

(deftest test-start-sync-sets-running-flag
  (testing "start-sync! sets sync-running? flag"
    (sync-api/start-sync! :1c)
    (is (true? @sync-api/sync-running?)
        "sync-running? should be true after starting sync")))

(deftest test-sse-stream-returns-correct-headers
  (testing "sse-stream returns correct SSE headers"
    (let [response (sync-api/sse-stream {})]
      (is (= 200 (:status response))
          "Response status should be 200")
      (is (= "text/event-stream" (get-in response [:headers "Content-Type"]))
          "Content-Type should be text/event-stream")
      (is (= "no-cache" (get-in response [:headers "Cache-Control"]))
          "Cache-Control should be no-cache")
      (is (= "no" (get-in response [:headers "X-Accel-Buffering"]))
          "X-Accel-Buffering should be no"))))

(deftest test-sse-stream-with-events
  (testing "sse-stream sends events from progress channel"
    (let [queue (java.util.concurrent.LinkedBlockingQueue. 100)]
      ;; Set up progress channel with test events
      (reset! sync-api/progress-channel queue)
      (.offer queue {:type :message :text "Test message"})
      (.offer queue {:type :done})

      ;; Get SSE response
      (let [response   (sync-api/sse-stream {})
            body       (:body response)
            output     (java.io.ByteArrayOutputStream.)
            _          (ring.core.protocols/write-body-to-stream body response output)
            output-str (.toString output "UTF-8")]

        (is (re-find #"event: message" output-str)
            "Output should contain message event")
        (is (re-find #"data: Test message" output-str)
            "Output should contain message data")
        (is (re-find #"event: done" output-str)
            "Output should contain done event")))))

;; ---------------------------------------------------------------------------
;; Phase 8 — new tests: start-sync! plan-based path
;; ---------------------------------------------------------------------------

(deftest start-sync-returns-run-id
  (testing "start-sync! for non-:1c what returns {:ok true :run-id <uuid>}"
    ;; Mock persist-plan! (no DB needed) and executor (fast return).
    ;; The thunks in the plan call ingest!/materialize! which are also mocked
    ;; via the :thunk fns produced by plan/expand-plan — we intercept at the
    ;; executor level so we never invoke them.
    (let [persisted-tasks (atom [])
          executor-plans  (atom [])]
      (with-redefs [plan/persist-plan!       (fn [p] (reset! persisted-tasks p) p)
                    executor/run-parallel!   (fn [p & _] (reset! executor-plans p)
                                               {:ok 0 :failed 0 :skipped 0 :total (count p)})]
        (let [result (sync-api/start-sync! :sales :marketplace :wb :period :last-7-days)]
          ;; Wait briefly for the background future to complete
          (Thread/sleep 300)
          ;; Verify result shape
          (is (true? (:ok result))
              "Result should have :ok true")
          (is (string? (:run-id result))
              "Result should have a :run-id string")
          (is (>= (count (:run-id result)) 36)
              "run-id should be at least 36 chars (UUID)")
          ;; Verify plan had tasks persisted (wb/sales → ingest + materialize = 2 tasks)
          (is (= 2 (count @persisted-tasks))
              "persist-plan! should have been called with 2 tasks (ingest + materialize)"))))))

(deftest start-sync-1c-still-direct
  (testing "start-sync! :1c does NOT generate a plan or call persist-plan!"
    (let [persist-called? (atom false)
          executor-called? (atom false)]
      (with-redefs [plan/persist-plan!     (fn [p] (reset! persist-called? true) p)
                    executor/run-parallel! (fn [p & _] (reset! executor-called? true) {})]
        (let [result (sync-api/start-sync! :1c)]
          ;; Wait briefly for the background future
          (Thread/sleep 300)
          ;; :1c returns plain {:ok true} without :run-id
          (is (= {:ok true} result)
              ":1c result should be exactly {:ok true}")
          (is (false? @persist-called?)
              "persist-plan! should NOT be called for :1c")
          (is (false? @executor-called?)
              "executor/run-parallel! should NOT be called for :1c"))))))

;; ---------------------------------------------------------------------------
;; FR-P2.8 — stuck-sync detection
;; ---------------------------------------------------------------------------

(deftest stuck-detection
  (testing "running run older than threshold is stuck"
    (let [now   (LocalDateTime/parse "2026-06-25T12:00:00")
          old   {:status "running"   :started-at "2026-06-25T11:00:00"} ; 60 min ago
          fresh {:status "running"   :started-at "2026-06-25T11:55:00"} ; 5 min ago
          done  {:status "completed" :started-at "2026-06-25T09:00:00"}] ; not running
      (is (true?  (executor/stuck? old   now 30)) "60-min running run should be stuck")
      (is (false? (executor/stuck? fresh now 30)) "5-min running run should not be stuck")
      (is (false? (executor/stuck? done  now 30)) "non-running run should never be stuck")))
  (testing "nil started-at is not stuck"
    (let [now (LocalDateTime/parse "2026-06-25T12:00:00")]
      (is (false? (executor/stuck? {:status "running" :started-at nil} now 30))
          "nil started-at should return false")))
  (testing "missing started-at is not stuck"
    (let [now (LocalDateTime/parse "2026-06-25T12:00:00")]
      (is (false? (executor/stuck? {:status "running"} now 30))
          "missing started-at should return false"))))

(deftest start-sync-invalid-marketplace
  (testing "start-sync! with unrecognised marketplace produces empty plan, returns success"
    ;; :invalid-mp is not in mp-entity-matrix so expand-plan returns [].
    ;; start-sync! should return {:ok true :run-id ... :total 0} rather than crash.
    (let [persisted-tasks (atom :not-called)
          executor-called? (atom false)]
      (with-redefs [plan/persist-plan!     (fn [p] (reset! persisted-tasks p) p)
                    executor/run-parallel! (fn [p & _] (reset! executor-called? true) {})]
        (let [result (sync-api/start-sync! :sales :marketplace :invalid-mp)]
          (Thread/sleep 100)
          (is (true? (:ok result))
              "Should return :ok true even for empty plan")
          (is (string? (:run-id result))
              "Should have a :run-id even for empty plan")
          (is (= 0 (:total result))
              "total should be 0 for empty plan")
          ;; persist-plan! is still called (with empty vector)
          (is (vector? @persisted-tasks)
              "persist-plan! should be called with a (possibly empty) vector")
          (is (= 0 (count @persisted-tasks))
              "No tasks should be persisted for invalid marketplace"))))))

