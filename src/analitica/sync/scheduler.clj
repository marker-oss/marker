(ns analitica.sync.scheduler
  "Daily auto-refresh. Reads sync_schedule, schedules a one-shot Java task at
   the configured time, re-arms after each fire. Skipped when a manual sync is
   already running."
  (:require [analitica.db :as db]
            [com.brunobonacci.mulog :as mu])
  (:import [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

;; ---------------------------------------------------------------------------
;; Private helpers
;; ---------------------------------------------------------------------------

(defn- now-iso []
  (.format (java.time.LocalDateTime/now)
           (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss")))

(defn compute-initial-delay-ms
  "Given a LocalDateTime `now` and target hour/minute (0-23 / 0-59),
   return the number of milliseconds until the next occurrence of that
   time. If today's target is in the past, schedule for tomorrow."
  [^java.time.LocalDateTime now hour minute]
  (let [today-target (-> now
                         (.withHour hour)
                         (.withMinute minute)
                         (.withSecond 0)
                         (.withNano 0))
        target       (if (.isAfter today-target now)
                       today-target
                       (.plusDays today-target 1))
        diff-ms      (-> (java.time.Duration/between now target)
                         .toMillis)]
    diff-ms))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private executor      (atom nil))   ; ScheduledExecutorService
(defonce ^:private current-handle (atom nil))  ; ScheduledFuture for next fire

;; ---------------------------------------------------------------------------
;; Public CRUD over sync_schedule
;; ---------------------------------------------------------------------------

(defn get-schedule
  "Returns the current singleton schedule row map, or nil if table empty."
  []
  (first (db/query ["SELECT * FROM sync_schedule WHERE id = 1"])))

(defn- compute-next-run-at
  "Compute the next ISO datetime string for a given hour/minute, starting from now."
  [hour minute]
  (let [now     (java.time.LocalDateTime/now)
        delay   (compute-initial-delay-ms now hour minute)
        next-dt (.plusNanos now (* delay 1000000))]
    (.format next-dt (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))))

(declare start-timer!)

(defn update-schedule!
  "Update the singleton schedule row.
   opts: {:enabled? bool :hour int :minute int :what str :marketplace str :period str}
   Re-arms the timer when enabled=true. Returns the updated row."
  [{:keys [enabled? hour minute what marketplace period]}]
  (let [now     (now-iso)
        enabled (if enabled? 1 0)
        h       (or hour 6)
        m       (or minute 0)
        w       (or what "all")
        mp      (or marketplace "all")
        per     (or period "last-7-days")
        next-at (when enabled? (compute-next-run-at h m))]
    (db/execute!
     ["UPDATE sync_schedule
       SET enabled = ?, hour = ?, minute = ?, what = ?,
           marketplace = ?, period = ?, next_run_at = ?, updated_at = ?
       WHERE id = 1"
      enabled h m w mp per next-at now])
    (let [row (get-schedule)]
      ;; Cancel existing handle
      (when-let [handle @current-handle]
        (when-not (.isCancelled handle)
          (.cancel handle false))
        (reset! current-handle nil))
      ;; Re-arm if enabled
      (when (and enabled? @executor)
        (start-timer!))
      row)))

;; ---------------------------------------------------------------------------
;; Coverage gap detection + backfill (audit 2026-07-02 P0-2)
;;
;; "sync ok" ≠ "data complete": the daily last-7-days run never re-fills a
;; historical hole (e.g. WB finance was empty for all of May 2026 while every
;; scheduled run reported success). Detect months with NO finance rows across
;; the recent window and re-ingest exactly those, so a hole self-heals before
;; the marketplace's report retention expires.
;;
;; Month granularity (not day) on purpose: WB finance carries a WEEKLY
;; date_from, so day-level "present" sets are noisy; a whole empty month is the
;; real, unambiguous failure mode.
;; ---------------------------------------------------------------------------

(defn month-seq
  "Inclusive vector of \"YYYY-MM\" strings from `from-month` to `to-month`."
  [from-month to-month]
  (let [ym   (fn [s] (java.time.YearMonth/parse s))
        stop (ym to-month)]
    (loop [cur (ym from-month) acc []]
      (if (pos? (compare cur stop))
        acc
        (recur (.plusMonths cur 1) (conj acc (str cur)))))))

(defn missing-months
  "Pure: months in [from-month..to-month] absent from `present-months`.
   `present-months` is any seq/set of \"YYYY-MM\". Returns a sorted vector."
  [present-months from-month to-month]
  (let [present (set present-months)]
    (vec (remove present (month-seq from-month to-month)))))

(defn- finance-present-months
  "Set of \"YYYY-MM\" that have ≥1 finance row for `mp` in [from..to], keyed by
   the row's EVENT month (COALESCE(event_date, date_from))."
  [mp from to]
  (->> (db/query
         ["SELECT DISTINCT substr(COALESCE(event_date, date_from),1,7) AS m
           FROM finance
           WHERE marketplace = ?
             AND COALESCE(event_date, date_from) >= ? AND COALESCE(event_date, date_from) <= ?"
          (name mp) from to])
       (keep :m)
       set))

(defn detect-finance-gaps
  "For each marketplace, months in the last `lookback-days` (ending `as-of`, an
   ISO date string) that have zero finance rows. Returns
   [{:marketplace :wb :month \"2026-05\" :from \"2026-05-01\" :to \"2026-05-31\"} …].
   The current (partial) month is never flagged — it is legitimately incomplete."
  [as-of lookback-days]
  (let [as-of-d    (java.time.LocalDate/parse as-of)
        from-d     (.minusDays as-of-d lookback-days)
        cur-month  (str (java.time.YearMonth/from as-of-d))
        from-month (str (java.time.YearMonth/from from-d))
        to-month   (str (java.time.YearMonth/from as-of-d))]
    (for [mp    [:wb :ozon :ym]
          month (missing-months (finance-present-months mp (str from-d) as-of)
                                 from-month to-month)
          :when (not= month cur-month)]      ; partial current month isn't a "gap"
      (let [ym  (java.time.YearMonth/parse month)]
        {:marketplace mp
         :month       month
         :from        (str (.atDay ym 1))
         :to          (str (.atEndOfMonth ym))}))))

(defn backfill-gaps!
  "Detect finance coverage gaps and re-ingest+materialize exactly those months.
   Idempotent (sync DELETE+INSERT); failure-isolated per gap so one bad month
   never aborts the scheduled run. Returns the detected gaps.

   `start-sync!` is injected to avoid the circular ns dependency (same lazy
   resolve `fire!` uses)."
  [start-sync! as-of lookback-days]
  (let [gaps (detect-finance-gaps as-of lookback-days)]
    (when (seq gaps)
      (mu/log ::coverage-gaps-detected :count (count gaps) :gaps gaps)
      (println (str "[SCHEDULER] finance coverage gaps: "
                    (mapv (juxt :marketplace :month) gaps))))
    (doseq [{:keys [marketplace from to month]} gaps]
      (try
        (start-sync! :finance :marketplace marketplace :period [from to])
        (mu/log ::coverage-gap-backfill :marketplace marketplace :month month)
        (catch Exception e
          (mu/log ::coverage-gap-backfill-failed
                  :marketplace marketplace :month month :error (.getMessage e)))))
    gaps))

;; ---------------------------------------------------------------------------
;; Timer / scheduler internals
;; ---------------------------------------------------------------------------

(defn- fire!
  "Invoked by the executor at the scheduled time.
   - skips if sync-running? is true (manual run in progress)
   - else: calls start-sync! with the schedule params
   - updates last-run-at + last-run-id in sync_schedule
   - re-arms next fire (24h later via recompute)"
  []
  ;; Resolve lazily to break circular ns dependency.
  ;; Dereference the var to get the current binding (supports with-redefs in tests).
  (let [sync-running? @(var-get (requiring-resolve 'analitica.web.api.sync/sync-running?))
        start-sync!   (var-get (requiring-resolve 'analitica.web.api.sync/start-sync!))]
    (try
      (let [sched (get-schedule)]
        ;; Record attempt regardless of outcome
        (db/execute!
         ["UPDATE sync_schedule SET last_run_at = ?, updated_at = ? WHERE id = 1"
          (now-iso) (now-iso)])
        (if sync-running?
          (do
            (println "[SCHEDULER] Skipping scheduled run — manual sync already running")
            (mu/log ::scheduler-skipped :reason "sync-running"))
          (let [what       (keyword (:what sched "all"))
                mp-str     (:marketplace sched "all")
                mp         (if (= mp-str "all") :all (keyword mp-str))
                period-str (:period sched "last-7-days")
                result     (start-sync! what :marketplace mp :period period-str)]
            (mu/log ::scheduler-fired :what what :marketplace mp :result result)
            (when-let [run-id (:run-id result)]
              (db/execute!
               ["UPDATE sync_schedule SET last_run_id = ?, updated_at = ? WHERE id = 1"
                run-id (now-iso)]))
            ;; Self-heal historical finance holes the last-7-days run can't
            ;; reach (audit P0-2). Non-fatal: never breaks the scheduled run.
            (try
              (backfill-gaps! start-sync!
                              (.format (java.time.LocalDate/now)
                                       java.time.format.DateTimeFormatter/ISO_LOCAL_DATE)
                              90)
              (catch Exception e
                (mu/log ::coverage-backfill-error :error (.getMessage e)))))))
      (catch Exception e
        (println (str "[SCHEDULER] fire! error: " (.getMessage e)))
        (mu/log ::scheduler-error :error-message (.getMessage e))))
    ;; Re-arm for next fire: recompute from current schedule hour/minute
    (try
      (start-timer!)
      (catch Exception e
        (println (str "[SCHEDULER] re-arm error: " (.getMessage e)))))))

(defn- start-timer!
  "Schedule the next fire based on current sync_schedule row.
   Only called when enabled=true and executor is running."
  []
  (when-let [^ScheduledExecutorService exec @executor]
    (let [sched   (get-schedule)
          h       (or (:hour sched) 6)
          m       (or (:minute sched) 0)
          now     (java.time.LocalDateTime/now)
          delay   (compute-initial-delay-ms now h m)
          next-at (compute-next-run-at h m)
          handle  (.schedule exec
                              ^Runnable (fn [] (fire!))
                              (long delay)
                              TimeUnit/MILLISECONDS)]
      (reset! current-handle handle)
      ;; Update next_run_at in DB
      (db/execute!
       ["UPDATE sync_schedule SET next_run_at = ?, updated_at = ? WHERE id = 1"
        next-at (now-iso)])
      (println (str "[SCHEDULER] Next fire scheduled at " next-at)))))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn start!
  "Start the singleton scheduler. Idempotent — calling twice is a no-op.
   Reads sync_schedule. If enabled, schedules the next fire."
  []
  (when (nil? @executor)
    (let [exec (Executors/newScheduledThreadPool 1)]
      (reset! executor exec)
      (println "[SCHEDULER] Started")
      (let [sched (get-schedule)]
        (when (= 1 (:enabled sched 0))
          (start-timer!))))))

(defn stop!
  "Stop and shutdown the scheduler. Used on JVM exit or when toggling off."
  []
  (when-let [handle @current-handle]
    (when-not (.isCancelled handle)
      (.cancel handle false))
    (reset! current-handle nil))
  (when-let [^ScheduledExecutorService exec @executor]
    (.shutdownNow exec)
    (reset! executor nil))
  (println "[SCHEDULER] Stopped"))
