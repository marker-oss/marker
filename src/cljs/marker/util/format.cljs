(ns marker.util.format
  "Russian-locale formatters — byte-identical behavior to format.js.
   NBSP = \\u00A0, minus = \\u2212 (−), decimal separator = comma."
  (:require [clojure.string :as str]))

(def ^:private NBSP " ")
(def ^:private MINUS "−")

(defn- add-thousands
  "Insert NBSP every 3 digits from the right, e.g. \"1234567\" → \"1\\u00A0234\\u00A0567\"."
  [s]
  (let [len (count s)]
    (loop [pos len parts []]
      (if (<= pos 0)
        (str/join NBSP parts)
        (let [start (max 0 (- pos 3))]
          (recur start (cons (subs s start pos) parts)))))))

(defn format-rub
  "Format number as Russian rubles: «1\\u00A0234\\u00A0567\\u00A0₽».
   Optional with-sign? adds + prefix for positive numbers.
   Negative sign is Unicode minus (\\u2212), not hyphen-minus."
  ([n] (format-rub n false))
  ([n with-sign?]
   (if (or (nil? n) (js/isNaN n))
     "—"
     (let [sign (cond
                  (neg? n)              MINUS
                  (and with-sign?
                       (pos? n))        "+"
                  :else                 "")
           abs-val (js/Math.abs (js/Math.round n))
           s       (add-thousands (.toString abs-val))]
       (str sign s NBSP "₽")))))

(defn format-int
  "Format integer with NBSP thousands separator. No sign, no unit.
   Negative numbers are prefixed with hyphen-minus (-)."
  [n]
  (if (or (nil? n) (js/isNaN n))
    "—"
    (let [rounded (js/Math.round n)
          sign    (if (neg? rounded) "-" "")
          abs-str (.toString (js/Math.abs rounded))]
      (str sign (add-thousands abs-str)))))

(defn format-short
  "Compact number: 1,2M / 3,4K / 567."
  [n]
  (if (or (nil? n) (js/isNaN n))
    "—"
    (let [abs-val (js/Math.abs n)]
      (cond
        (>= abs-val 1000000) (str (-> (/ n 1000000) (.toFixed 1) (.replace "." ",")) "M")
        (>= abs-val 1000)    (str (-> (/ n 1000)    (.toFixed 1) (.replace "." ",")) "K")
        :else                (.toString (js/Math.round n))))))

(defn format-pct
  "Format as percentage: «12,3%» (comma decimal separator).
   digits defaults to 1."
  ([n] (format-pct n 1))
  ([n digits]
   (if (or (nil? n) (js/isNaN n))
     "—"
     (str (-> (.toFixed n digits) (.replace "." ",")) "%"))))

(defn format-mul
  "Format as multiplier: «1,5×»."
  [n]
  (if (or (nil? n) (js/isNaN n))
    "—"
    (str (-> (.toFixed n 1) (.replace "." ",")) "×")))

(defn format-date
  "Format js/Date as DD.MM.YYYY."
  [d]
  (let [dd   (-> (.getDate d)        str (.padStart 2 "0"))
        mm   (-> (inc (.getMonth d)) str (.padStart 2 "0"))
        yyyy (.getFullYear d)]
    (str dd "." mm "." yyyy)))

(defn plural-ru
  "Russian plural form selector.
   n=1    → one   (\"товар\")
   n=2-4  → few   (\"товара\")
   n=5+   → many  (\"товаров\")
   Exceptions: 11-14 always → many.
   Example: (plural-ru 21 \"товар\" \"товара\" \"товаров\") => \"товар\""
  [n one few many]
  (let [a  (mod (js/Math.abs n) 100)
        a1 (mod a 10)]
    (cond
      (and (>= a 11) (<= a 14)) many
      (= a1 1)                  one
      (and (>= a1 2) (<= a1 4)) few
      :else                     many)))

(defn format-days
  "Format a day count with a Russian «Дн.» suffix: «12\\u00A0Дн.».
   nil / NaN → «—». Non-abbreviated plural is not used here — the
   dashboard descriptor unit is the fixed «Дн.» abbreviation."
  [n]
  (if (or (nil? n) (js/isNaN n))
    "—"
    (str (format-int n) NBSP "Дн.")))

(defn format-suffixed
  "Render a number according to a descriptor :suffix keyword.
     :rub          → format-rub   («1\\u00A0234\\u00A0₽»)
     :pct          → format-pct   («12,3%»)
     :qty          → format-int + «\\u00A0шт»
     :days         → format-days  («12\\u00A0Дн.»)
     :mul          → format-mul   («1,5×»)
     nil / :none   → format-int   (plain grouped integer)
   nil value ALWAYS → «—» regardless of suffix."
  [value suffix]
  (if (nil? value)
    "—"
    (case suffix
      :rub          (format-rub value)
      :pct          (format-pct value)
      :qty          (str (format-int value) NBSP "шт")
      :days         (format-days value)
      :mul          (format-mul value)
      (:none nil)   (format-int value)
      (format-int value))))

;; ---------------------------------------------------------------------------
;; Exact decimal-string formatting.
;; Treasury / ledger values arrive as DECIMAL-as-string (e.g. \"-742000.00\").
;; js/parseFloat would lose precision on large magnitudes, so we group the
;; integer part on the STRING itself and never coerce to a JS number.
;; ---------------------------------------------------------------------------

(defn- blank-str? [s]
  (or (nil? s) (= "" (str s))))

(defn- round-int-part
  "HALF_UP to whole rubles on the STRING (BigInt-exact, no parseFloat):
   bump the integer part when the first fractional digit is ≥ 5."
  [int-part frac-part]
  (if (and (seq frac-part)
           (>= (js/parseInt (subs frac-part 0 1) 10) 5))
    (str (+ (js/BigInt int-part) (js/BigInt 1)))
    int-part))

(defn format-decimal-str
  "Format a DECIMAL-as-string (e.g. \"-742000.49\") to a grouped RU-locale
   whole-ruble string WITHOUT js/parseFloat — precision-exact for arbitrarily
   large magnitudes. Rounds HALF_UP on the magnitude; a zero result never
   carries a sign (no «−0»). Groups the integer part with NBSP; negative
   sign → Unicode minus. nil / \"\" → «—»."
  [s]
  (if (blank-str? s)
    "—"
    (let [t        (str/trim (str s))
          neg?     (str/starts-with? t "-")
          unsigned (if (or neg? (str/starts-with? t "+")) (subs t 1) t)
          [int-part frac-part] (str/split unsigned #"\." 2)
          int-part (if (or (nil? int-part) (= "" int-part)) "0" int-part)
          rounded  (round-int-part int-part frac-part)
          zero?    (some? (re-matches #"0+" rounded))
          sign     (if (and neg? (not zero?)) MINUS "")]
      (str sign (add-thousands rounded)))))

(defn format-decimal-rub
  "Like format-decimal-str but appends the «\\u00A0₽» ruble unit.
   Precision-exact (no js/parseFloat). nil / \"\" → «—»."
  [s]
  (if (blank-str? s)
    "—"
    (str (format-decimal-str s) NBSP "₽")))
