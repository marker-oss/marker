(ns analitica.domain.sales
  (:require [analitica.marketplace.protocol :as proto]
            [analitica.marketplace.registry :as registry]
            [analitica.db :as db]
            [analitica.report.table :as table]
            [analitica.report.export :as export]
            [analitica.util.time :as t]
            [analitica.util.math :as math]))

;; ---------------------------------------------------------------------------
;; Data fetching (DB or API)
;; ---------------------------------------------------------------------------

(defn- get-mp [marketplace]
  (registry/get-marketplace (or marketplace :wb)))

(defn- resolve-dates [period]
  (cond
    (keyword? period) (t/period period)
    (vector? period)  period
    :else             [(:from period) (:to period)]))

(defn- db-sales [from to marketplace]
  ;; The marketplace clause MUST be inserted before ORDER BY — appending it
  ;; after the ORDER BY would silently turn it into part of the ordering
  ;; expression and the filter would be ignored entirely.
  (let [mp-clause (when marketplace " AND marketplace = ?")
        params    (cond-> [from (str to "T23:59:59")] marketplace (conj (name marketplace)))]
    (->> (db/query (into [(str "SELECT * FROM sales WHERE date >= ? AND date <= ?" mp-clause " ORDER BY date")] params))
         (mapv #(-> % (update :type keyword) (update :marketplace keyword))))))

(defn fetch-sales
  "Fetch sales for a period. Reads from DB by default, :source :api for live."
  [period & {:keys [marketplace source] :or {marketplace :wb source :db}}]
  (let [[from to] (resolve-dates period)]
    (case source
      :db  (db-sales from to marketplace)
      :api (proto/fetch-sales (get-mp marketplace) from to))))

(defn fetch-orders
  [period & {:keys [marketplace source] :or {marketplace :wb source :db}}]
  (let [[from to] (resolve-dates period)]
    (case source
      :db  (let [mp-clause (when marketplace " AND marketplace = ?")
                 params    (cond-> [from (str to "T23:59:59")] marketplace (conj (name marketplace)))]
             (->> (db/query (into [(str "SELECT * FROM orders WHERE date >= ? AND date <= ?" mp-clause " ORDER BY date")] params))
                  (mapv #(-> % (update :status keyword) (update :marketplace keyword)))))
      :api (proto/fetch-orders (get-mp marketplace) from to))))

;; ---------------------------------------------------------------------------
;; Aggregation helpers
;; ---------------------------------------------------------------------------

(defn- parse-date-str
  "Trim a date/datetime string to YYYY-MM-DD. Returns nil when input is
   nil, blank, or shorter than 10 chars — `subs` would otherwise throw
   StringIndexOutOfBoundsException on data with malformed dates."
  [s]
  (when (and (string? s) (>= (count s) 10))
    (subs s 0 10)))

(defn- group-and-sum [data group-fn]
  (->> data
       (group-by group-fn)
       (map (fn [[k items]]
              (let [sales   (filter #(= :sale (:type %)) items)
                    returns (filter #(= :return (:type %)) items)]
                {:group         k
                 :sales-count   (count sales)
                 :returns-count (count returns)
                 ;; Coalesce revenue: WB fills :for-pay, Ozon fills only
                 ;; :total-price (postings API doesn't return per-item payout),
                 ;; YM may also be partial. Falling back keeps the column
                 ;; meaningful across all 3 marketplaces.
                 :revenue       (reduce +
                                        0.0
                                        (map (fn [s]
                                               (or (:for-pay s)
                                                   (:price-with-disc s)
                                                   (:finished-price s)
                                                   (:total-price s)
                                                   0))
                                             sales))
                 :avg-price     (math/round2
                                 (math/safe-div
                                  (reduce + 0.0 (map #(or (:finished-price %) (:total-price %) 0) sales))
                                  (count sales)))})))
       (sort-by :revenue >)))

;; ---------------------------------------------------------------------------
;; Reports
;; ---------------------------------------------------------------------------

(defn by-day [sales-data]
  (group-and-sum sales-data #(parse-date-str (:date %))))

(defn by-article
  "Group sales by `:article`. Each output row carries summary metrics
   plus :nm-id and :subject pulled from the first matching source row,
   so callers (homepage Top-movers, sku-sheet links) can resolve the
   article to a marketplace card."
  [sales-data]
  (let [meta-by-art (->> (group-by :article sales-data)
                         (into {} (map (fn [[art items]]
                                         (let [item (first items)]
                                           [art {:nm-id   (:nm-id item)
                                                 :subject (:subject item)}])))))]
    (mapv (fn [row] (merge row (get meta-by-art (:group row))))
          (group-and-sum sales-data :article))))

(defn by-category [sales-data]
  (group-and-sum sales-data :subject))

(defn by-brand [sales-data]
  (group-and-sum sales-data :brand))

(defn by-warehouse [sales-data]
  (group-and-sum sales-data :warehouse))

(defn by-region [sales-data]
  (group-and-sum sales-data :region))

(defn totals
  "Period rollup. See docs/canonical-formulas.md §Sales.4 for the canonical formulas.

   Revenue uses the same coalesce chain as `by-day` so Ozon (which fills only
   :total-price in sales rows, not :for-pay) reports a non-zero gross figure."
  [sales-data]
  (let [sales   (filter #(= :sale (:type %)) sales-data)
        returns (filter #(= :return (:type %)) sales-data)
        revenue-of (fn [s]
                     (or (:for-pay s)
                         (:price-with-disc s)
                         (:finished-price s)
                         (:total-price s)
                         0))]
    {:total-sales    (count sales)
     :total-returns  (count returns)
     :sales-count    (count sales)
     :returns-count  (count returns)
     :total-revenue  (math/round2 (reduce + 0.0 (map revenue-of sales)))
     :revenue        (math/round2 (reduce + 0.0 (map revenue-of sales)))
     :avg-price      (math/round2 (math/safe-div
                                   (reduce + 0.0 (map #(or (:finished-price %) (:total-price %) 0) sales))
                                   (count sales)))
     :return-rate    (math/percentage (count returns) (+ (count sales) (count returns)))
     ;; Disambiguate cross-MP article-code collisions per Sales.7.3.
     ;; Two marketplaces can use the same `:article` string for different
     ;; physical SKUs (e.g. legacy migrated catalogs). Keying on
     ;; (marketplace, article) keeps each cross-MP variant distinct;
     ;; for single-MP datasets this collapses to the legacy count.
     :unique-skus    (count (distinct (map (juxt :marketplace :article) sales-data)))}))

;; ---------------------------------------------------------------------------
;; Dashboard
;; ---------------------------------------------------------------------------

(defn dashboard
  [period & {:keys [marketplace source] :or {marketplace :wb source :db}}]
  (println "\nЗагрузка данных продаж...")
  (let [data    (fetch-sales period :marketplace marketplace :source source)
        summary (totals data)]

    (table/print-summary
     (str "ПРОДАЖИ — " (if (keyword? period) (name period) (str (:from period) " — " (:to period))))
     [["Продажи"            (:total-sales summary)]
      ["Возвраты"           (:total-returns summary)]
      ["Выручка (к оплате)" (:total-revenue summary)]
      ["Средний чек"        (:avg-price summary)]
      ["% возвратов"        (str (:return-rate summary) "%")]
      ["Уникальных SKU"     (:unique-skus summary)]])

    (println "\n── По дням ──")
    (table/print-table
     [[:group "Дата"] [:sales-count "Продажи"] [:returns-count "Возвраты"]
      [:revenue "Выручка"] [:avg-price "Ср. чек"]]
     (by-day data))

    (println "\n── Топ-10 артикулов ──")
    (table/print-table
     [[:group "Артикул"] [:sales-count "Продажи"] [:returns-count "Возвраты"]
      [:revenue "Выручка"] [:avg-price "Ср. чек"]]
     (take 10 (by-article data)))

    (println "\n── По категориям ──")
    (table/print-table
     [[:group "Категория"] [:sales-count "Продажи"] [:returns-count "Возвраты"]
      [:revenue "Выручка"] [:avg-price "Ср. чек"]]
     (by-category data))

    (println "\n── По складам ──")
    (table/print-table
     [[:group "Склад"] [:sales-count "Продажи"] [:returns-count "Возвраты"]
      [:revenue "Выручка"]]
     (by-warehouse data))

    summary))

;; ---------------------------------------------------------------------------
;; Export
;; ---------------------------------------------------------------------------

(def ^:private sales-export-cols
  [[:group "Артикул"] [:sales-count "Продажи"] [:returns-count "Возвраты"]
   [:revenue "Выручка"] [:avg-price "Ср. чек"]])

(defn export-csv [period path & opts]
  (let [data (apply fetch-sales period opts)]
    (export/to-csv path sales-export-cols (by-article data))))

(defn export-excel [period path & opts]
  (let [data (apply fetch-sales period opts)]
    (export/to-excel path
                     [{:name "По артикулам" :cols sales-export-cols :rows (by-article data)}
                      {:name "По дням"
                       :cols [[:group "Дата"] [:sales-count "Продажи"] [:returns-count "Возвраты"]
                              [:revenue "Выручка"] [:avg-price "Ср. чек"]]
                       :rows (by-day data)}
                      {:name "По категориям"
                       :cols [[:group "Категория"] [:sales-count "Продажи"] [:returns-count "Возвраты"]
                              [:revenue "Выручка"]]
                       :rows (by-category data)}])))
