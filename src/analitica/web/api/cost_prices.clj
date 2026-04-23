(ns analitica.web.api.cost-prices
  "HTTP handlers for cost-price ingest.

   `POST /api/cost-prices/upload` — accepts a multipart `file` part
   (1C units.csv export), writes it to a temp file, feeds it through
   `analitica.costsource/ingest!` via the csv1c provider, returns the
   ingest summary as JSON.

   Future providers (1C HTTP API, Мойсклад API, …) will expose
   additional endpoints under the same `/api/cost-prices/…` prefix.
   The underlying ingest pipeline is shared."
  (:require [analitica.costsource :as costsource]
            [analitica.costsource.csv1c :as csv1c]
            [clojure.java.io :as io])
  (:import [java.io File]))

(defn- save-upload-to-temp
  "Copy an uploaded multipart file-part to a temp file. Returns the
   java.io.File on success. Caller is responsible for deletion."
  [{:keys [tempfile filename]}]
  (let [suffix (or (when (and filename (pos? (.indexOf ^String filename (int \.))))
                     (subs filename (.lastIndexOf ^String filename (int \.))))
                   ".csv")
        out    (File/createTempFile "costprices-" suffix)]
    (io/copy tempfile out)
    out))

(defn upload-csv
  "Compojure handler for POST /api/cost-prices/upload.

   Expects multipart form data with a `file` part. Returns 400 when no
   file is provided, 500 on ingest errors, 200 with the ingest summary
   otherwise."
  [request]
  (let [file-part (get-in request [:multipart-params "file"])]
    (cond
      (or (nil? file-part) (zero? (:size file-part 0)))
      {:status 400
       :body   {:error "No file uploaded (expect multipart field `file`)"}}

      :else
      (let [^File temp (save-upload-to-temp file-part)]
        (try
          (let [result (costsource/ingest! (csv1c/make-source (.getAbsolutePath temp)))]
            {:status 200
             :body   (assoc result :filename (:filename file-part))})
          (catch Throwable t
            {:status 500
             :body   {:error   (.getMessage t)
                      :filename (:filename file-part)}})
          (finally
            (.delete temp)))))))
