(ns analitica.bot.registry
  "CRUD for bot_subscriptions: save/get/list/delete with CSV serde for
   cadences and metrics.  Depends on analitica.bot.subscription for schema
   constants and validation rules (max-metrics, default-metric-set).

   All functions use analitica.db/query and db/execute! (single SQLite DS)."
  (:require [analitica.db :as db]
            [analitica.bot.subscription :as sub]))

;; ---------------------------------------------------------------------------
;; Row coercion: DB map → domain map
;; ---------------------------------------------------------------------------

(defn- row->sub
  "Coerce a raw unqualified SQLite row to a BotSubscription domain map."
  [row]
  (when row
    {:id             (:id row)
     :chat-id        (:chat-id row)
     :label          (:label row)
     :cadences       (sub/csv->cadences (:cadences row))
     :metrics        (sub/csv->metrics (:metrics row))
     :show-movers?   (not (zero? (or (:show-movers row) 0)))
     :marketplace    (keyword (or (:marketplace row) "all"))
     :gate-when-empty (keyword (or (:gate-when-empty row) "skip"))
     :status         (keyword (or (:status row) "active"))
     :created-at     (:created-at row)
     :updated-at     (:updated-at row)}))

;; ---------------------------------------------------------------------------
;; CRUD
;; ---------------------------------------------------------------------------

(defn get-subscription
  "Return BotSubscription for chat-id, or nil if not found."
  [chat-id]
  (-> (db/query ["SELECT * FROM bot_subscriptions WHERE chat_id = ?" chat-id])
      first
      row->sub))

(defn save-subscription!
  "Upsert a subscription for chat-id. Returns the saved BotSubscription map.
   Uses INSERT OR REPLACE (UNIQUE chat_id constraint) — same chat = update."
  [{:keys [chat-id label cadences metrics show-movers? marketplace gate-when-empty]}]
  (let [cadences-csv (sub/cadences->csv (or cadences #{:daily}))
        metrics-csv  (sub/metrics->csv  (or metrics []))
        show-int     (if show-movers? 1 0)
        mp-str       (name (or marketplace :all))
        gate-str     (name (or gate-when-empty :skip))
        now          (str (java.time.LocalDateTime/now))]
    (db/execute!
      ["INSERT INTO bot_subscriptions
          (chat_id, label, cadences, metrics, show_movers, marketplace, gate_when_empty,
           status, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, 'active', ?, ?)
         ON CONFLICT(chat_id) DO UPDATE SET
           label           = excluded.label,
           cadences        = excluded.cadences,
           metrics         = excluded.metrics,
           show_movers     = excluded.show_movers,
           marketplace     = excluded.marketplace,
           gate_when_empty = excluded.gate_when_empty,
           status          = 'active',
           updated_at      = excluded.updated_at"
       chat-id label cadences-csv metrics-csv show-int mp-str gate-str now now])
    (get-subscription chat-id)))

(defn list-subscriptions
  "Return all :active subscriptions."
  []
  (->> (db/query ["SELECT * FROM bot_subscriptions WHERE status = 'active'"])
       (mapv row->sub)))

(defn delete-subscription!
  "Remove subscription for chat-id. Returns nil."
  [chat-id]
  (db/execute! ["DELETE FROM bot_subscriptions WHERE chat_id = ?" chat-id])
  nil)

(defn mark-dormant!
  "Set subscription status to :dormant (after persistent Telegram failures)."
  [chat-id]
  (db/execute!
    ["UPDATE bot_subscriptions SET status = 'dormant', updated_at = ? WHERE chat_id = ?"
     (str (java.time.LocalDateTime/now)) chat-id]))
