(ns analitica.marketplace.ozon.performance.api
  "Ozon Performance API read-only wrappers — spec 011 (T018).

   Each wrapper delegates to the client protocol (client.clj), then runs the
   response through `schema-validator/validate!` against the T009 contract
   registered for the endpoint (:ozon/performance-*). Unknown/renamed fields
   → warning (logged); a critical shape break → throw (caught upstream by the
   ingest failure-isolation wrapper). READ-ONLY: no write/campaign-management
   call exists here (FR-010)."
  (:require [analitica.marketplace.ozon.performance.client :as client]
            [analitica.marketplace.ozon.performance.transform] ;; registers contracts
            [analitica.schema.validator :as validator]
            [clojure.string]))

(defn get-token
  "Return a valid Bearer token (cached inside the client)."
  [client]
  (client/-token client))

(defn list-campaigns
  "GET /api/client/campaign → validated [Campaign …]. Optionally filter by
   `:state` (client-side, since the wire filter is a plain query param the
   underlying client applies when configured)."
  [client & {:keys [state]}]
  (let [campaigns (client/-list-campaigns client)]
    (validator/validate! :ozon/performance-campaign {:list campaigns})
    (cond->> campaigns
      state (filterv #(= state (:state %))))))

(defn daily-stats
  "GET /api/client/statistics/daily → validated [DailyStatRow …] for the given
   campaign ids over [from..to]."
  [client campaign-ids from to]
  (let [rows (client/-daily-stats client campaign-ids from to)]
    (validator/validate! :ozon/performance-daily {:rows rows})
    rows))

;; ---------------------------------------------------------------------------
;; Async statistics report (P1 / US3, T036) — create → poll → download.
;; Same 3-step pattern as the Ozon Seller storage-report (ozon/impl.clj
;; fetch-storage-costs): create returns a task UUID, poll until state OK/ERROR,
;; then download the per-SKU rows. READ-ONLY: create-report is a read-side async
;; request, never a campaign-management write (FR-010).
;; ---------------------------------------------------------------------------

(defn create-statistics-report
  "POST /api/client/statistics (groupBy DATE) → the report UUID string. The
   report covers `campaign-ids` over [from..to]."
  [client campaign-ids from to]
  (client/-create-statistics-report client campaign-ids from to))

(defn poll-statistics-status
  "GET /api/client/statistics/{UUID} → the report state keyword, one of
   :in-progress | :ok | :error. Maps the wire :state string (IN_PROGRESS / OK /
   ERROR) case-insensitively; an unknown state → :in-progress (keep polling)."
  [client uuid]
  (let [state (some-> (:state (client/-statistics-status client uuid))
                      clojure.string/upper-case)]
    (case state
      "OK"          :ok
      "ERROR"       :error
      "IN_PROGRESS" :in-progress
      :in-progress)))

(defn download-statistics-report
  "GET /api/client/statistics/report?UUID= → validated [StatisticsReportRow …]
   (the :rows vector). Same validation posture as daily-stats."
  [client uuid]
  (let [resp (client/-download-statistics-report client uuid)]
    (validator/validate! :ozon/performance-statistics-report resp)
    (:rows resp)))

(defn fetch-statistics-report
  "Full async report cycle: create → poll (bounded) → download. Returns the
   per-SKU rows, or throws on ERROR / timeout (caught upstream by the ingest
   failure-isolation wrapper). `opts`:
     :poll-ms   — sleep between polls (default 3000; tests pass 0)
     :max-polls — attempts before timeout (default 40)."
  [client campaign-ids from to & {:keys [poll-ms max-polls]
                                   :or {poll-ms 3000 max-polls 40}}]
  (let [uuid (create-statistics-report client campaign-ids from to)]
    (when-not uuid
      (throw (ex-info "Performance statistics report UUID missing"
                      {:campaign-ids campaign-ids :from from :to to})))
    (loop [attempt 0]
      (case (poll-statistics-status client uuid)
        :ok    (download-statistics-report client uuid)
        :error (throw (ex-info "Performance statistics report failed"
                               {:uuid uuid :state :error}))
        (if (>= attempt max-polls)
          (throw (ex-info "Performance statistics report timed out"
                          {:uuid uuid :attempts attempt}))
          (do (when (pos? poll-ms) (Thread/sleep poll-ms))
              (recur (inc attempt))))))))
