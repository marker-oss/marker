(ns analitica.util.time
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
