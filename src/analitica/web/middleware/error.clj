(ns analitica.web.middleware.error
  "Outermost middleware: convert any unhandled exception into a 500 with a
   trace-id (logged via mulog) and NO stacktrace in the response body.
   It sits OUTSIDE wrap-json-response, so it must emit an already-serialized
   JSON STRING body itself (a map body would never get serialized — Jetty
   rejects a Clojure map body)."
  (:require [com.brunobonacci.mulog :as mu]
            [jsonista.core :as json]))

(defn wrap-exception [handler]
  (fn [request]
    (try
      (handler request)
      (catch Throwable t
        (let [trace-id (str (java.util.UUID/randomUUID))]
          (mu/log ::unhandled-exception
                  :trace-id      trace-id
                  :uri           (:uri request)
                  :method        (:request-method request)
                  :error-message (.getMessage t)
                  :error-type    (.getName (class t))
                  :stacktrace    (mapv str (.getStackTrace t)))
          {:status 500
           :headers {"Content-Type" "application/json; charset=utf-8"}
           :body (json/write-value-as-string {:error "internal" :trace_id trace-id})})))))
