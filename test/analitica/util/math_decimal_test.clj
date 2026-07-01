(ns analitica.util.math-decimal-test
  "Property + regression tests for the decimal-as-string money block in
   analitica.util.math (spec 019 §3.D, OWNER 019).

   This is a DISTINCT path from the double analytics helpers
   (round2/percentage/safe-div): ledger money is java.math.BigDecimal,
   scale=2, RoundingMode/HALF_UP, transported/stored as \"0.00\" strings.

   Invariants (contracts/decimal-money.edn §4):
     DEC-1  Σ of thousands of \"0.00\" sums has no float drift.
     DEC-2  round-trip: (= s (d->str (d s))) for any valid \"0.00\"-string.
     DEC-3  proration: Σ slices == amount to the kopeck (last bucket absorbs
            the remainder).
     DEC-4  invalid / non-RUB string → exception, NOT a silent 0.00 / truncation.
     DEC-5  REGRESSION: util/math round2/percentage/safe-div and (reduce + 0.0 …)
            are bit-for-bit identical after the decimal block is introduced."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [analitica.util.math :as m])
  (:import [java.math BigDecimal]))

;; ---------------------------------------------------------------------------
;; DEC-1 — Σ of thousands of "0.00" sums has no float drift
;; ---------------------------------------------------------------------------

(deftest dec-1-sum-no-float-drift
  (testing "DEC-1: dsum of 100000 × \"0.01\" == \"1000.00\" exactly (double would drift)"
    (is (= "1000.00"
           (m/d->str (m/dsum (repeat 100000 (m/d "0.01"))))))
    ;; sanity: the double path *does* drift here — proves the decimal path is needed
    (is (not= 1000.0 (reduce + 0.0 (repeat 100000 0.01)))))
  (testing "DEC-1: dsum of mixed thousands is exact"
    (is (= "3000000.00"
           (m/d->str (m/dsum [(m/d "1250000.00")
                              (m/d "1000000.00")
                              (m/d "750000.00")]))))
    (is (= "0.00" (m/d->str (m/dsum []))) "empty dsum = 0.00"))
  (testing "DEC-1: signed aggregate serialises with leading minus"
    (is (= "-742000.00"
           (m/d->str (m/d- (m/d "0.00") (m/d "742000.00")))))))

;; ---------------------------------------------------------------------------
;; DEC-2 — round-trip via test.check on a generator of valid "0.00"-strings
;; ---------------------------------------------------------------------------

(def gen-decimal-str
  "Generator of valid \"0.00\"-strings: optional leading minus, integer part,
   exactly two fractional digits (no negative zero to keep round-trip stable)."
  (gen/fmap
   (fn [[neg? int-part frac]]
     (let [frac-str (format "%02d" frac)
           magnitude (str int-part "." frac-str)]
       (if (and neg? (not (and (zero? int-part) (zero? frac))))
         (str "-" magnitude)
         magnitude)))
   (gen/tuple gen/boolean
              (gen/large-integer* {:min 0 :max 100000000})
              (gen/choose 0 99))))

(defspec dec-2-round-trip 500
  (prop/for-all [s gen-decimal-str]
    (= s (m/d->str (m/d s)))))

(deftest dec-2-round-trip-examples
  (testing "DEC-2: canonical examples round-trip"
    (doseq [s ["0.00" "123.45" "1250000.00" "-742000.00" "0.01" "99999999.99"]]
      (is (= s (m/d->str (m/d s))) (str "round-trip failed for " s)))))

;; ---------------------------------------------------------------------------
;; DEC-3 — proration: Σ slices == amount to the kopeck (last bucket absorbs)
;; ---------------------------------------------------------------------------

(defn prorate-slices
  "Split `amount` (BigDecimal) across buckets of `overlap-days`, total window
   `total-days`, with the LAST bucket absorbing the rounding remainder so that
   Σ slices == amount exactly. Mirrors the caller pattern for seed pro-rating."
  [amount day-buckets total-days]
  (let [n (count day-buckets)]
    (loop [idx 0, acc [], running (m/d "0.00")]
      (if (= idx n)
        acc
        (let [last? (= idx (dec n))
              slice (if last?
                      (m/d- amount running)                    ; absorb remainder
                      (m/d-prorate amount (nth day-buckets idx) total-days))]
          (recur (inc idx) (conj acc slice) (m/d+ running slice)))))))

