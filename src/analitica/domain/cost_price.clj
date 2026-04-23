(ns analitica.domain.cost-price
  "Cost-price lookup with in-memory caches fed by a CostSource.

   Public API:
     (get-price article)
     (get-price article barcode)      — with fuzzy fallback (see below)
     (set-prices-from-rows! rows)     — replace in-memory caches from
         a canonical row seq. Called by `analitica.costsource/ingest!`.
     (load-from-1c [path])            — convenience for CLI/boot that
         delegates to csv1c provider + set-prices-from-rows!. Thin
         wrapper kept for backward compatibility with existing callers."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [analitica.costsource.csv1c :as csv1c]
            [analitica.report.table :as table]))

;; Two maps: by article (e.g. "3452/Бежевый" -> 1590.0) and by barcode
(defonce ^:private cost-prices (atom {}))
(defonce ^:private cost-by-barcode (atom {}))

;; ---------------------------------------------------------------------------
;; Cache population (shared by all providers)
;; ---------------------------------------------------------------------------

(defn set-prices-from-rows!
  "Replace in-memory caches from a seq of canonical cost-price rows
   (each carries at least :article, :barcode, :cost-price). Keeps the
   historical single-price-per-article semantics: when 1C has multiple
   barcodes for one article, the FIRST row's price wins at article level.
   Per-barcode detail is preserved in the barcode atom."
  [rows]
  (let [by-art (->> rows
                    (group-by :article)
                    (map (fn [[art items]]
                           [art (:cost-price (first items))]))
                    (into {}))
        by-bc  (->> rows
                    (filter #(and (:barcode %) (seq (:barcode %))))
                    (map (fn [r] [(:barcode r) (:cost-price r)]))
                    (into {}))]
    (reset! cost-prices by-art)
    (reset! cost-by-barcode by-bc)
    {:articles (count by-art) :barcodes (count by-bc)}))

(defn load-from-1c
  "Backward-compatible bootstrap: parse a 1C CSV and populate the
   in-memory caches. Prefer `analitica.costsource/ingest!` with a
   `csv1c/make-source` for new callers — it additionally upserts into
   the cost_prices DB table and validates rows."
  ([] (load-from-1c "1c/units.csv"))
  ([path]
   (try
     (let [rows     (csv1c/parse-file path)
           {:keys [articles barcodes]} (set-prices-from-rows! rows)]
       (println "Загружено из 1С:" articles "артикулов," barcodes "баркодов")
       {:articles articles :barcodes barcodes})
     (catch clojure.lang.ExceptionInfo e
       (if (= :file-not-found (:cause (ex-data e)))
         (println "Файл не найден:" path)
         (throw e))))))

(defn load-from-db!
  "Populate in-memory caches from the `cost_prices` DB table.
   Returns {:articles :barcodes} or {:articles 0 :barcodes 0} when empty.

   Call this at boot (after `db/init!`) instead of `load-from-1c` — the
   DB is now the source of truth, seeded by any CostSource provider
   through `analitica.costsource/ingest!`. The CSV file is just one
   possible source, not the runtime state holder."
  []
  ;; Requiring `analitica.db` at call-time avoids a load-time cycle:
  ;; analitica.db -> jdbc init; analitica.domain.cost-price -> db. Using
  ;; requiring-resolve dodges the ns-level require that would otherwise
  ;; bring db in before it's ready in some test-harness orders.
  (let [q (requiring-resolve 'analitica.db/query)
        rows (@q ["SELECT article, barcode, cost_price FROM cost_prices"])
        ;; Convert DB result (snake_case) to domain row shape (kebab).
        normalized (mapv (fn [r]
                           {:article    (:article r)
                            :barcode    (:barcode r)
                            :cost-price (:cost-price r)})
                         rows)]
    (set-prices-from-rows! normalized)))

;; ---------------------------------------------------------------------------
;; Simple loaders
;; ---------------------------------------------------------------------------

(defn load-from-edn
  "Load cost prices from EDN file.
   Expected format: {\"article\" 500.0}"
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (do
        (reset! cost-prices (edn/read-string (slurp f)))
        (println "Loaded" (count @cost-prices) "cost prices from" path))
      (println "Cost price file not found:" path))))

(defn load-from-csv
  "Load cost prices from simple CSV: article,cost_price"
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (let [lines (rest (str/split-lines (slurp f)))
            data  (->> lines
                       (map #(str/split % #"[,;\t]"))
                       (filter #(>= (count %) 2))
                       (map (fn [[art price]] [(str/trim art)
                                               (Double/parseDouble (str/trim price))]))
                       (into {}))]
        (reset! cost-prices data)
        (println "Loaded" (count data) "cost prices from" path))
      (println "Cost price file not found:" path))))

;; ---------------------------------------------------------------------------
;; Accessors
;; ---------------------------------------------------------------------------

(defn set-price! [article price]
  (swap! cost-prices assoc article price))

(defn average-price
  "Average cost price across all loaded articles."
  []
  (let [prices (vals @cost-prices)]
    (when (seq prices)
      (/ (reduce + prices) (count prices)))))

(defn- normalize-article
  "Canonicalise an article name for fuzzy matching across marketplaces:
   lowercase + strip trailing digit runs (size suffix). E.g.
   `6405/Голубой48` → `6405/голубой`, `1523-1/Серый` → `1523-1/серый`.
   1C CSVs omit the size; WB lowercases; YM/Ozon often include size."
  [s]
  (when s
    (-> s
        clojure.string/lower-case
        (clojure.string/replace #"\s+$" "")
        (clojure.string/replace #"\d+$" ""))))

(defonce ^:private cost-by-normalized (atom {}))

(defn- rebuild-normalized-index! []
  (reset! cost-by-normalized
          (reduce-kv (fn [acc art price]
                       (let [k (normalize-article art)]
                         (if (and k (not (contains? acc k)))
                           (assoc acc k price)
                           acc)))
                     {}
                     @cost-prices)))

;; Rebuild the normalized index whenever cost-prices changes via load-*.
;; Simple add-watch keeps the two atoms in sync without touching load sites.
(add-watch cost-prices ::normalized-index
           (fn [_ _ _ _] (rebuild-normalized-index!)))

(defn get-price
  "Get cost price by article with fallback strategy:
     1. strict match on article
     2. strict match on barcode
     3. fuzzy match: lowercase + strip size suffix (e.g. /Голубой48 → /голубой).
   Returns nil when nothing matches."
  ([article] (get-price article nil))
  ([article barcode]
   (or (get @cost-prices article)
       (when barcode (get @cost-by-barcode barcode))
       (when article (get @cost-by-normalized (normalize-article article))))))

(defn all-prices [] @cost-prices)
(defn all-barcodes [] @cost-by-barcode)

(defn report
  "Print loaded cost prices summary."
  []
  (let [prices @cost-prices]
    (table/print-summary
     "СЕБЕСТОИМОСТЬ ИЗ 1С"
     [["Артикулов" (count prices)]
      ["Баркодов"  (count @cost-by-barcode)]
      ["Мин. цена" (when (seq prices) (apply min (vals prices)))]
      ["Макс. цена" (when (seq prices) (apply max (vals prices)))]
      ["Средняя"   (when (seq prices) (/ (reduce + (vals prices)) (count prices)))]])
    (println "\n── Артикулы (первые 30) ──")
    (table/print-table
     [[:article "Артикул"] [:cost "Себестоимость"]]
     (->> prices
          (map (fn [[art cost]] {:article art :cost cost}))
          (sort-by :article)
          (take 30)))))
