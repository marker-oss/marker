(ns analitica.domain.abc
  (:require [analitica.domain.finance :as finance]
            [analitica.report.table :as table]
            [analitica.util.math :as math]))

(defn- classify
  "Assign ABC category based on cumulative percentage. §ABC.1 in canonical-formulas.md.

   Formula: cum% = round2(100 × Σ(value-fn, items[0..i]) / total).
   Boundaries: cum% ≤ 80 → A; 80 < cum% ≤ 95 → B; else → C.
   When total = 0 (empty or all-zero criterion), returns nil — caller must handle."
  [sorted-items value-fn]
  (let [total (reduce + 0.0 (map value-fn sorted-items))]
    (when (pos? total)
      (loop [items sorted-items
             cum   0.0
             result []]
        (if (empty? items)
          result
          (let [item (first items)
                cum' (+ cum (value-fn item))
                pct  (math/round2 (* 100.0 (/ cum' total)))
                cat  (cond
                       (<= pct 80)  "A"
                       (<= pct 95)  "B"
                       :else        "C")]
            (recur (rest items)
                   cum'
                   (conj result (assoc item :abc-category cat :cum-pct pct)))))))))

(defn- article-profit
  "Per-article net profit computable in the finance aggregation pipeline:
   for-pay (net payout) − total-cost (net-of-returns COGS). Ad-spend is NOT
   attributable per-article in finance data, so it is excluded here — this is
   the honest per-article profit basis for ABC ranking (016 US4 / R7)."
  [row]
  (- (double (or (:for-pay row) 0.0))
     (double (or (:total-cost row) 0.0))))

(defn analyze-by
  "Perform ABC analysis on finance data over the FULL dataset (never a top-N
   slice or a single page — VR-a1/SC-006).
   criterion: :revenue, :for-pay, :sales-qty, :profit.

   :profit ranks by per-article net profit (for-pay − total-cost); the
   cumulative-share `classify` machinery (≤80→A, ≤95→B, else C) is reused
   unchanged. 016 US4 / R7."
  [finance-data criterion]
  (let [by-art  (finance/by-article finance-data)
        val-fn  (case criterion
                  :revenue   :revenue
                  :for-pay   :for-pay
                  :sales-qty (comp double :sales-qty)
                  :profit    article-profit
                  :revenue)
        sorted  (sort-by val-fn > by-art)]
    (classify sorted val-fn)))

(defn summary
  "Summarize ABC categories."
  [abc-data]
  (->> abc-data
       (group-by :abc-category)
       (map (fn [[cat items]]
              {:category     cat
               :count        (count items)
               :revenue      (math/round2 (reduce + 0.0 (map :revenue items)))
               :for-pay      (math/round2 (reduce + 0.0 (map :for-pay items)))
               :sales-qty    (reduce + 0 (map :sales-qty items))
               :returns-qty  (reduce + 0 (map :returns-qty items))}))
       (sort-by :category)))

(defn report
  "Print ABC analysis report.
   Usage:
     (report {:from \"2026-03-01\" :to \"2026-03-31\"})
     (report :last-30-days)
     (report :last-30-days :by :for-pay)"
  [period & {:keys [marketplace by] :or {marketplace :wb by :revenue}}]
  (println "\nЗагрузка данных для ABC-анализа...")
  (let [fin-data (finance/fetch-finance period :marketplace marketplace)
        abc-data (analyze-by fin-data by)
        summ     (summary abc-data)]

    (table/print-summary
     (str "ABC-АНАЛИЗ (по " (name by) ")")
     [])

    (table/print-table
     [[:category "Кат."] [:count "Артикулов"] [:sales-qty "Продажи"]
      [:returns-qty "Возвраты"] [:revenue "Выручка"] [:for-pay "К выплате"]]
     summ)

    (println "\n── Категория A ──")
    (table/print-table
     [[:article "Артикул"] [:abc-category "Кат."] [:sales-qty "Прод."]
      [:revenue "Выручка"] [:for-pay "К выплате"] [:cum-pct "Нак.%"]]
     (filter #(= "A" (:abc-category %)) abc-data))

    (println "\n── Категория C (кандидаты на вывод) ──")
    (let [c-items (filter #(= "C" (:abc-category %)) abc-data)]
      (if (seq c-items)
        (table/print-table
         [[:article "Артикул"] [:sales-qty "Прод."] [:returns-qty "Возвр."]
          [:revenue "Выручка"] [:for-pay "К выплате"]]
         c-items)
        (println "  Нет артикулов в категории C.")))

    {:summary summ :details abc-data}))
