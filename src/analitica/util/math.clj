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
