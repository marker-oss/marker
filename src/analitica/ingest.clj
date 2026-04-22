(ns analitica.ingest
  "Ingest layer: fetch raw data from marketplace APIs and save to raw_data table.
   Does NOT transform data — stores the raw API responses as JSON."
  (:require [analitica.db :as db]
            [analitica.marketplace.registry :as registry]
            [analitica.marketplace.wb.api :as wb-api]
            [analitica.marketplace.ozon.api :as ozon-api]
            [analitica.marketplace.ym.api :as ym-api]
            [analitica.schema.validator :as schema-validator]
            [analitica.util.time :as t])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- resolve-period [period]
  (cond
    (keyword? period) (t/period period)
    (vector? period)  period
    :else             [(:from period) (:to period)]))

(defn- get-mp [marketplace]
  (registry/get-marketplace (or marketplace :wb)))

(defn- today-str []
  (.format (LocalDate/now) (DateTimeFormatter/ofPattern "yyyy-MM-dd")))

;; ---------------------------------------------------------------------------
;; WB ingest
;; ---------------------------------------------------------------------------

(defn- ingest-wb-orders! [client from to]
  (let [data (wb-api/orders client from :flag 0)]
    (db/insert-raw! :wb :orders from to data)
    (println (str "  Ingested WB orders: " (count data) " items"))
    (count data)))

(defn- ingest-wb-sales! [client from to]
  (let [data (wb-api/sales client from :flag 0)]
    (db/insert-raw! :wb :sales from to data)
    (println (str "  Ingested WB sales: " (count data) " items"))
    (count data)))

