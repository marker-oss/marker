(ns analitica.freshness
  "Stale-data detection — pure layer.

  Pure:  (stale-info* opts last-syncs) — given a pre-fetched last-syncs map,
                                          returns a staleness descriptor.
  Impure: (last-sync-by-mp-entity)    — queries DB, returns the map.
          (stale-info  opts)           — one-shot: fetches + computes.

  Returns:
    {:status :ok}
    {:status :stale :reason \"…\" :last-sync iso :age-days N
     :worst-pair [:mp :source] :max-lag-days N}"
  (:require [analitica.db :as db]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as μ]))

;; ---------------------------------------------------------------------------
;; Hardcoded V1 max-lag thresholds (days)
;; ---------------------------------------------------------------------------

(def ^:private max-lag-days
  {[:wb   :finance]  7
   [:wb   :storage]  14
   [:wb   :regions]  7
   [:wb   :stocks]   2
   [:wb   :sales]    2
   [:wb   :orders]   2
   [:wb   :prices]   7
   [:wb   :stats]    2

   [:ozon :finance]  30
   [:ozon :cashflow] 30
   [:ozon :sales]    2
   [:ozon :orders]   2
   [:ozon :stocks]   2
   [:ozon :prices]   7
   [:ozon :stats]    2

   [:ym   :finance]  2
   [:ym   :sales]    2
   [:ym   :orders]   2
   [:ym   :stocks]   2
   [:ym   :prices]   7
   [:ym   :stats]    2})

;; ---------------------------------------------------------------------------
;; Reports → data sources mapping
;; ---------------------------------------------------------------------------

(def ^:private report->sources
  {:sales   [:sales]
   :orders  [:orders]
   :finance [:finance]
   :ue      [:sales :finance :stats]
   :pnl     [:finance]
   :abc     [:finance :sales]
   :stock   [:stocks]
   :returns [:finance :sales]
   :buyout  [:finance :sales]
   :geo     [:sales]
   :trends  [:sales :finance]
   :losses  [:finance :stocks]})

(defn report-data-sources
  "Returns a vector of data-source keywords for the given report type.
   Returns nil for unknown report types."
  [report-type]
  (get report->sources report-type))

;; ---------------------------------------------------------------------------
;; All supported marketplaces
;; ---------------------------------------------------------------------------

(def ^:private all-mps [:wb :ozon :ym])

;; ---------------------------------------------------------------------------
;; ISO date arithmetic helpers
;; ---------------------------------------------------------------------------

(defn- parse-date
  "Parse first 10 chars of an ISO string as LocalDate."
  [iso]
  (when (and iso (>= (count iso) 10))
    (java.time.LocalDate/parse (subs iso 0 10))))

(defn- days-between
  "Days from `iso-from` date to `today-str` date (positive = today is later)."
  [iso-from today-str]
  (if-let [from-date (parse-date iso-from)]
    (let [today-date (java.time.LocalDate/parse today-str)]
      (.between java.time.temporal.ChronoUnit/DAYS from-date today-date))
    ;; nil last-sync = never synced → treat as extremely old
    Long/MAX_VALUE))

;; ---------------------------------------------------------------------------
;; Russian MP labels
;; ---------------------------------------------------------------------------

(def ^:private mp-label {:wb "WB" :ozon "Ozon" :ym "YM"})

;; ---------------------------------------------------------------------------
;; Pure core
;; ---------------------------------------------------------------------------

(defn stale-info*
  "Pure stale-data detector. Does not touch the DB.

  opts:
    :report      — keyword e.g. :pnl
    :marketplace — keyword: :wb | :ozon | :ym | :all | nil (nil → all)
    :today       — ISO date string YYYY-MM-DD (default: today via Java)

  last-syncs:
    map {[:mp :entity-type] iso-datetime-or-nil}

  Returns:
    {:status :ok}  when no pair exceeds its threshold
    {:status :stale :reason str :last-sync iso :age-days N
     :worst-pair [:mp :src] :max-lag-days N}"
  [{:keys [report marketplace today]} last-syncs]
  (let [today-str  (or today
                       (str (java.time.LocalDate/now)))
        sources    (report-data-sources report)
        mps        (if (contains? #{nil :all} marketplace)
                     all-mps
                     [marketplace])
        ;; Build candidate (mp, source) pairs
        candidates (for [mp mps src sources] [mp src])
        ;; Compute age for each pair
        aged       (for [[mp src :as pair] candidates
                         :let [ts        (get last-syncs pair)
                               age       (days-between ts today-str)
                               threshold (get max-lag-days pair
                                             ;; unknown pair: use default 7
                                             7)]]
                     {:pair  pair :age age :threshold threshold :ts ts})
        ;; Find stale pairs (strict >)
        stale-ones (filter #(> (:age %) (:threshold %)) aged)]
    (if (empty? stale-ones)
      {:status :ok}
      ;; Pick worst: most days-over-threshold
      (let [worst     (apply max-key #(- (:age %) (:threshold %)) stale-ones)
            [mp src]  (:pair worst)
            ts        (:ts worst)]
        {:status       :stale
         :reason       (if ts
                         (str (mp-label mp mp) " " (name src)
                              " отстаёт на " (:age worst) " дн.")
                         (str (mp-label mp mp) " " (name src)
                              " — синхронизация не выполнялась"))
         :last-sync    ts
         :age-days     (if (= Long/MAX_VALUE (:age worst)) nil (:age worst))
         :worst-pair   (:pair worst)
         :max-lag-days (:threshold worst)}))))

;; ---------------------------------------------------------------------------
;; Impure: DB fetch
;; ---------------------------------------------------------------------------

(defn last-sync-by-mp-entity
  "Query sync_tasks for MAX(finished_at) per (marketplace, entity_type) where
   status = 'ok'. Returns map {[:mp-kw :entity-kw] iso-or-nil}."
  []
  (try
    (let [rows (db/query
                ["SELECT marketplace, entity_type, MAX(finished_at) AS last_sync
                  FROM sync_tasks
                  WHERE status = 'ok'
                  GROUP BY marketplace, entity_type"])]
      (into {}
            (map (fn [r]
                   [(vector (keyword (:marketplace r))
                            (keyword (:entity-type r)))
                    (:last-sync r)])
                 rows)))
    (catch Exception e
      (μ/log ::last-sync-query-failed :error (.getMessage e))
      {})))

(defn stale-info
  "One-shot: fetch last-syncs from DB and compute stale status.

  opts:
    :report      — keyword e.g. :pnl
    :marketplace — keyword: :wb | :ozon | :ym | :all | nil
    :period      — ignored by this function (present for caller convenience)"
  [opts]
  (let [last-syncs (last-sync-by-mp-entity)]
    (stale-info* opts last-syncs)))
