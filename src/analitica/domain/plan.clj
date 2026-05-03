(ns analitica.domain.plan
  "Monthly plan domain — Run Rate forecast, pace multiplier, and CRUD
   over monthly_plans table.

   Run Rate philosophy: forecast end-of-month value from MTD actual
   plus a sliding 7-day velocity. Early in the month (< 7 days elapsed)
   the 7-day window is degenerate, so fall back to MTD pace.

   Pace multiplier answers: 'how much faster than current velocity must
   we go to hit target?' 1.0 = on track, > 1.0 = behind, < 1.0 = ahead."
  (:require [analitica.db :as db]))

(defn run-rate
  "Forecast end-of-month value.

   forecast = actual-mtd + velocity × days-remaining
   velocity = last-7d-actual / 7   (when days-elapsed >= 7)
            = actual-mtd / days-elapsed   (when days-elapsed < 7,
              avoids unstable 7-day window early in the month)"
  [{:keys [actual-mtd days-elapsed days-in-month last-7d-actual]}]
  (let [days-remaining (max 0 (- days-in-month days-elapsed))
        velocity       (cond
                         (zero? days-elapsed) 0.0
                         (< days-elapsed 7)   (/ (double actual-mtd)
                                                 (double days-elapsed))
                         :else                (/ (double last-7d-actual) 7.0))]
    (+ (double actual-mtd)
       (* velocity days-remaining))))

(defn pace-multiplier
  "Multiplier for current velocity needed to land on target.

   1.0  → already on/over target (no acceleration needed).
   >1.0 → must speed up by this factor to land on target.
   <1.0 → ahead of plan; can comfortably slow down by this factor.
   POSITIVE_INFINITY → target unmet AND we have no path: either no time
                       remaining, or zero forecast momentum from
                       actual-MTD. Callers should render as 'не успеть'."
  [{:keys [actual-mtd forecast target days-remaining]}]
  (let [delta-needed   (- target actual-mtd)
        delta-forecast (- forecast actual-mtd)]
    (cond
      (<= delta-needed 0)           1.0
      (zero? days-remaining)        Double/POSITIVE_INFINITY
      (<= delta-forecast 0)         Double/POSITIVE_INFINITY
      :else                         (double (/ delta-needed delta-forecast)))))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(def ^:private valid-marketplaces #{"wb" "ozon" "ym" "all"})

(def ^:private valid-metrics
  #{"revenue" "orders" "gross_profit" "margin_pct"
    "ad_spend" "drr_pct" "profit_margin_pct"})

(def ^:private month-pattern #"^\d{4}-(0[1-9]|1[0-2])$")

(defn validate-row
  "Return nil when row is valid, otherwise a string describing the failure."
  [{:keys [period-month marketplace metric target-value]}]
  (cond
    (or (not (string? period-month))
        (not (re-matches month-pattern period-month)))
      "period_month must be YYYY-MM"
    (not (contains? valid-marketplaces (some-> marketplace name)))
      (str "marketplace must be one of " valid-marketplaces)
    (not (contains? valid-metrics (some-> metric name)))
      (str "metric must be one of " valid-metrics)
    (or (nil? target-value)
        (not (number? target-value))
        (not (pos? target-value)))
      "target_value must be a positive number"
    :else nil))

;; ---------------------------------------------------------------------------
;; Lookup over pre-fetched rows
;; ---------------------------------------------------------------------------

(defn lookup-plan
  "Find the most-specific target_value for (period-month, marketplace, metric).
   Per-MP wins over 'all'. Returns nil when neither is set."
  [rows {:keys [period-month marketplace metric]}]
  (let [mp-name     (some-> marketplace name)
        metric-name (some-> metric name)
        match? (fn [r mp]
                 (and (= (:period-month r) period-month)
                      (= (some-> (:marketplace r) name) mp)
                      (= (some-> (:metric r) name) metric-name)))
        per-mp (some #(when (match? % mp-name) %) rows)
        all    (some #(when (match? % "all") %) rows)]
    (some-> (or per-mp all) :target-value)))

;; ---------------------------------------------------------------------------
;; Persistence (SQLite via analitica.db)
;; ---------------------------------------------------------------------------

(defn fetch-plans
  "Return all rows for `period-month` (YYYY-MM)."
  [period-month]
  (db/query
    ["SELECT period_month, marketplace, metric, target_value, updated_at
        FROM monthly_plans WHERE period_month = ?"
     period-month]))

(defn save-plan!
  "Insert or replace a single row. Throws ex-info on validation failure."
  [{:keys [period-month marketplace metric target-value] :as row}]
  (when-let [err (validate-row row)]
    (throw (ex-info err {:row row})))
  (db/execute!
    ["INSERT INTO monthly_plans
        (period_month, marketplace, metric, target_value, updated_at)
      VALUES (?, ?, ?, ?, datetime('now'))
      ON CONFLICT (period_month, marketplace, metric)
      DO UPDATE SET target_value = excluded.target_value,
                    updated_at   = excluded.updated_at"
     period-month (name marketplace) (name metric) (double target-value)]))

(defn delete-plan!
  "Remove one row by primary key. No-op when absent."
  [{:keys [period-month marketplace metric]}]
  (db/execute!
    ["DELETE FROM monthly_plans
       WHERE period_month = ? AND marketplace = ? AND metric = ?"
     period-month (name marketplace) (name metric)]))

(defn clear-month!
  "Wipe all rows for `period-month`. Test helper."
  [period-month]
  (db/execute!
    ["DELETE FROM monthly_plans WHERE period_month = ?" period-month]))
