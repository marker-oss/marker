(ns analitica.marketplace.wb.api
  "Raw WB API endpoint wrappers. Each function returns parsed JSON data."
  (:require [analitica.marketplace.wb.client :as c]
            [clojure.string :as cstr]))

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
  "Fetch all WB ad campaigns. Returns a flat vector of
   `{:advertId N :changeTime T :type T :status S}` maps.

   The legacy `/adv/v1/promotion/adverts` (POST + query status filter) was
   removed by WB in 2025. The replacement is `GET /adv/v1/promotion/count`
   which returns campaigns grouped by (type, status):

     {\"adverts\": [{\"type\": 9, \"status\": 11, \"count\": 116,
                     \"advert_list\": [{\"advertId\": ..., \"changeTime\": ...}, ...]},
                    ...]}

   We flatten this into one entry per campaign and re-attach the parent
   group's `:type` and `:status` so downstream callers (fullstats) can
   still filter. The `status`/`type` keyword args are kept for
   compatibility but now act as post-flatten filters.

   Default statuses #{7 9 11} = completed + active + paused. Completed (7)
   MUST be included: re-syncing a past period after its campaigns finish
   would otherwise fetch fullstats for nothing, silently zeroing historical
   WB ad spend (audit 2026-07-02 P0-1a)."
  [client & {:keys [status type]
             :or   {status #{7 9 11}}}]
  (let [resp     (c/get-request client :advert "/adv/v1/promotion/count")
        groups   (or (get resp :adverts) [])
        wanted-s (if (set? status) status (set status))
        wanted-t (when type (if (set? type) type (set type)))]
    (->> groups
         (filter (fn [g]
                   (and (or (nil? wanted-s) (contains? wanted-s (:status g)))
                        (or (nil? wanted-t) (contains? wanted-t (:type g))))))
         (mapcat (fn [g]
                   (mapv (fn [a]
                           (assoc a :type (:type g) :status (:status g)))
                         (or (:advert_list g) []))))
         vec)))

(defn fullstats
  "Fetch per-day per-article ad stats for given campaigns over a date range.

   The legacy `POST /adv/v2/fullstats` (body = `[{:id :dates}]`) was
   removed in 2025; replacement is `GET /adv/v3/fullstats` with query
   params `ids=...&begin=YYYY-MM-DD&end=YYYY-MM-DD`. The response shape
   is preserved by WB:

     [{:advertId ..., :days [{:date ..., :apps [{:nm_id ..., :sum ...}]}]} ...]

   Returns the concatenated vector across batches. Chunks campaign ids
   into ≤50 per request — the v3 endpoint enforces a stricter limit than
   the legacy v2 (which accepted 100); WB returns 400 'number of advert
   cannot be more than 50' when the cap is exceeded. Empty list
   short-circuits.

   NOTE: WB requires ≤31-day ranges per request — callers must chunk
   longer periods externally."
  [client campaign-ids date-from date-to]
  (if (empty? campaign-ids)
    []
    (->> (partition-all 50 campaign-ids)
         (mapcat
           (fn [chunk]
             (let [ids-csv (cstr/join "," (map str chunk))
                   resp    (c/get-request client :advert "/adv/v3/fullstats"
                                          :query-params {"ids"       ids-csv
                                                         "beginDate" date-from
                                                         "endDate"   date-to})]
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
