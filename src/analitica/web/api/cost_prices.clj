(ns analitica.web.api.cost-prices
  "HTTP handlers for cost-price ingest.

   `POST /api/cost-prices/upload` — accepts a multipart `file` part
   (1C units.csv export), writes it to a temp file, feeds it through
   `analitica.costsource/ingest!` via the csv1c provider, returns the
   ingest summary as JSON.

   `GET /api/cost-prices/imports` — returns the last N rows from the
   `cost_prices_imports` audit table (newest first).

   Future providers (1C HTTP API, Мойсклад API, …) will expose
   additional endpoints under the same `/api/cost-prices/…` prefix.
   The underlying ingest pipeline is shared."
  (:require [analitica.costsource :as costsource]
            [analitica.costsource.csv1c :as csv1c]
            [analitica.db :as db]
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

(defn list-imports
  "GET handler — return last N rows of cost_prices_imports (newest first).
   Accepts optional query param `limit` (default 50, max 500)."
  [request]
  (let [raw (or (get-in request [:params :limit])
                (get-in request [:params "limit"]))
        n   (try (max 1 (min 500 (Integer/parseInt (str raw))))
                 (catch Exception _ 50))
        rows (db/query
               ["SELECT id, source, imported_at, fetched, loaded, rejected, filename, notes
                 FROM cost_prices_imports
                 ORDER BY id DESC
                 LIMIT ?" n])]
    {:status 200 :body {:limit n :imports (vec rows)}}))

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
          (let [result (costsource/ingest!
                         (csv1c/make-source (.getAbsolutePath temp))
                         {:filename (:filename file-part)
                          :notes    "web upload"})]
            {:status 200
             :body   (assoc result :filename (:filename file-part))})
          (catch Throwable t
            {:status 500
             :body   {:error   (.getMessage t)
                      :filename (:filename file-part)}})
          (finally
            (.delete temp)))))))

(defn preview-csv
  "Compojure handler for POST /api/cost-prices/preview.

   Parses the uploaded CSV without touching the DB. Returns the parsed
   rows + per-line errors so the UI can confirm before commit. Caps the
   row preview at 200 to keep payloads small for big imports.

   200 body shape:
     {:filename     ...
      :total-lines  N      ; lines in the CSV
      :valid        N      ; successfully parsed
      :errors-count N      ; rejected rows
      :skipped      N      ; meta/header/blank lines
      :rows         [{...} …]   ; first 200 valid rows
      :errors       [{:line :raw :reason} …]   ; first 200 errors}"
  [request]
  (let [file-part (get-in request [:multipart-params "file"])]
    (cond
      (or (nil? file-part) (zero? (:size file-part 0)))
      {:status 400
       :body   {:error "No file uploaded (expect multipart field `file`)"}}

      :else
      (let [^File temp (save-upload-to-temp file-part)]
        (try
          (let [{:keys [rows errors skipped total-lines]}
                (csv1c/parse-file-with-diagnostics (.getAbsolutePath temp))]
            {:status 200
             :body   {:filename     (:filename file-part)
                      :total-lines  total-lines
                      :valid        (count rows)
                      :errors-count (count errors)
                      :skipped      skipped
                      :rows         (vec (take 200 rows))
                      :errors       (vec (take 200 errors))}})
          (catch Throwable t
            {:status 500
             :body   {:error    (.getMessage t)
                      :filename (:filename file-part)}})
          (finally
            (.delete temp)))))))
