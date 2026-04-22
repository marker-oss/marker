(ns analitica.marketplace.ym.api
  "Raw Yandex Market Partner API endpoint wrappers. Each function returns parsed JSON data."
  (:require [analitica.marketplace.ym.client :as client]))

;; ---------------------------------------------------------------------------
;; Order Stats — POST /campaigns/{campaignId}/stats/orders
;; Primary source for commission/cost breakdown per-order.
;;
;; FR-020 (spec 003 US1): :statuses is optional. When omitted (or empty), YM
;; returns orders across ALL statuses. Callers who need a subset can pass
;; e.g. `:statuses ["DELIVERED"]` — preserved for backward compatibility.
;; ---------------------------------------------------------------------------

(defn- order-stats-page
  [client date-from date-to page-token statuses]
  (client/post-request client
    (str "/campaigns/" (:campaign-id client) "/stats/orders")
    :body (cond-> {:dateFrom date-from
                   :dateTo   date-to}
            page-token       (assoc :pageToken page-token)
            (seq statuses)   (assoc :statuses (vec statuses)))))

(defn order-stats
  "Fetch order statistics with commission breakdown.
   Paginates automatically via pageToken.

   Optional kwarg `:statuses` — when provided and non-empty, restricts the
   query to that subset (e.g. `[\"DELIVERED\"]`). When omitted/nil/empty,
   YM returns orders of all statuses (FR-020)."
  [client date-from date-to & {:keys [statuses]}]
  (loop [page-token nil
         result     []]
    (let [resp       (order-stats-page client date-from date-to page-token statuses)
          items      (get-in resp [:result :orders] [])
          next-token (get-in resp [:result :paging :nextPageToken])]
      (let [accumulated (into result items)]
        (if (and (seq next-token) (seq items))
          (recur next-token accumulated)
          accumulated)))))
