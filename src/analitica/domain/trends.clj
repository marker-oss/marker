(ns analitica.domain.trends
  (:require [analitica.db :as db]
            [analitica.report.table :as table]
            [analitica.report.export :as export]
            [analitica.util.math :as math]
            [analitica.util.time :as t])
  (:import [java.time LocalDate]))

(defn- weekly-sales
  "Get sales aggregated by ISO week from DB."
  [from to]
  (db/query [(str "SELECT substr(date,1,10) as day, type, "
                  "count(*) as cnt, sum(for_pay) as total "
                  "FROM sales WHERE date >= ? AND date <= ? "
                  "GROUP BY day, type ORDER BY day")
             from (str to "T23:59:59")]))

(defn- compare-periods
  "Compare two periods and calculate change.

  Canon reference: §Trends.1 (canonical-formulas.md).

  Accepts two sequences of pre-aggregated rows in the shape returned by
  `weekly-sales` ({:day :type :cnt :total}, string type \"sale\"/\"return\").
  Returns a vector of 4 metric maps:
    - Sales qty, Returns qty, Revenue: :change-pct = math/percentage(Δ, max(1, prev))
    - Avg check: :change-pct nil (ratio-of-ratios is misleading — see §Trends.1)."
  [current previous label-current label-previous]
  (let [cur-sales  (reduce + 0 (map :cnt (filter #(= "sale" (:type %)) current)))
        cur-ret    (reduce + 0 (map :cnt (filter #(= "return" (:type %)) current)))
        cur-rev    (reduce + 0.0 (map :total (filter #(= "sale" (:type %)) current)))
        prev-sales (reduce + 0 (map :cnt (filter #(= "sale" (:type %)) previous)))
        prev-ret   (reduce + 0 (map :cnt (filter #(= "return" (:type %)) previous)))
        prev-rev   (reduce + 0.0 (map :total (filter #(= "sale" (:type %)) previous)))]
    [{:metric "Продажи шт"   :current cur-sales  :previous prev-sales
      :change (- cur-sales prev-sales) :change-pct (math/percentage (- cur-sales prev-sales) (max 1 prev-sales))}
     {:metric "Возвраты шт"  :current cur-ret    :previous prev-ret
      :change (- cur-ret prev-ret)     :change-pct (math/percentage (- cur-ret prev-ret) (max 1 prev-ret))}
     {:metric "Выручка"      :current (math/round2 cur-rev) :previous (math/round2 prev-rev)
      :change (math/round2 (- cur-rev prev-rev))
      :change-pct (math/percentage (- cur-rev prev-rev) (max 1.0 prev-rev))}
     {:metric "Средний чек"
      :current (math/round2 (math/safe-div cur-rev cur-sales))
      :previous (math/round2 (math/safe-div prev-rev prev-sales))
      :change (math/round2 (- (math/safe-div cur-rev cur-sales) (math/safe-div prev-rev prev-sales)))
      :change-pct nil}]))

(defn wow
  "Week-over-week comparison."
  []
  (let [today    (t/today)
        cur-end  (t/format-date today)
        cur-start (t/format-date (t/days-ago 7))
        prev-end (t/format-date (t/days-ago 7))
        prev-start (t/format-date (t/days-ago 14))
        current  (weekly-sales cur-start cur-end)
        previous (weekly-sales prev-start prev-end)
        comp     (compare-periods current previous "Текущая неделя" "Прошлая неделя")]
    (table/print-summary "ТРЕНД: НЕДЕЛЯ К НЕДЕЛЕ (WoW)" [])
    (table/print-table
     [[:metric "Метрика"] [:previous "Прошлая"] [:current "Текущая"]
      [:change "Изм."] [:change-pct "Изм.%"]]
     comp)
    comp))

(defn mom
  "Month-over-month comparison."
  []
  (let [today    (t/today)
        cur-end  (t/format-date today)
        cur-start (t/format-date (t/days-ago 30))
        prev-end (t/format-date (t/days-ago 30))
        prev-start (t/format-date (t/days-ago 60))
        current  (weekly-sales cur-start cur-end)
        previous (weekly-sales prev-start prev-end)
        comp     (compare-periods current previous "Текущий месяц" "Прошлый месяц")]
    (table/print-summary "ТРЕНД: МЕСЯЦ К МЕСЯЦУ (MoM)" [])
    (table/print-table
     [[:metric "Метрика"] [:previous "Прошлый"] [:current "Текущий"]
      [:change "Изм."] [:change-pct "Изм.%"]]
     comp)
    comp))

(defn daily
  "Daily sales dynamics for a period.

  Canon reference: §Trends.3 / §Trends.5 (canonical-formulas.md).

  Calls `weekly-sales` (SQL pre-aggregated rows, §Trends.2) then re-groups
  by :day in memory, summing :sales, :returns, :revenue per day.
  Output sorted ascending by :day. :revenue rounded via math/round2."
  [period]
  (let [[from to] (if (keyword? period) (t/period period) [(:from period) (:to period)])
        data (weekly-sales from to)
        by-day (->> data
                    (group-by :day)
                    (map (fn [[day items]]
                           {:day     day
                            :sales   (reduce + 0 (map :cnt (filter #(= "sale" (:type %)) items)))
                            :returns (reduce + 0 (map :cnt (filter #(= "return" (:type %)) items)))
                            :revenue (math/round2 (reduce + 0.0 (map :total (filter #(= "sale" (:type %)) items))))}))
                    (sort-by :day))]
    (table/print-summary "ДИНАМИКА ПО ДНЯМ" [])
    (table/print-table
     [[:day "Дата"] [:sales "Продажи"] [:returns "Возвраты"] [:revenue "Выручка"]]
     by-day)
    by-day))
