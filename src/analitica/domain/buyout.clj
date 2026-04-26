(ns analitica.domain.buyout
  (:require [analitica.db :as db]
            [analitica.domain.sales :as sales]
            [analitica.report.table :as table]
            [analitica.report.export :as export]
            [analitica.util.time :as t]
            [analitica.util.math :as math]))

(defn analyze
  "Per-article buyout rate from sales data. §Buyout.1, §Buyout.7.

   Legacy formula: buyout-rate = math/percentage(sold, sold+returned).
   Output rows sorted ascending by :buyout-rate (worst first — riskiest articles at top).

   Field note: :ordered = sold + returned (total unit operations). It is NOT
   orders placed on the marketplace — that data lives in the `orders` table.
   The misleading name is a deferred breaking-change; see §Buyout.6.1.

   :buyout-rate is nil when :ordered = 0 (math/percentage returns nil on
   zero denominator). Callers must handle nil before arithmetic. See §Buyout.6.4.

   Optional kwargs:
     :marketplace          — keyword to scope `sales/fetch-sales`. Without it,
                             buyout is computed across all marketplaces (legacy).
     :orders-by-article    — map `{article -> {:placed N :cancelled N}}` from
                             `db/orders-by-article`. When supplied, each row
                             gains :placed, :cancelled, :cancel-rate and
                             :true-buyout-rate (sold / placed) — the §Buyout.7
                             metrics that account for cancellations never seen
                             in the sales table."
  [period & {:keys [marketplace orders-by-article]}]
  (let [data     (sales/fetch-sales period :marketplace marketplace)
        by-art   (->> data
                      (group-by :article)
                      (map (fn [[art items]]
                             (let [sold      (count (filter #(= :sale (:type %)) items))
                                   rets      (count (filter #(= :return (:type %)) items))
                                   total     (+ sold rets)
                                   row       {:article     art
                                              :subject     (:subject (first items))
                                              :ordered     total
                                              :bought      sold
                                              :returned    rets
                                              :buyout-rate (math/percentage sold total)}
                                   o         (get orders-by-article art)
                                   placed    (when o (or (:placed o) 0))]
                               (cond-> row
                                 placed (assoc :placed           placed
                                               :cancelled        (or (:cancelled o) 0)
                                               :cancel-rate      (math/percentage (or (:cancelled o) 0) placed)
                                               :true-buyout-rate (math/percentage sold placed))))))
                      (sort-by :buyout-rate))]
    by-art))

(defn report
  [period]
  (println "\nАнализ % выкупа...")
  (let [data    (analyze period)
        total-o (reduce + 0 (map :ordered data))
        total-b (reduce + 0 (map :bought data))
        total-r (reduce + 0 (map :returned data))
        low     (filter #(and (>= (:ordered %) 3) (< (or (:buyout-rate %) 100) 70)) data)]

    (table/print-summary
     "АНАЛИЗ ВЫКУПА"
     [["Всего операций"    total-o]
      ["Выкуплено"         total-b]
      ["Возвращено"        total-r]
      ["Общий % выкупа"    (str (math/percentage total-b total-o) "%")]])

    (println "\n── Низкий % выкупа (< 70%, мин. 3 операции) ──")
    (if (seq low)
      (table/print-table
       [[:article "Артикул"] [:subject "Предмет"] [:ordered "Операций"]
        [:bought "Выкуп"] [:returned "Возврат"] [:buyout-rate "% выкупа"]]
       low)
      (println "  Все артикулы с хорошим выкупом."))

    (println "\n── Топ-20 по количеству операций ──")
    (table/print-table
     [[:article "Артикул"] [:ordered "Операций"] [:bought "Выкуп"]
      [:returned "Возврат"] [:buyout-rate "% выкупа"]]
     (->> data (sort-by :ordered >) (take 20)))

    data))

(defn export-excel [period path]
  (let [data (analyze period)]
    (export/to-excel path "Buyout rate"
      [[:article "Article"] [:subject "Subject"] [:ordered "Total ops"]
       [:bought "Bought"] [:returned "Returned"] [:buyout-rate "Buyout %"]]
      data)))
