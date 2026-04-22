(ns analitica.marketplace.wb.api
  "Raw WB API endpoint wrappers. Each function returns parsed JSON data."
  (:require [analitica.marketplace.wb.client :as c]))

;; ---------------------------------------------------------------------------
;; Statistics API
;; ---------------------------------------------------------------------------

(defn orders
  "Fetch orders. date-from: yyyy-MM-dd string.
   flag: 0 = changes since date-from, 1 = orders for that specific date."
  [client date-from & {:keys [flag] :or {flag 1}}]
  (c/get-request client :statistics "/api/v1/supplier/orders"
                 :query-params {"dateFrom" date-from
                                "flag"     flag}))

(defn sales
  "Fetch sales/returns. date-from: yyyy-MM-dd string.
   flag: 0 = changes since date-from, 1 = sales for that specific date."
  [client date-from & {:keys [flag] :or {flag 1}}]
  (c/get-request client :statistics "/api/v1/supplier/sales"
                 :query-params {"dateFrom" date-from
                                "flag"     flag}))

(defn stocks
  "Fetch current stock levels on WB warehouses."
  [client]
  (c/get-request client :statistics "/api/v1/supplier/stocks"
                 :query-params {"dateFrom" "2019-01-01"}))

(defn report-detail-by-period
  "Fetch financial report for period. Paginated via rrdid.
   Returns up to `limit` records starting after `rrdid`.
   Call repeatedly until empty response."
  [client date-from date-to & {:keys [rrdid limit] :or {rrdid 0 limit 100000}}]
  (c/get-request client :statistics "/api/v5/supplier/reportDetailByPeriod"
                 :query-params {"dateFrom" date-from
                                "dateTo"   date-to
                                "rrdid"    rrdid
                                "limit"    limit}))

(defn report-detail-by-period-all
  "Fetch ALL pages of the financial report for the period.
   Automatically paginates via rrdid."
  [client date-from date-to]
  (loop [rrdid  0
         result []]
    (let [page (report-detail-by-period client date-from date-to
                                        :rrdid rrdid :limit 100000)]
      (if (or (nil? page) (empty? page))
        result
        (let [last-rrdid (:rrd_id (last page))]
          (recur last-rrdid (into result page)))))))

;; ---------------------------------------------------------------------------
;; Analytics API
;; ---------------------------------------------------------------------------

(defn nm-report-detail
  "Fetch product statistics (views, cart, orders, buyouts, conversion).
   date-from, date-to: yyyy-MM-dd strings."
  [client date-from date-to & {:keys [page] :or {page 1}}]
  (c/post-request client :analytics "/api/v2/nm-report/detail"
                  :body {"period"    {"begin" date-from "end" date-to}
                         "page"      page}))

(defn paid-storage-create
  "Create async paid storage report task."
  [client date-from date-to]
  (c/get-request client :analytics "/api/v1/paid_storage"
                 :query-params {"dateFrom" date-from
                                "dateTo"   date-to}
                 :limiter-key  :wb/analytics-paid-storage-create
                 :limiter-rpm  1))

(defn paid-storage-status
  "Check paid storage task status."
  [client task-id]
  (c/get-request client :analytics
                 (str "/api/v1/paid_storage/tasks/" task-id "/status")
                 :limiter-key :wb/analytics-paid-storage-status
                 :limiter-rpm 12))

(defn paid-storage-download
  "Download paid storage report."
  [client task-id]
  (c/get-request client :analytics
                 (str "/api/v1/paid_storage/tasks/" task-id "/download")
                 :limiter-key :wb/analytics-paid-storage-download
                 :limiter-rpm 1))

(defn region-sales
  "Fetch sales by region."
  [client date-from date-to]
  (c/get-request client :analytics "/api/v1/analytics/region-sale"
                 :query-params {"dateFrom" date-from
                                "dateTo"   date-to}))

(defn warehouse-remains-create
  "Create async warehouse remains report."
  [client]
  (c/get-request client :analytics "/api/v1/warehouse_remains"))

(defn warehouse-remains-status [client task-id]
  (c/get-request client :analytics
                 (str "/api/v1/warehouse_remains/tasks/" task-id "/status")))

(defn warehouse-remains-download [client task-id]
  (c/get-request client :analytics
                 (str "/api/v1/warehouse_remains/tasks/" task-id "/download")))

;; ---------------------------------------------------------------------------
;; Advert API
;; ---------------------------------------------------------------------------

(defn ad-campaigns
  "Fetch ad campaigns info."
  [client & {:keys [status] :or {status [9 11]}}]
  (c/post-request client :advert "/adv/v1/promotion/adverts"
                  :query-params {"status" status}))

(defn fullstats
  "Fetch per-day per-article ad stats for given campaigns over a date range.

   POST /adv/v2/fullstats with body
     [{:id cid :dates [from to]}, ...]

   Returns a flat vector of campaign-stats maps:
     [{:id ..., :days [{:date ..., :apps [{:nm_id ..., :sum ...}]}]} ...]

   Chunks campaign ids into batches of ≤100 per request (WB API limit).
   Empty campaign list → no HTTP call, returns [].

   NOTE: WB requires ≤31-day ranges per request — callers must chunk
   longer periods externally (we don't split by month here to keep the
   wrapper thin)."
  [client campaign-ids date-from date-to]
  (if (empty? campaign-ids)
    []
    (->> (partition-all 100 campaign-ids)
         (mapcat
           (fn [chunk]
             (let [body (mapv (fn [cid] {:id cid :dates [date-from date-to]})
                              chunk)
                   resp (c/post-request client :advert "/adv/v2/fullstats"
                                        :body body)]
               (or resp []))))
         vec)))

;; ---------------------------------------------------------------------------
;; Prices API
;; ---------------------------------------------------------------------------

(defn prices
  "Fetch current prices and discounts. Paginated via offset/limit."
  [client & {:keys [limit offset] :or {limit 1000 offset 0}}]
  (c/get-request client :prices "/api/v2/list/goods/filter"
                 :query-params {"limit"  limit
                                "offset" offset}))
