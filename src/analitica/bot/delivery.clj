(ns analitica.bot.delivery
  "Idempotent, failure-isolated per-recipient delivery engine.
   Manages bot_deliveries rows: pre-send lookup (delivered→skip, SC-005),
   per-recipient try/catch (SC-006), fail_count/dormant promotion (R4).

   Key invariant (FR-005):
     UNIQUE(chat_id, cadence, period) in bot_deliveries.
     Before sending: lookup existing row.
     - outcome=:delivered → return {:skip-reason :already-delivered}
     - outcome=:gated or :failed → allow retry (re-send attempt)
     - no row → proceed to send
   After send: record outcome via record-delivery!."
  (:require [analitica.db :as db]
            [analitica.bot.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- now-str []
  (str (java.time.LocalDateTime/now)))

(defn- lookup-delivery
  "Return existing delivery record for (chat-id, cadence, period), or nil."
  [chat-id cadence period]
  (first (db/query
           ["SELECT * FROM bot_deliveries
              WHERE chat_id = ? AND cadence = ? AND period = ?"
            chat-id (name cadence) period])))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn record-delivery!
  "Insert or update a delivery record for (chat-id, cadence, period).
   Uses INSERT OR REPLACE so that a gated→delivered upgrade is clean."
  [{:keys [chat-id cadence period outcome detail fail-count]}]
  (db/execute!
    ["INSERT INTO bot_deliveries (chat_id, cadence, period, outcome, detail, fail_count, sent_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(chat_id, cadence, period) DO UPDATE SET
         outcome    = excluded.outcome,
         detail     = excluded.detail,
         fail_count = excluded.fail_count,
         sent_at    = excluded.sent_at"
     chat-id
     (name cadence)
     period
     (name outcome)
     detail
     (or fail-count 0)
     (now-str)]))

(defn maybe-deliver!
  "Idempotency gate + delivery executor.

   `key-map`  — {:chat-id :cadence :period}
   `send-fn`  — zero-arg thunk; called only when delivery should proceed;
                must return {:sent? bool :detail str}.

   Returns:
     {:skip-reason :already-delivered}  — prior outcome=:delivered found
     {:outcome :delivered :detail ...}  — send-fn succeeded
     {:outcome :failed    :detail ...}  — send-fn threw or returned sent?=false
   "
  [{:keys [chat-id cadence period]} send-fn]
  (let [existing (lookup-delivery chat-id cadence period)]
    (if (= "delivered" (:outcome existing))
      {:skip-reason :already-delivered}
      ;; Attempt send
      (let [result (try
                     (send-fn)
                     (catch Throwable t
                       {:sent? false :detail (.getMessage t)}))]
        (if (:sent? result)
          (do
            (record-delivery! {:chat-id    chat-id
                                :cadence    cadence
                                :period     period
                                :outcome    :delivered
                                :detail     (:detail result)
                                :fail-count 0})
            {:outcome :delivered :detail (:detail result)})
          (do
            (let [fail-count (inc (or (:fail-count existing) 0))]
              (record-delivery! {:chat-id    chat-id
                                  :cadence    cadence
                                  :period     period
                                  :outcome    :failed
                                  :detail     (:detail result)
                                  :fail-count fail-count})
              ;; Promote to dormant after threshold (default 5)
              (when (>= fail-count 5)
                (try (registry/mark-dormant! chat-id) (catch Throwable _ nil))))
            {:outcome :failed :detail (:detail result)}))))))
