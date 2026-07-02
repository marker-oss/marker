(ns analitica.domain.plan
  "Monthly plan domain — Run Rate forecast, pace multiplier, CRUD over
   monthly_plans table, per-SKU plan targets, variance computation, and
   import parser.

   Run Rate philosophy: forecast end-of-month value from MTD actual
   plus a sliding 7-day velocity. Early in the month (< 7 days elapsed)
   the 7-day window is degenerate, so fall back to MTD pace.

   Pace multiplier answers: 'how much faster than current velocity must
   we go to hit target?' 1.0 = on track, > 1.0 = behind, < 1.0 = ahead.

   Per-SKU extension (spec 017 US4):
   - monthly_plans gains a nullable `sku` column ('' = MP-level aggregate).
   - lookup-plan-sku: 3-level precedence (per-SKU > per-MP > all-MP).
   - compute-variance: PlanFactRow with variance-abs / variance-pct.
   - parse-import-rows: CSV/map rows → ImportOutcome (validate, coerce,
     split ok/bad). Metric strings coerced via interim->canonical-metric."
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
;; Metric coercion — canonical slug registry (spec 017 US4 / §1a)
;; Placed before validation so coerce-metric is available to validate-row.
;; ---------------------------------------------------------------------------

;; Interim → canonical slug mapping (§1a of contracts/plan-fact-sku.edn).
;; Wire/DB strings from existing rows or import files are coerced here.
;; 016 canonical-metric-slugs owns the canonical set; this map is the
;; single coercion point — do not duplicate it elsewhere.
(def ^:private interim->canonical-metric
  {"revenue"           :revenue
   "orders"            :orders
   "gross_profit"      :gross-margin
   "margin_pct"        :margin-pct
   "profit_margin_pct" :margin-pct
   "ad_spend"          :advertising
   "drr_pct"           :drr-pct
   "net_profit"        :net-profit})

;; Set of 016 canonical keyword slugs consumed by 017 (FR-026/SC-009).
;; 017 does NOT declare metrics — it only consumes them from 016.
;; Interim-fallback: until 016 ships its full descriptor registry, this
;; set reflects the 8 slugs listed in contracts/plan-fact-sku.edn §1.
(def ^:private canonical-metric-slugs
  #{:revenue :orders :gross-margin :margin-pct
    :advertising :drr-pct :net-profit})

(defn coerce-metric
  "Coerce a raw metric string (wire/import) to a canonical keyword slug.
   Returns nil when the string does not map to a known slug."
  [s]
  (when (string? s)
    (let [kw-direct (keyword s)]
      (or (canonical-metric-slugs kw-direct)          ; already a slug string
          (get interim->canonical-metric s)))))         ; interim → canonical

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(def ^:private valid-marketplaces #{"wb" "ozon" "ym" "all"})

;; Interim string metrics accepted by validate-row (pre-016 wire format).
;; Canonical keyword slugs are also accepted (coerce-metric resolves both).
(def ^:private valid-metrics
  #{"revenue" "orders" "gross_profit" "margin_pct"
    "ad_spend" "drr_pct" "profit_margin_pct"})

