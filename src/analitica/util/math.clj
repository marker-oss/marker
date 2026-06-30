(ns analitica.util.math)

(defn round2
  "Round to 2 decimal places."
  [x]
  (when x
    (double (/ (Math/round (* (double x) 100.0)) 100.0))))

(defn percentage
  "Calculate percentage: (part / total) * 100. Returns nil if total is zero."
  [part total]
  (when (and total (not (zero? total)))
    (round2 (* 100.0 (/ (double part) (double total))))))

(defn safe-div
  "Safe division, returns 0 if divisor is zero."
  [a b]
  (if (and b (not (zero? b)))
    (/ (double a) (double b))
    0.0))

(defn unit-qty
  "Canonical unit count for a sales/finance row when Σ-weighting by quantity.
   A row with no `:quantity` is one unit (WB rows carry no quantity and are
   1 unit/row), so a missing quantity coalesces to 1. This is the single
   source for the `(or (:quantity row) 1)` convention used wherever a metric
   sums units rather than rows (avg-price denominator, mp-share weighting —
   spec 014 FR-002/FR-003/FR-011). Guarantees WB zero-regression: on a
   quantity-less set, Σ unit-qty == row count, so the metric is unchanged.
   NOTE: COGS (FR-004) deliberately uses `(or (:quantity row) 0)`, not this —
   a service/unknown row contributes 0 cost-units, not 1."
  [row]
  (or (:quantity row) 1))

(defn pct-delta
  "Percentage change from `previous` to `current`. Returns 0.0 when
   previous is nil or zero. Rounded to 2 dp."
  [current previous]
  (if (or (nil? previous) (zero? previous))
    0.0
    (round2 (* 100.0 (/ (- current previous) previous)))))

;; ---------------------------------------------------------------------------
;; Advertising-effectiveness KPIs (ROAS / ДРР / ROMI)
;; ---------------------------------------------------------------------------

(def ad-spend-threshold-rub
  "Below this absolute ad-spend (₽) advertising-effectiveness KPIs
   (ROAS, ДРР, ROMI) become meaningless: a single stray ad-stats row
   of a few kopecks balloons ROAS into 7-digit territory while ДРР
   rounds to 0.0%. KPIs return nil below this threshold so display
   layers can render «—»."
  100.0)

(defn roas
  "ROAS (revenue / ad-spend) as a float, or nil when ad-spend is below
   `ad-spend-threshold-rub`. Use for both Pulse and any per-article
   ROAS so the same noise-floor applies everywhere."
  [revenue ad-spend]
  (when (and (number? ad-spend) (>= ad-spend ad-spend-threshold-rub))
    (round2 (/ (double (or revenue 0.0)) ad-spend))))

(defn drr
  "ДРР (ad-spend / revenue × 100) as a float, or nil when ad-spend is
   below threshold or revenue is non-positive. Mirrors `roas` so both
   KPIs vanish together when ad data is meaningless."
  [revenue ad-spend]
  (when (and (number? ad-spend) (>= ad-spend ad-spend-threshold-rub)
             (number? revenue) (pos? revenue))
    (round2 (* 100.0 (/ ad-spend revenue)))))

(defn romi
  "Return on Marketing Investment — alias for `roas`. Some pages
   historically call the same revenue/ad-spend ratio ROMI."
  [revenue ad-spend]
  (roas revenue ad-spend))
