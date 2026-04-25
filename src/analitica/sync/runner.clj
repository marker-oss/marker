(ns analitica.sync.runner
  "V4 Sync Task Runner — Phase 2.
   Wraps registry CRUD into a lifecycle envelope for executing sync tasks.
   The envelope catches all Throwables so a failing task never aborts the
   surrounding executor."
  (:require [analitica.sync.registry :as reg]
            [clojure.string :as str]))

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
;; Task runner envelope
;; ---------------------------------------------------------------------------

(defn run-task!
  "Execute the task identified by `task-id`, recording lifecycle in
   sync_tasks via the registry. Catches all Throwables — the envelope
   never propagates exceptions, so a failing task does not abort the
   surrounding executor.

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
  (reg/set-running! task-id)
  (try
    (let [result (thunk)
          items  (when (number? result) result)]
      (reg/record-success! task-id (or items 0)))
    (catch Throwable t
      (let [kind (classify-error t)
            msg  (or (.getMessage t) "unknown")]
        (reg/record-error! task-id (name kind) msg)))))
