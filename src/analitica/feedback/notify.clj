(ns analitica.feedback.notify
  "Telegram notification for new feedback. Reads bot-token/chat-id from
   app_settings (self-service). Async + non-fatal: a failure here never
   blocks storing the feedback.

   Also exposes `send-message!` — a generalised single-chat Telegram sender
   reused by analitica.bot.digest (spec 017). Token is always
   'notify.telegram.bot-token'; caller supplies the target chat-id explicitly
   so multiple subscribed chats can be reached from the same bot token."
  (:require [analitica.settings :as settings]
            [hato.client :as hc]
            [clojure.java.io :as io]))

(defn- tg-cfg []
  (let [o (try (settings/overrides) (catch Throwable _ {}))]
    {:token   (get o "notify.telegram.bot-token")
     :chat-id (get o "notify.telegram.chat-id")}))

(defn configured? []
  (let [{:keys [token chat-id]} (tg-cfg)]
    (boolean (and (seq token) (seq chat-id)))))

(defn- message-text [{:keys [id kind message page-url]}]
  (str "🆕 Marker feedback #" id
       "\nТип: " (or kind "—")
       "\nСтраница: " (or page-url "—")
       "\n\n" message))

(defn notify!
  [{:keys [attachments] :as row}]
  (try
    (let [{:keys [token chat-id]} (tg-cfg)]
      (if-not (and (seq token) (seq chat-id))
        {:sent? false :detail "telegram not configured"}
        (do
          (hc/post (str "https://api.telegram.org/bot" token "/sendMessage")
                   {:form-params {:chat_id chat-id :text (message-text row)}
                    :throw-exceptions true})
          (doseq [{:keys [stored-path filename]} attachments]
            (when (and stored-path (.exists (io/file stored-path)))
              (hc/post (str "https://api.telegram.org/bot" token "/sendDocument")
                       {:multipart [{:name "chat_id" :content (str chat-id)}
                                    {:name "document" :content (io/file stored-path)
                                     :file-name filename}]
                        :throw-exceptions true})))
          {:sent? true :detail "ok"})))
    (catch Throwable t
      (println "WARNING: feedback Telegram notify failed:" (.getMessage t))
      {:sent? false :detail (.getMessage t)})))

(defn notify-async! [row]
  (future (notify! row))
  nil)

;; ---------------------------------------------------------------------------
;; Generalised sender — reused by bot.digest (spec 017)
;; ---------------------------------------------------------------------------

(defn send-message!
  "Send a plain-text Telegram message to `chat-id` using the shared bot token.
   Token is read from app_settings 'notify.telegram.bot-token' (same key as
   notify!). Returns {:sent? bool :detail str}. Never throws."
  [chat-id text]
  (try
    (let [token (get (try (settings/overrides) (catch Throwable _ {}))
                     "notify.telegram.bot-token")]
      (if-not (seq token)
        {:sent? false :detail "telegram not configured"}
        (do
          (hc/post (str "https://api.telegram.org/bot" token "/sendMessage")
                   {:form-params {:chat_id chat-id :text text :parse_mode "Markdown"}
                    :throw-exceptions true})
          {:sent? true :detail "ok"})))
    (catch Throwable t
      (println "WARNING: bot Telegram send failed:" (.getMessage t))
      {:sent? false :detail (.getMessage t)})))
