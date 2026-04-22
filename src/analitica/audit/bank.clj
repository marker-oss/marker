(ns analitica.audit.bank
  "Parse bank reference data for reconciliation / KPI baseline.

   Two shapes supported:
     - Scalar sum (CLI --bank-sum 12345.67) → {:sum N :by-date nil :missing-dates []}
     - CSV file  (CLI --bank-csv path)      → {:sum N :by-date {date→amount}
                                               :missing-dates [YYYY-MM-DD ...]}

   CSV format:
     - First non-empty line is a header
     - Delimiter auto-detected: `,` vs `;` (by counting occurrences on header line)
     - UTF-8 BOM (U+FEFF) stripped from the first character
     - Two columns: date (ISO YYYY-MM-DD) and amount (decimal, dot as separator)

   Missing-dates are computed relative to the requested period: every ISO date
   in [from..to] that has no entry in :by-date is listed. This supports FR-011
   (refuse to record KPI baseline when reference data is incomplete)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------------------------
;; parse-bank-sum — scalar helper for --bank-sum CLI flag
;; ---------------------------------------------------------------------------

(defn parse-bank-sum
  "Parse a scalar bank sum string into a double. Throws ex-info on garbage."
  [s]
  (when (nil? s)
    (throw (ex-info "parse-bank-sum: nil input" {:input s})))
  (try
    (Double/parseDouble (str s))
    (catch NumberFormatException e
      (throw (ex-info (str "parse-bank-sum: not a number: " (pr-str s))
                      {:input s}
                      e)))))

;; ---------------------------------------------------------------------------
;; CSV parsing helpers
;; ---------------------------------------------------------------------------

(def ^:private utf8-bom-char (char 0xFEFF))

(defn- strip-bom
  "Remove the UTF-8 BOM (U+FEFF) from the start of a string, if present."
  [^String s]
  (if (and s (pos? (.length s)) (= utf8-bom-char (.charAt s 0)))
    (subs s 1)
    s))

(defn- detect-delimiter
  "Pick `,` or `;` based on whichever occurs more often on the header line."
  [header]
  (let [commas     (count (filter #(= \, %) header))
        semicolons (count (filter #(= \; %) header))]
    (if (>= semicolons commas) \; \,)))

(defn- parse-line
  "Split one CSV line by the detected delimiter. Empty lines → nil."
  [^String line ^Character delim]
  (when (and line (seq (str/trim line)))
    (mapv str/trim (str/split line (re-pattern (java.util.regex.Pattern/quote (str delim)))))))

;; ---------------------------------------------------------------------------
;; Period expansion — all ISO dates in [from..to] inclusive
;; ---------------------------------------------------------------------------

(def ^:private iso (DateTimeFormatter/ISO_LOCAL_DATE))

(defn- iso-parse [^String s] (LocalDate/parse s iso))
(defn- iso-format [^LocalDate d] (.format d iso))

(defn- dates-in-period
  "Expand {:from from-iso :to to-iso} into a vector of every ISO date string
   in that closed range. Returns [] if from is after to."
  [{:keys [from to]}]
  (if (or (nil? from) (nil? to))
    []
    (let [start (iso-parse from)
          end   (iso-parse to)]
      (loop [cur start
             acc (transient [])]
        (if (.isAfter cur end)
          (persistent! acc)
          (recur (.plusDays cur 1) (conj! acc (iso-format cur))))))))

;; ---------------------------------------------------------------------------
;; parse-bank-csv — main entry point
;; ---------------------------------------------------------------------------

(defn parse-bank-csv
  "Read a bank reference CSV at `path` and return
     {:sum N
      :by-date {\"2026-03-01\" 1000.0 ...}
      :missing-dates [\"2026-03-02\" ...]}

   Delimiter (`,` or `;`) is auto-detected from the header row. UTF-8 BOM is
   stripped. Decimal separator is `.` (not `,`). `period` is an arbitrary map
   like {:from \"YYYY-MM-DD\" :to \"YYYY-MM-DD\"} used to compute missing-dates.

   Throws ex-info on unreadable files or rows."
  [path period]
  (when-not (.exists (io/file path))
    (throw (ex-info (str "bank CSV not found: " path) {:path path})))
  (with-open [r (io/reader path :encoding "UTF-8")]
    (let [lines (doall (line-seq r))
          lines (remove str/blank? lines)]
      (when (empty? lines)
        (throw (ex-info (str "bank CSV is empty: " path) {:path path})))
      (let [header-raw (strip-bom (first lines))
            delim      (detect-delimiter header-raw)
            rows       (rest lines)
            by-date    (reduce
                         (fn [acc line]
                           (let [[d a] (parse-line line delim)]
                             (if (and d a)
                               (try
                                 (assoc acc d (Double/parseDouble a))
                                 (catch NumberFormatException _
                                   (throw (ex-info (str "parse-bank-csv: bad amount on line " (pr-str line))
                                                   {:line line :amount a}))))
                               acc)))
                         {}
                         rows)
            expected (set (dates-in-period period))
            missing  (->> expected
                          (remove (set (keys by-date)))
                          sort
                          vec)
            total    (reduce + 0.0 (vals by-date))]
        {:sum           total
         :by-date       by-date
         :missing-dates missing}))))
