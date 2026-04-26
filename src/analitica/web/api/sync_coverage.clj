(ns analitica.web.api.sync-coverage
  "Per-day coverage data for the /sync page heatmap.

   Returns a map keyed by [mp data-type] with a vector of ISO dates present.
   Used to render a per-MP × per-type heatmap (one cell per day, colored by
   presence) on the /sync page."
  (:require [clojure.string :as str]
            [analitica.db :as db]))

(defn- iso-date-fragment
  "Extract the ISO date prefix from a value. Handles both 'YYYY-MM-DD...'
   and 'DD-MM-YYYY...' formats. Returns ISO (YYYY-MM-DD) or nil."
  [s]
  (when (and (string? s) (>= (count s) 10))
    (let [p (subs s 0 10)]
      (cond
        (re-matches #"\d{4}-\d{2}-\d{2}" p) p
        (re-matches #"\d{2}-\d{2}-\d{4}" p) (let [[d m y] (str/split p #"-")]
                                              (str y "-" m "-" d))
        (re-matches #"\d{2}\.\d{2}\.\d{4}" p) (let [[d m y] (str/split p #"\.")]
                                                (str y "-" m "-" d))
        :else nil))))

(defn- days-for-table
  "Fetch distinct ISO date strings for a table/column, optionally filtered by marketplace."
  [table date-col marketplace]
  (let [mp-clause (when marketplace " AND marketplace = ?")
        params (cond-> [] marketplace (conj (name marketplace)))
        sql (str "SELECT DISTINCT " date-col " AS d FROM " (name table)
                 " WHERE " date-col " IS NOT NULL" mp-clause
                 " ORDER BY d")
        rows (try (db/query (into [sql] params))
                  (catch Exception _ []))]
    (vec (distinct (keep (comp iso-date-fragment :d) rows)))))

(defn- expand-period
  "Inclusive ISO-day expansion of a [period_begin, period_end] pair."
  [period-begin period-end]
  (let [start (java.time.LocalDate/parse period-begin)
        end   (java.time.LocalDate/parse period-end)]
    (->> (iterate #(.plusDays % 1) start)
         (take-while #(not (.isAfter % end)))
         (map str))))

(defn- cashflow-storage-days
  "Storage days for an MP whose storage cost is reported in cash_flow_periods
   (Ozon only). Each weekly period is expanded into its constituent ISO days."
  [marketplace]
  (let [rows (try
               (db/query [(str "SELECT period_begin, period_end FROM cash_flow_periods "
                               "WHERE source = ? AND storage IS NOT NULL "
                               "ORDER BY period_begin")
                          (name marketplace)])
               (catch Exception _ []))]
    (->> rows
         (mapcat (fn [r] (expand-period (:period-begin r) (:period-end r))))
         distinct
         sort
         vec)))

(defn coverage-by-mp-and-type
  "Return per-MP per-type per-day coverage map.
   Shape: {:wb {:finance {:days [...]} :orders {...}} :ozon {...} ...}

   Storage row notes:
   - WB: per-day per-article from /api/v1/paid_storage.
   - Ozon: weekly periods from cash_flow_statement, expanded into days from
     cash_flow_periods. Per-article granularity is a separate (broken) endpoint.
   - YM: omitted entirely. Business is FBS-only — there is no marketplace
     storage cost, so showing an empty row would be a false-broken signal.
     The /sync date-range API surfaces a {:na true} sentinel for this same fact."
  []
  {:wb   {:finance {:days (days-for-table :finance "event_date" :wb)}
          :orders  {:days (days-for-table :orders "date" :wb)}
          :sales   {:days (days-for-table :sales "date" :wb)}
          :storage {:days (days-for-table :paid_storage "date" :wb)}
          :stocks  {:days (days-for-table :stocks "synced_at" :wb)}}
   :ozon {:finance {:days (days-for-table :finance "event_date" :ozon)}
          :orders  {:days (days-for-table :orders "date" :ozon)}
          :sales   {:days (days-for-table :sales "date" :ozon)}
          :storage {:days (cashflow-storage-days :ozon)}
          :stocks  {:days (days-for-table :stocks "synced_at" :ozon)}}
   :ym   {:finance {:days (days-for-table :finance "event_date" :ym)}
          :orders  {:days (days-for-table :orders "date" :ym)}
          :sales   {:days (days-for-table :sales "date" :ym)}
          :stocks  {:days (days-for-table :stocks "synced_at" :ym)}}})
