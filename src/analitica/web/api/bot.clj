(ns analitica.web.api.bot
  "HTTP handlers for /api/v1/bot/* — Telegram digest bot subscriptions (spec 017).

   Routes (registered in server.clj — see returned routes):
     GET    /api/v1/bot/subscriptions          → list + bot-configured? + max-metrics
     POST   /api/v1/bot/subscriptions          → create (upsert) a subscription
     PUT    /api/v1/bot/subscriptions/:chat-id → update a subscription
     DELETE /api/v1/bot/subscriptions/:chat-id → unsubscribe
     POST   /api/v1/bot/test                    → manual digest test (reuses notify path)
     POST   /api/v1/bot/max-request             → record a MAX forward-link request (FR-027)

   Subscriptions key on chat-id (registry keys by chat_id; UNIQUE), so the
   mutating routes take :chat-id, not a numeric :id.

   Token source (§5): app_settings 'notify.telegram.bot-token' (shared Marker bot).
   configured? = (seq token) — same path as feedback/notify.

   Contract: specs/017-engagement-bot-planfact/contracts/bot-subscription.edn §4."
  (:require [analitica.settings :as settings]
            [analitica.bot.subscription :as sub]
            [analitica.bot.registry :as registry]
            [analitica.bot.digest :as digest]
            [analitica.feedback.notify :as notify]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- body-of [req]
  (let [b (:body req)]
    (if (map? b) b (or (:params req) {}))))

(defn- chat-id-of [req]
  (or (get-in req [:params :chat-id])
      (get-in req [:route-params :chat-id])))

(defn- ->kw [x]
  (cond (keyword? x) x
        (and (string? x) (seq x)) (keyword x)
        :else nil))

(defn- coerce-cadences
  "Accept a set/vector of strings or keywords, or a CSV string → #{:daily …}."
  [cadences]
  (cond
    (string? cadences) (sub/csv->cadences cadences)
    (coll? cadences)   (into #{} (keep ->kw) cadences)
    :else              #{}))

(defn- coerce-metrics
  "Accept a vector of strings or keywords, or a CSV string → ordered [:slug …]."
  [metrics]
  (cond
    (string? metrics) (sub/csv->metrics metrics)
    (coll? metrics)   (into [] (keep ->kw) metrics)
    :else             []))

(defn- coerce-params
  "Coerce a raw request body into the domain subscription param map."
  [body chat-id]
  {:chat-id         (str chat-id)
   :label           (:label body)
   :cadences        (coerce-cadences (:cadences body))
   :metrics         (coerce-metrics (:metrics body))
   :show-movers?    (boolean (:show-movers? body))
   :marketplace     (or (->kw (:marketplace body)) :all)
   :gate-when-empty (or (->kw (:gate-when-empty body)) :skip)})

(defn- bot-configured? []
  (boolean (seq (get (try (settings/overrides) (catch Throwable _ {}))
                     "notify.telegram.bot-token"))))

(defn- save-subscription
  "Shared create/update path: validate (FR-012 max-metrics), then upsert.
   Returns a ring response map."
  [params]
  (let [verdict (sub/validate-subscription params)]
    (if-not (:valid? verdict)
      {:status 422 :body {:saved? false :error (:error verdict)}}
      (try
        (let [saved (registry/save-subscription! params)]
          {:status 200 :body {:saved? true :subscription saved}})
        (catch Exception e
          {:status 500 :body {:saved? false :error (.getMessage e)}})))))

;; ---------------------------------------------------------------------------
;; GET /api/v1/bot/subscriptions
;; ---------------------------------------------------------------------------

(defn get-subscriptions
  "GET /api/v1/bot/subscriptions
   → {:subscriptions [BotSubscription…] :bot-configured? bool :max-metrics n}"
  [_req]
  {:status 200
   :body   {:subscriptions   (vec (registry/list-subscriptions))
            :bot-configured? (bot-configured?)
            :max-metrics     sub/max-metrics}})

;; ---------------------------------------------------------------------------
;; POST /api/v1/bot/subscriptions  (create)
;; ---------------------------------------------------------------------------

(defn post-subscription
  "POST /api/v1/bot/subscriptions
   Body: {:chat-id :label :cadences :metrics :show-movers? :marketplace :gate-when-empty}
   → {:saved? true :subscription …} | 422 {:saved? false :error …}"
  [req]
  (let [body    (body-of req)
        chat-id (:chat-id body)]
    (if-not (seq (str chat-id))
      {:status 422 :body {:saved? false :error "chat-id required"}}
      (save-subscription (coerce-params body chat-id)))))

;; ---------------------------------------------------------------------------
;; PUT /api/v1/bot/subscriptions/:chat-id  (update)
;; ---------------------------------------------------------------------------

(defn put-subscription
  "PUT /api/v1/bot/subscriptions/:chat-id
   Body: subscription fields (chat-id taken from the path).
   → {:saved? true :subscription …} | 422 {:saved? false :error …}"
  [req]
  (if-let [chat-id (chat-id-of req)]
    (save-subscription (coerce-params (body-of req) chat-id))
    {:status 400 :body {:saved? false :error "Missing or invalid chat-id"}}))

;; ---------------------------------------------------------------------------
;; DELETE /api/v1/bot/subscriptions/:chat-id  (unsubscribe)
;; ---------------------------------------------------------------------------

(defn delete-subscription
  "DELETE /api/v1/bot/subscriptions/:chat-id → {:deleted? true}"
  [req]
  (if-let [chat-id (chat-id-of req)]
    (do
      (registry/delete-subscription! chat-id)
      {:status 200 :body {:deleted? true}})
    {:status 400 :body {:deleted? false :error "Missing or invalid chat-id"}}))

;; ---------------------------------------------------------------------------
;; POST /api/v1/bot/test  (manual digest test)
;; ---------------------------------------------------------------------------

(defn collect-payload!
  "Collect the DigestSource payload for a subscription over [from to].
   Wrapped as its own fn so tests can with-redef it (avoids the heavy
   collect-page-data! DB path). Mirrors bot.scheduler/collect-payload!."
  [subscription from to]
  (try
    (let [mp     (:marketplace subscription)
          mp-arg (when (not= mp :all) mp)]
      ((requiring-resolve 'analitica.web.pages.digest/collect-page-data!)
       :from from :to to :marketplace mp-arg))
    (catch Throwable t
      (println "WARNING: bot /test collect-payload! failed:" (.getMessage t))
      nil)))

(defn post-test
  "POST /api/v1/bot/test  body {:chat-id …}
   Assembles yesterday's digest for the chat's subscription and sends it via
   the shared notify path. → {:sent? bool :detail str}."
  [req]
  (let [chat-id (str (:chat-id (body-of req)))
        sub-row (when (seq chat-id) (registry/get-subscription chat-id))]
    (cond
      (not (seq chat-id))
      {:status 200 :body {:sent? false :detail "chat-id required"}}

      (nil? sub-row)
      {:status 200 :body {:sent? false :detail "no subscription for chat-id"}}

      :else
      (let [today   (str (java.time.LocalDate/now))
            payload (collect-payload! sub-row today today)]
        (if (nil? payload)
          {:status 200 :body {:sent? false :detail "report data not available"}}
          (let [result (digest/build-and-send!
                         payload sub-row
                         {:gate-mode :flag :sender-fn notify/send-message!})]
            {:status 200
             :body   {:sent?   (= :delivered (:outcome result))
                      :outcome (:outcome result)
                      :detail  (:detail result)}}))))))

;; ---------------------------------------------------------------------------
;; POST /api/v1/bot/max-request  (FR-027)
;; ---------------------------------------------------------------------------

(defn post-max-request
  "POST /api/v1/bot/max-request  body {:contact …}
   Records a MAX forward-link request (channel is not supported; TG-first).
   → {:recorded? true}"
  [req]
  (let [contact (str (or (:contact (body-of req)) ""))
        stamp   (str (java.time.LocalDateTime/now))]
    (try
      (settings/set! "bot.max-request" (str stamp " | " contact))
      {:status 200 :body {:recorded? true}}
      (catch Exception e
        {:status 500 :body {:recorded? false :error (.getMessage e)}}))))
