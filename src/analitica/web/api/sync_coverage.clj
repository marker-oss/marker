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

(defn- consecutive-iso-runs
  "Group a SORTED seq of ISO date strings into runs of calendar-consecutive
   days. Returns a vector of [first-iso last-iso] pairs (the gaps/holes)."
  [iso-days]
  (->> (map #(java.time.LocalDate/parse %) iso-days)
       (reduce (fn [runs d]
                 (let [run  (peek runs)
                       prev (peek run)]
                   (if (and prev (= d (.plusDays prev 1)))
                     (conj (pop runs) (conj run d))
                     (conj runs [d]))))
               [])
       (mapv (fn [run] [(str (first run)) (str (last run))]))))

(defn day-list->coverage
  "Turn a list of present ISO days into an honest coverage descriptor (P0-A
   Part B, specs/010). `kind` ∈ {:snapshot :event-stream :monthly-batch}.

   - :snapshot      → point-in-time; reports :as-of, NEVER a span/holes/expected.
   - :event-stream  → :present / :expected calendar days + :holes (gap ranges)
   - :monthly-batch → same as event-stream.

   :present counts DISTINCT calendar days (not timestamps/records), which is
   the fix for the inflated 'N дн' bug. :status ∈ {:full :partial :missing}."
  [kind days]
  (let [present-days (vec (sort (distinct (remove nil? days))))
        present      (count present-days)]
    (if (zero? present)
      {:kind kind :present 0 :span nil :expected 0 :holes [] :status :missing}
      (let [from (first present-days)
            to   (last present-days)]
        (if (= kind :snapshot)
          {:kind :snapshot :present present :as-of to :status :full}
          (let [all         (expand-period from to)
                present-set (set present-days)
                missing     (remove present-set all)
                holes       (consecutive-iso-runs missing)]
            {:kind     kind
             :present  present
             :span     {:from from :to to}
             :expected (count all)
             :holes    holes
             :status   (if (seq holes) :partial :full)}))))))

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

(defn coverage-report
  "Enriched per-(mp, entity) coverage for the /sync matrix (P0-A Part B, specs/010).

   Each cell is a `day-list->coverage` descriptor carrying :kind, calendar-day
   :present/:expected, :holes and :status — so the UI can answer 'what do we
   hold for date D?' and stop mislabelling row-counts as days or snapshots as
   ranges. Adds ad_stats and regions (FR-P2.6), previously absent from the matrix.

   Entity → temporal kind:
     :event-stream  — sales, orders (per-day events)
     :monthly-batch — finance/realization, storage, ad_stats, regions
     :snapshot      — stocks, prices (point-in-time; reported as :as-of)"
  []
  (let [ev   (fn [table col mp] (day-list->coverage :event-stream  (days-for-table table col mp)))
        snap (fn [table col mp] (day-list->coverage :snapshot      (days-for-table table col mp)))
        bat  (fn [days]         (day-list->coverage :monthly-batch days))]
    {:wb   {:sales    (ev   :sales        "date"       :wb)
            :orders   (ev   :orders       "date"       :wb)
            :finance  (bat  (days-for-table :finance      "event_date" :wb))
            :storage  (bat  (days-for-table :paid_storage "date"       :wb))
            :stocks   (snap :stocks       "synced_at"  :wb)
            :prices   (snap :prices       "synced_at"  :wb)
            ;; ad_stats has no `marketplace` column (WB-only advert table) → nil mp.
            :ad_stats (bat  (days-for-table :ad_stats     "date"       nil))}
     :ozon {:sales    (ev   :sales        "date"       :ozon)
            :orders   (ev   :orders       "date"       :ozon)
            :finance  (bat  (days-for-table :finance      "event_date" :ozon))
            :storage  (bat  (cashflow-storage-days :ozon))
            :stocks   (snap :stocks       "synced_at"  :ozon)
            :prices   (snap :prices       "synced_at"  :ozon)}
     :ym   {:sales    (ev   :sales        "date"       :ym)
            :orders   (ev   :orders       "date"       :ym)
            :finance  (bat  (days-for-table :finance      "event_date" :ym))
            :stocks   (snap :stocks       "synced_at"  :ym)
            :prices   (snap :prices       "synced_at"  :ym)}
     ;; Cross-MP entities (no per-marketplace split) — chips under the matrix.
     :stats   (bat  (days-for-table :product_stats "date_from"  nil))
     :regions (bat  (days-for-table :region_sales  "date_from"  nil))
     :1c      (snap :cost_prices  "updated_at" nil)}))
