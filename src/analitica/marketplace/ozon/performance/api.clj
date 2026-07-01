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
            [analitica.schema.validator :as validator]))

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
