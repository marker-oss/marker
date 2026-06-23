(ns analitica.web.middleware.body-limit
  "Fast pre-parse reject of oversize request bodies by the client-supplied
   Content-Length header — defends against HONEST oversized clients only.
   It is NOT the hard OOM cap: a hostile client can omit Content-Length
   (Transfer-Encoding: chunked) or understate it and bypass this check. The
   hard cap is enforced at the edge by Caddy's `request_body max_size`
   (Plan C) — verify that is configured before relying on body limits against
   malicious input. Feedback also enforces its own per-file 10 MB cap."
  (:require [clojure.string :as str]))

(def json-max-bytes      (* 10 1024 1024))
(def multipart-max-bytes (* 50 1024 1024))

(defn- limit-for [request]
  (let [ct (str (get-in request [:headers "content-type"]))]
    (if (str/starts-with? ct "multipart/") multipart-max-bytes json-max-bytes)))

(defn wrap-content-length-limit [handler]
  (fn [request]
    (let [cl (get-in request [:headers "content-length"])
          n  (try (when cl (Long/parseLong (str/trim cl))) (catch Exception _ nil))]
      (if (and n (> n (limit-for request)))
        {:status 413 :body {:error "payload too large"}}
        (handler request)))))
