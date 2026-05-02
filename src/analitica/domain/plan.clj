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
   Returns 1.0 when degenerate (no momentum / no time left)."
  [{:keys [actual-mtd forecast target days-remaining]}]
  (let [delta-needed   (- target actual-mtd)
        delta-forecast (- forecast actual-mtd)]
    (cond
      (zero? days-remaining)        1.0
      (<= delta-forecast 0)         1.0
      :else                         (double (/ delta-needed delta-forecast)))))
