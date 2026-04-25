(ns analitica.sync.executor
  "V4 Sync Executor — Phase 3.
   Sequential execution of a plan (vector of task descriptors with :thunks),
   honoring :depends-on dependency constraints.
   No parallelism (Phase 5). No retries (Phase 6)."
  (:require [analitica.sync.registry :as registry]
            [analitica.sync.runner :as runner]))

;; ---------------------------------------------------------------------------
;; Private helpers
;; ---------------------------------------------------------------------------

(def ^:private terminal-failure-statuses
  "Registry statuses that indicate a task cannot be depended upon."
  #{"failed" "skipped"})

(defn- dep-failure-reason
  "If any dep has a terminal-failure status, return a human-readable reason string.
   Returns nil if all deps are ok."
  [dep-ids]
  (reduce (fn [_ dep-id]
            (let [row (registry/find-task dep-id)]
              (when (terminal-failure-statuses (:status row))
                (reduced (str "dependency failed: " dep-id)))))
          nil
          dep-ids))

(defn- already-terminal?
  "True if the task row already has a terminal status (ok/failed/skipped).
   Used for idempotency — don't re-run a task that has already completed."
  [task-id]
  (let [row    (registry/find-task task-id)
        status (:status row)]
    (contains? #{"ok" "failed" "skipped"} status)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn run-sequential!
  "Run a plan (vector of task descriptors with :thunks) sequentially,
   honoring :depends-on. If any dep failed (registry status='failed' or
   'skipped'), the dependent task is recorded as 'skipped' via
   registry/record-skipped! with reason 'dependency failed: <dep-id>'.

   For each task in plan order:
     - if task already has a terminal status → skip (idempotent)
     - if any dep is :failed or :skipped → mark this task :skipped
     - else → call (runner/run-task! id thunk)

   Returns a summary map:
     {:run-id      ...
      :total       <n>
      :ok          <n>
      :failed      <n>
      :skipped     <n>
      :duration-ms <total wall clock>}"
  [plan]
  (let [start-ms (System/currentTimeMillis)
        ;; Extract run-id from first task (nil if plan is empty)
        run-id   (:run-id (first plan))
        ;; Accumulate counters
        counters (reduce
                   (fn [acc {:keys [id depends-on thunk]}]
                     (cond
                       ;; Idempotency: don't re-run terminal tasks
                       (already-terminal? id)
                       acc

                       ;; Check dependencies
                       :else
                       (let [reason (dep-failure-reason depends-on)]
                         (if reason
                           (do
                             (registry/record-skipped! id reason)
                             (update acc :skipped inc))
                           ;; Run the task via the runner envelope
                           (let [row (runner/run-task! id thunk)]
                             (if (= "ok" (:status row))
                               (update acc :ok inc)
                               (update acc :failed inc)))))))
                   {:ok 0 :failed 0 :skipped 0}
                   plan)
        end-ms   (System/currentTimeMillis)]
    {:run-id      run-id
     :total       (count plan)
     :ok          (:ok counters)
     :failed      (:failed counters)
     :skipped     (:skipped counters)
     :duration-ms (- end-ms start-ms)}))
