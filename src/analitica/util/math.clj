(ns analitica.util.math
  (:import [java.math BigDecimal RoundingMode]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Analytics money path — DOUBLE (FROZEN, spec 019 SC-008)
;; ---------------------------------------------------------------------------
;; round2 / percentage / safe-div and the ~88 (reduce + 0.0 …) call-sites are
;; the analytics money type: P&L, unit-economics, Pulse, sales/finance canon.
;; Copeck-exactness is NOT required (we round on display). This block MUST stay
;; bit-for-bit unchanged — the decimal ledger block below is a DISTINCT path
;; with NO bridge between them (contracts/decimal-money.edn §5).
;; ═══════════════════════════════════════════════════════════════════════════

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

;; ═══════════════════════════════════════════════════════════════════════════
;; Ledger money path — DECIMAL-as-STRING (spec 019 §3.D, OWNER 019)
;; ---------------------------------------------------------------------------
;; A DISTINCT path from the double analytics helpers above. Ledger money is
;; java.math.BigDecimal, scale=2, RoundingMode/HALF_UP; transported and stored
;; as "0.00" strings (TEXT in SQLite, transit-string to the SPA). Applies to
;; LEDGER money only: treasury operations/balances, obligations, ДДС cells,
;; 12-month dynamics. Copeck-exact — column total == Σ rows to the kopeck
;; (FR-004 / SC-002). NO bridge to the double path (contracts/decimal-money.edn
;; §5): parse/render exclusively through `d`/`d->str`, never parseDouble/format.
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:const decimal-scale
  "Scale (fractional digits) for all ledger money — kopecks."
  2)

(def decimal-str-re
  "Wire/storage form of ledger money: exactly two fractional digits, optional
   leading minus for internal aggregates (net/balance/delta). Contract regex
   from contracts/decimal-money.edn :transport :format."
  #"^-?\d+\.\d{2}$")

(def DecimalStr
  "Malli schema for the decimal-string wire/storage form (\"0.00\").
   Registered by callers as :treasury/decimal-str."
  [:re decimal-str-re])

(defn decimal-str?
  "True iff `s` is a valid ledger decimal-string (\"0.00\"-form). A plain
   predicate so callers/Malli can validate the wire form without importing
   Malli here."
  [s]
  (boolean (and (string? s) (re-matches decimal-str-re s))))

(defn d
  "Parse a ledger decimal-string \"0.00\" → BigDecimal (scale=2, HALF_UP).
   Throws on any value outside `decimal-str-re` — precision drift (e.g.
   \"123.456\") and non-strings (floats on the wire) raise rather than being
   silently rounded/truncated to 0.00 (DEC-4 / FR-022). RUB is the only
   currency; a non-RUB amount is rejected by the caller's currency check, this
   fn owns only the numeric form."
  [s]
  (when-not (decimal-str? s)
    (throw (ex-info (str "Invalid ledger decimal-string: " (pr-str s)
                         " (expected \"0.00\"-form matching " decimal-str-re ")")
                    {:value s :expected (str decimal-str-re)})))
  (.setScale (BigDecimal. ^String s) decimal-scale RoundingMode/HALF_UP))

(defn d->str
  "Render a BigDecimal ledger amount → \"0.00\"-string with exactly 2 digits.
   The single path to wire/TEXT. Normalises scale so \"1.0\" and \"1.00\"
   render identically."
  [^BigDecimal x]
  (.toPlainString (.setScale x decimal-scale RoundingMode/HALF_UP)))

(defn d+
  "Exact BigDecimal addition (no intermediate float)."
  [& xs]
  (reduce (fn [^BigDecimal a ^BigDecimal b] (.add a b))
          (BigDecimal/ZERO)
          xs))

(defn d-
  "Exact BigDecimal subtraction: (d- a b c …) = a − b − c − … . With a single
   arg returns it unchanged (no unary negation implied)."
  [^BigDecimal a & xs]
  (reduce (fn [^BigDecimal acc ^BigDecimal b] (.subtract acc b)) a xs))

(defn dsum
  "Σ of a collection of BigDecimal — the basis for a ДДС column total
   (FR-004). (dsum []) == (d \"0.00\")."
  [coll]
  (reduce (fn [^BigDecimal a ^BigDecimal b] (.add a b))
          (BigDecimal/ZERO)
          coll))

(defn d-zero?
  "True iff `x` == 0.00 by value (e.g. a settled obligation, remaining=0)."
  [^BigDecimal x]
  (zero? (.compareTo x BigDecimal/ZERO)))

(defn dneg?
  "True iff `x` < 0.00 by value."
  [^BigDecimal x]
  (neg? (.compareTo x BigDecimal/ZERO)))

(defn dcmp
  "Compare two BigDecimal by VALUE via .compareTo (NOT .equals — \"1.0\" and
   \"1.00\" compare equal). Returns -1 / 0 / 1."
  [^BigDecimal a ^BigDecimal b]
  (.compareTo a b))

(defn d-prorate
  "Pro-rate `amount` (BigDecimal) by a day-overlap ratio: amount × overlap-days
   ÷ total-days, rounded HALF_UP to 2dp. Decimal analogue of the double
   day-overlap in db.clj:1027 (period/pro-rate-rows). To keep Σ slices == amount
   to the kopeck, callers let the LAST bucket absorb the rounding remainder
   (last-slice = amount − Σ earlier slices); see DEC-3. Returns 0.00 when
   total-days is 0."
  [^BigDecimal amount overlap-days total-days]
  (if (zero? total-days)
    (d "0.00")
    (.divide (.multiply amount (BigDecimal/valueOf (long overlap-days)))
             (BigDecimal/valueOf (long total-days))
             decimal-scale
             RoundingMode/HALF_UP)))
