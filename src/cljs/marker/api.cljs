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

(defn- period->params
  "Map a human-readable period string to ISO from/to query params.
   For Phase 8 we send the period label as-is and let the backend resolve it.
   The backend already has parse-period-params fallback to last-30-days."
  [period]
  ;; We don't attempt to resolve preset labels client-side here.
  ;; The backend's parse-period-params falls back to last-30-days when
  ;; :from/:to are absent — which is fine for non-custom ranges.
  ;; TODO: send explicit from/to once a client-side period resolver is added.
  {})

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
