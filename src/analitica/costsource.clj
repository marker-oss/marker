(ns analitica.costsource
  "Source-agnostic ingest pipeline for cost-price data.

   Providers (`analitica.costsource.csv1c`, future
   `analitica.costsource.moysklad-api`, etc) implement
   `analitica.costsource.protocol/CostSource`. This namespace owns the
   common path: fetch → archive raw → transform → validate → persist.

   Public API:
     (ingest! source)  → {:source :loaded :rejected :errors}"
  (:require [analitica.costsource.protocol :as p]
            [analitica.db :as db]
            [analitica.domain.cost-price :as cp]
            [clojure.string :as str]))

(defn- now-str []
  (str (java.time.OffsetDateTime/now)))

(defn- valid-row?
  "A row is usable if article is present and cost-price ≥ 0."
  [r]
  (and (string? (:article r))
       (not (str/blank? (:article r)))
       (number? (:cost-price r))
       (>= (:cost-price r) 0)))

(defn- split-ok-bad [rows]
  (reduce (fn [acc r]
            (if (valid-row? r)
              (update acc :ok conj r)
              (update acc :bad conj r)))
          {:ok [] :bad []}
          rows))

(defn- to-db-rows
  "Collapse per-row records to the current DB shape (one row per article,
   barcode=\"\"). Phase 2 will switch to per-barcode; keeping the current
   shape here preserves read semantics for existing `get-price` callers."
  [rows]
  (->> rows
       (group-by :article)
       (mapv (fn [[article items]]
               (let [head (first items)]
                 [article
                  ""
                  (:cost-price head)
                  (or (:nomenclature head) "")
                  (or (:color head) "")
                  (or (:quantity head) 0.0)
                  (now-str)])))))

(defn ingest!
  "Ingest cost prices from `source` (anything implementing CostSource).
   Returns {:source :loaded :rejected :errors :fetched}.

   Side effects:
     - INSERT OR REPLACE rows in cost_prices table
     - Refresh in-memory atoms in `analitica.domain.cost-price` so
       existing `get-price` callers see the fresh data immediately"
  [source]
  (let [source-name (p/source-id source)
        raw         (p/fetch-cost-prices source)
        {:keys [ok bad]} (split-ok-bad raw)
        db-rows     (to-db-rows ok)
        inserted    (db/insert-batch! :cost_prices
                                      [:article :barcode :cost_price
                                       :nomenclature :color :quantity_1c :updated_at]
                                      db-rows)]
    ;; Refresh cache atoms from the ok-rows so get-price works without a
    ;; re-read of the file. Mirrors the old (reset! …) semantics.
    (cp/set-prices-from-rows! ok)
    {:source    source-name
     :fetched   (count raw)
     :loaded    (or inserted (count db-rows))
     :rejected  (count bad)
     :errors    (vec (take 10 bad))}))
