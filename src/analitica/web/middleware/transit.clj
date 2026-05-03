(ns analitica.web.middleware.transit
  "Transit-JSON content negotiation middleware for the Marker SPA API.

   Two middlewares:

   `wrap-transit-response`
     When the handler returns a Clojure data structure AND the request
     carries `Accept: application/transit+json`, encodes the body with
     transit-json and sets the Content-Type header accordingly.
     Falls through unchanged for every other request (HTML pages, existing
     JSON endpoints, static resources) so no legacy behaviour is altered.

   `wrap-transit-body`
     When the request carries `Content-Type: application/transit+json`,
     reads the raw InputStream body and replaces :body with the decoded
     Clojure value.  Used by the POST /what-if-recalc endpoint.

   Transit format: :json (text) — readable in browser devtools, smaller
   wire size than msgpack for typical map payloads."
  (:require [cognitect.transit :as transit])
  (:import (java.io ByteArrayOutputStream ByteArrayInputStream)))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- transit-json-accept?
  "Return true when the request's Accept header mentions transit+json."
  [request]
  (let [accept (get-in request [:headers "accept"] "")]
    (boolean (re-find #"application/transit\+json" accept))))

(defn- transit-json-content?
  "Return true when the request's Content-Type is transit+json."
  [request]
  (let [ct (get-in request [:headers "content-type"] "")]
    (boolean (re-find #"application/transit\+json" ct))))

(defn- clj-data?
  "Return true when v is a Clojure data structure (not a String or byte-array).
   We only encode maps, vectors, sequences, sets — not raw strings (those are
   already rendered bodies) and not nil."
  [v]
  (and (some? v)
       (not (string? v))
       (not (bytes? v))
       (or (map? v) (vector? v) (seq? v) (set? v) (list? v))))

(defn encode-transit-json
  "Encode `data` to a transit-json byte array, return as String."
  ^String [data]
  (let [out    (ByteArrayOutputStream.)
        writer (transit/writer out :json)]
    (transit/write writer data)
    (.toString out "UTF-8")))

(defn decode-transit-json
  "Decode a transit-json InputStream or String to a Clojure value."
  [input]
  (let [stream (cond
                 (instance? java.io.InputStream input) input
                 (string? input) (ByteArrayInputStream. (.getBytes ^String input "UTF-8"))
                 (bytes? input)  (ByteArrayInputStream. ^bytes input)
                 :else           nil)]
    (when stream
      (let [reader (transit/reader stream :json)]
        (transit/read reader)))))

;; ---------------------------------------------------------------------------
;; Public middleware
;; ---------------------------------------------------------------------------

(defn wrap-transit-response
  "Encode response body as transit-json when client requests it.

   Condition: response body is a Clojure data structure AND request
   Accept header includes 'application/transit+json'.

   All other responses (HTML strings, already-serialised JSON strings,
   nil bodies) pass through untouched."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (and (transit-json-accept? request)
               (clj-data? (:body response)))
        (-> response
            (assoc  :body (encode-transit-json (:body response)))
            (assoc-in [:headers "Content-Type"] "application/transit+json; charset=utf-8"))
        response))))

(defn wrap-transit-body
  "Decode request body from transit-json when Content-Type indicates it.

   Replaces :body with the decoded Clojure value.  When decoding fails
   (malformed transit), passes through with the original body so the
   handler can return a 400 gracefully."
  [handler]
  (fn [request]
    (if (transit-json-content? request)
      (let [decoded (try
                      (decode-transit-json (:body request))
                      (catch Exception _ nil))]
        (handler (if decoded
                   (assoc request :body decoded)
                   request)))
      (handler request))))