(defn- ingest-wb-finance-chunk! [client chunk-from chunk-to]
  (let [data (schema-validator/with-validation
               :wb/report-detail-by-period
               #(wb-api/report-detail-by-period-all client chunk-from chunk-to)
               #(db/insert-raw! :wb :finance chunk-from chunk-to %))]
    (println (str "  Ingested WB finance " chunk-from " .. " chunk-to ": " (count data) " items"))
    (count data)))

(defn- ingest-wb-storage-chunk! [client chunk-from chunk-to]
  ;; Async flow: create -> poll -> download
  (let [task-id (get-in (wb-api/paid-storage-create client chunk-from chunk-to)
                        [:data :taskId])]
    (when-not task-id
      (throw (ex-info "Paid storage task id is missing"
                      {:date-from chunk-from :date-to chunk-to})))
    (loop [attempts 0]
      (Thread/sleep 5000)
      (let [status (get-in (wb-api/paid-storage-status client task-id) [:data :status])]
        (cond
          (= "done" status)
          (let [data (or (wb-api/paid-storage-download client task-id) [])]
            (db/insert-raw! :wb :storage chunk-from chunk-to data)
            (println (str "  Ingested WB storage " chunk-from " .. " chunk-to ": " (count data) " items"))
            (count data))

          (#{"error" "failed"} status)
          (throw (ex-info "Paid storage task failed"
                          {:task-id task-id :status status}))

          (>= attempts 24)
          (throw (ex-info "Paid storage task timed out"
                          {:task-id task-id :status status}))

          :else
          (recur (inc attempts)))))))

(defn- ingest-wb-stocks! [client]
  (let [today (today-str)
        data  (wb-api/stocks client)]
    (db/insert-raw! :wb :stocks today today data)
    (println (str "  Ingested WB stocks: " (count data) " items"))
    (count data)))

(defn- ingest-wb-product-stats-chunk! [client chunk-from chunk-to]
  ;; Paginate through nm-report-detail pages within a single chunk
  (let [data (loop [page 1 result []]
               (let [resp  (wb-api/nm-report-detail client chunk-from chunk-to :page page)
                     cards (get-in resp [:data :cards] [])]
                 (if (empty? cards)
                   result
                   (recur (inc page) (into result cards)))))]
    (db/insert-raw! :wb :product_stats chunk-from chunk-to data)
    (println (str "  Ingested WB product stats " chunk-from " .. " chunk-to ": " (count data) " items"))
    (count data)))

(defn- ingest-wb-prices! [client]
  (let [today (today-str)
        ;; Paginate via offset
        data  (loop [offset 0 result []]
                (let [resp  (wb-api/prices client :limit 1000 :offset offset)
                      items (get-in resp [:data :listGoods] [])]
                  (if (empty? items)
                    result
                    (recur (+ offset 1000) (into result items)))))]
    (db/insert-raw! :wb :prices today today data)
    (println (str "  Ingested WB prices: " (count data) " items"))
    (count data)))

(defn- ingest-wb-regions! [client from to]
  (let [data (wb-api/region-sales client from to)]
    (db/insert-raw! :wb :regions from to data)
    (println (str "  Ingested WB regions: " (count data) " items"))
    (count data)))

;; ---------------------------------------------------------------------------
;; Ozon ingest
;; ---------------------------------------------------------------------------

(defn- ingest-ozon-postings!
  "Fetch FBO + FBS postings once. Both orders and sales derive from this."
  [client from to]
  (let [fbo  (ozon-api/fbo-orders client from to)
        fbs  (ozon-api/fbs-orders client from to)
        data (into (vec fbo) fbs)]
    (db/insert-raw! :ozon :postings from to data)
    (println (str "  Ingested Ozon postings: " (count data) " items (FBO: " (count fbo) ", FBS: " (count fbs) ")"))
    (count data)))

(defn- ingest-ozon-stocks! [client]
  (let [today (today-str)
        data  (ozon-api/stocks client)]
    (db/insert-raw! :ozon :stocks today today data)
    (println (str "  Ingested Ozon stocks: " (count data) " items"))
    (count data)))

(defn- ingest-ozon-product-stats! [client from to]
  (let [metrics ["views" "session_view_pdp" "conv_tocart_pdp" "ordered_units"
                 "revenue" "cancellations" "returns" "delivered_units" "gross_profit"]
        data    (ozon-api/analytics-data client from to metrics)]
    (db/insert-raw! :ozon :product_stats from to data)
    (println "  Ingested Ozon product stats")
    1))

(defn- ingest-ozon-prices! [client]
  (let [today    (today-str)
        items    (ozon-api/product-list client)
        offer-ids (mapv #(get % :offer_id) items)
        resp     (ozon-api/product-info client offer-ids)
        products (get-in resp [:result :items] [])]
    (db/insert-raw! :ozon :prices today today products)
    (println (str "  Ingested Ozon prices: " (count products) " items"))
    (count products)))

(defn- ingest-ozon-sku-map!
  "Ingest Ozon product catalog for SKU → offer_id mapping.
   Also populates ozon_sku_map table for use by materialize."
  [client]
  (try
    ((requiring-resolve 'analitica.marketplace.ozon.impl/sync-sku-map!) client)
    (catch Exception e
      (println (str "  WARNING: Could not build Ozon SKU map: " (.getMessage e))))))

(defn- ingest-ozon-cashflow! [client from to]
  (let [data (ozon-api/cash-flow-statement client from to)
        cnt  (count (:cash_flows data))]
    (db/insert-raw! :ozon :cashflow from to data)
    (println (str "  Ingested Ozon cash flow: " cnt " periods"))
    cnt))

(defn- months-in-range
  "Return seq of [year month] pairs covering every month that overlaps [from,to].
   `from` and `to` are ISO date strings 'YYYY-MM-DD'."
  [from to]
  (let [parse (fn [s]
                (let [ld (java.time.LocalDate/parse s)]
                  [(.getYear ld) (.getMonthValue ld)]))
        [y1 m1] (parse from)
        [y2 m2] (parse to)]
    (loop [y y1 m m1 acc []]
      (cond
        (or (> y y2) (and (= y y2) (> m m2)))
        acc
        (= m 12)
        (recur (inc y) 1 (conj acc [y m]))
        :else
        (recur y (inc m) (conj acc [y m]))))))

(defn- month-range
  "Return [first-day last-day] ISO strings for a (year, month) pair."
  [year month]
  (let [ld-first (java.time.LocalDate/of ^int year ^int month 1)
        ld-last  (.withDayOfMonth ld-first (.lengthOfMonth ld-first))]
    [(.toString ld-first) (.toString ld-last)]))

(defn- ingest-ozon-realization! [client from to]
  (let [months (months-in-range from to)
        total  (volatile! 0)]
    (doseq [[y m] months]
      (let [[mf mt] (month-range y m)
            data    (schema-validator/with-validation
                      :ozon/finance-realization
                      #(ozon-api/finance-realization client y m)
                      (fn [resp]
                        ;; Only store if the API returned any rows (a not-yet-closed month
                        ;; returns an empty report; we don't overwrite existing raw_data
                        ;; with an empty snapshot in that case).
                        (when (seq (get resp :rows []))
                          (db/insert-raw! :ozon :realization mf mt resp))))
            rows    (get data :rows [])]
        (when (seq rows)
          (vswap! total + (count rows))
          (println (str "  Ingested Ozon realization " mf " .. " mt ": "
                        (count rows) " per-article rows")))))
    @total))

(defn- ingest-ozon-storage-chunk! [client chunk-from chunk-to]
  (let [report-id (ozon-api/storage-report-create client chunk-from chunk-to)]
    (when-not report-id
      (throw (ex-info "Ozon storage report id is missing"
                      {:date-from chunk-from :date-to chunk-to})))
    (loop [attempts 0]
      (Thread/sleep 10000)
      (let [result (ozon-api/storage-report-status client report-id)
            status (get result :status)]
        (cond
          (= "ready" status)
          (let [url  (get result :file)
                data (let [d (ozon-api/storage-report-download client url)]
                       (if (sequential? d) d []))]
            (db/insert-raw! :ozon :storage chunk-from chunk-to data)
            (println (str "  Ingested Ozon storage " chunk-from " .. " chunk-to ": " (count data) " items"))
            (count data))

          (= "error" status)
          (throw (ex-info "Ozon storage report failed"
                          {:report-id report-id :status status}))

          (>= attempts 23)
          (throw (ex-info "Ozon storage report timed out"
                          {:report-id report-id :status status}))

          :else
          (recur (inc attempts)))))))

;; ---------------------------------------------------------------------------
;; YM ingest
;; ---------------------------------------------------------------------------

(defn- ingest-ym-orders! [client from to]
  (let [data (ym-api/orders client from to)]
    (db/insert-raw! :ym :orders from to data)
    (println (str "  Ingested YM orders: " (count data) " items"))
    (count data)))

(defn- ingest-ym-finance! [client from to]
  (let [data (ym-api/order-stats client from to)]
    (db/insert-raw! :ym :finance from to data)
    (println (str "  Ingested YM finance: " (count data) " items"))
    (count data)))

(defn- ingest-ym-stocks! [client]
  (let [today (today-str)
        data  (ym-api/stocks client)]
    (db/insert-raw! :ym :stocks today today data)
    (println (str "  Ingested YM stocks: " (count data) " items"))
    (count data)))

(defn- ingest-ym-product-stats! [client from to]
  (let [data (ym-api/sku-stats client from to)]
    (db/insert-raw! :ym :product_stats from to data)
    (println "  Ingested YM product stats")
    1))

(defn- ingest-ym-prices! [client]
  (let [today (today-str)
        data  (ym-api/prices client)]
    (db/insert-raw! :ym :prices today today data)
    (println (str "  Ingested YM prices: " (count data) " items"))
    (count data)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn ingest-orders!
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [[from to] (resolve-period period)
        client    (get-mp marketplace)]
    (println (str "Ingesting orders for " (name marketplace) " " from " .. " to))
    (case marketplace
      :wb   (ingest-wb-orders! client from to)
      :ozon (ingest-ozon-postings! client from to)
      :ym   (ingest-ym-orders! client from to))))

(defn ingest-sales!
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [[from to] (resolve-period period)
        client    (get-mp marketplace)]
    (println (str "Ingesting sales for " (name marketplace) " " from " .. " to))
    (case marketplace
      :wb   (ingest-wb-sales! client from to)
      ;; Ozon: postings are shared; ingest once, derive both orders and sales
      :ozon (ingest-ozon-postings! client from to)
      ;; YM: orders and sales use same raw data
      :ym   (ingest-ym-orders! client from to))))

(defn ingest-finance!
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [[from to] (resolve-period period)
        client    (get-mp marketplace)]
    (println (str "Ingesting finance for " (name marketplace) " " from " .. " to))
    (case marketplace
      :ozon
      ;; Ozon finance now uses /v2/finance/realization (monthly, per-article).
      ;; The old /v3/finance/transaction/list data is kept for audit trail only
      ;; and is not refreshed by this command — see specs/002-calculation-audit
      ;; verdicts.md §B-005 for the rationale.
      (do (println "  Building Ozon SKU map...")
          (ingest-ozon-sku-map! client)
          (try
            (ingest-ozon-realization! client from to)
            (catch Exception e
              (println (str "  ERROR ingesting Ozon realization: " (.getMessage e)))
              0)))
      ;; WB and YM keep their existing per-chunk pipeline.
      (reduce (fn [acc [chunk-from chunk-to]]
                (try
                  (+ acc (case marketplace
                           :wb (ingest-wb-finance-chunk! client chunk-from chunk-to)
                           :ym (let [data (schema-validator/with-validation
                                             :ym/order-stats
                                             #(ym-api/order-stats client chunk-from chunk-to)
                                             #(db/insert-raw! :ym :finance chunk-from chunk-to %))]
                                 (println (str "  Ingested YM finance " chunk-from " .. " chunk-to ": " (count data) " items"))
                                 (count data))))
                  (catch Exception e
                    (println (str "  ERROR " chunk-from ".." chunk-to ": " (.getMessage e)))
                    acc)))
              0 (t/date-chunks from to 30)))))

(defn ingest-storage!
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [[from to] (resolve-period period)
        client    (get-mp marketplace)
        chunks    (t/date-chunks from to 8)]
    (println (str "Ingesting storage for " (name marketplace) " " from " .. " to))
    (reduce (fn [acc [chunk-from chunk-to]]
              (try
                (+ acc (case marketplace
                         :wb   (ingest-wb-storage-chunk! client chunk-from chunk-to)
                         :ozon (ingest-ozon-storage-chunk! client chunk-from chunk-to)
                         :ym   (do (println "  YM storage not supported") 0)))
                (catch Exception e
                  (println (str "  ERROR " chunk-from ".." chunk-to ": " (.getMessage e)))
                  acc)))
            0 chunks)))

(defn ingest-stocks!
  [& {:keys [marketplace] :or {marketplace :wb}}]
  (let [client (get-mp marketplace)]
    (println (str "Ingesting stocks for " (name marketplace)))
    (case marketplace
      :wb   (ingest-wb-stocks! client)
      :ozon (ingest-ozon-stocks! client)
      :ym   (ingest-ym-stocks! client))))

(defn ingest-product-stats!
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [[from to] (resolve-period period)
        client    (get-mp marketplace)
        ;; WB nm-report-detail allows max 30-day periods
        chunks    (t/date-chunks from to 30)]
    (println (str "Ingesting product stats for " (name marketplace) " " from " .. " to))
    (reduce (fn [acc [chunk-from chunk-to]]
              (try
                (+ acc (case marketplace
                         :wb   (ingest-wb-product-stats-chunk! client chunk-from chunk-to)
                         :ozon (do (let [data (ozon-api/analytics-data client chunk-from chunk-to
                                                ["views" "session_view_pdp" "conv_tocart_pdp" "ordered_units"
                                                 "revenue" "cancellations" "returns" "delivered_units" "gross_profit"])]
                                     (db/insert-raw! :ozon :product_stats chunk-from chunk-to data)
                                     (println (str "  Ingested Ozon product stats " chunk-from " .. " chunk-to))
                                     1))
                         :ym   (do (let [data (ym-api/sku-stats client chunk-from chunk-to)]
                                     (db/insert-raw! :ym :product_stats chunk-from chunk-to data)
                                     (println (str "  Ingested YM product stats " chunk-from " .. " chunk-to))
                                     1))))
                (catch Exception e
                  (println (str "  ERROR " chunk-from ".." chunk-to ": " (.getMessage e)))
                  acc)))
            0 chunks)))

(defn ingest-prices!
  [& {:keys [marketplace] :or {marketplace :wb}}]
  (let [client (get-mp marketplace)]
    (println (str "Ingesting prices for " (name marketplace)))
    (case marketplace
      :wb   (ingest-wb-prices! client)
      :ozon (ingest-ozon-prices! client)
      :ym   (ingest-ym-prices! client))))

(defn ingest-cashflow!
  [period & {:keys [marketplace] :or {marketplace :ozon}}]
  (if-not (= marketplace :ozon)
    (println "Cash flow statement is only available for Ozon")
    (let [[from to] (resolve-period period)
          client    (get-mp marketplace)]
      (println (str "Ingesting cash flow for " (name marketplace) " " from " .. " to))
      (ingest-ozon-cashflow! client from to))))

(defn ingest-regions!
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (when (not= marketplace :wb)
    (println "ingest-regions! supports only :wb marketplace")
    (throw (ex-info "ingest-regions! supports only :wb marketplace"
                    {:marketplace marketplace})))
  (let [[from to] (resolve-period period)
        client    (get-mp marketplace)]
    (println (str "Ingesting regions for " (name marketplace) " " from " .. " to))
    (ingest-wb-regions! client from to)))

(defn ingest!
  "Ingest raw data from marketplace API to raw_data table.
   Usage:
     (ingest! :finance :period :last-30-days :marketplace :wb)
     (ingest! :all :period :last-30-days :marketplace :ozon)"
  [what & {:keys [period marketplace] :or {marketplace :wb}}]
  (println (str "\n=== Ingest: " (name what) " ==="))
  (case what
    :sales    (ingest-sales! period :marketplace marketplace)
    :orders   (ingest-orders! period :marketplace marketplace)
    :finance  (ingest-finance! period :marketplace marketplace)
    :storage  (ingest-storage! period :marketplace marketplace)
    :stocks   (ingest-stocks! :marketplace marketplace)
    :stats    (ingest-product-stats! period :marketplace marketplace)
    :prices   (ingest-prices! :marketplace marketplace)
    :regions  (ingest-regions! period :marketplace marketplace)
    :cashflow (ingest-cashflow! period :marketplace marketplace)
    :all      (let [p       (or period :last-30-days)
                    safe-do (fn [label f]
                              (try (f)
                                   (catch Exception e
                                     (println (str "  ERROR [" label "]: " (.getMessage e))))))]
                (safe-do "orders"  #(ingest-orders! p :marketplace marketplace))
                (safe-do "sales"   #(ingest-sales! p :marketplace marketplace))
                (safe-do "finance" #(ingest-finance! p :marketplace marketplace))
                (safe-do "storage" #(ingest-storage! p :marketplace marketplace))
                (safe-do "stocks"  #(ingest-stocks! :marketplace marketplace))
                (safe-do "stats"   #(ingest-product-stats! p :marketplace marketplace))
                (safe-do "prices"  #(ingest-prices! :marketplace marketplace))
                (when (= marketplace :wb)
                  (safe-do "regions" #(ingest-regions! p :marketplace marketplace)))
                (when (= marketplace :ozon)
                  (safe-do "cashflow" #(ingest-cashflow! p :marketplace marketplace)))
                (println "=== Ingest complete ==="))))
