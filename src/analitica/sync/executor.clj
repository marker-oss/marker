(ns analitica.sync.executor
  "V4 Sync Executor — Phase 3/4/5.
   Sequential and DAG-parallel execution of a plan (vector of task
   descriptors with :thunks), honoring :depends-on dependency constraints.
   No retries (Phase 6).

   Phase 4 adds run-summary and recent-runs for the task-matrix API.
   Phase 5 adds run-parallel! using ExecutorCompletionService."
  (:require [analitica.db :as db]
            [analitica.sync.registry :as registry]
            [analitica.sync.runner :as runner]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as mu])
  (:import [java.util.concurrent
            Executors ExecutorCompletionService Callable TimeUnit]
           [java.time LocalDateTime Duration]
           [java.time.format DateTimeFormatter]))

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

(defn with-run-context
  "Run f inside a mulog context carrying :run-id, so every mu/log emitted
   during the task inherits the run-id for trace correlation."
  [run-id f]
  (mu/with-context {:run-id run-id} (f)))

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
                           (do (registry/record-skipped! id reason)
                               (update acc :skipped inc))
                           ;; US2 T026: background-op span (FR-011).
                           ;; Correlated under run-id via mu/with-context (line 54).
                           ;; Only allow-list keys — no per-SKU/article labels (FR-014).
                           (let [t0  (System/currentTimeMillis)
                                 row (runner/run-task! id thunk)
                                 ok? (= "ok" (:status row))]
                             (mu/log :marker/background-op
                                     :operation   :sync
                                     :outcome     (if ok? :success :error)
                                     :duration-ms (double (- (System/currentTimeMillis) t0)))
                             (if ok?
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
;; Phase 5 — DAG-aware parallel executor
;;
;; Strategy: ExecutorCompletionService + a "ready set" we re-evaluate after
;; each task finishes. Workers (default 8) consume from the completion
;; service's queue; the dispatcher loop submits tasks as their deps clear.
;;
;; The per-MP rate-limiters in analitica.util.http already serialize same-MP
;; requests at the HTTP boundary, so a worker pool > 1 is safe — concurrent
;; tasks for different MPs proceed in parallel; concurrent tasks for the
;; same MP queue at the rate-limiter, which is the desired behaviour.
;; ---------------------------------------------------------------------------

(defn- deps-resolution
  "Inspect deps for the given task. Returns one of:
     :ready       — every dep is :ok or no deps at all
     :failed      — at least one dep is :failed/:skipped (mark dependent skipped)
     :pending     — at least one dep still pending/running/retrying"
  [dep-ids]
  (if (empty? dep-ids)
    :ready
    (loop [remaining dep-ids any-pending? false]
      (if (empty? remaining)
        (if any-pending? :pending :ready)
        (let [row    (registry/find-task (first remaining))
              status (:status row)]
          (cond
            (terminal-failure-statuses status) :failed
            (= "ok" status)                    (recur (rest remaining) any-pending?)
            :else                              (recur (rest remaining) true)))))))

(defn run-parallel!
  "Run a plan in parallel across `workers` worker threads (default 8).

   Honors :depends-on edges: a task waits until all deps reach :ok. If any
   dep is :failed or :skipped, the dependent is recorded :skipped via
   registry/record-skipped! (no thunk invocation).

   Returns the same summary shape as run-sequential!.

   Idempotency: tasks already in a terminal state are skipped at submit
   time (they pass through the counters un-counted because they were
   counted on the previous run).

   Shutdown is clean — pool is .shutdown and .awaitTermination'd in
   try/finally even on exceptions."
  [plan & {:keys [workers] :or {workers 8}}]
  (let [start-ms (System/currentTimeMillis)
        run-id   (:run-id (first plan))
        n-tasks  (count plan)]
    (if (zero? n-tasks)
      {:run-id run-id :total 0 :ok 0 :failed 0 :skipped 0
       :duration-ms (- (System/currentTimeMillis) start-ms)}
      (let [pool   (Executors/newFixedThreadPool workers)
            ecs   (ExecutorCompletionService. pool)
            ;; Mutable bookkeeping
            in-flight (atom #{})         ; set of task IDs currently submitted
            done      (atom #{})         ; set of task IDs whose terminal status is committed
            counters  (atom {:ok 0 :failed 0 :skipped 0})
            by-id     (into {} (map (juxt :id identity) plan))]
        (try
          ;; Submit tasks whose deps are :ready (or empty). Loop until every
          ;; task is done.
          (loop []
            ;; 1) Submit any newly-ready tasks not yet in-flight or done.
            (doseq [{:keys [id depends-on thunk]} plan
                    :when (not (or (@in-flight id) (@done id)))]
              (cond
                (already-terminal? id)
                ;; Pre-existing terminal task → mark done, don't count
                (swap! done conj id)

                :else
                (case (deps-resolution depends-on)
                  :ready
                  (do (swap! in-flight conj id)
                      (.submit ecs ^Callable
                               (fn []
                                 (with-run-context run-id
                                   (fn []
                                     (let [row (runner/run-task! id thunk)]
                                       {:id id :status (:status row)}))))))

                  :failed
                  (let [reason (dep-failure-reason depends-on)]
                    (registry/record-skipped! id (or reason "dependency failed"))
                    (swap! counters update :skipped inc)
                    (swap! done conj id))

                  :pending
                  nil)))                ; wait for an in-flight dep to finish

            ;; 2) If everything is done, exit
            (if (= (count @done) n-tasks)
              nil
              (let [;; Block on next completion
                    fut    (.take ecs)
                    result (.get fut)
                    {:keys [id status]} result]
                (swap! in-flight disj id)
                (swap! done conj id)
                (case status
                  "ok"      (swap! counters update :ok inc)
                  "failed"  (swap! counters update :failed inc)
                  "skipped" (swap! counters update :skipped inc)
                  nil)
                (recur))))
          (finally
            (.shutdown pool)
            (when-not (.awaitTermination pool 30 TimeUnit/SECONDS)
              (.shutdownNow pool))))
        {:run-id      run-id
         :total       n-tasks
         :ok          (:ok @counters)
         :failed      (:failed @counters)
         :skipped     (:skipped @counters)
         :duration-ms (- (System/currentTimeMillis) start-ms)}))))

;; ---------------------------------------------------------------------------
;; FR-P2.8 — stuck-sync detection
;; ---------------------------------------------------------------------------

(def ^:private iso-fmt
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))

(defn stuck?
  "Pure classifier: true iff the run is in status 'running' AND its age
   (measured from :started-at) exceeds threshold-min minutes.

   Arguments:
     run           — map with at least :status and :started-at (ISO string
                     'yyyy-MM-dd'T'HH:mm:ss', the format stored by registry)
     now           — java.time.LocalDateTime representing the current instant
     threshold-min — integer; age in minutes above which the run is stuck

   Returns false when :started-at is nil/missing or status is not 'running'."
  [run now threshold-min]
  (let [status     (:status run)
        started-at (:started-at run)]
    (boolean
      (and (= "running" status)
           started-at
           (let [started (LocalDateTime/parse started-at iso-fmt)
                 age-min (/ (.toMillis (Duration/between started now)) 60000.0)]
             (> age-min threshold-min))))))

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

(defn- enrich-run
  "Add :stuck? and :age-min to a run summary map.
   :age-min is the age of the run in fractional minutes (from :started-at to now).
   :stuck?  is true when status is 'running' and age-min > 30.
   Both keys are always present; age-min is nil when :started-at is absent."
  [run-map]
  (let [now        (LocalDateTime/now)
        started-at (:started-at run-map)
        age-min    (when started-at
                     (let [started (LocalDateTime/parse started-at iso-fmt)]
                       (/ (.toMillis (Duration/between started now)) 60000.0)))]
    (assoc run-map
           :age-min age-min
           :stuck?  (stuck? run-map now 30))))

(defn recent-runs
  "Return a vector of the last 10 distinct run-ids ordered by latest started_at DESC,
   each with the same rollup shape as run-summary, enriched with :stuck? and :age-min.

   :stuck?  — true when status is 'running' and the run is older than 30 minutes
   :age-min — fractional minutes since :started-at (nil when started-at absent)

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
    (mapv (fn [row]
            (some-> (run-summary (:run-id row))
                    enrich-run))
          rows)))
