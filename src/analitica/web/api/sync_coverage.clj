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

(defn coverage-by-mp-and-type
  "Return per-MP per-type per-day coverage map.
   Shape: {:wb {:finance {:days [...]} :orders {...}} :ozon {...} ...}"
  []
  {:wb   {:finance {:days (days-for-table :finance "date_from" :wb)}
          :orders  {:days (days-for-table :orders "date" :wb)}
          :sales   {:days (days-for-table :sales "date" :wb)}
          :storage {:days (days-for-table :paid_storage "date" :wb)}
          :stocks  {:days (days-for-table :stocks "synced_at" :wb)}}
   :ozon {:finance {:days (days-for-table :finance "date_from" :ozon)}
          :orders  {:days (days-for-table :orders "date" :ozon)}
          :sales   {:days (days-for-table :sales "date" :ozon)}
          :stocks  {:days (days-for-table :stocks "synced_at" :ozon)}}
   :ym   {:finance {:days (days-for-table :finance "date_from" :ym)}
          :orders  {:days (days-for-table :orders "date" :ym)}
          :sales   {:days (days-for-table :sales "date" :ym)}
          :stocks  {:days (days-for-table :stocks "synced_at" :ym)}}})
