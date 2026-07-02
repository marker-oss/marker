(ns analitica.domain.trends
  (:require [analitica.db :as db]
            [analitica.report.table :as table]
            [analitica.report.export :as export]
            [analitica.util.math :as math]
            [analitica.util.time :as t])
  (:import [java.time LocalDate]))

(defn- weekly-sales
  "Get sales aggregated by day+type from DB.

   The marketplace clause MUST be inserted before GROUP BY — appending it
   after would silently turn it into part of the grouping expression and
   the filter would be ignored entirely (same SQL pitfall as
   `domain.sales/db-sales`)."
  [from to & {:keys [marketplace]}]
  (let [mp-clause (when marketplace " AND marketplace = ?")
        params    (cond-> [from (str to "T23:59:59")]
                    marketplace (conj (name marketplace)))]
    (db/query (into [(str "SELECT substr(date,1,10) as day, type, "
                          "count(*) as cnt, sum(for_pay) as total "
                          "FROM sales WHERE date >= ? AND date <= ?" mp-clause
                          " GROUP BY day, type ORDER BY day")]
                    params))))

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

(defn- resolve-period-window
  "Return [from-str to-str] from a period arg.
   Accepts a keyword (delegates to t/period), a {:from :to} map, or a
   [from to] vector. When nil, defaults to the last 7 days ending today."
  [period]
  (cond
    (nil? period)     (let [today (t/format-date (t/today))]
                        [(t/format-date (t/days-ago 7)) today])
    (keyword? period) (t/period period)
    (vector? period)  period
    :else             [(:from period) (:to period)]))

(defn- prev-window
  "Given [cur-from cur-to] (ISO strings), return [prev-from prev-to] —
   the equal-length span ending the day before cur-from.

   Canon §Trends.1 (LT4): previous = same-length window immediately
   before the selected period, so chart/table share one consistent
   «выбранный период vs предыдущий равный период» comparison."
  [cur-from cur-to]
  (let [from-d  (java.time.LocalDate/parse cur-from)
        to-d    (java.time.LocalDate/parse cur-to)
        n-days  (inc (.until from-d to-d java.time.temporal.ChronoUnit/DAYS))
        p-to    (.minusDays from-d 1)
        p-from  (.minusDays p-to (dec n-days))]
    [(t/format-date p-from) (t/format-date p-to)]))

(defn wow
  "Period-vs-preceding-equal-period comparison, defaulting to a 7-day window.

   Canon §Trends.1 (LT4): a single «выбранный период vs предыдущий равный
   период» series replaces the old hardcoded WoW/MoM pair.  When `period`
   is omitted the window falls back to the last 7 days ending today.

   Args:
     period      — keyword, {:from :to} map, [from to] vector, or nil
     :marketplace — optional; scopes the SQL filter in weekly-sales"
  [period & {:keys [marketplace]}]
  (let [[cur-start cur-end] (resolve-period-window period)
        [prev-start prev-end] (prev-window cur-start cur-end)
        current  (weekly-sales cur-start cur-end :marketplace marketplace)
        previous (weekly-sales prev-start prev-end :marketplace marketplace)
        comp     (compare-periods current previous "Текущий период" "Предыдущий период")]
    (table/print-summary "ТРЕНД: ПЕРИОД К ПЕРИОДУ" [])
    (table/print-table
     [[:metric "Метрика"] [:previous "Предыдущий"] [:current "Текущий"]
      [:change "Изм."] [:change-pct "Изм.%"]]
     comp)
    comp))

(defn mom
  "Period-vs-preceding-equal-period comparison, defaulting to a 30-day window.

   Alias for `wow` with a 30-day fallback — both now share the same
   period-aware logic. Canon §Trends.1 (LT4).

   Args:
     period      — keyword, {:from :to} map, [from to] vector, or nil
     :marketplace — optional; scopes the SQL filter in weekly-sales"
  [period & {:keys [marketplace]}]
  (let [[cur-start cur-end] (if (nil? period)
                               [(t/format-date (t/days-ago 30))
                                (t/format-date (t/today))]
                               (resolve-period-window period))
        [prev-start prev-end] (prev-window cur-start cur-end)
        current  (weekly-sales cur-start cur-end :marketplace marketplace)
        previous (weekly-sales prev-start prev-end :marketplace marketplace)
        comp     (compare-periods current previous "Текущий период" "Предыдущий период")]
    (table/print-summary "ТРЕНД: ПЕРИОД К ПЕРИОДУ" [])
    (table/print-table
     [[:metric "Метрика"] [:previous "Предыдущий"] [:current "Текущий"]
      [:change "Изм."] [:change-pct "Изм.%"]]
     comp)
    comp))

(defn daily
  "Daily sales dynamics for a period.

  Canon reference: §Trends.3 / §Trends.5 (canonical-formulas.md).

  Calls `weekly-sales` (SQL pre-aggregated rows, §Trends.2) then re-groups
  by :day in memory, summing :sales, :returns, :revenue per day.
  Output sorted ascending by :day. :revenue rounded via math/round2.
  Optional `:marketplace` scopes the SQL filter."
  [period & {:keys [marketplace]}]
  (let [[from to] (if (keyword? period) (t/period period) [(:from period) (:to period)])
        data (weekly-sales from to :marketplace marketplace)
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
