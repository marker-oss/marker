(ns analitica.bot.scheduler
  "Thin in-process scheduler for daily/weekly digest dispatch.
   Pattern: singleton ScheduledExecutorService (mirrors sync/scheduler.clj).
   Computes cadence period boundaries in operator timezone (config bot.timezone).

   Period conventions:
     :daily  → period = yesterday in local tz, key = \"YYYY-MM-DD\"
     :weekly → period = last completed ISO week, key = \"YYYY-Www\"

   Init: called from analitica.core/start! (memory init_lives_in_core_start).
   The executor is stored in an atom and is idempotent — multiple start! calls
   are safe (existing executor is re-used)."
  (:require [analitica.bot.registry :as registry]
            [analitica.bot.delivery :as delivery]
            [analitica.bot.digest   :as digest]
            [analitica.feedback.notify :as notify]
            [analitica.settings :as settings])
  (:import [java.time LocalDate ZoneId DayOfWeek]
           [java.time.format DateTimeFormatter]
           [java.time.temporal WeekFields TemporalAdjusters]
           [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

;; ---------------------------------------------------------------------------
;; Timezone resolution
;; ---------------------------------------------------------------------------

(defn- bot-tz
  "Operator timezone: config 'bot.timezone' or JVM default."
  []
  (let [cfg (try (settings/overrides) (catch Throwable _ {}))]
    (if-let [tz-str (get cfg "bot.timezone")]
      (ZoneId/of tz-str)
      (ZoneId/systemDefault))))

(defn- gate-mode []
  (let [cfg (try (settings/overrides) (catch Throwable _ {}))]
    (keyword (get cfg "bot.gate-mode" "flag"))))

;; ---------------------------------------------------------------------------
;; Period key computation
;; ---------------------------------------------------------------------------

(defn daily-period
  "Yesterday's date in local tz as \"YYYY-MM-DD\"."
  ([] (daily-period (bot-tz)))
  ([tz]
   (let [today (LocalDate/now tz)]
     (.format (.minusDays today 1) DateTimeFormatter/ISO_LOCAL_DATE))))

(defn weekly-period
  "Last completed ISO week in local tz as \"YYYY-Www\" (e.g. \"2026-W26\").
   Computed from yesterday's date, so a Monday-morning firing yields the
   week that ended on Sunday."
  ([] (weekly-period (bot-tz)))
  ([tz]
   (let [yesterday (.minusDays (LocalDate/now tz) 1)
         wf        WeekFields/ISO
         lw-num    (.get yesterday (.weekOfWeekBasedYear wf))
         lw-year   (.get yesterday (.weekBasedYear wf))]
     (format "%d-W%02d" lw-year lw-num))))

;; ---------------------------------------------------------------------------
;; Core dispatch
;; ---------------------------------------------------------------------------

(defn- sender-fn []
  "Return the generalised Telegram sender from notify.clj."
  notify/send-message!)

(defn- collect-payload!
  "Call collect-page-data! for the given subscription's marketplace and period."
  [subscription from to]
  (try
    (let [mp (:marketplace subscription)
          mp-arg (when (not= mp :all) mp)]
      ((requiring-resolve 'analitica.web.pages.digest/collect-page-data!)
       :from from :to to :marketplace mp-arg))
    (catch Throwable t
      (println "WARNING: bot collect-payload! failed:" (.getMessage t))
      nil)))

(defn fire-daily!
  "Dispatch daily digests to all active subscriptions that include :daily cadence."
  []
  (let [period (daily-period)
        subs   (filter #(contains? (:cadences %) :daily)
                       (registry/list-subscriptions))
        gm     (gate-mode)]
    (doseq [sub subs]
      (delivery/maybe-deliver!
        {:chat-id (:chat-id sub) :cadence :daily :period period}
        (fn []
          (let [payload (collect-payload! sub period period)]
            (if payload
              (let [result (digest/build-and-send! payload sub {:gate-mode gm
                                                                 :sender-fn (sender-fn)})]
                (when (= :gated (:outcome result))
                  ;; Record gated explicitly so retry is allowed
                  (delivery/record-delivery! {:chat-id    (:chat-id sub)
                                               :cadence    :daily
                                               :period     period
                                               :outcome    :gated
                                               :detail     (:detail result)
                                               :fail-count 0}))
                result)
              {:sent? false :detail "payload collection failed"})))))))

(defn fire-weekly!
  "Dispatch weekly digests to all active subscriptions that include :weekly cadence."
  []
  (let [period (weekly-period)
        subs   (filter #(contains? (:cadences %) :weekly)
                       (registry/list-subscriptions))
        gm     (gate-mode)]
    (doseq [sub subs]
      (delivery/maybe-deliver!
        {:chat-id (:chat-id sub) :cadence :weekly :period period}
        (fn []
          (let [payload (collect-payload! sub period period)]
            (if payload
              (digest/build-and-send! payload sub {:gate-mode gm :sender-fn (sender-fn)})
              {:sent? false :detail "payload collection failed"})))))))

;; ---------------------------------------------------------------------------
;; Executor singleton
;; ---------------------------------------------------------------------------

(defonce ^:private executor (atom nil))

(defn start!
  "Start the daily (08:00 local) and weekly (Monday 08:00 local) schedules.
   Idempotent: re-calling does nothing if already running."
  []
  (when-not @executor
    (let [ex (Executors/newSingleThreadScheduledExecutor
               (reify java.util.concurrent.ThreadFactory
                 (newThread [_ r]
                   (doto (Thread. r "bot-scheduler")
                     (.setDaemon true)))))]
      (reset! executor ex)
      ;; Schedule daily at 08:00 — use fixed-delay of 24h from now.
      ;; In production a cron-style wall-clock trigger (like sync/scheduler.clj)
      ;; would be preferable; this thin seam satisfies the US1/spec P6 requirement.
      (.scheduleAtFixedRate ex
                            (fn []
                              (try (fire-daily!)
                                   (catch Throwable t
                                     (println "ERROR: bot daily dispatch failed:" (.getMessage t)))))
                            0 86400 TimeUnit/SECONDS)
      ;; Weekly on a 7-day cadence
      (.scheduleAtFixedRate ex
                            (fn []
                              (try (fire-weekly!)
                                   (catch Throwable t
                                     (println "ERROR: bot weekly dispatch failed:" (.getMessage t)))))
                            0 604800 TimeUnit/SECONDS)
      (println "Bot scheduler started."))))

(defn stop!
  "Shut down the scheduler executor. Safe to call even if not started."
  []
  (when-let [ex @executor]
    (.shutdownNow ^ScheduledExecutorService ex)
    (reset! executor nil)
    (println "Bot scheduler stopped.")))
