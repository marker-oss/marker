(ns analitica.web.api.coverage
  "Data-coverage API: which days in a range have at least one finance row.

   Used by the period-picker calendar to mark days with/without data. The
   client fetches `/api/coverage?from=<iso>&to=<iso>[&marketplace=<mp>]`
   and renders green/gray bars under each day in the calendar."
  (:require [analitica.db :as db]))

(defn days-with-data
  "Return a sorted vector of ISO date strings (YYYY-MM-DD) within [from, to]
   inclusive that have at least one finance row. Optional :marketplace keyword
   narrows by MP (e.g. :wb / :ozon / :ym). Dates are taken from finance.date_from.

   Example: (days-with-data \"2026-04-01\" \"2026-04-30\") => [\"2026-04-01\" \"2026-04-05\" …]"
  [from to & {:keys [marketplace]}]
  (let [mp-clause (if marketplace " AND marketplace = ?" "")
        params    (cond-> [from to] marketplace (conj (name marketplace)))
        sql       (str "SELECT DISTINCT date_from AS d FROM finance "
                       "WHERE date_from IS NOT NULL "
                       "AND date_from >= ? AND date_from <= ?" mp-clause
                       " ORDER BY date_from")
        rows      (db/query (into [sql] params))]
    (->> rows (map :d) (filter some?) vec)))
