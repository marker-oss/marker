(ns analitica.marketplace.ozon.impl
  (:require [analitica.marketplace.protocol :as proto]
            [analitica.marketplace.ozon.client :as client]
            [analitica.marketplace.ozon.api :as api]
            [analitica.marketplace.ozon.transform :as transform]
            [analitica.db :as db])
  (:import [analitica.marketplace.ozon.client OzonClient]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- months-in-range
  "Returns a seq of [year month] pairs covering the range [date-from date-to].
   date-from and date-to are ISO date strings like \"2024-01-15\"."
  [date-from date-to]
  (let [parse-ym  (fn [s] [(Integer/parseInt (subs s 0 4))
                            (Integer/parseInt (subs s 5 7))])
        [y1 m1]   (parse-ym date-from)
        [y2 m2]   (parse-ym date-to)]
    (loop [y y1 m m1 acc []]
      (if (or (< y y2) (and (= y y2) (<= m m2)))
        (recur (if (= m 12) (inc y) y)
               (if (= m 12) 1 (inc m))
               (conj acc [y m]))
        acc))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- all-postings
  "Fetch FBO + FBS orders and return as a single seq."
  [client date-from date-to]
  (concat (api/fbo-orders client date-from date-to)
          (api/fbs-orders client date-from date-to)))

(defn sync-sku-map!
  "Build and persist {sku → offer_id} mapping from Ozon product catalog.
   Fetches product list, then product info to extract fbo_sku/fbs_sku.

   /v3/product/info/list caps each request at ~1000 offer_ids, so
   we batch accordingly."
  [client]
  (let [items      (api/product-list client)
        offer-ids  (mapv #(get % :offer_id) items)
        batch-size 500
        products   (mapcat (fn [batch]
                             ;; v3 returns {:items [...]} at top level,
                             ;; not wrapped in :result.
                             (get (api/product-info client (vec batch))
                                  :items []))
                           (partition-all batch-size offer-ids))
        pairs     (->> products
                       (mapcat (fn [p]
                                 (let [offer-id (get p :offer_id)]
                                   (for [src  (get p :sources [])
                                         :let [sku (get src :sku)]
                                         :when sku]
                                     [sku offer-id]))))
                       (into {})
                       vec)]
    (when (seq pairs)
      (db/save-ozon-sku-map! pairs)
      (println (str "  Ozon SKU map: " (count pairs) " mappings saved")))
    (count pairs)))

;; ---------------------------------------------------------------------------
;; Protocol implementation
;; ---------------------------------------------------------------------------

(extend-type OzonClient
  proto/MarketplaceAPI

  (fetch-orders [this date-from date-to]
    (transform/->orders (all-postings this date-from date-to)))

  (fetch-sales [this date-from date-to]
    (transform/->sales (all-postings this date-from date-to)))

  (fetch-stocks [this]
    (let [items (api/stocks this)]
      (transform/->stocks items)))

  (fetch-finance-report [this date-from date-to]
    (let [ts-from  (str date-from "T00:00:00Z")
          ts-to    (str date-to   "T00:00:00Z")
          ops      (api/finance-report this ts-from ts-to)
          ;; Load sku-map from DB (populated by sync-sku-map!)
          sku-map  (let [cached (db/ozon-sku-map)]
                     (if (seq cached)
                       cached
                       ;; Fallback: try to build from API if DB is empty
                       (do (sync-sku-map! this)
                           (db/ozon-sku-map))))]
      (transform/->finance-report ops sku-map)))

  (fetch-product-stats [this date-from date-to]
    (let [metrics ["views" "session_view_pdp" "conv_tocart_pdp" "ordered_units"
                   "revenue" "cancellations" "returns" "delivered_units" "gross_profit"]
          resp    (api/analytics-data this date-from date-to metrics)]
      (transform/->product-stats resp)))

  (fetch-storage-costs [this date-from date-to]
    (let [report-id (api/storage-report-create this date-from date-to)]
      (when-not report-id
        (throw (ex-info "Storage report id is missing"
                        {:date-from date-from :date-to date-to})))
      (loop [attempts 0]
        (Thread/sleep 10000)
        (let [result (api/storage-report-status this report-id)
              status (get result :status)]
          (cond
            (= "ready" status)
            (let [url  (get result :file)
                  data (api/storage-report-download this url)]
              (transform/->storage-costs (if (sequential? data) data [])))

            (= "error" status)
            (throw (ex-info "Storage report task failed"
                            {:report-id report-id :status status}))

            (>= attempts 23) ; 24 attempts total (0..23)
            (throw (ex-info "Storage report task timed out"
                            {:report-id report-id :status status}))

            :else
            (recur (inc attempts)))))))

  (fetch-ad-campaigns [_this]
    [])

  (fetch-prices [this]
    (let [items     (api/product-list this)
          offer-ids (mapv #(get % :offer_id) items)
          resp      (api/product-info this offer-ids)
          products  (get-in resp [:result :items] [])]
      (transform/->prices products))))
