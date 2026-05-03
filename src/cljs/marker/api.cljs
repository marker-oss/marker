(ns marker.api
  "Transit-JSON API helpers for the Marker SPA.
   Provides get/post wrappers that set Accept/Content-Type headers,
   encode request bodies with Transit, and decode responses.

   Uses day8.re-frame/http-fx (:http-xhrio effect) — require this ns
   to ensure the effect handler is registered before any dispatch."
  (:require [cognitect.transit :as t]
            [day8.re-frame.http-fx] ; side-effect: registers :http-xhrio
            [ajax.core :refer [transit-response-format transit-request-format]]))

;; ---------------------------------------------------------------------------
;; Transit encode / decode (exported for tests)
;; ---------------------------------------------------------------------------

(defn encode-transit
  "Encode `x` to a Transit-JSON string."
  [x]
  (let [w (t/writer :json)]
    (t/write w x)))

(defn decode-transit
  "Decode a Transit-JSON string to a ClojureScript value."
  [s]
  (let [r (t/reader :json)]
    (t/read r s)))

;; ---------------------------------------------------------------------------
;; URL helpers
;; ---------------------------------------------------------------------------

(defn- mp-param
  "Encode mp-filter keyword vector to a comma-joined string, or nil if all 3."
  [mp-filter]
  (when (and (seq mp-filter) (< (count mp-filter) 3))
    (clojure.string/join "," (map name mp-filter))))

;; ---------------------------------------------------------------------------
;; Period resolver
;; ---------------------------------------------------------------------------

(defn- fmt-date
  "Format a js/Date as \"YYYY-MM-DD\"."
  [^js d]
  (let [y  (.getFullYear d)
        m  (inc (.getMonth d))   ; getMonth is 0-indexed
        dy (.getDate d)]
    (str y "-"
         (if (< m 10) (str "0" m) m) "-"
         (if (< dy 10) (str "0" dy) dy))))

(defn- days-ago
  "Return a js/Date `n` calendar days before `now`. DST-safe — uses the Date
   constructor's day-arithmetic, which works in calendar days, not milliseconds."
  [^js/Date now n]
  (js/Date. (.getFullYear now)
            (.getMonth now)
            (- (.getDate now) n)))

(defn- period->params
  "Map a Russian period preset label (or YYYY-MM-DD,YYYY-MM-DD custom range)
   to {:from \"YYYY-MM-DD\" :to \"YYYY-MM-DD\"} query params.
   Accepts an optional `now` js/Date for deterministic testing."
  ([period] (period->params period (js/Date.)))
  ([period ^js now]
   (cond
     (nil? period) {}

     (= period "Сегодня")
     (let [t (fmt-date now)]
       {:from t :to t})

     (= period "Вчера")
     (let [t (fmt-date (days-ago now 1))]
       {:from t :to t})

     (= period "Последние 7 дней")
     {:from (fmt-date (days-ago now 7)) :to (fmt-date now)}

     (= period "Последние 30 дней")
     {:from (fmt-date (days-ago now 30)) :to (fmt-date now)}

     (= period "Этот месяц")
     {:from (fmt-date (js/Date. (.getFullYear now) (.getMonth now) 1))
      :to   (fmt-date now)}

     (= period "Прошлый месяц")
     (let [y  (.getFullYear now)
           m  (.getMonth now)    ; 0-indexed; JS Date handles m=0 → Dec of y-1
           ;; day 1 of previous month (JS handles negative-month rollback)
           first-prev (js/Date. y (- m 1) 1)
           ;; day 0 of current month = last day of previous month
           last-prev  (js/Date. y m 0)]
       {:from (fmt-date first-prev) :to (fmt-date last-prev)})

     (= period "Этот квартал")
     (let [m            (.getMonth now)  ; 0-indexed
           q-start-month (- m (mod m 3)) ; 0-indexed month of quarter start
           q-start       (js/Date. (.getFullYear now) q-start-month 1)]
       {:from (fmt-date q-start) :to (fmt-date now)})

     (= period "Этот год")
     {:from (fmt-date (js/Date. (.getFullYear now) 0 1))
      :to   (fmt-date now)}

     ;; Custom range: "YYYY-MM-DD,YYYY-MM-DD" — checked last (regex is expensive)
     (re-matches #"^\d{4}-\d{2}-\d{2},\d{4}-\d{2}-\d{2}$" period)
     (let [[from to] (clojure.string/split period #",")]
       {:from from :to to})

     :else {})))

(defn build-params
  "Build query params map from filter state.
   mp-filter: keyword vec, period: string, compare: bool."
  [{:keys [mp-filter period compare]}]
  (cond-> (period->params period)
    (mp-param mp-filter) (assoc :mp (mp-param mp-filter))
    compare              (assoc :compare "true")))

(defn- params->query-string
  "Encode a map to a URL query string. Returns \"\" for empty map."
  [m]
  (if (empty? m)
    ""
    (str "?" (->> m
                  (map (fn [[k v]] (str (name k) "=" (js/encodeURIComponent (str v)))))
                  (clojure.string/join "&")))))

(defn build-url
  "Append query params to base URL."
  [base params]
  (str base (params->query-string params)))

;; ---------------------------------------------------------------------------
;; Request map builders for :http-xhrio
;; ---------------------------------------------------------------------------

(defn get-xhrio
  "Build an http-xhrio effect map for a Transit GET request.
   url          — full URL including query string
   on-success   — event vector keyword (will be dispatched with response body)
   on-failure   — event vector keyword"
  [url on-success on-failure]
  {:method          :get
   :uri             url
   :timeout         15000
   :response-format (transit-response-format)
   :on-success      on-success
   :on-failure      on-failure})

(defn post-xhrio
  "Build an http-xhrio effect map for a Transit POST request."
  [url body on-success on-failure]
  {:method          :post
   :uri             url
   :timeout         15000
   :params          body
   :format          (transit-request-format)
   :response-format (transit-response-format)
   :on-success      on-success
   :on-failure      on-failure})
