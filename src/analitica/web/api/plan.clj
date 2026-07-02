(ns analitica.web.api.plan
  "HTTP handlers for spec 017 US4 — per-SKU Plan/Fact API (transit/JSON).

   Routes (registered in server.clj — see :notes for exact registration):
     GET  /api/v1/plan/sku?period_month=YYYY-MM&marketplace=wb
          → {:rows [PlanFactRow …] :totals {:plan N :actual N}}
     POST /api/v1/plan/sku/preview  (multipart `file`; period_month, marketplace)
          → ImportOutcome (+ capped :rows preview, NO DB write)
     POST /api/v1/plan/sku/import   (multipart; writes valid rows via save-plan!)
          → ImportOutcome

   Actuals basis (FR-025): per-article actuals come from the SAME aggregate the
   rest of the app uses — analitica.domain.sales/{fetch-sales,by-article}
   (revenue := SUM(for-pay) on sale rows, canon Sales.3). This is the source
   behind Top-movers and the SKU drill-down sheet.

   The server-rendered /plan MP-grid editor (web/pages/plan.clj) is untouched;
   this is the NEW per-SKU SPA surface.

   Contract: specs/017-engagement-bot-planfact/contracts/plan-fact-sku.edn §5.

   Run focused tests:
     clojure -M:test --focus analitica.web.api.plan-test"
  (:require [analitica.db :as db]
            [analitica.domain.plan :as plan]
            [analitica.domain.sales :as sales]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.ative.docjure.spreadsheet :as xl])
  (:import [java.io File]
           [java.time YearMonth]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private month-pattern #"^\d{4}-(0[1-9]|1[0-2])$")
(def ^:private preview-cap 200)

(defn- form-value
  "Read a form/query field that may be keyword- or string-keyed."
  [m k]
  (or (get m (name k)) (get m (keyword k))))

(defn- month->range
  "YYYY-MM → [from to] as inclusive ISO date strings (first..last of month)."
  [ym-str]
  (let [ym    (YearMonth/parse ym-str)
        from  (.atDay ym 1)
        to    (.atEndOfMonth ym)]
    [(str from) (str to)]))

(defn- valid-period? [s]
  (boolean (and (string? s) (re-matches month-pattern s))))

;; ---------------------------------------------------------------------------
;; Catalogue of known SKUs — the same articles the app tracks (from sales)
;; ---------------------------------------------------------------------------

(defn- known-skus
  "Distinct articles present in the sales table (the app's SKU catalogue).
   Used to reject orphan import targets (unknown SKU)."
  []
  (->> (db/query ["SELECT DISTINCT article FROM sales WHERE article IS NOT NULL"])
       (map :article)
       (remove str/blank?)
       set))

;; ---------------------------------------------------------------------------
;; GET /api/v1/plan/sku — plan × actual per SKU with variance
;; ---------------------------------------------------------------------------

(defn- actuals-by-article
  "Per-article actual revenue for [period-month, marketplace], from the
   canonical sales aggregate (revenue := SUM(for-pay) on sale rows).
   Returns {article => revenue-double}. marketplace 'all' → nil (all MPs)."
  [period-month marketplace]
  (let [[from to] (month->range period-month)
        mp        (when (not= marketplace "all") (keyword marketplace))
        sales-rows (sales/fetch-sales [from to] :marketplace mp)]
    (->> (sales/by-article sales-rows)
         (into {} (map (fn [r] [(:group r) (double (or (:revenue r) 0.0))]))))))

(defn get-plan-sku
  "GET /api/v1/plan/sku?period_month=YYYY-MM&marketplace=wb
   → {:rows [PlanFactRow …] :totals {:plan N :actual N}}

   Rows are the UNION of:
     - every article with actual sales in the period (actual from sales), and
     - every per-SKU plan target for the period (so 0-sales targets show up).
   Variance is edge-safe (see plan/compute-variance): nil plan → nil variance."
  [req]
  (let [params       (:params req)
        period-month (form-value params :period_month)
        marketplace  (or (form-value params :marketplace) "all")]
    (cond
      (not (valid-period? period-month))
      {:status 422 :body {:ok false :error "period_month must be YYYY-MM"}}

      (not (contains? #{"wb" "ozon" "ym" "all"} marketplace))
      {:status 422 :body {:ok false :error "marketplace must be one of wb/ozon/ym/all"}}

      :else
      (let [actuals    (actuals-by-article period-month marketplace)
            all-plans  (plan/fetch-plans period-month)
            ;; per-SKU revenue plan targets for this marketplace (sku ≠ "")
            metric     :revenue
            plan-of    (fn [sku]
                         (plan/lookup-plan-sku
                           all-plans
                           {:period-month period-month
                            :marketplace  marketplace
                            :metric       metric
                            :sku          sku}))
            ;; SKUs that have a per-SKU (sku≠'') plan target on this MP
            plan-skus  (->> all-plans
                            (filter (fn [r]
                                      (and (seq (or (:sku r) ""))
                                           (= (some-> (:marketplace r) name) marketplace)
                                           (= (or (plan/coerce-metric (some-> (:metric r) name))
                                                  (keyword (some-> (:metric r) name)))
                                              metric))))
                            (map :sku)
                            set)
            skus       (sort (into (set (keys actuals)) plan-skus))
            rows       (mapv (fn [sku]
                               (let [plan-v (plan-of sku)
                                     actual (double (get actuals sku 0.0))]
                                 (plan/compute-variance
                                   {:sku    sku
                                    :metric metric
                                    :plan   plan-v
                                    :actual actual})))
                             skus)
            total-plan   (reduce + 0.0 (keep :plan rows))
            total-actual (reduce + 0.0 (map #(or (:actual %) 0.0) rows))]
        {:status 200
         :body   {:rows   rows
                  :totals {:plan   total-plan
                           :actual total-actual}}}))))

;; ---------------------------------------------------------------------------
;; Import file parsing (CSV / XLSX → seq of raw {:sku :metric :target-value})
;; ---------------------------------------------------------------------------

(defn- normalize-header
  "Lower-case, trim, and unify header names to canonical keys."
  [h]
  (-> (str h) str/trim str/lower-case))

(defn- header->key
  "Map a raw header cell to a raw-row key, or nil if unrecognized."
  [h]
  (case (normalize-header h)
    ("sku" "артикул")                          :sku
    ("metric" "метрика")                       :metric
    ("target_value" "target" "цель" "план")    :target-value
    ("marketplace" "mp")                       :marketplace
    nil))

(defn- rows->raw
  "Given a header row (seq of cell strings) + data rows (seq of seq of cell
   strings), produce a seq of raw import maps keyed by :sku/:metric/etc."
  [header data-rows]
  (let [col-keys (mapv header->key header)]
    (for [row data-rows
          :when (some #(and % (seq (str/trim (str %)))) row)] ; skip blank lines
      (reduce
        (fn [acc [k cell]]
          (if k (assoc acc k (some-> cell str str/trim)) acc))
        {}
        (map vector col-keys row)))))

(defn- parse-csv
  "Parse a CSV file into raw import maps. Splits on comma or semicolon."
  [^File f]
  (let [lines (->> (with-open [r (io/reader f)] (vec (line-seq r)))
                   (remove str/blank?))
        split (fn [line] (mapv str/trim (str/split line #"[,;]" -1)))]
    (when (seq lines)
      (let [header (split (first lines))
            data   (map split (rest lines))]
        (rows->raw header data)))))

(defn- parse-xlsx
  "Parse an XLSX file into raw import maps via docjure. Reads the first sheet."
  [^File f]
  (let [wb    (xl/load-workbook (.getAbsolutePath f))
        sheet (first (xl/sheet-seq wb))
        rows  (->> (xl/row-seq sheet)
                   (map (fn [r] (mapv xl/read-cell (xl/cell-seq r)))))]
    (when (seq rows)
      (rows->raw (first rows) (rest rows)))))

(defn- parse-upload
  "Dispatch on filename extension: .xlsx → docjure, else CSV."
  [^File f filename]
  (let [lower (some-> filename str/lower-case)]
    (if (and lower (or (str/ends-with? lower ".xlsx") (str/ends-with? lower ".xls")))
      (parse-xlsx f)
      (parse-csv f))))

(defn- save-upload-to-temp
  "Copy an uploaded multipart file-part to a temp file. Caller deletes it."
  [{:keys [tempfile filename]}]
  (let [suffix (or (when (and filename (pos? (.indexOf ^String filename (int \.))))
                     (subs filename (.lastIndexOf ^String filename (int \.))))
                   ".csv")
        out    (File/createTempFile "planimport-" suffix)]
    (io/copy tempfile out)
    out))

;; ---------------------------------------------------------------------------
;; Shared multipart plumbing for preview / import
;; ---------------------------------------------------------------------------

(defn- with-parsed-upload
  "Validate the multipart request (file part + period_month), parse the file,
   run plan/parse-import-rows, and hand the ImportOutcome + parsed-file-part to
   `k`. Returns a Ring response. `k` :: outcome → response.
   Returns 400 (no file) / 422 (bad period/marketplace) directly."
  [req k]
  (let [mp-params    (:multipart-params req)
        file-part    (get mp-params "file")
        period-month (form-value mp-params :period_month)
        marketplace  (or (form-value mp-params :marketplace) "all")]
    (cond
      (or (nil? file-part) (zero? (:size file-part 0)))
      {:status 400 :body {:ok false :error "No file uploaded (expect multipart field `file`)"}}

      (not (valid-period? period-month))
      {:status 422 :body {:ok false :error "period_month must be YYYY-MM"}}

      (not (contains? #{"wb" "ozon" "ym" "all"} marketplace))
      {:status 422 :body {:ok false :error "marketplace must be one of wb/ozon/ym/all"}}

      :else
      (let [^File temp (save-upload-to-temp file-part)]
        (try
          (let [raw-rows (or (parse-upload temp (:filename file-part)) [])
                outcome  (plan/parse-import-rows
                           raw-rows
                           {:period-month period-month
                            :marketplace  marketplace
                            :known-skus   (known-skus)})]
            (k outcome))
          (catch Throwable t
            {:status 500 :body {:ok false :error (.getMessage t)}})
          (finally
            (.delete temp)))))))

;; ---------------------------------------------------------------------------
;; POST /api/v1/plan/sku/preview — parse only, NO DB write
;; ---------------------------------------------------------------------------

(defn preview-plan-sku
  "POST /api/v1/plan/sku/preview (multipart). Parses the upload and returns an
   ImportOutcome WITHOUT touching the DB. :rows preview capped at 200."
  [req]
  (with-parsed-upload req
    (fn [outcome]
      {:status 200
       :body   (-> outcome
                   (update :rows (fn [rows] (vec (take preview-cap rows))))
                   (update :errors vec))})))

;; ---------------------------------------------------------------------------
;; POST /api/v1/plan/sku/import — writes valid rows atomically per row
;; ---------------------------------------------------------------------------

(defn import-plan-sku
  "POST /api/v1/plan/sku/import (multipart). Parses the upload and persists the
   valid rows via plan/save-plan! (per-row upsert). Returns the ImportOutcome.
   Rejected rows are reported in :errors (never silently dropped, FR-020)."
  [req]
  (with-parsed-upload req
    (fn [outcome]
      (doseq [row (:rows outcome)]
        (plan/save-plan! row))
      {:status 200
       :body   (update outcome :errors vec)})))