(def ^:private month-pattern #"^\d{4}-(0[1-9]|1[0-2])$")

(defn validate-row
  "Return nil when row is valid, otherwise a string describing the failure.
   Accepts metric as either interim string (pre-016) or canonical keyword slug."
  [{:keys [period-month marketplace metric target-value]}]
  (let [metric-name (some-> metric name)]
    (cond
      (or (not (string? period-month))
          (not (re-matches month-pattern period-month)))
        "period_month must be YYYY-MM"
      (not (contains? valid-marketplaces (some-> marketplace name)))
        (str "marketplace must be one of " valid-marketplaces)
      ;; Accept: interim string in valid-metrics OR resolves via coerce-metric
      (not (or (contains? valid-metrics metric-name)
               (some? (coerce-metric metric-name))))
        (str "metric must be one of " valid-metrics)
      (or (nil? target-value)
          (not (number? target-value))
          (not (pos? target-value)))
        "target_value must be a positive number"
      :else nil)))

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
;; Extended in the per-SKU section below to include the :sku dimension.
;; ---------------------------------------------------------------------------

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

;; ---------------------------------------------------------------------------
;; Extended save-plan! — supports :sku dimension
;; ---------------------------------------------------------------------------

(defn save-plan!
  "Insert or replace a single row. Throws ex-info on validation failure.
   :sku is optional; defaults to '' (MP-level aggregate, backward compat)."
  [{:keys [period-month marketplace metric target-value sku] :as row}]
  (when-let [err (validate-row row)]
    (throw (ex-info err {:row row})))
  (let [sku-val (or sku "")]
    (db/execute!
      ["INSERT INTO monthly_plans
          (period_month, marketplace, metric, sku, target_value, updated_at)
        VALUES (?, ?, ?, ?, ?, datetime('now'))
        ON CONFLICT (period_month, marketplace, metric, sku)
        DO UPDATE SET target_value = excluded.target_value,
                      updated_at   = excluded.updated_at"
       period-month (name marketplace) (name metric) sku-val (double target-value)])))

;; ---------------------------------------------------------------------------
;; Extended fetch-plans — includes sku column
;; ---------------------------------------------------------------------------

(defn fetch-plans
  "Return all rows for `period-month` (YYYY-MM), including sku column."
  [period-month]
  (db/query
    ["SELECT period_month, marketplace, metric, sku, target_value, updated_at
        FROM monthly_plans WHERE period_month = ?"
     period-month]))

;; ---------------------------------------------------------------------------
;; lookup-plan-sku — 3-level precedence (FR-023, AS-5)
;; ---------------------------------------------------------------------------

(defn lookup-plan-sku
  "Find the most-specific target_value for (period-month, marketplace, metric, sku).
   Precedence (most-specific wins):
     1. (period, mp,  metric, sku)   — per-SKU per-MP
     2. (period, mp,  metric, '')    — per-MP aggregate
     3. (period, all, metric, '')    — cross-MP aggregate
   Returns nil when none found."
  [rows {:keys [period-month marketplace metric sku]}]
  (let [mp-name     (some-> marketplace name)
        metric-name (some-> metric name)
        ;; Normalize metric name: accept both keyword slugs and interim strings.
        ;; Rows from DB store interim strings; comparisons normalise both sides.
        canonical-m (or (coerce-metric metric-name) metric)
        row-metric  (fn [r]
                      (let [rm (some-> (:metric r) name)]
                        (or (coerce-metric rm) (keyword rm))))
        match-mp?   (fn [r mp sku-val]
                      (and (= (:period-month r) period-month)
                           (= (some-> (:marketplace r) name) mp)
                           (= (row-metric r) canonical-m)
                           (= (or (:sku r) "") sku-val)))
        per-sku  (some #(when (match-mp? % mp-name sku) %) rows)
        per-mp   (some #(when (match-mp? % mp-name "") %) rows)
        all-mp   (some #(when (match-mp? % "all" "") %) rows)]
    (some-> (or per-sku per-mp all-mp) :target-value)))

;; ---------------------------------------------------------------------------
;; compute-variance — PlanFactRow with variance fields (FR-021, FR-022)
;; ---------------------------------------------------------------------------

(defn compute-variance
  "Given a map with :sku, :metric, :plan (may be nil), :actual (number),
   return a PlanFactRow map with :variance-abs and :variance-pct.

   Variance semantics (contracts/plan-fact-sku.edn §3):
     plan=nil             → variance-abs=nil, variance-pct=nil (NOT −100%)
     plan>0, actual=0     → variance-abs=−plan, variance-pct=−100.0
     plan>0, actual>0     → abs=actual−plan; pct=(actual−plan)/plan×100
     plan=0               → variance-pct=nil (guard against divide-by-zero)"
  [{:keys [sku metric plan actual]}]
  (let [variance-abs (when (some? plan)
                       (- (double actual) (double plan)))
        variance-pct (when (and (some? plan) (some? variance-abs))
                       (if (zero? (double plan))
                         nil
                         (* (/ variance-abs (double plan)) 100.0)))]
    {:sku          sku
     :metric       metric
     :plan         plan
     :actual       actual
     :variance-abs (some-> variance-abs double)
     :variance-pct (some-> variance-pct double)}))

;; ---------------------------------------------------------------------------
;; parse-import-rows — import parser (FR-020, AS-1)
;; ---------------------------------------------------------------------------

(defn- parse-target-value
  "Parse a target-value string (comma or dot decimal) to Double.
   Returns nil on failure."
  [s]
  (when (string? s)
    (try
      (Double/parseDouble (.replace s "," "."))
      (catch NumberFormatException _ nil))))

(defn parse-import-rows
  "Parse a sequence of raw import maps (from CSV/XLSX) into an ImportOutcome.

   Each raw row must have :sku, :metric, :target-value (all strings).
   :marketplace is optional; defaults to `context :marketplace`.

   Context map: {:period-month YYYY-MM :marketplace string :known-skus set-of-strings}

   Returns ImportOutcome:
     {:total N :loaded N :rejected N
      :rows  [validated PlanTarget maps]
      :errors [{:line N :sku s :reason s}]}

   Rules (FR-020):
     - Empty or unknown SKU (not in :known-skus) → reject 'unknown SKU'.
     - Unknown metric string (not in interim->canonical-metric or slug set)
       → reject 'unknown metric'.
     - target-value not parseable / ≤ 0 → reject 'target must be positive number'.
     - Duplicates within file: both rows reported as loaded (last-wins at upsert)."
  [raw-rows {:keys [period-month marketplace known-skus]}]
  (let [results
        (map-indexed
          (fn [idx raw]
            (let [sku        (or (:sku raw) "")
                  metric-str (or (:metric raw) "")
                  tv-str     (or (:target-value raw) "")
                  mp         (or (:marketplace raw) marketplace)
                  line       (inc idx)]
              (cond
                ;; Empty or unknown SKU
                (or (empty? sku) (not (contains? known-skus sku)))
                {:ok? false :line line :sku sku
                 :reason "unknown SKU"}

                ;; Unknown metric
                (nil? (coerce-metric metric-str))
                {:ok? false :line line :sku sku
                 :reason (str "unknown metric: " metric-str)}

                ;; Parse target value
                :else
                (let [tv (parse-target-value tv-str)]
                  (if (or (nil? tv) (<= tv 0))
                    {:ok? false :line line :sku sku
                     :reason "target must be positive number"}
                    {:ok?    true
                     :row    {:period-month period-month
                              :marketplace  mp
                              :metric       (coerce-metric metric-str)
                              :sku          sku
                              :target-value tv}})))))
          raw-rows)
        ok     (filter :ok?  results)
        bad    (filter (complement :ok?) results)]
    {:total    (count raw-rows)
     :loaded   (count ok)
     :rejected (count bad)
     :rows     (mapv :row ok)
     :errors   (mapv (fn [e] {:line (:line e) :sku (:sku e) :reason (:reason e)}) bad)}))
