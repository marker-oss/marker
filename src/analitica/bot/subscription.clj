(ns analitica.bot.subscription
  "Durable per-chat bot subscription registry — schema constants + validation.

   Stores and retrieves `bot_subscriptions` rows (FR-001, FR-009, data-model §1).
   Each chat has at most one subscription (UNIQUE chat_id); a repeat subscribe
   for the same chat is an upsert, not a duplicate.

   CSV encoding:
     :cadences  — #{:daily :weekly} ↔ \"daily,weekly\" (or \"daily\" / \"weekly\")
     :metrics   — [:revenue :net-profit …] ↔ \"revenue,net-profit\" (ordered CSV)

   Validation (FR-012, FR-013):
     - cadences must be non-empty (at least one cadence)
     - metrics count ≤ max-metrics (default 10; from app_settings \"bot.max-metrics\")
     - metric slugs absent from the active 016 dictionary are silently dropped at
       render time and purged on the next save (FR-013)

   Init: called exclusively from analitica.core/start! (memory init_lives_in_core_start)."
  (:require [analitica.web.report-schemas :as rs]))

;; ---------------------------------------------------------------------------
;; Constants (spec §1, contracts/bot-subscription.edn)
;; ---------------------------------------------------------------------------

(def max-metrics
  "Maximum number of metric slugs per subscription (FR-012).
   Configurable via app_settings 'bot.max-metrics'; 10 is the default."
  10)

(def default-metric-set
  "Default ordered metric slugs applied when :metrics is empty (FR-011).
   All slugs MUST exist in 016 canonical-metric-slugs."
  [:revenue :net-profit :margin-pct :drr-pct])

(def valid-cadences #{:daily :weekly})

;; ---------------------------------------------------------------------------
;; CSV serde helpers
;; ---------------------------------------------------------------------------

(defn cadences->csv [cadences-set]
  (->> cadences-set (map name) sort (clojure.string/join ",")))

(defn csv->cadences [s]
  (->> (clojure.string/split (or s "") #",")
       (keep #(when (seq %) (keyword %)))
       (filter valid-cadences)
       set))

(defn metrics->csv [metrics-vec]
  (->> metrics-vec (map name) (clojure.string/join ",")))

(defn csv->metrics [s]
  (if (clojure.string/blank? s)
    []
    (->> (clojure.string/split s #",")
         (keep #(when (seq %) (keyword %)))
         vec)))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn validate-metrics
  "Returns {:valid? bool :rejected [...] :error str}.
   Validates that all slugs are in 016 canonical-metric-slugs and count ≤ max."
  [metrics]
  (let [known    rs/canonical-metric-slugs
        rejected (vec (remove known metrics))
        too-many (> (count metrics) max-metrics)]
    (cond
      too-many
      {:valid? false
       :rejected []
       :error (str "metrics exceed max (" max-metrics ")")}

      (seq rejected)
      {:valid? false
       :rejected rejected
       :error (str "unknown metric slugs: " rejected)}

      :else
      {:valid? true :rejected [] :error nil})))

(defn validate-subscription
  "Validates a subscription param map before save.
   Returns {:valid? bool :error str-or-nil}."
  [{:keys [cadences metrics]}]
  (cond
    (empty? cadences)
    {:valid? false :error "select at least one cadence"}

    :else
    (let [mv (validate-metrics (or metrics []))]
      (if (:valid? mv)
        {:valid? true :error nil}
        mv))))
