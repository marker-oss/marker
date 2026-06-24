(ns analitica.util.time
  (:require [analitica.util.period :as period])
  (:import [java.time LocalDate LocalDateTime ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.time.temporal ChronoUnit]))

(def ^:private fmt-date (DateTimeFormatter/ofPattern "yyyy-MM-dd"))
(def ^:private fmt-datetime (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))

(defn today [] (LocalDate/now))

(defn parse-date [s]
  (LocalDate/parse s fmt-date))

(defn format-date [^LocalDate d]
  (.format d fmt-date))

(defn format-datetime [^LocalDateTime dt]
  (.format dt fmt-datetime))

(defn days-ago [n]
  (.minusDays (today) n))

(defn date-range
  "Returns a seq of LocalDate from start to end (inclusive)."
  [^LocalDate start ^LocalDate end]
  (->> (iterate #(.plusDays % 1) start)
       (take-while #(not (.isAfter % end)))))

;; ---------------------------------------------------------------------------
;; Period helpers — return [date-from date-to] as strings
;; ---------------------------------------------------------------------------

(defn period
  "Returns [from to] date strings for common periods.
   Supported: :today :yesterday :last-7-days :last-30-days :this-week :this-month"
  [kw]
  (let [t (today)]
    (case kw
      :today      [(format-date t) (format-date t)]
      :yesterday  (let [y (.minusDays t 1)] [(format-date y) (format-date y)])
      :last-7-days  [(format-date (days-ago 7)) (format-date t)]
      :last-30-days [(format-date (days-ago 30)) (format-date t)]
      :this-week    (let [dow (.getValue (.getDayOfWeek t))
                          mon (.minusDays t (dec dow))]
                      [(format-date mon) (format-date t)])
      :this-month   (let [first-day (.withDayOfMonth t 1)]
                      [(format-date first-day) (format-date t)])
      (throw (ex-info (str "Unknown period: " kw) {:period kw})))))

(defn days-between [^LocalDate a ^LocalDate b]
  (.between ChronoUnit/DAYS a b))

(defn minus-days
  "Shift a YYYY-MM-DD date string backward by N days.
   Used by ingest-ozon-postings! to widen the in_process_at filter so
   postings created before the requested window but delivered within it
   still land in raw_data."
  [date-str n]
  (-> (parse-date date-str) (.minusDays (long n)) format-date))

(defn date-chunks
  "Split [from to] into chunks of at most max-days each.
   Returns a vec of [chunk-from chunk-to] date-string pairs."
  [from-str to-str max-days]
  (let [start (parse-date from-str)
        end   (parse-date to-str)]
    (loop [cur start
           chunks []]
      (if (.isAfter cur end)
        chunks
        (let [chunk-end (let [ce (.plusDays cur (dec max-days))]
                          (if (.isAfter ce end) end ce))]
          (recur (.plusDays chunk-end 1)
                 (conj chunks [(format-date cur) (format-date chunk-end)])))))))

;; ---------------------------------------------------------------------------
;; Period parsing for web UI
;; ---------------------------------------------------------------------------

(defn parse-period
  "Parse period string from HTTP query parameter.
   Supported formats:
   - \"last-week\" → previous complete week (Monday to Sunday)
   - \"last-7-days\" → last 7 days including today
   - \"last-30-days\" → last 30 days including today
   - \"this-month\" → current month from 1st to today
   - \"YYYY-MM-DD,YYYY-MM-DD\" → custom date range
   Returns [from to] as string vector, or throws ex-info on invalid input."
  [s]
  (cond
    (nil? s)
    (throw (ex-info "Period cannot be nil" {:period s}))

    (= s "last-week")
    (let [[f t] (period/resolve-preset :last-week)]
      [(period/format-date f) (period/format-date t)])

    (= s "last-7-days")
    (period :last-7-days)

    (= s "last-30-days")
    (period :last-30-days)

    (= s "this-month")
    (period :this-month)

    (re-matches #"\d{4}-\d{2}-\d{2},\d{4}-\d{2}-\d{2}" s)
    (let [[from to] (clojure.string/split s #",")]
      (try
        (parse-date from)
        (parse-date to)
        [from to]
        (catch Exception e
          (throw (ex-info (str "Invalid date format in period: " s)
                          {:period s :error (.getMessage e)})))))

    :else
    (throw (ex-info (str "Unknown period format: " s)
                    {:period s
                     :supported ["last-week" "last-7-days" "last-30-days"
                                 "this-month" "YYYY-MM-DD,YYYY-MM-DD"]}))))

(defn resolve-period
  "Normalize a period argument to [from to] date-string pair.

   Accepts: keyword (looked up via `period` or `period/resolve-preset`),
   2-vector [from to], or map {:from :to}."
  [p]
  (cond
    (keyword? p) (let [local-presets #{:today :yesterday :last-7-days :last-30-days
                                       :this-week :this-month}]
                   (if (contains? local-presets p)
                     (period p)
                     (if-let [[f t] (period/resolve-preset p)]
                       [(period/format-date f) (period/format-date t)]
                       (throw (ex-info (str "Unknown period keyword: " p) {:period p})))))
    (vector? p)  p
    (map? p)     [(:from p) (:to p)]
    :else (throw (ex-info "Unrecognized period" {:period p}))))
