(ns analitica.marketplace.ym.api
  "Raw Yandex Market Partner API endpoint wrappers. Each function returns parsed JSON data."
  (:require [analitica.marketplace.ym.client :as client]))

;; ---------------------------------------------------------------------------
;; Orders — GET /campaigns/{campaignId}/orders
;; ---------------------------------------------------------------------------

(defn orders
  "Fetch orders for the given date range.
   Paginates automatically via page number."
  [client date-from date-to]
  (loop [page 1
         result []]
    (let [resp  (client/get-request client
                  (str "/campaigns/" (:campaign-id client) "/orders")
                  :query-params {:fromDate date-from
                                 :toDate   date-to
                                 :page     page
                                 :pageSize 50})
          items (get resp :orders [])
          pager (get resp :pager {})]
      (let [accumulated (into result items)]
        (if (and (seq items)
                 (< page (get pager :pagesCount 1)))
          (recur (inc page) accumulated)
          accumulated)))))

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

;; ---------------------------------------------------------------------------
;; Stocks — paginated via nextPageToken
;; ---------------------------------------------------------------------------

(defn- stocks-page
  [client page-token]
  (client/post-request client
                       (str "/campaigns/" (:campaign-id client) "/offers/stocks")
                       :query-params (when page-token {:page_token page-token})
                       :body {}))

(defn stocks
  "Fetch all stock levels. Paginates automatically via nextPageToken."
  [client]
  (loop [page-token nil
         result     []]
    (let [resp       (stocks-page client page-token)
          items      (get-in resp [:result :warehouses] [])
          next-token (get-in resp [:result :paging :nextPageToken])]
      (let [accumulated (into result items)]
        (if (seq next-token)
          (recur next-token accumulated)
          accumulated)))))

;; ---------------------------------------------------------------------------
;; Finance Report (async) — goods-realization
;; ---------------------------------------------------------------------------

(defn finance-report-generate
  "Initiate async goods-realization report generation.
   Returns the reportId string."
  [client year month]
  (let [resp (client/post-request client
               "/v2/reports/goods-realization/generate"
               :query-params {:format "JSON"}
               :body {:campaignId (parse-long (str (:campaign-id client)))
                      :year       year
                      :month      month})]
    (get-in resp [:result :reportId])))

(defn finance-report-status
  "Check the status of an async report by reportId.
   Returns the full response map."
  [client report-id]
  (client/get-request client (str "/v2/reports/info/" report-id)))

;; ---------------------------------------------------------------------------
;; SKU Stats — POST, no pagination
;; ---------------------------------------------------------------------------

(defn sku-stats
  "Fetch SKU-level statistics for the given date range."
  [client date-from date-to]
  (client/post-request client
                       (str "/businesses/" (:business-id client) "/stats/skus")
                       :body {:dateFrom date-from :dateTo date-to}))

;; ---------------------------------------------------------------------------
;; Prices — paginated via nextPageToken
;; ---------------------------------------------------------------------------

(defn- prices-page
  [client page-token]
  (let [params (cond-> {}
                 page-token (assoc :page_token page-token))]
    (client/get-request client
                        (str "/campaigns/" (:campaign-id client) "/offer-prices")
                        :query-params params)))

(defn prices
  "Fetch all offer prices. Paginates automatically via nextPageToken."
  [client]
  (loop [page-token nil
         result     []]
    (let [resp       (prices-page client page-token)
          items      (get-in resp [:result :offers] [])
          next-token (get-in resp [:result :paging :nextPageToken])]
      (let [accumulated (into result items)]
        (if (seq next-token)
          (recur next-token accumulated)
          accumulated)))))
