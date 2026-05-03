(ns analitica.web.api.report
  (:require [analitica.db :as db]
            [analitica.domain.sales :as sales]
            [analitica.domain.finance :as finance]
            [analitica.domain.unit-economics :as ue]
            [analitica.domain.pnl :as pnl]
            [analitica.domain.preliminary :as preliminary]
            [analitica.domain.abc :as abc]
            [analitica.domain.stock :as stock]
            [analitica.domain.returns :as returns]
            [analitica.domain.buyout :as buyout]
            [analitica.domain.geography :as geography]
            [analitica.domain.trends :as trends]
            [analitica.util.time :as t]
            [analitica.util.period :as period]
            [analitica.web.report-schemas :as rs]))

;; ---------------------------------------------------------------------------
;; Helper functions
;; ---------------------------------------------------------------------------

(defn- resolve-dates
  "Convert period to [from to] date strings. Handles keyword, vector, and map."
  [period]
  (t/resolve-period period))

;; ---------------------------------------------------------------------------
;; Report data functions
;; ---------------------------------------------------------------------------

(defn- compute-report
  "Pure computation for a single period window. No compare awareness."
  [report-type period & {:keys [marketplace trend-type article]}]
  (try
    (case report-type
      ;; Sales report
      :sales
      (let [sales-data (sales/fetch-sales period
                                          :marketplace marketplace
                                          :source :db)
            rows (sales/by-day sales-data)]
        {:rows (vec rows) :totals {}})

      ;; Finance report
      :finance
      (let [finance-data (finance/fetch-finance period
                                                :marketplace marketplace
                                                :source :db)
            rows (finance/by-article finance-data)
            totals (finance/totals finance-data)]
        {:rows (vec rows) :totals totals})

      ;; Unit Economics report
      :ue
      (let [[from to] (resolve-dates period)
            finance-data (cond->> (finance/fetch-finance period
                                                         :marketplace marketplace
                                                         :source :db)
                           (seq article) (filter #(= article (:article %))))
            ;; Load storage costs by article
            storage-map (let [rows (db/storage-by-article from to :marketplace marketplace)]
                          (when (seq rows)
                            (into {} (map (juxt :article :storage-cost) rows))))
            ;; Load ad spend by article
            ad-map (let [rows (db/ad-spend-by-article from to :marketplace marketplace)]
                     (when (seq rows)
                       (into {} (map (juxt :article :ad-spend) rows))))
            rows (ue/calculate finance-data
                               :storage-by-article storage-map
                               :ad-spend-by-article ad-map)
            totals (ue/totals rows)]
        {:rows (vec rows) :totals totals})

      ;; P&L report — single summary map goes to :totals; :rows is empty.
      ;; For Ozon: when realization isn't yet published for the period
      ;; (current month always lags ~3 weeks), revenue from finance is 0.
      ;; Overlay with cash-flow-derived preliminary so the user sees a
      ;; real number with a "preliminary" badge instead of «0 ₽».
      ;; See analitica.domain.preliminary.
      :pnl
      (let [[from to]    (resolve-dates period)
            finance-data (finance/fetch-finance period
                                                :marketplace marketplace
                                                :source :db)
            cf-adj       (pnl/load-cf-adjustments from to marketplace)
            totals       (-> (pnl/calculate finance-data
                                            :marketplace marketplace
                                            :cf-adjustments cf-adj
                                            :from from :to to)
                             (preliminary/maybe-overlay-preliminary
                               {:period      {:from from :to to}
                                :marketplace marketplace}))]
        {:rows [] :totals totals})

      ;; ABC analysis
      :abc
      (let [finance-data (finance/fetch-finance period
                                                :marketplace marketplace
                                                :source :db)
            rows (abc/analyze-by finance-data :revenue)]
        {:rows (vec rows) :totals {}})

      ;; Stock report
      :stock
      (let [stocks (stock/fetch-stocks :marketplace marketplace :source :db)
            rows (stock/by-article stocks)]
        {:rows (vec rows) :totals {}})

      ;; Returns report
      :returns
      (let [sales-data (sales/fetch-sales period
                                          :marketplace marketplace
                                          :source :db)
            rows (returns/by-article sales-data)]
        {:rows (vec rows) :totals {}})

      ;; Buyout analysis. Per §Buyout.7, when `orders-by-article` is wired in,
      ;; rows expose `:placed`, `:cancelled`, `:cancel-rate`, `:true-buyout-rate`
      ;; in addition to the legacy sales-only `:buyout-rate`.
      :buyout
      (let [[from to]    (resolve-dates period)
            orders-rows  (db/orders-by-article from to :marketplace marketplace)
            orders-map   (into {} (map (juxt :article identity) orders-rows))]
        {:rows (vec (buyout/analyze period
                                    :marketplace marketplace
                                    :orders-by-article orders-map))
         :totals {}})

      ;; Geography report — :combined covers WB (region_sales) + YM/Ozon
      ;; (sales table per-event region) without double-counting WB.
      :geo
      (let [region-data (geography/fetch-regions period
                                                 :source      :combined
                                                 :marketplace marketplace)
            rows (geography/by-region region-data)]
        {:rows (vec rows) :totals {}})

      ;; Trends report
      :trends
      (case (or trend-type :wow)
        :wow   {:rows (vec (trends/wow              :marketplace marketplace)) :totals {}}
        :mom   {:rows (vec (trends/mom              :marketplace marketplace)) :totals {}}
        :daily {:rows (vec (trends/daily period     :marketplace marketplace)) :totals {}}
        {:rows (vec (trends/wow :marketplace marketplace)) :totals {}})

      ;; Unknown report type
      {:rows [] :totals {}})

    (catch Exception _
      {:rows [] :totals {}})))

;; ---------------------------------------------------------------------------
;; Compare enrichment
;; ---------------------------------------------------------------------------

(defn enrich-with-compare
  "Join current rows with prev rows and add _prev/_delta/_delta_pct columns for
   each key in delta-cols.

   Parameters:
   - current    — seq of row maps (current period)
   - prev       — seq of row maps (previous period); may be shorter or empty
   - key-fn     — fn or keyword used to join rows (e.g. :article)
   - delta-cols — seq of keywords whose prev/delta/delta_pct triplet to add

   For each current row:
   - If a matching prev row exists (same key-fn value):
       :<col>_prev       = prev value, or nil if the column is missing from the prev row
       :<col>_delta      = current - prev  (rounded to 2 decimals); nil if either value is non-numeric
       :<col>_delta_pct  = 100 × delta / |prev|  (nil when prev = 0 or prev is nil)
   - If no matching prev row: _prev = nil, _delta = nil, _delta_pct = nil

   Returns a vector of enriched rows."
  [current prev key-fn delta-cols]
  (let [prev-index (into {} (map (juxt key-fn identity) prev))]
    (mapv (fn [row]
            (let [prev-row (get prev-index (key-fn row))]
              (reduce (fn [r col]
                        (let [cur-val  (get r col)
                              prev-val (when prev-row (get prev-row col))
                              delta    (when (and (number? cur-val) (number? prev-val))
                                         (let [d (- cur-val prev-val)]
                                           (/ (Math/round (* d 100.0)) 100.0)))
                              ;; |prev| denominator so a loss shrinking from
                              ;; -1000 to -500 reports +50% (improvement),
                              ;; not -50% (which is what raw `prev` produced
                              ;; — a sign flip on every negative-prev row).
                              delta-pct (when (and delta prev-val (not (zero? prev-val)))
                                          (let [abs-prev (Math/abs (double prev-val))]
                                            (/ (Math/round (* 100.0 (/ delta abs-prev) 100.0)) 100.0)))
                              pk (keyword (str (name col) "_prev"))
                              dk (keyword (str (name col) "_delta"))
                              dpk (keyword (str (name col) "_delta_pct"))]
                          (assoc r pk prev-val dk delta dpk delta-pct)))
                      row
                      delta-cols)))
          current)))

;; ---------------------------------------------------------------------------
;; Row join key by report type
;; ---------------------------------------------------------------------------

(def ^:private row-join-key
  "Map of report-type → keyword used to join current and prev rows."
  {:ue      :article
   :finance :article
   :sales   :group
   :abc     :article
   :returns :article
   :buyout  :article
   :geo     :region})

(defn- delta-cols-for-schema
  "Return the delta-supported column keys from a report schema."
  [schema]
  (->> (:columns schema)
       (filter :delta-supported?)
       (mapv :key)))

(defn report-data
  "Returns map {:rows :totals [:compare {:rows :totals}]}.

   Parameters:
   - report-type: keyword (:sales, :finance, :ue, :pnl, :abc, :stock, :returns, :buyout, :geo, :trends)
   - period: keyword or map with :from/:to keys
   - marketplace: optional keyword (:wb, :ozon, :ym)
   - trend-type: optional keyword for trends report (:wow, :mom, :daily)
   - article: optional string to filter UE report to a single article
   - compare: :prev → computes same-length prior window and attaches as :compare key.
              :none (default) → no :compare key.

   Report type mappings:
   - sales:   sales/fetch-sales + sales/by-day; :totals {}
   - finance: finance/by-article; :totals from finance/totals
   - ue:      unit-economics/calculate (storage+ad from DB); :totals from ue/totals
   - pnl:     pnl/calculate → :totals; :rows []
   - abc:     abc/analyze-by :revenue; :totals {}
   - stock:   stock/by-article; :totals {}
   - returns: returns/by-article; :totals {}
   - buyout:  buyout/analyze; :totals {}
   - geo:     geography/by-region; :totals {}
   - trends:  wow/mom/daily; :totals {}

   Requirements: 7.2, 13.1-13.12"
  [report-type period-arg & {:keys [marketplace trend-type article compare]
                              :or   {compare :none}}]
  (let [current (compute-report report-type period-arg
                                :marketplace marketplace
                                :trend-type  trend-type
                                :article     article)]
    (if (= compare :prev)
      (let [[from to]    (t/resolve-period period-arg)
            [pf pt]      (period/compare-period {:from from :to to})
            prev-period  {:from pf :to pt}
            prev         (compute-report report-type prev-period
                                         :marketplace marketplace
                                         :trend-type  trend-type
                                         :article     article)
            ;; Enrich current rows with _prev/_delta/_delta_pct columns when
            ;; the schema defines delta-supported columns and a join key exists.
            join-key     (get row-join-key report-type)
            schema       (rs/get-schema report-type)
            dcols        (when (and join-key schema) (delta-cols-for-schema schema))
            enriched-rows (if (and join-key (seq dcols))
                            (enrich-with-compare (:rows current) (:rows prev)
                                                 join-key dcols)
                            (:rows current))]
        (-> current
            (assoc :rows enriched-rows)
            (assoc :compare prev)))
      current)))
