;; TODO(obs/7e): remaining bare (catch Exception _ …) sites in this ns are
;; tracked for conversion to analitica.util.safe/safely post-pilot — see
;; docs/superpowers/plans/2026-06-23-pilot-hardening-observability.md Task 2.
(ns analitica.domain.losses
  "Losses report: identifies SKUs bleeding money through storage costs,
   low margin, or forecast deterioration.

   Three loss classes:
     :dead-stock        — storage_cost > 100 RUB AND 0 sales in period
     :storage-eats-margin — storage/revenue > 20% AND profit < 500 RUB
     :forecast-negative — days-to-break-even < 30 at current storage burn

   See docs/canonical-formulas.md §Losses for canonical formulas."
  (:require [analitica.db :as db]
            [analitica.domain.unit-economics :as ue]
            [analitica.domain.stock :as stock]
            [analitica.domain.finance :as finance]
            [analitica.util.math :as math]
            [analitica.util.time :as t]))

;; ---------------------------------------------------------------------------
;; Losses.1 — Dead stock classifier
;; ---------------------------------------------------------------------------

(defn- dead-stock-rows
  "SKUs with positive storage cost (> 100 RUB) but 0 sales in period.
   storage-by-article: {article -> storage-cost}
   ue-data: seq of per-article rows from ue/calculate (may be empty)."
  [storage-by-article ue-data]
  (let [ue-by-art (into {} (map (juxt :article identity) ue-data))]
    (->> storage-by-article
         (keep (fn [[article storage-cost]]
                 (when (and (> (or storage-cost 0) 100)
                            (let [ue-row (get ue-by-art article)]
                              (or (nil? ue-row)
                                  (zero? (or (:sales-qty ue-row) 0)))))
                   {:article     article
                    :loss-type   :dead-stock
                    :storage-cost (math/round2 storage-cost)
                    :revenue     0.0
                    :profit      (- (math/round2 storage-cost))
                    :sales-qty   0
                    :suggestion  "Скидка -30% или ликвидация"})))
         (sort-by :article))))

;; ---------------------------------------------------------------------------
;; Losses.2 — Storage-eats-margin classifier
;; ---------------------------------------------------------------------------

(defn- storage-eats-margin-rows
  "SKUs where storage/revenue > 20% AND profit < 500 RUB.
   ue-data: seq of per-article rows from ue/calculate."
  [ue-data]
  (->> ue-data
       (keep (fn [row]
               (let [rev     (or (:revenue row) 0)
                     storage (or (:storage row) 0)
                     profit  (or (:profit row) 0)]
                 (when (and (pos? rev)
                            (> (/ storage rev) 0.20)
                            (< profit 500))
                   {:article       (:article row)
                    :loss-type     :storage-eats-margin
                    :storage-cost  (math/round2 storage)
                    :revenue       (math/round2 rev)
                    :profit        (math/round2 profit)
                    :storage-ratio (math/round2 (* 100.0 (/ storage rev)))
                    :sales-qty     (or (:sales-qty row) 0)
                    :suggestion    (if (> (/ storage rev) 0.4)
                                     "Критично: повысить цену +15% или снять"
                                     "Повысить цену +5-10%")}))))
       (sort-by :article)))

;; ---------------------------------------------------------------------------
;; Losses.3 — Forecast-negative classifier
;; ---------------------------------------------------------------------------