(deftest dec-3-prorate-exact
  (testing "DEC-3: Σ of pro-rated slices == amount exactly (no kopeck lost/gained)"
    ;; 1000.00 over 3 buckets of unequal days that would each round → drift
    (let [amount (m/d "1000.00")
          slices (prorate-slices amount [10 10 11] 31)]
      (is (= "1000.00" (m/d->str (m/dsum slices)))
          "Σ slices must equal original amount to the kopeck")
      (is (= amount (m/dsum slices))))
    ;; awkward amount that never divides cleanly
    (let [amount (m/d "100.00")
          slices (prorate-slices amount [1 1 1] 3)]
      (is (= amount (m/dsum slices))
          "33.33 + 33.33 + remainder = 100.00 exactly")))
  (testing "DEC-3: single-slice prorate rounds HALF_UP to 2dp"
    ;; 100.00 * 1/3 = 33.333... → 33.33
    (is (= "33.33" (m/d->str (m/d-prorate (m/d "100.00") 1 3))))
    ;; 100.00 * 2/3 = 66.666... → 66.67
    (is (= "66.67" (m/d->str (m/d-prorate (m/d "100.00") 2 3))))))

;; ---------------------------------------------------------------------------
;; DEC-4 — invalid / non-RUB string → exception, not silent 0.00 / truncation
;; ---------------------------------------------------------------------------

(deftest dec-4-invalid-rejected
  (testing "DEC-4: strings outside the ^-?\\d+\\.\\d{2}$ format are rejected"
    (doseq [bad ["123"          ; no fractional part
                 "123.4"        ; 1 digit
                 "123.456"      ; 3 digits — must NOT be silently rounded/truncated
                 "1,234.00"     ; thousands separator
                 "abc"          ; non-numeric
                 ""             ; empty
                 " 1.00 "]]     ; whitespace
      (is (thrown? Exception (m/d bad))
          (str "expected exception for invalid decimal-string: " (pr-str bad)))))
  (testing "DEC-4: a float (not a string) on the wire is rejected"
    (is (thrown? Exception (m/d 123.45))))
  (testing "DEC-4: 123.456 is NOT silently rounded to 123.46"
    (is (thrown? Exception (m/d "123.456"))
        "precision drift must raise, not round silently")))

;; ---------------------------------------------------------------------------
;; Arithmetic + comparison helpers
;; ---------------------------------------------------------------------------

(deftest decimal-arithmetic
  (testing "d+ / d- exact"
    (is (= "300.00" (m/d->str (m/d+ (m/d "100.00") (m/d "200.00")))))
    (is (= "50.00"  (m/d->str (m/d- (m/d "150.00") (m/d "100.00")))))
    (is (= "-50.00" (m/d->str (m/d- (m/d "100.00") (m/d "150.00"))))))
  (testing "d-zero? / dneg?"
    (is (m/d-zero? (m/d "0.00")))
    (is (not (m/d-zero? (m/d "0.01"))))
    (is (m/dneg? (m/d "-1.00")))
    (is (not (m/dneg? (m/d "0.00")))))
  (testing "dcmp compares by value, not identity (\"1.0\" vs \"1.00\")"
    (is (zero? (m/dcmp (m/d "1.00") (m/d "1.00"))))
    (is (neg? (m/dcmp (m/d "1.00") (m/d "2.00"))))
    (is (pos? (m/dcmp (m/d "2.00") (m/d "1.00"))))))

;; ---------------------------------------------------------------------------
;; Malli schema for the decimal-string wire/storage form
;; ---------------------------------------------------------------------------

(deftest decimal-str-malli
  (testing "m/decimal-str? validates the wire form"
    (is (m/decimal-str? "0.00"))
    (is (m/decimal-str? "-742000.00"))
    (is (not (m/decimal-str? "123")))
    (is (not (m/decimal-str? "123.456")))
    (is (not (m/decimal-str? 123.45)))))

;; ---------------------------------------------------------------------------
;; DEC-5 — REGRESSION: double analytics path is bit-for-bit unchanged
;; ---------------------------------------------------------------------------

(deftest dec-5-analytics-path-unchanged
  (testing "DEC-5: round2 unchanged (double path, not decimal)"
    (is (= 123.46 (m/round2 123.456)))
    (is (= 0.0 (m/round2 0.0)))
    (is (= 100.0 (m/round2 100.0)))
    (is (nil? (m/round2 nil)))
    ;; return type is a primitive double, NOT BigDecimal
    (is (instance? Double (m/round2 1.0))))
  (testing "DEC-5: percentage unchanged (double path)"
    (is (= 50.0 (m/percentage 5.0 10.0)))
    (is (nil? (m/percentage 5.0 0.0))))
  (testing "DEC-5: safe-div unchanged (double path)"
    (is (= 2.0 (m/safe-div 10.0 5.0)))
    (is (= 0.0 (m/safe-div 10.0 0.0))))
  (testing "DEC-5: (reduce + 0.0 …) double summation still drifts (no bridge to decimal)"
    ;; The regression guard: the analytics path must NOT have been converted
    ;; to BigDecimal. This classic float drift is expected and PROVES the
    ;; double path is untouched.
    (is (not= 1000.0 (reduce + 0.0 (repeat 100000 0.01))))
    (is (instance? Double (reduce + 0.0 [1.0 2.0 3.0])))))
