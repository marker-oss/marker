(ns analitica.sync.runner
  "V4 Sync Task Runner — Phase 2 / Phase 6.
   Wraps registry CRUD into a lifecycle envelope for executing sync tasks.
   Phase 6 adds exponential-backoff auto-retry for transient error classes.
   The envelope catches all Throwables so a failing task never aborts the
   surrounding executor."
  (:require [analitica.sync.registry :as reg]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]))

;; ---------------------------------------------------------------------------
;; Error classification
;; ---------------------------------------------------------------------------

(defn classify-error
  "Map a Throwable to one of the registry error_kind keyword values.

   Priority order (first match wins):
     1. HTTP status code from ex-data :status
        - 429          → :http-429
        - 400-428, 430-499 → :http-4xx
        - 500-599      → :http-5xx
     2. Timeout exception class
        - SocketTimeoutException, HttpTimeoutException → :timeout
     3. Timeout message heuristic
        - message contains \"timeout\" or \"timed out\" (case-insensitive) → :timeout
     4. Validation error from ex-data
        - :type is :validation-error or :schema-drift → :validation
        - :violations is non-nil → :validation
     5. Fallback → :internal"
  [^Throwable throwable]
  (let [data    (ex-data throwable)
        status  (:status data)
        msg     (or (.getMessage throwable) "")]
    (cond
      ;; 1a. HTTP 429
      (= status 429)
      :http-429

      ;; 1b. HTTP 4xx (excluding 429)
      (and (integer? status) (>= status 400) (< status 500))
      :http-4xx

      ;; 1c. HTTP 5xx
      (and (integer? status) (>= status 500) (< status 600))
      :http-5xx

      ;; 2. Timeout exception class
      (or (instance? java.net.SocketTimeoutException throwable)
          (instance? java.net.http.HttpTimeoutException throwable))
      :timeout

      ;; 3. Timeout message heuristic
      (or (str/includes? (str/lower-case msg) "timeout")
          (str/includes? (str/lower-case msg) "timed out"))
      :timeout

      ;; 4. Validation
      (or (#{:validation-error :schema-drift} (:type data))
          (some? (:violations data)))
      :validation

      ;; 5. Fallback
      :else
      :internal)))

;; ---------------------------------------------------------------------------
;; Backoff helper
;; ---------------------------------------------------------------------------

(defn- compute-backoff-ms
  "Returns sleep duration for the given attempt number (1-indexed). Capped
   at 60s. Includes random jitter to avoid thundering-herd on repeated
   downstream outage.

   Formula: min(60_000, 1000 * 2^(attempt-1) + rand(0..1000))"
  [attempt]
  (let [base   (* 1000 (Math/pow 2 (dec attempt)))
        jitter (rand-int 1000)]
    (min 60000 (long (+ base jitter)))))

;; ---------------------------------------------------------------------------
;; Retryable error classes
;; ---------------------------------------------------------------------------

(def ^:private retryable-kinds #{:http-5xx :http-429 :timeout})

;; ---------------------------------------------------------------------------
;; Task runner envelope
;; ---------------------------------------------------------------------------

(defn run-task!
  "Execute the task identified by `task-id`, recording lifecycle in
   sync_tasks via the registry. Catches all Throwables — the envelope
   never propagates exceptions, so a failing task does not abort the
   surrounding executor.

   Phase 6: auto-retry with exponential backoff for retryable error classes
   (:http-5xx, :http-429, :timeout). The number of allowed attempts is read
   from the task row's max_attempts column (default 1 = no retry).

   Args:
     task-id  — string, must already exist in sync_tasks (status=pending).
                Throws ex-info with :task-id if the task is not found
                (strict contract — caller is responsible for pre-creating
                the task via the planner in Phase 3).
     thunk    — () → number-of-items-processed | any
                Non-number return values are treated as 0 items.

   Returns the post-execution row map from the registry, or nil if the
   strict pre-flight check throws (which would only happen in tests that
   catch the ex-info themselves)."
  [task-id thunk]
  ;; Strict pre-flight: task must exist before we attempt to run it.
  (let [existing (reg/find-task task-id)]
    (when (nil? existing)
      (throw (ex-info "Task not found" {:task-id task-id}))))
  (let [max-attempts (or (:max-attempts (reg/find-task task-id)) 1)]
    (loop [attempt 1]
      (reg/set-running! task-id)
      (let [result (try
                     {:ok (thunk)}
                     (catch Throwable t
                       {:err t :kind (classify-error t)}))]
        (cond
          ;; Success
          (:ok result)
          (let [items (when (number? (:ok result)) (:ok result))]
            (reg/record-success! task-id (or items 0)))

          ;; Retryable failure with remaining attempts
          (and (contains? retryable-kinds (:kind result))
               (< attempt max-attempts))
          (let [backoff-ms (compute-backoff-ms attempt)
                kind-name  (name (:kind result))]
            (mu/log ::retry-scheduled
                    :task-id task-id
                    :attempt attempt
                    :max-attempts max-attempts
                    :error-kind kind-name
                    :backoff-ms backoff-ms)
            (reg/set-retrying! task-id)
            (Thread/sleep backoff-ms)
            (recur (inc attempt)))

          ;; Final failure (non-retryable or exhausted)
          :else
          (let [t    (:err result)
                kind (name (:kind result))
                msg  (or (.getMessage ^Throwable t) "unknown")]
            (reg/record-error! task-id kind msg)))))))
