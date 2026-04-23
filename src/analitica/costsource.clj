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

(defn- dedup-per-key
  "Collapse rows sharing the same DB primary key `(article, barcode)`.
   Keeps the first occurrence (preserves CSV order); downstream callers
   can re-run ingest to overwrite with INSERT-OR-REPLACE semantics."
  [rows]
  (->> rows
       (reduce (fn [{:keys [seen out] :as acc} r]
                 (let [k [(:article r) (or (:barcode r) "")]]
                   (if (contains? seen k)
                     acc
                     {:seen (conj seen k) :out (conj out r)})))
               {:seen #{} :out []})
       :out))

(defn- to-db-rows
  "Per-barcode DB rows preserving all canonical fields: characteristic
   (1С size/composition), color, nomenclature, quantity_1c, updated_at.
   Rows with no barcode land with barcode='' as the article-level
   fallback used by cost-price/get-price when a strict-barcode lookup
   misses."
  [rows]
  (let [ts (now-str)]
    (mapv (fn [r]
            [(:article r)
             (or (:barcode r) "")
             (:cost-price r)
             (or (:nomenclature r) "")
             (or (:color r) "")
             (or (:characteristic r) "")
             (or (:quantity r) 0.0)
             ts])
          rows)))

(defn- record-import!
  "Log one ingest into cost_prices_imports for audit. Safe to no-op
   when the table doesn't exist yet (db/init! hasn't run)."
  [{:keys [source fetched loaded rejected] :as _summary} opts]
  (try
    (db/execute!
      ["INSERT INTO cost_prices_imports
         (source, imported_at, fetched, loaded, rejected, filename, notes)
         VALUES (?, ?, ?, ?, ?, ?, ?)"
       (name source)
       (now-str)
       (int fetched)
       (int loaded)
       (int rejected)
       (:filename opts)
       (:notes opts)])
    (catch Throwable t
      ;; Don't let audit-logging failures break the ingest itself.
      (println "  [warn] cost_prices_imports log failed:" (.getMessage t)))))

(defn ingest!
  "Ingest cost prices from `source` (anything implementing CostSource).
   Returns {:source :fetched :loaded :rejected :errors}.

   Side effects:
     - INSERT OR REPLACE rows in `cost_prices` table (per-barcode)
     - INSERT one row in `cost_prices_imports` for audit (source kind,
       timestamp, counts, optional filename/notes via `opts`)
     - Refresh in-memory atoms in `analitica.domain.cost-price` so
       existing `get-price` callers see the fresh data immediately

   `rejected` counts rows that failed minimal validation (article blank
   or cost-price < 0). Duplicate (article, barcode) rows after the first
   are silently dropped — INSERT-OR-REPLACE would do the same, but we
   count the unique rows for the caller's summary.

   Optional `opts` map: {:filename \"units.csv\" :notes \"manual upload\"}."
  ([source] (ingest! source {}))
  ([source opts]
   (let [source-name (p/source-id source)
         raw         (p/fetch-cost-prices source)
         {:keys [ok bad]} (split-ok-bad raw)
         deduped     (dedup-per-key ok)
         db-rows     (to-db-rows deduped)
         inserted    (db/insert-batch!
                       :cost_prices
                       [:article :barcode :cost_price
                        :nomenclature :color :characteristic :quantity_1c :updated_at]
                       db-rows)
         summary     {:source    source-name
                      :fetched   (count raw)
                      :loaded    (or inserted (count db-rows))
                      :rejected  (count bad)
                      :errors    (vec (take 10 bad))}]
     (cp/set-prices-from-rows! ok)
     (record-import! summary opts)
     summary)))
