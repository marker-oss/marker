(ns analitica.ingest
  "Ingest layer: fetch raw data from marketplace APIs and save to raw_data table.
   Does NOT transform data — stores the raw API responses as JSON."
  (:require [analitica.config :as config]
            [analitica.db :as db]
            [analitica.marketplace.registry :as registry]
            [analitica.marketplace.wb.api :as wb-api]
            [analitica.marketplace.ozon.api :as ozon-api]
            [analitica.marketplace.ozon.performance.api :as perf-api]
            [analitica.marketplace.ym.api :as ym-api]
            [analitica.marketplace.ym.client :as ym-client]
            [analitica.schema.validator :as schema-validator]
            [analitica.util.time :as t]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]
            [jsonista.core :as j])
  (:import [java.io ByteArrayOutputStream]
           [java.time LocalDate]
           [java.time.format DateTimeFormatter]
           [java.util.zip ZipInputStream]))

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

(defn ingest-wb-ad-stats!
  "Fetch WB ad campaign list + per-day fullstats for [from..to], persist raw.

   1. `wb-api/ad-campaigns` → list of campaigns (we care about :advertId).
   2. `wb-api/fullstats` for each campaign chunk (≤100 per batch) with the
      [from..to] window — WB API caps a single request at 31 days, so
      callers must already chunk by month for longer periods.
   3. Persist combined response to raw_data with
      source='wb' entity_type='ad_stats'.

   Returns the number of campaigns ingested (distinct campaign stats
   entries in the stored response).

   Empty campaign list short-circuits: `fullstats` is NOT invoked and an
   empty vector is stored so downstream materialize sees a deterministic
   'nothing to do' state (idempotent with prior no-op runs)."
  [client date-from date-to]
  (let [campaigns (wb-api/ad-campaigns client)
        ;; Each campaign entry has shape {:advertId N :status S :type T ...}
        ;; Pull ids for fullstats; filter out nil.
        ids       (into [] (keep #(get % :advertId) campaigns))
        stats     (if (seq ids)
                    (wb-api/fullstats client ids date-from date-to)
                    [])
        cnt       (count stats)]
    (db/insert-raw! :wb :ad_stats date-from date-to (vec stats))
    (println (str "  Ingested WB ad_stats " date-from " .. " date-to
                  ": " cnt " campaigns (from " (count campaigns) " total)"))
    cnt))

;; ---------------------------------------------------------------------------
;; Ozon ingest
;; ---------------------------------------------------------------------------

(def ^:private postings-lookback-days
  "How far back from the requested `from` to widen the in_process_at filter
   when ingesting Ozon postings. Postings whose order date falls outside
   this window won't appear in raw_data even if they were delivered inside
   the requested period — the materialize-sales date filter then drops
   them at the sales-row stage. 60 days covers the long tail of late
   deliveries / cancellations / returns observed in production."
  60)

(defn- ingest-ozon-postings!
  "Fetch FBO + FBS postings, chunking by 3-day windows to defend against
   Ozon's cursor-pagination quirks: in the wild we've observed `last_id`
   returning `\"\"` on a full page (the cursor-stall safety net in
   fbo-orders/fbs-orders then bails at 200–300 items even when the store
   has thousands). With 3-day chunks each window typically holds <100
   postings — fits in a single page request and avoids the stall path
   entirely. Earlier 7-day chunks left 175/353 articles missing in
   downstream materialize for active stores; 3-day shrinks the loss
   surface roughly 2.5× without saturating Ozon rate limits.

   Pulls in_process_at ∈ [from − N days .. to] (default N=60) so postings
   whose order was placed before the requested window but were delivered
   within it land in raw_data. materialize-sales then filters by the
   actual delivery date for the requested window. Without this widening
   ~57% of April Ozon sales rows were missing from the sales table.

   Concatenates results across all chunks before persisting one raw_data row
   for the full [from..to] range (the original window, not the widened one,
   so future ingest runs requested for an earlier period won't double-cover)."
  [client from to]
  (let [shifted-from (t/minus-days from postings-lookback-days)
        chunks (t/date-chunks shifted-from to 3)
        fbo    (vec (mapcat (fn [[cf ct]] (ozon-api/fbo-orders client cf ct)) chunks))
        fbs    (vec (mapcat (fn [[cf ct]] (ozon-api/fbs-orders client cf ct)) chunks))
        data   (into fbo fbs)]
    (db/insert-raw! :ozon :postings from to data)
    (println (str "  Ingested Ozon postings: " (count data) " items "
                  "(FBO: " (count fbo) ", FBS: " (count fbs) ", "
                  (count chunks) " 3-day chunks, lookback "
                  postings-lookback-days "d for cross-month deliveries)"))
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

(defn- ingest-ozon-prices!
  "Pull every product's price details via /v3/product/info/list, batched
   to stay under Ozon's per-request offer_id cap. Two issues this code
   used to have, both silently swallowed by the safe-do wrapper:

     1. Response path. The previous code read `[:result :items]`, but v3
        emits `{:items [...]}` at the top level (see impl.clj sync-sku-map!).
        Result: products always [], item_count=0 — but actually the call
        threw on big stores (see #2), so even the empty insert never ran.
     2. No batching. /v3/product/info/list rejects requests with too many
        offer_ids. impl.clj already mirrors a 500-item batch size for
        sync-sku-map!. We do the same here and concatenate.

   Errors per batch are logged but do not abort the whole ingest — a
   single bad batch shouldn't leave raw_data empty."
  [client]
  (let [today      (today-str)
        items      (ozon-api/product-list client)
        offer-ids  (mapv #(get % :offer_id) items)
        batch-size 500
        products   (->> offer-ids
                        (partition-all batch-size)
                        (mapcat (fn [batch]
                                  (try
                                    (-> (ozon-api/product-info client (vec batch))
                                        (get :items []))
                                    (catch Exception e
                                      (println (str "  WARNING Ozon prices batch (" (count batch)
                                                    " items): " (.getMessage e)))
                                      []))))
                        vec)]
    (db/insert-raw! :ozon :prices today today products)
    (println (str "  Ingested Ozon prices: " (count products) " items "
                  "(" (count offer-ids) " offer_ids in "
                  (quot (+ (count offer-ids) (dec batch-size)) batch-size) " batches)"))
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

(defn- not-yet-published?
  "Ozon realization is published mid-next-month; an early request for the
   current or last-week month commonly comes back as HTTP 404. Treat that
   as 'data not ready', not as an error, so :all ingest doesn't surface
   noisy red lines for an external publication delay we can't fix."
  [throwable]
  (let [d (ex-data throwable)]
    (or (and d (= 404 (:status d)))
        (some-> throwable .getMessage (.contains "status: 404")))))

(defn- ingest-ozon-realization! [client from to]
  (let [months (months-in-range from to)
        total  (volatile! 0)]
    (doseq [[y m] months]
      (let [[mf mt] (month-range y m)]
        (try
          (let [data (schema-validator/with-validation
                       :ozon/finance-realization
                       #(ozon-api/finance-realization client y m)
                       (fn [resp]
                         ;; Only store if the API returned any rows. A
                         ;; not-yet-closed month returns empty; don't
                         ;; overwrite existing raw_data with [].
                         (when (seq (get resp :rows []))
                           (db/insert-raw! :ozon :realization mf mt resp))))
                rows (get data :rows [])]
            (when (seq rows)
              (vswap! total + (count rows))
              (println (str "  Ingested Ozon realization " mf " .. " mt ": "
                            (count rows) " per-article rows"))))
          (catch Throwable t
            (if (not-yet-published? t)
              (println (str "  Ozon realization " mf " .. " mt
                            " not yet published (Ozon publishes mid-next-month) — skipping"))
              (throw t))))))
    @total))

(defn- ingest-ozon-transactions!
  "Fetch /v3/finance/transaction/list operations for [from..to], wrap in
   `:ozon/transaction-list` schema-validation, and persist as raw_data
   with `entity_type = :transactions` (distinct from legacy `:finance`
   and the canonical `:realization` ingest).

   Per US3B (spec 003): this raw is the audit-trail source for per-article
   service attribution. The downstream materialize step reads this raw
   back and merges service costs into realization-derived finance-rows."
  [client from to]
  (let [ops (schema-validator/with-validation
              :ozon/transaction-list
              #(ozon-api/finance-report client from to)
              (fn [operations]
                ;; finance-report returns a flat vector of operations; store the
                ;; wrapped shape {:operations [...]} so the materialize layer can
                ;; read it back with a known key.
                (db/insert-raw! :ozon :transactions from to
                                {:operations (vec operations)})))
        cnt (count ops)]
    (println (str "  Ingested Ozon transactions " from " .. " to ": " cnt " operations"))
    cnt))

;; (Removed ingest-ozon-storage-chunk! — it pointed at /v1/report/postings/create
;; which is the postings export, not a storage report. Ozon paid storage flows
;; through transactions/list as service operations and is materialized into
;; finance directly. See ingest-storage! for the new no-op behaviour.)

;; ---------------------------------------------------------------------------
;; YM ingest
;; ---------------------------------------------------------------------------

(defn- ingest-ym-orders! [client from to]
  ;; YM API only exposes `order-stats` (see US1 trim — ym/api.clj kept the
  ;; authoritative /stats/orders endpoint). Orders and sales both derive
  ;; from this response in downstream materialize.
  (let [data (ym-api/order-stats client from to)]
    (db/insert-raw! :ym :orders from to data)
    (println (str "  Ingested YM orders: " (count data) " items"))
    (count data)))

(defn- ingest-ym-finance! [client from to]
  (let [data (ym-api/order-stats client from to)]
    (db/insert-raw! :ym :finance from to data)
    (println (str "  Ingested YM finance: " (count data) " items"))
    (count data)))

;; ---------------------------------------------------------------------------
;; YM united-netting ingest (US6 / FR-017)
;;
;; The YM /v2/reports/united-netting/generate endpoint is async:
;;   1. POST with {:businessId N :dateFrom D1 :dateTo D2} and query
;;      `?format=JSON` → {:result {:reportId UUID :estimatedGenerationTime ms}}
;;   2. Poll GET /v2/reports/info/{reportId} until status=DONE.
;;      Observed production wait: 40+ min (estimatedGenerationTime is
;;      often optimistic). Backoff is long: 30 → 60 → 120 → 300 → 600 …
;;   3. Download result.file URL. For `format=JSON` the file is a ZIP
;;      containing one JSON entry (observed name: transaction_date.json).
;;
;; Graceful-degrade policy (FR-017): neither timeout nor FAILED raises.
;; Each outcome is mu/log'd and the fn returns nil so callers keep running.
;; ---------------------------------------------------------------------------

(def ^:private default-netting-backoff-seq
  "Sleep seconds between polls. Total cap ≈ 45 min (7 attempts); most
   production reports finish within the first 300 s entry but we budget
   for the observed 40+ min outlier."
  [30 60 120 300 600 600 600])

(defn download-netting-zip
  "Download the netting ZIP from `url`, extract the first `.json` entry,
   and parse it. Returns the parsed map. Public so tests can `with-redefs`
   around the network call."
  [url]
  (let [json-mapper (j/object-mapper {:decode-key-fn true})]
    (with-open [conn-in (io/input-stream url)
                zip-in  (ZipInputStream. conn-in)]
      (loop []
        (if-let [entry (.getNextEntry zip-in)]
          (if (str/ends-with? (str/lower-case (.getName entry)) ".json")
            (let [baos (ByteArrayOutputStream.)]
              (io/copy zip-in baos)
              (j/read-value
                (String. (.toByteArray baos) "UTF-8")
                json-mapper))
            (recur))
          (throw (ex-info "Netting ZIP contained no .json entry"
                          {:url url})))))))

(defn- generate-netting-report!
  "POST /v2/reports/united-netting/generate → returns reportId (string/UUID)
   or nil if the call did not yield one."
  [client date-from date-to]
  (let [business-id (some-> (:business-id client)
                            (cond-> (string? (:business-id client))
                              parse-long))
        resp (ym-client/post-request client
               "/v2/reports/united-netting/generate"
               :query-params {:format "JSON"}
               :body {:businessId business-id
                      :dateFrom   date-from
                      :dateTo     date-to})]
    (get-in resp [:result :reportId])))

(defn- poll-netting-report
  "Poll /v2/reports/info/{report-id} using `backoff-seq` (seconds).
   Returns {:status :done :file URL} on success, {:status :failed} on
   FAILED, {:status :timeout} if the backoff-seq is exhausted without
   a terminal state."
  [client report-id backoff-seq]
  (loop [[wait & more] backoff-seq]
    (when wait
      (Thread/sleep (* 1000 (long wait))))
    (let [resp   (ym-client/get-request client
                   (str "/v2/reports/info/" report-id))
          result (get resp :result {})
          status (get result :status)]
      (cond
        (= "DONE" status)
        {:status :done :file (get result :file) :raw result}

        (= "FAILED" status)
        {:status :failed :raw result}

        (seq more)
        (recur more)

        :else
        {:status :timeout :raw result}))))

(defn ingest-ym-netting!
  "Ingest the YM united-netting report for [dateFrom, dateTo].

   Async flow: POST generate → poll info until DONE → download ZIP →
   extract the single `.json` entry → validate against
   :ym/united-netting → persist to raw_data with source='ym' and
   entity_type='netting'.

   On terminal FAILED status or backoff exhaustion, logs via
   `mu/log` (::netting-failed or ::netting-timeout) and returns nil —
   the operator's wider ingest pipeline keeps going (FR-017).

   Options:
     :dateFrom    — ISO date string 'YYYY-MM-DD' (required)
     :dateTo      — ISO date string 'YYYY-MM-DD' (required)
     :backoff-seq — vector of seconds between polls. Defaults to
                    [30 60 120 300 600 600 600]. Tests pass [0 0 …]
                    to skip actual sleeps."
  [client & {:keys [dateFrom dateTo backoff-seq]
             :or   {backoff-seq default-netting-backoff-seq}}]
  (when-not (and dateFrom dateTo)
    (throw (ex-info "ingest-ym-netting! requires :dateFrom and :dateTo"
                    {:dateFrom dateFrom :dateTo dateTo})))
  (mu/log ::netting-start :date-from dateFrom :date-to dateTo)
  (let [report-id (try
                    (generate-netting-report! client dateFrom dateTo)
                    (catch Throwable t
                      (mu/log ::netting-generate-error
                              :error (.getMessage t)
                              :date-from dateFrom
                              :date-to dateTo)
                      nil))]
    (if-not report-id
      (do (mu/log ::netting-failed
                  :reason :no-report-id
                  :date-from dateFrom
                  :date-to dateTo)
          nil)
      (let [poll (try
                   (poll-netting-report client report-id backoff-seq)
                   (catch Throwable t
                     (mu/log ::netting-poll-error
                             :error (.getMessage t)
                             :report-id report-id)
                     {:status :timeout}))]
        (case (:status poll)
          :done
          (let [file-url (:file poll)]
            (try
              (let [parsed (download-netting-zip file-url)
                    validated (schema-validator/with-validation
                                :ym/united-netting
                                (constantly parsed)
                                #(db/insert-raw! :ym :netting dateFrom dateTo %))
                    cnt (count (get validated :rows []))]
                (mu/log ::netting-done
                        :rows-count cnt
                        :date-from dateFrom
                        :date-to dateTo)
                (println (str "  Ingested YM netting " dateFrom " .. " dateTo
                              ": " cnt " rows"))
                {:rows-count cnt})
              (catch Throwable t
                (mu/log ::netting-download-error
                        :error (.getMessage t)
                        :report-id report-id
                        :file-url file-url)
                nil)))

          :failed
          (do (mu/log ::netting-failed
                      :report-id report-id
                      :date-from dateFrom
                      :date-to dateTo
                      :raw (:raw poll))
              nil)

          :timeout
          (do (mu/log ::netting-timeout
                      :report-id report-id
                      :date-from dateFrom
                      :date-to dateTo
                      :backoff-seq backoff-seq)
              nil))))))

;; YM stocks/prices/product-stats: the API client (ym/api.clj) has
;; full paginating wrappers — `ym-api/stocks`, `ym-api/prices`,
;; `ym-api/sku-stats`. Earlier (US1, commit 1de89e6) the ingest layer
;; was trimmed to only /stats/orders; the API wrappers stayed. Wire
;; them back into raw_data so /sync 'Stocks' / 'Prices' / 'Stats'
;; buttons stop throwing for YM.

(defn- ingest-ym-stocks! [client]
  (let [today (today-str)
        data  (ym-api/stocks client)]
    (db/insert-raw! :ym :stocks today today data)
    (println (str "  Ingested YM stocks: " (count data) " warehouse rows"))
    (count data)))

(defn- ingest-ym-product-stats! [client from to]
  (let [data (ym-api/sku-stats client from to)]
    (db/insert-raw! :ym :product_stats from to data)
    (println (str "  Ingested YM product-stats " from " .. " to))
    1))

(defn- ingest-ym-prices! [client]
  (let [today (today-str)
        data  (ym-api/prices client)]
    (db/insert-raw! :ym :prices today today data)
    (println (str "  Ingested YM prices: " (count data) " offers"))
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
      ;; Ozon finance uses /v2/finance/realization (monthly, per-article)
      ;; as the canonical for-pay/retail source (B-005). Spec 003 US3B then
      ;; layers /v3/finance/transaction/list on top to enable per-article
      ;; logistics/acquiring/storage attribution — ingest both raw shapes
      ;; here so the materialize step has everything it needs.
      (do (println "  Building Ozon SKU map...")
          (ingest-ozon-sku-map! client)
          (try
            (ingest-ozon-realization! client from to)
            (catch Exception e
              (println (str "  ERROR ingesting Ozon realization: " (.getMessage e)))
              0))
          (try
            (ingest-ozon-transactions! client from to)
            (catch Exception e
              (println (str "  ERROR ingesting Ozon transactions: " (.getMessage e)))
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
  "Daily paid-storage costs.

   - WB:   real per-day storage report (`/api/v1/paid_storage`).
   - Ozon: no-op. The previous code POSTed to `/v1/report/postings/create`
           which is the *postings* export, not storage; it returned 4×
           HTTP 400 ('Filter is required'). Ozon paid storage is billed
           via `transactions/list` line items
           (MarketplaceServiceItemTemporaryStorage / WarehouseStock) which
           the finance ingest already pulls; materialize-finance maps
           those services into the existing storage cost columns. A
           separate paid_storage row per day for Ozon would be redundant
           and the API endpoint to produce it doesn't exist here.
   - YM:   not exposed in YM API."
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (let [[from to] (resolve-period period)
        client    (get-mp marketplace)]
    (println (str "Ingesting storage for " (name marketplace) " " from " .. " to))
    (case marketplace
      :wb   (let [chunks (t/date-chunks from to 8)]
              (reduce (fn [acc [chunk-from chunk-to]]
                        (try
                          (+ acc (ingest-wb-storage-chunk! client chunk-from chunk-to))
                          (catch Exception e
                            (println (str "  ERROR " chunk-from ".." chunk-to ": " (.getMessage e)))
                            acc)))
                      0 chunks))
      :ozon (do (println "  Ozon paid storage flows through transactions/list (services)") 0)
      :ym   (do (println "  YM storage not supported") 0))))

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
                         :ym   (ingest-ym-product-stats! client chunk-from chunk-to)))
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

(defn ingest-ads!
  "Public ingest entry-point for WB ad_stats. The function chain (campaigns →
   fullstats → raw_data → flatten → ad_stats table → allocate to finance.ad_cost)
   has existed for a long time but was never wired into `ingest!` / `:all` / the
   sync planner — so DRR was silently 0% across all WB reports. This wrapper
   matches the (period :marketplace mp) contract used by every other ingest
   step and chunks the period to ≤31 days because WB /adv/v2/fullstats rejects
   wider windows."
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (when (not= marketplace :wb)
    (throw (ex-info "ingest-ads! supports only :wb marketplace"
                    {:marketplace marketplace})))
  (let [[from to] (resolve-period period)
        client    (get-mp marketplace)
        chunks    (t/date-chunks from to 31)]
    (println (str "Ingesting WB ad_stats " from " .. " to
                  " (" (count chunks) " chunk(s) of ≤31 days)"))
    (reduce (fn [acc [cf ct]]
              (+ acc (or (ingest-wb-ad-stats! client cf ct) 0)))
            0
            chunks)))

;; ---------------------------------------------------------------------------
;; Ozon Performance (advertising) ingest — spec 011 US1 (T020)
;; ---------------------------------------------------------------------------

(defn- perf-client
  "Resolve the registered OzonPerfClient, or nil if the Performance feature is
   not configured / not registered. (`core/register-marketplaces!` registers
   :ozon-performance only when both credential parts are present.)
   Defensive: any error resolving config/registry ⇒ nil (feature stays off)."
  []
  (try
    (when (config/ozon-performance-config)
      (try (registry/get-marketplace :ozon-performance)
           (catch Exception _ nil)))
    (catch Exception _ nil)))

(defn- do-ingest-ozon-ads!
  "The raw fetch+persist body of ingest-ozon-ads!, without the optionality /
   failure-isolation guards. Kept separate so the guards live in one place.

   The raw payload bundles campaigns + daily rows in one batch so materialize
   knows each campaign's advObjectType (SKU vs BANNER) alongside its spend:
     {:campaigns [Campaign …] :daily-rows [DailyStatRow …]}
   `db/insert-raw!` is INSERT-OR-REPLACE on the natural key (source,
   entity_type, date_from, date_to) → re-ingest is idempotent (FR-007).

   T038 (P1): when `fetch-stats?` is true, also run the async statistics report
   (create → poll → download) and persist per-campaign/per-day efficiency rows
   as raw_data entity_type :ad_campaign_stats. Gated behind the flag so the P0
   daily-stats path remains synchronous and fast by default."
  [client from to & {:keys [fetch-stats?] :or {fetch-stats? false}}]
  (let [_          (perf-api/get-token client)
        campaigns  (perf-api/list-campaigns client)
        camp-ids   (mapv :id campaigns)
        daily-rows (if (seq camp-ids)
                     (perf-api/daily-stats client camp-ids from to)
                     [])]
    (db/insert-raw! :ozon :ad_performance from to
                    {:campaigns campaigns :daily-rows daily-rows})
    (println (str "Ingested Ozon ad_performance " from " .. " to
                  " (" (count campaigns) " campaign(s), "
                  (count daily-rows) " daily row(s))"))
    ;; T038 (P1): async statistics report — per-SKU efficiency counters.
    ;; Runs only when fetch-stats? is true. Non-fatal: a failure here is caught
    ;; by the outer try/catch in ingest-ozon-ads! (failure-isolation, FR-006).
    (let [stat-rows
          (when (and fetch-stats? (seq camp-ids))
            (let [rows (perf-api/fetch-statistics-report client camp-ids from to
                                                         :poll-ms 0)]
              ;; Persist as raw_data :ad_campaign_stats. INSERT-OR-REPLACE on
              ;; the natural key (source, entity_type, date_from, date_to) →
              ;; re-ingest is idempotent (FR-007).
              (db/insert-raw! :ozon :ad_campaign_stats from to
                              {:campaigns campaigns :stat-rows rows})
              (println (str "  Ingested Ozon ad_campaign_stats: "
                            (count rows) " stat row(s)"))
              rows))]
      {:campaigns  (count campaigns)
       :daily-rows (count daily-rows)
       :stat-rows  (count (or stat-rows []))
       :status     :ok})))

(defn ingest-ozon-ads!
  "Fetch Ozon Performance advertising data (token → campaigns → daily stats)
   and persist it as raw_data (source='ozon', entity_type=:ad_performance).

   Arities:
     [from to]                     — resolve the registered OzonPerfClient from
                                     config; when Performance is NOT configured,
                                     short-circuit with {:status :not-configured}.
     [from to & opts]              — same but accepts keyword opts (see below).
     [client from to & opts]       — use an explicit client (or test stub).

   Opts:
     :fetch-stats? (default false) — T038 (P1): also run the async statistics
       report (create → poll → download) for per-SKU/per-campaign efficiency
       counters, persisted as raw_data :ad_campaign_stats. Gated so the P0
       daily-stats path stays synchronous and fast by default.

   OPTIONALITY (FR-005): no credentials ⇒ {:status :not-configured} + one info
   line; nothing is fetched or written; finance.ad_cost stays 0.

   FAILURE-ISOLATION (FR-006/SC-005): the entire fetch is wrapped in try/catch.
   An advertising error (auth 401, timeout, 503) is logged via mu/log
   ::ad-ingest-failed and returned as {:status :ad-unavailable :error …};
   finance.ad_cost is NOT touched and the Seller ingest is unaffected. A 401 is
   retried once inside the client (single token refresh) before it surfaces here.

   READ-ONLY (FR-010): only campaign-list + daily-stats reads are issued.

   Returns {:campaigns N :daily-rows M :stat-rows K :status :ok}
        or {:status :not-configured}
        or {:status :ad-unavailable :error \"…\"}."
  ([from to]
   (if-let [client (perf-client)]
     (ingest-ozon-ads! client from to)
     (do (println "Ozon Performance not configured — skipping ad_stats")
         {:status :not-configured})))
  ([client from to & {:keys [fetch-stats?] :or {fetch-stats? false}}]
   (try
     (do-ingest-ozon-ads! client from to :fetch-stats? fetch-stats?)
     (catch Exception e
       (mu/log ::ad-ingest-failed
               :marketplace :ozon
               :period [from to]
               :error (.getMessage e))
       (println (str "  WARNING: Ozon Performance ad_stats unavailable for "
                     from " .. " to " — " (.getMessage e)
                     " (isolated; finance.ad_cost untouched)"))
       {:status :ad-unavailable :error (.getMessage e)}))))

(defn ingest-ad-stats!
  "Marketplace-generic :ad-stats entry-point (spec 011 T029). Dispatches the
   ad_stats verb by marketplace:
     :wb   → ingest-ads! (WB campaigns/fullstats — existing, chunked ≤31d).
     :ozon → ingest-ozon-ads! (Ozon Performance — resolves the period to
             [from to] then runs the optional/isolated Performance ingest).
   Any other marketplace has no advertising source yet → no-op {:status :none}.

   Supports `clj -M:run ingest :ad-stats --marketplace ozon --period …` and the
   REPL `(ingest! :ad-stats :marketplace :ozon :period …)`."
  [period & {:keys [marketplace] :or {marketplace :wb}}]
  (case marketplace
    :wb   (ingest-ads! period :marketplace :wb)
    :ozon (let [[from to] (resolve-period period)]
            (ingest-ozon-ads! from to))
    (do (println (str "No ad_stats source for marketplace " (name marketplace)))
        {:status :none})))

(defn ingest!
  "Ingest raw data from marketplace API to raw_data table.
   Usage:
     (ingest! :finance :period :last-30-days :marketplace :wb)
     (ingest! :ad-stats :period :last-30-days :marketplace :ozon)
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
    :ad-stats (ingest-ad-stats! period :marketplace marketplace)
    :ad_stats (ingest-ad-stats! period :marketplace marketplace)
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
                  (safe-do "regions"  #(ingest-regions! p :marketplace marketplace))
                  (safe-do "ad_stats" #(ingest-ads!     p :marketplace marketplace)))
                (when (= marketplace :ozon)
                  (safe-do "cashflow" #(ingest-cashflow! p :marketplace marketplace))
                  ;; Spec 011 US2 (T030): Ozon Performance ad_stats — non-fatal
                  ;; and self-isolating (returns :not-configured/:ad-unavailable
                  ;; without throwing); safe-do is an extra guard.
                  (let [[from to] (resolve-period p)]
                    (safe-do "ad_stats" #(ingest-ozon-ads! from to))))
                (println "=== Ingest complete ==="))))
