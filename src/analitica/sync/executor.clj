(ns analitica.sync.executor
  "V4 Sync Executor — Phase 3/4.
   Sequential execution of a plan (vector of task descriptors with :thunks),
   honoring :depends-on dependency constraints.
   No parallelism (Phase 5). No retries (Phase 6).

   Phase 4 adds run-summary and recent-runs for the task-matrix API."
  (:require [analitica.db :as db]
            [analitica.sync.registry :as registry]
            [analitica.sync.runner :as runner]
            [clojure.string :as str]))

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

;; ---------------------------------------------------------------------------
;; Phase 4 — run-summary and recent-runs for the task-matrix API
;; ---------------------------------------------------------------------------

(def ^:private running-statuses  #{"pending" "running" "retrying"})
(def ^:private terminal-statuses #{"ok" "failed" "skipped"})

(defn- overall-status
  "Compute top-level run status from a vector of task rows.
   - any task :pending|:running|:retrying  → 'running'
   - all terminal AND any :failed          → 'failed'
   - else (all terminal, all ok/skipped)   → 'done'"
  [rows]
  (cond
    (some #(running-statuses (:status %)) rows) "running"
    (some #(= "failed" (:status %)) rows)       "failed"
    :else                                        "done"))

(defn- row->task-summary
  "Convert a registry row map to the task-summary map for the API response.
   Registry returns keys as kebab-case keywords (next.jdbc default)."
  [row]
  {:id           (:id row)
   :marketplace  (:marketplace row)
   :entity-type  (:entity-type row)
   :phase        (:phase row)
   :status       (:status row)
   :items        (:items row)
   :duration-ms  (:duration-ms row)
   :attempts     (:attempts row)
   :started-at   (:started-at row)
   :finished-at  (:finished-at row)
   :error-kind   (:error-kind row)
   :error-msg    (:error-msg row)
   :depends-on   (let [d (:depends-on row)]
                   (if (and d (not (empty? d)))
                     (str/split d #",\s*")
                     []))})

(defn run-summary
  "Return a summary map for the given run-id, suitable for JSON serialization.

   Returns nil when run-id is unknown (no tasks found) so the HTTP route
   can 404.

   Shape:
     {:run-id       '...'
      :total        N
      :started-at   '...'   earliest started_at among all tasks (nil if none started)
      :finished-at  '...'   latest finished_at if all terminal, else nil
      :status       'running|done|failed'
      :tasks        [{:id :marketplace :entity-type :phase :status :items ...}]}"
  [run-id]
  (let [rows (registry/find-tasks-for-run run-id)]
    (when (seq rows)
      (let [task-summaries (mapv row->task-summary rows)
            status         (overall-status rows)
            started-ats    (->> rows (map :started-at) (filter some?) sort)
            finished-ats   (->> rows (map :finished-at) (filter some?) sort)
            all-terminal?  (every? #(terminal-statuses (:status %)) rows)]
        {:run-id      run-id
         :total       (count rows)
         :started-at  (first started-ats)
         :finished-at (when all-terminal? (last finished-ats))
         :status      status
         :tasks       task-summaries}))))

(defn recent-runs
  "Return a vector of the last 10 distinct run-ids ordered by latest started_at DESC,
   each with the same rollup shape as run-summary.

   Used by GET /api/sync/runs/recent."
  []
  (let [rows (try
               (db/query
                 ["SELECT DISTINCT run_id, MAX(started_at) AS last_started
                   FROM sync_tasks
                   GROUP BY run_id
                   ORDER BY last_started DESC
                   LIMIT 10"])
               (catch Exception _ []))]
    (mapv #(run-summary (:run-id %)) rows)))
