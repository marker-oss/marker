(ns analitica.util.period
  "Pure period helpers for the new UI period picker.

   - `resolve-preset` — 5 named presets (last-7-days / last-30-days / this-month /
                        prev-month / custom) → [from-date to-date] (LocalDate vec).
   - `compare-period` — same-length prior period ending the day before :from.
   - `parse-url-state` — extract {:from :to :preset :compare :marketplace} from query params.
   - `default-state` — default {:preset :last-30-days :compare :none :marketplace \"all\"}.
   - `days-between`, `parse-date`, `format-date` — small utilities.

   Note: This ns is distinct from `analitica.util.time` — that one handles legacy
   period-string parsing for existing HTTP query params (e.g. `period=last-week`).
   This ns is the forward-looking source of truth for the new picker UX."
  (:import (java.time LocalDate)
           (java.time.temporal ChronoUnit)))

(defn today [] (LocalDate/now))

(defn parse-date
  "Accepts java.time.LocalDate or ISO string (YYYY-MM-DD)."
  [s]
  (if (instance? LocalDate s) s (LocalDate/parse s)))

(defn format-date
  "LocalDate → ISO string."
  [d]
  (str d))

(def presets
  "5 named presets (minimal set per Q-presets design decision).
   :last-7-days / :last-30-days / :this-month / :prev-month / :custom"
  [:last-7-days :last-30-days :this-month :prev-month :custom])

(defn resolve-preset
  "Return [from-date to-date] as LocalDate for a preset.
   today-date arg is for testability; omit in prod for current date.
   :custom returns nil — custom range requires explicit dates."
  ([preset] (resolve-preset preset (today)))
  ([preset today-date]
   (let [td (parse-date today-date)]
     (case preset
       :last-7-days  [(.minusDays td 6) td]
       :last-30-days [(.minusDays td 29) td]
       :this-month   [(.withDayOfMonth td 1) td]
       :prev-month   (let [last-month (.minusMonths td 1)
                           first-of-prev (.withDayOfMonth last-month 1)]
                       [first-of-prev
                        (.withDayOfMonth last-month (.lengthOfMonth last-month))])
       :custom       nil
       nil))))

(defn days-between
  "Inclusive day count between two dates (from and to counted).
   E.g. (days-between \"2026-04-01\" \"2026-04-30\") → 30."
  [from to]
  (inc (.until (parse-date from) (parse-date to) ChronoUnit/DAYS)))

(defn intersect-days
  "Inclusive-day overlap between two date ranges. Returns 0 when ranges
   do not overlap. Inputs are ISO strings or LocalDate."
  [a-from a-to b-from b-to]
  (let [a-f (parse-date a-from)
        a-t (parse-date a-to)
        b-f (parse-date b-from)
        b-t (parse-date b-to)
        start (if (.isAfter b-f a-f) b-f a-f)
        end   (if (.isBefore b-t a-t) b-t a-t)]
    (if (.isAfter start end)
      0
      (inc (.until start end ChronoUnit/DAYS)))))

(defn pro-rate-rows
  "Pro-rate a seq of period-bucketed rows against a target window
   [from..to]. Each row must expose its own period via `period-begin-key`
   and `period-end-key`; numeric fields listed in `numeric-keys` are
   scaled by the day-overlap ratio (overlap-days / row-period-days).

   Rows whose period does not overlap [from..to] are dropped.

   Returns a seq of maps with the same keys as input plus an
   additional `:overlap-days` field. Non-numeric / missing values
   pass through unchanged."
  [rows {:keys [from to period-begin-key period-end-key numeric-keys]
         :or   {period-begin-key :period-begin
                period-end-key   :period-end}}]
  (->> rows
       (keep (fn [row]
               (let [pb (get row period-begin-key)
                     pe (get row period-end-key)
                     row-days   (when (and pb pe) (days-between pb pe))
                     overlap    (when (and pb pe) (intersect-days from to pb pe))]
                 (when (and overlap (pos? overlap) (pos? row-days))
                   (let [factor (/ (double overlap) (double row-days))]
                     (-> (reduce (fn [acc k]
                                   (let [v (get acc k)]
                                     (if (number? v)
                                       (assoc acc k (* (double v) factor))
                                       acc)))
                                 row
                                 numeric-keys)
                         (assoc :overlap-days overlap)))))))))

(defn compare-period
  "Same-length prior period ending the day before :from.
   Input {:from :to} where both are ISO strings. Returns [from-str to-str] as ISO strings."
  [{:keys [from to]}]
  (let [from-d (parse-date from)
        to-d (parse-date to)
        length (days-between from-d to-d)
        prev-to (.minusDays from-d 1)
        prev-from (.minusDays prev-to (dec length))]
    [(format-date prev-from) (format-date prev-to)]))

(defn- lookup
  "Get value from params map with string or keyword key — Ring's middleware may deliver either."
  [params k]
  (or (get params (name k))
      (get params (keyword k))))

(defn parse-url-state
  "Extract period + compare from HTTP query params. Accepts either string-keyed or keyword-keyed params maps.
   Returns {:from :to :preset :compare :marketplace}."
  [query-params]
  {:from       (lookup query-params :from)
   :to         (lookup query-params :to)
   :preset     (some-> (lookup query-params :preset) keyword)
   :compare    (case (or (lookup query-params :compare) "none")
                 "prev" :prev
                 "none" :none
                 :none)
   :marketplace (or (lookup query-params :marketplace) "all")})

(defn default-state
  "Default picker state: last-30-days, no compare, all marketplaces."
  ([] (default-state (today)))
  ([today-date]
   (let [[from to] (resolve-preset :last-30-days today-date)]
     {:from (format-date from)
      :to   (format-date to)
      :preset :last-30-days
      :compare :none
      :marketplace "all"})))