(defn- forecast-negative-rows
  "SKUs with positive profit now but negative forecast within 30 days.
   Formula: days-to-break-even = profit / daily_storage
   Flags when days-to-break-even < 30 AND future-storage-cost > profit.
   ue-data: seq of per-article rows from ue/calculate
   storage-by-article: {article -> storage-cost}
   stock-by-art: result of (stock/by-article stocks) — map of {article -> row}
   days-in-period: integer, number of days covered by the finance period."
  [ue-data storage-by-article stock-by-art days-in-period]
  (let [days (max 1 days-in-period)
        daily-storage (fn [article]
                        (/ (or (get storage-by-article article) 0.0)
                           days))]
    (->> ue-data
         (keep (fn [row]
                 (let [art    (:article row)
                       profit (or (:profit row) 0)
                       stock  (get stock-by-art art)
                       qty-full (or (:quantity-full stock) 0)
                       ds     (daily-storage art)
                       sales-per-day (/ (or (:sales-qty row) 0) days)
                       remaining-days (if (pos? sales-per-day)
                                        (/ qty-full sales-per-day)
                                        365.0)
                       future-storage-cost (* ds remaining-days)
                       days-to-break-even (if (pos? ds)
                                            (/ profit ds)
                                            9999.0)]
                   (when (and (pos? profit)
                              (< days-to-break-even 30)
                              (> future-storage-cost profit))
                     {:article             art
                      :loss-type           :forecast-negative
                      :storage-cost        (math/round2 (or (get storage-by-article art) 0.0))
                      :revenue             (math/round2 (or (:revenue row) 0))
                      :profit              (math/round2 profit)
                      :days-to-break-even  (math/round2 days-to-break-even)
                      :sales-qty           (or (:sales-qty row) 0)
                      :suggestion          "Повысить цену +5% или акция на быструю распродажу"}))))
         (sort-by :article))))

;; ---------------------------------------------------------------------------
;; Loss aggregation helpers (pure)
;; ---------------------------------------------------------------------------

(defn- sum-negative-profit
  "Sum :profit across `rows`, counting only negative values. Mirrors the
   semantics already used for :total-loss — positive profits in any
   loss-class must NOT inflate (or worse, cancel out) the reported loss."
  [rows]
  (math/round2
    (reduce + 0.0 (keep #(let [p (or (:profit %) 0)] (when (neg? p) p)) rows))))

(defn- loss-totals
  "Pure totals for a (dead, eat, fcst) loss-row triple. Exposed for
   testing — the orchestrator just calls this with the classified rows."
  [dead eat fcst]
  {:total-loss          (sum-negative-profit (concat dead eat fcst))
   :dead-stock-loss     (sum-negative-profit dead)
   :storage-eats-loss   (sum-negative-profit eat)
   :forecast-count      (count fcst)
   :dead-stock-count    (count dead)
   :storage-eats-count  (count eat)
   :total-sku-affected  (+ (count dead) (count eat) (count fcst))})

;; ---------------------------------------------------------------------------
;; Losses.4 — Orchestrator
;; ---------------------------------------------------------------------------

(defn calculate
  "Compute losses for a period and marketplace.
   Returns {:rows [...] :totals {...}}.

   Only WB has paid_storage ingestion; for Ozon/YM storage-map will be empty
   so all three loss classes will return [] — graceful empty, not a crash.

   See docs/canonical-formulas.md §Losses."
  [period & {:keys [marketplace]}]
  (let [[from to] (t/resolve-period period)
        from-ld   (t/parse-date from)
        to-ld     (t/parse-date to)
        days      (max 1 (t/days-between from-ld to-ld))

        ;; Finance data — catch coverage errors for non-WB (no data → [])
        fin-data  (try
                    (finance/fetch-finance period :marketplace marketplace)
                    (catch Exception _ []))

        ;; Storage map — WB only in practice; Ozon/YM returns {}
        storage-rows (try
                       (db/storage-by-article from to :marketplace marketplace)
                       (catch Exception _ []))
        storage-map  (into {} (map (juxt :article :storage-cost) storage-rows))

        ;; UE rows — skip coverage check crash for empty finance
        ue-data   (if (empty? fin-data)
                    []
                    (try
                      (ue/calculate fin-data :storage-by-article storage-map)
                      (catch Exception _ [])))

        ;; Stock data
        stock-rows (try
                     (stock/fetch-stocks :marketplace marketplace)
                     (catch Exception _ []))
        stock-by-art (into {} (map (juxt :article identity)
                                   (stock/by-article stock-rows)))

        ;; Three loss classifiers
        dead  (vec (dead-stock-rows storage-map ue-data))
        eat   (vec (storage-eats-margin-rows ue-data))
        fcst  (vec (forecast-negative-rows ue-data storage-map stock-by-art days))

        all-rows (vec (concat dead eat fcst))]

    {:rows   all-rows
     :totals (loss-totals dead eat fcst)}))
