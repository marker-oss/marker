(ns analitica.settings
  "Runtime, DB-backed settings that override config.edn. One row per dotted
   key in app_settings (see analitica.db). Secrets are stored plaintext on a
   trusted single-tenant host; masking is presentation-only (never reversible)."
  (:require [analitica.db :as db]))

(defn get-all
  "All settings as {key {:value :secret? :updated-at}}."
  []
  (->> (db/query ["SELECT key, value, secret, updated_at FROM app_settings"])
       (reduce (fn [acc {:keys [key value secret updated-at]}]
                 (assoc acc key {:value      value
                                 :secret?    (= 1 secret)
                                 :updated-at updated-at}))
               {})))

(defn overrides
  "Flat {key value} map for the config cascade."
  []
  (->> (db/query ["SELECT key, value FROM app_settings"])
       (reduce (fn [acc {:keys [key value]}] (assoc acc key value)) {})))

(defn set!
  "Upsert one setting. :secret? marks it for masking (default false)."
  [key value & {:keys [secret?] :or {secret? false}}]
  (db/execute! ["INSERT INTO app_settings (key, value, secret, updated_at)
                 VALUES (?, ?, ?, datetime('now'))
                 ON CONFLICT(key) DO UPDATE SET
                   value = excluded.value,
                   secret = excluded.secret,
                   updated_at = excluded.updated_at"
                key value (if secret? 1 0)])
  key)

(defn delete! [key]
  (db/execute! ["DELETE FROM app_settings WHERE key = ?" key])
  key)

(defn mask
  "Presentation-only mask: ••••<last 4>. nil → nil."
  [v]
  (when v
    (let [s (str v)]
      (if (>= (count s) 4)
        (str "••••" (subs s (- (count s) 4)))
        "••••"))))

(defn masked-all
  "get-all with secret values replaced by (mask value)."
  []
  (reduce-kv (fn [acc k {:keys [secret? value] :as m}]
               (assoc acc k (cond-> m secret? (assoc :value (mask value)))))
             {}
             (get-all)))
