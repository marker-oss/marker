(ns analitica.marketplace.ozon.api
  "Raw Ozon Seller API endpoint wrappers. Each function returns parsed JSON data."
  (:require [analitica.marketplace.ozon.client :as c]
            [analitica.util.http :as http]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- ->timestamp
  "Convert ISO date string '2024-01-15' to RFC3339 timestamp required by Ozon API."
  [date-str]
  (if (re-find #"T" date-str)
    date-str
    (str date-str "T00:00:00Z")))

;; ---------------------------------------------------------------------------
;; FBO Orders — cursor pagination via last_id
;; ---------------------------------------------------------------------------

(defn- fbo-orders-page [client date-from date-to last-id]
  (c/post-request client "/v3/posting/fbo/list"
                  :body {"dir"     "asc"
                         "filter"  {"since" (->timestamp date-from)
                                    "to"    (->timestamp date-to)}
                         "limit"   100
                         "with"    {"analytics_data" true "financial_data" true}
                         "last_id" last-id}))

(defn fbo-orders
  "Fetch all FBO orders for the given date range. Paginates via cursor (last_id)."
  [client date-from date-to]
  (loop [last-id "" result []]
    (let [resp     (fbo-orders-page client date-from date-to last-id)
          postings (get-in resp [:result :postings] [])]
      (if (empty? postings)
        result
        (let [new-last-id (get-in resp [:result :last_id] "")]
          (if (= new-last-id last-id)
            (into result postings)
            (recur new-last-id (into result postings))))))))

;; ---------------------------------------------------------------------------
;; FBS Orders — cursor pagination via last_id
;; ---------------------------------------------------------------------------

(defn- fbs-orders-page [client date-from date-to last-id]
  (c/post-request client "/v3/posting/fbs/list"
                  :body {"dir"     "asc"
                         "filter"  {"since" (->timestamp date-from)
                                    "to"    (->timestamp date-to)}
                         "limit"   100
                         "with"    {"analytics_data" true "financial_data" true}
                         "last_id" last-id}))

(defn fbs-orders
  "Fetch all FBS orders for the given date range. Paginates via cursor (last_id)."
  [client date-from date-to]
  (loop [last-id "" result []]
    (let [resp     (fbs-orders-page client date-from date-to last-id)
          postings (get-in resp [:result :postings] [])]
      (if (empty? postings)
        result
        (let [new-last-id (get-in resp [:result :last_id] "")]
          (if (= new-last-id last-id)
            (into result postings)
            (recur new-last-id (into result postings))))))))

;; ---------------------------------------------------------------------------
;; Finance Transactions — /v3/finance/transaction/list
;; ---------------------------------------------------------------------------

(defn- finance-page [client date-from date-to page]
  (c/post-request client "/v3/finance/transaction/list"
                  :body {"filter"    {"date"             {"from" (->timestamp date-from)
                                                          "to"   (->timestamp date-to)}
                                      "transaction_type" "all"}
                         "page"      page
                         "page_size" 1000}))

(defn finance-report
  "Fetch all finance transactions for the given date range. Paginates automatically."
  [client date-from date-to]
  (loop [page 1 result []]
    (let [resp       (finance-page client date-from date-to page)
          operations (get-in resp [:result :operations] [])
          page-count (get-in resp [:result :page_count] 1)
          acc        (into result operations)]
      (if (< page page-count)
        (recur (inc page) acc)
        acc))))

;; ---------------------------------------------------------------------------
;; Finance Realization — /v2/finance/realization (monthly, per-article)
;; This is the CLEAN source for per-article P&L aggregation. Unlike
;; transaction/list (which is a full operational audit trail with ~83%
;; account-level services that can't be mapped to single articles),
;; /v2/finance/realization returns one row per (article × month) with
;; commission/amount breakdowns ready for P&L rollup.
;; ---------------------------------------------------------------------------

(defn finance-realization
  "Fetch the monthly realization report for the given year/month.
   Returns {:header {…} :rows [{…per-article…}]} from :result.

   Unlike transaction/list, this is a summary per article — single request
   per month, no pagination needed. Max granularity is month."
  [client year month]
  (let [resp (c/post-request client "/v2/finance/realization"
                             :body {"year"  year
                                    "month" month})]
    (:result resp)))

;; ---------------------------------------------------------------------------
;; Product List — paginated, defined before stocks (stocks depends on it)
;; ---------------------------------------------------------------------------

(defn- product-list-page [client last-id]
  (c/post-request client "/v3/product/list"
                  :body {"filter"  {}
                         "last_id" last-id
                         "limit"   1000}))

(defn product-list
  "Fetch all products. Paginates automatically.
   Each item has :product_id (SKU) and :offer_id (article)."
  [client]
  (loop [last-id "" result []]
    (let [resp  (product-list-page client last-id)
          items (get-in resp [:result :items] [])]
      (if (empty? items)
        result
        (let [new-last-id (get-in resp [:result :last_id] "")]
          (if (= new-last-id last-id)
            (into result items)
            (recur new-last-id (into result items))))))))

;; ---------------------------------------------------------------------------
;; Stocks — /v2/analytics/stock_on_warehouses (paginated, all products)
;; ---------------------------------------------------------------------------

(defn stocks
  "Fetch all stock levels by warehouse using /v2/analytics/stock_on_warehouses.
   Returns rows with :item_code (offer_id), :sku, :warehouse_name, :free_to_sell_amount."
  [client]
  (loop [offset 0 result []]
    (let [resp  (c/post-request client "/v2/analytics/stock_on_warehouses"
                                :body {"limit"          1000
                                       "offset"         offset
                                       "warehouse_type" "ALL"})
          rows  (get-in resp [:result :rows] [])]
      (if (empty? rows)
        result
        (recur (+ offset 1000) (into result rows))))))

;; ---------------------------------------------------------------------------
;; Analytics Data
;; ---------------------------------------------------------------------------

(defn analytics-data
  "Fetch analytics data for the given date range and metrics list."
  [client date-from date-to metrics]
  (c/post-request client "/v1/analytics/data"
                  :body {"date_from" date-from
                         "date_to"   date-to
                         "metrics"   metrics
                         "dimension" ["sku"]
                         "limit"     1000
                         "offset"    0}))

;; ---------------------------------------------------------------------------
;; Product Info
;; ---------------------------------------------------------------------------

(defn product-info
  "Fetch product info for a list of offer-ids."
  [client offer-ids]
  (c/post-request client "/v2/product/info/list"
                  :body {"offer_id" offer-ids}))

(defn product-info-by-sku
  "Fetch product info for a list of numeric SKUs.
   Returns items with :offer_id and :sources (containing sku mappings)."
  [client skus]
  (c/post-request client "/v3/product/info/list"
                  :body {"sku" skus}))

;; ---------------------------------------------------------------------------
;; Storage Report (async)
;; ---------------------------------------------------------------------------

(defn storage-report-create
  "Create an async storage report task. Returns the reportId string."
  [client date-from date-to]
  (get-in (c/post-request client "/v1/report/postings/create"
                           :body {"date_from" date-from "date_to" date-to})
          [:result :code]))

(defn storage-report-status
  "Check the status of an async storage report task."
  [client report-id]
  (get (c/get-request client (str "/v1/report/info/" report-id)) :result))

(defn storage-report-download
  "Download the storage report file from the given URL."
  [_client url]
  (http/request {:method :get :url url}))

;; ---------------------------------------------------------------------------
;; Cash Flow Statement — /v1/finance/cash-flow-statement/list
;; ---------------------------------------------------------------------------

(defn cash-flow-statement
  "Fetch cash flow statement (Финансы → Выплаты) for the given date range.
   Automatically paginates. Returns {:cash_flows [...] :details [...]}."
  [client date-from date-to]
  (loop [page 1 all-cf [] all-dt []]
    (let [resp       (c/post-request client "/v1/finance/cash-flow-statement/list"
                       :body {"date"         {"from" (->timestamp date-from)
                                              "to"   (->timestamp date-to)}
                              "with_details" true
                              "page"         page
                              "page_size"    50})
          result     (get resp :result {})
          page-count (get result :page_count 1)
          cf         (into all-cf (get result :cash_flows []))
          dt         (into all-dt (get result :details []))]
      (if (< page page-count)
        (recur (inc page) cf dt)
        {:cash_flows cf :details dt}))))
