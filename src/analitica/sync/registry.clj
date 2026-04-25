(ns analitica.sync.registry
  "V4 Sync Task Registry — CRUD over the sync_tasks SQLite table.
   Phase 1: no executor logic; pure data operations."
  (:require [analitica.db :as db]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Private helpers
;; ---------------------------------------------------------------------------

(defn- ^:private now-iso []
  (.format (java.time.LocalDateTime/now)
           (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss")))

(defn- ^:private duration-ms
  "Compute duration in ms between two ISO 8601 strings (seconds precision).
   Returns nil if either is nil."
  [started-at finished-at]
  (when (and started-at finished-at)
    (let [fmt (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss")
          s   (java.time.LocalDateTime/parse started-at fmt)
          f   (java.time.LocalDateTime/parse finished-at fmt)]
      (.toMillis (java.time.Duration/between s f)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn create-task!
  "Insert a new task with status='pending', attempts=0.
   task-spec keys: :id :run-id :marketplace :entity-type :phase
                   and optionally :chunk :max-attempts :period-from :period-to
                   :parent-id :depends-on (seq of ids)"
  [{:keys [id run-id marketplace entity-type phase chunk
           max-attempts period-from period-to parent-id depends-on]}]
  (db/execute!
   ["INSERT INTO sync_tasks
       (id, run_id, marketplace, entity_type, phase, chunk,
        status, attempts, max_attempts,
        period_from, period_to, parent_id, depends_on)
     VALUES (?, ?, ?, ?, ?, ?, 'pending', 0, ?, ?, ?, ?, ?)"
    id run-id marketplace entity-type phase chunk
    (or max-attempts 1)
    period-from period-to parent-id
    ;; Spec separator is ", " (comma-space) so consumers that split on ", "
    ;; round-trip cleanly when the list has multiple items.
    (when depends-on (str/join ", " depends-on))])
  (first (db/query ["SELECT * FROM sync_tasks WHERE id = ?" id])))

(defn set-running!
  "Set status='running', increment attempts, record started_at=NOW."
  [task-id]
  (let [now (now-iso)]
    (db/execute!
     ["UPDATE sync_tasks
       SET status = 'running', started_at = ?, attempts = attempts + 1
       WHERE id = ?"
      now task-id])
    (first (db/query ["SELECT * FROM sync_tasks WHERE id = ?" task-id]))))

(defn record-success!
  "Set status='ok', finished_at=NOW, duration_ms, items; clear error fields."
  [task-id items]
  (let [now     (now-iso)
        started (:started-at (first (db/query ["SELECT started_at FROM sync_tasks WHERE id = ?" task-id])))
        dur     (duration-ms started now)]
    (db/execute!
     ["UPDATE sync_tasks
       SET status = 'ok', finished_at = ?, duration_ms = ?, items = ?,
           error_msg = NULL, error_kind = NULL
       WHERE id = ?"
      now dur items task-id])
    (first (db/query ["SELECT * FROM sync_tasks WHERE id = ?" task-id]))))

(defn record-error!
  "Set status='failed', finished_at=NOW, duration_ms, error_kind, error_msg (truncated to 2000 chars)."
  [task-id error-kind error-msg]
  (let [now     (now-iso)
        started (:started-at (first (db/query ["SELECT started_at FROM sync_tasks WHERE id = ?" task-id])))
        dur     (duration-ms started now)
        msg     (when error-msg (subs error-msg 0 (min 2000 (count error-msg))))]
    (db/execute!
     ["UPDATE sync_tasks
       SET status = 'failed', finished_at = ?, duration_ms = ?,
           error_kind = ?, error_msg = ?
       WHERE id = ?"
      now dur error-kind msg task-id])
    (first (db/query ["SELECT * FROM sync_tasks WHERE id = ?" task-id]))))

(defn record-skipped!
  "Set status='skipped', finished_at=NOW, error_msg=reason.
   Used when a dependency failed."
  [task-id reason]
  (let [now (now-iso)]
    (db/execute!
     ["UPDATE sync_tasks
       SET status = 'skipped', finished_at = ?, error_msg = ?
       WHERE id = ?"
      now reason task-id])
    (first (db/query ["SELECT * FROM sync_tasks WHERE id = ?" task-id]))))

(defn find-task
  "Returns row map for task-id, or nil."
  [task-id]
  (first (db/query ["SELECT * FROM sync_tasks WHERE id = ?" task-id])))

(defn find-tasks-for-run
  "Returns vector of all task rows for run-id, ordered by id ASC."
  [run-id]
  (db/query ["SELECT * FROM sync_tasks WHERE run_id = ? ORDER BY id ASC" run-id]))

(defn find-pending-for-run
  "Returns vector of tasks with status='pending' for the given run."
  [run-id]
  (db/query ["SELECT * FROM sync_tasks WHERE run_id = ? AND status = 'pending' ORDER BY id ASC" run-id]))

(defn find-failed-since
  "Returns vector of failed tasks whose finished_at >= iso-date-str."
  [iso-date-str]
  (db/query ["SELECT * FROM sync_tasks WHERE status = 'failed' AND finished_at >= ? ORDER BY finished_at ASC"
             iso-date-str]))

(defn reset-for-retry!
  "Set status='pending', clear error/timing fields. Leaves attempts and max_attempts as-is."
  [task-id]
  (db/execute!
   ["UPDATE sync_tasks
     SET status = 'pending', error_msg = NULL, error_kind = NULL,
         finished_at = NULL, duration_ms = NULL, started_at = NULL
     WHERE id = ?"
    task-id])
  (first (db/query ["SELECT * FROM sync_tasks WHERE id = ?" task-id])))

(defn set-retrying!
  "Mark a task as awaiting retry. Status='retrying'; preserve attempts so
   the UI can show 'attempt N/M' from the row."
  [task-id]
  (db/execute! ["UPDATE sync_tasks SET status='retrying' WHERE id = ?" task-id]))
