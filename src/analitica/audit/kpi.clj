(ns analitica.audit.kpi
  "Accuracy KPI baseline measurement (US2).

   Implements FR-005 / FR-011 / SC-002 / SC-007:
     - `select-skus`    — pick the SKU scope for a measurement (top-N by
                          `SUM(retail_amount)` on finance, minus articles that
                          participate in multi-article ad campaigns).
     - `verdict-for`    — decide :meets-kpi / :misses-kpi from a relative pct
                          (±3% boundary, inclusive per research R5).
     - `measure!`       — full pipeline: WB-only gate → FR-011 completeness
                          check on bank reference → reconcile → SUM(for_pay)
                          on scope → INSERT into `accuracy_kpi_measurements`.
     - `list-measurements` / `show-measurement` — read-only accessors with
                          JSON deserialisation for `sku_list` / `breakdown` /
                          `reference_excel_by_article`.

   Persistence shape follows data-model.md §AccuracyKpiMeasurement and the
   DDL in `analitica.db/ddl-statements`."
  (:require [analitica.db :as db]
            [analitica.audit.core :as audit-core])
  (:import [java.util UUID]
           [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:const kpi-tolerance-pct
  "Accuracy KPI threshold from vision §13 / SC-007: |rel-pct| <= 3.0 passes."
  3.0)

(def ^:const default-top-n
  "Default SKU scope size — vision's 30 SKU × 30 days rule."
  30)

;; ---------------------------------------------------------------------------
;; select-skus — SKU scope picker
;; ---------------------------------------------------------------------------

(defn- top-skus-query-rows
  "Run the top-N-by-retail-amount query. Returns a vector of {:article :sum-amount}."
  [marketplace {:keys [from to]} top-n]
  (let [sql (str "SELECT article, SUM(retail_amount) AS sum_amount
                  FROM finance
                  WHERE date_from <= ? AND date_to >= ?
                    AND marketplace = ?
                    AND article IS NOT NULL AND article != ''
                  GROUP BY article
                  ORDER BY sum_amount DESC
                  LIMIT ?")]
    (db/query [sql to from (name marketplace) top-n])))

(defn- articles-in-multi-article-campaigns
  "Return a set of article strings that participate in ad campaigns covering
   more than one distinct article. Matches nm_id→article via finance rows so
   ad_stats (which lacks `article`) can still be mapped.

   Returns `#{}` when ad_stats is empty or no multi-article campaigns exist."
  [marketplace]
  (let [sql (str "SELECT DISTINCT f.article
                  FROM ad_stats a
                  JOIN (SELECT DISTINCT nm_id, article, marketplace FROM finance
                        WHERE article IS NOT NULL AND article != '') f
                    ON a.nm_id = f.nm_id
                  WHERE a.campaign_id IN (
                    SELECT campaign_id FROM ad_stats
                    JOIN (SELECT DISTINCT nm_id, article FROM finance
                          WHERE article IS NOT NULL AND article != ''
                            AND marketplace = ?) f2
                      ON ad_stats.nm_id = f2.nm_id
                    GROUP BY campaign_id
                    HAVING COUNT(DISTINCT f2.article) > 1)
                    AND f.marketplace = ?")
        rows (try (db/query [sql (name marketplace) (name marketplace)])
                  (catch Throwable _ []))]
    (set (keep :article rows))))

(defn select-skus
  "Pick the SKU scope for a KPI measurement.

   Usage:
     (select-skus db :wb {:from \"...\" :to \"...\"})
     (select-skus db :wb period :top-n 30 :exclude-multi-campaign? true)

   Options:
     :top-n                   (default 30) — how many top articles to return
     :exclude-multi-campaign? (default true) — drop articles that participate
                                                in ad campaigns covering more
                                                than one distinct article (the
                                                ad-spend attribution ambiguity
                                                surfaced by B-003).

   Returns a vector of article-ids (strings), in the order the query produced
   them (descending retail_amount).

   `db` is accepted as the first argument for API parity with other audit
   functions but is not actually threaded through — under the hood the shared
   `analitica.db` datasource is used. Callers that need per-test isolation
   should rely on `with-isolated-db` instead."
  [_db marketplace period & {:keys [top-n exclude-multi-campaign?]
                             :or   {top-n default-top-n
                                    exclude-multi-campaign? true}}]
  (let [top (mapv :article (top-skus-query-rows marketplace period top-n))
        excluded (if exclude-multi-campaign?
                   (articles-in-multi-article-campaigns marketplace)
                   #{})]
    (vec (remove excluded top))))

;; ---------------------------------------------------------------------------
;; verdict-for — pure verdict function
;; ---------------------------------------------------------------------------

(defn verdict-for
  "Return :meets-kpi if |rel-pct| <= 3.0, else :misses-kpi.

   The threshold is inclusive (exactly ±3.0 passes) per research R5."
  [rel-pct]
  (if (<= (Math/abs (double (or rel-pct 0.0))) kpi-tolerance-pct)
    :meets-kpi
    :misses-kpi))

;; ---------------------------------------------------------------------------
;; measure! — full pipeline
;; ---------------------------------------------------------------------------

(defn- sum-for-pay-by-articles
  "SUM(finance.for_pay) for the given articles within period/marketplace.
   Empty `articles` short-circuits to 0.0."
  [{:keys [from to]} marketplace articles]
  (if (empty? articles)
    0.0
    (let [placeholders (clojure.string/join "," (repeat (count articles) "?"))
          sql (str "SELECT COALESCE(SUM(for_pay), 0) AS total
                    FROM finance
                    WHERE date_from <= ? AND date_to >= ?
                      AND marketplace = ?
                      AND article IN (" placeholders ")")
          params (into [sql to from (name marketplace)] articles)
          row    (first (db/query params))]
      (double (or (:total row) 0.0)))))

(defn- now-iso []
  (str (Instant/now)))

(defn- valid-bank-input?
  "Spec: bank-input is either `{:sum N}` scalar OR `{:sum N :by-date {...}
   :missing-dates [...]}`. `:sum` is required."
  [bi]
  (and (map? bi) (number? (:sum bi))))

(defn measure!
  "Run a full Accuracy KPI measurement and persist one row into
   `accuracy_kpi_measurements`.

   Required keys on the argument map:
     :marketplace — keyword; MVP is gated to :wb (vision §spec:132)
     :period      — {:from \"YYYY-MM-DD\" :to \"YYYY-MM-DD\"}
     :bank-input  — map from `audit.bank/parse-bank-*`:
                    `{:sum N}` or `{:sum N :by-date {...} :missing-dates [...]}`
     :tolerance   — {:rel N :abs N} forwarded to run-reconcile!

   Optional:
     :skus           — vec of articles; when supplied, skips top-N selection
     :excel-sum      — secondary reference (double)
     :excel-by-article — {article expected} map (serialised as JSON)
     :captured-by    — operator name (default: `(System/getenv \"USER\")`)

   Throws `ex-info` with:
     :type :kpi-mvp-gated-to-wb      when marketplace ≠ :wb
     :type :incomplete-bank-reference when :missing-dates is non-empty (FR-011)

   Both guards run BEFORE any DB write.

   Returns the generated `kpi-id` (UUID string)."
  [{:keys [marketplace period bank-input tolerance skus excel-sum
           excel-by-article captured-by]}]
  ;; --- Guard 1: WB-only MVP gate -------------------------------------------
  (when-not (= :wb marketplace)
    (throw (ex-info (str "KPI measurement is MVP-gated to WB; got " marketplace
                         ". Use `audit reconcile` for other marketplaces.")
                    {:type        :kpi-mvp-gated-to-wb
                     :marketplace marketplace})))
  ;; --- Guard 2: FR-011 bank-reference completeness -------------------------
  (when-not (valid-bank-input? bank-input)
    (throw (ex-info "bank-input must be {:sum N [...]}"
                    {:type :invalid-bank-input :bank-input bank-input})))
  (when (seq (:missing-dates bank-input))
    (throw (ex-info (str "Bank data incomplete: missing dates "
                         (vec (:missing-dates bank-input))
                         ". Refusing to record baseline (FR-011).")
                    {:type          :incomplete-bank-reference
                     :missing-dates (:missing-dates bank-input)
                     :period        period})))
  ;; --- Run reconcile to attach report-id ------------------------------------
  (let [reconcile-result (audit-core/run-reconcile!
                           {:marketplace marketplace
                            :period      period
                            :tolerance   (or tolerance {:rel 0.01 :abs 10.0})
                            :bank-input  bank-input})
        report-id   (get-in reconcile-result [:report :report/id])
        ;; --- SKU scope ------------------------------------------------------
        sku-scope   (if (seq skus)
                      (vec skus)
                      (select-skus (db/ds) marketplace period))
        selection-method (if (seq skus) "explicit-override" "top-30-by-retail-amount")
        ;; --- Measured value (product-side) ----------------------------------
        measured    (sum-for-pay-by-articles period marketplace sku-scope)
        bank-sum    (double (:sum bank-input))
        abs-rub     (- measured bank-sum)
        rel-pct     (if (zero? bank-sum) 0.0 (* 100.0 (/ abs-rub bank-sum)))
        verdict     (verdict-for rel-pct)
        kpi-id      (str (UUID/randomUUID))
        cap-by      (or captured-by (System/getenv "USER") "unknown")]
    ;; --- Persist --------------------------------------------------------------
    (db/execute!
      ["INSERT INTO accuracy_kpi_measurements
        (kpi_id, captured_at, captured_by, marketplace, period_from, period_to,
         sku_list, sku_selection_method, reference_bank_sum, reference_excel_sum,
         reference_excel_by_article, measured_value, delta_abs_rub, delta_rel_pct,
         verdict, breakdown, report_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
       kpi-id
       (now-iso)
       cap-by
       (name marketplace)
       (:from period)
       (:to period)
       (db/serialize-json sku-scope)
       selection-method
       bank-sum
       (when excel-sum (double excel-sum))
       (when excel-by-article (db/serialize-json excel-by-article))
       measured
       abs-rub
       rel-pct
       (name verdict)
       nil   ;; breakdown — computed only when misses-kpi and explicitly requested
       (or report-id "")])
    kpi-id))

;; ---------------------------------------------------------------------------
;; list-measurements / show-measurement
;; ---------------------------------------------------------------------------

(defn- parse-json-safe [s]
  (when (and (string? s) (seq s))
    (try (db/parse-json s) (catch Throwable _ nil))))

(defn- deserialize-row
  "Convert a db-row (snake_case from `db/query`) into the domain-map shape,
   parsing JSON fields on the way out."
  [row]
  (-> row
      (update :sku-list                  (fnil parse-json-safe "[]"))
      (update :reference-excel-by-article parse-json-safe)
      (update :breakdown                 parse-json-safe)))

(defn list-measurements
  "Return all KPI measurements, most recent first. Optional `:marketplace`
   keyword filters to a single marketplace."
  ([] (list-measurements nil))
  ([{:keys [marketplace limit]}]
   (let [sql (if marketplace
               "SELECT * FROM accuracy_kpi_measurements
                WHERE marketplace = ?
                ORDER BY captured_at DESC
                LIMIT ?"
               "SELECT * FROM accuracy_kpi_measurements
                ORDER BY captured_at DESC
                LIMIT ?")
         params (if marketplace
                  [sql (name marketplace) (or limit 1000)]
                  [sql (or limit 1000)])
         rows  (db/query params)]
     (mapv deserialize-row rows))))

(defn show-measurement
  "Fetch a single KPI measurement by kpi-id, or nil when not found."
  [kpi-id]
  (some-> (first (db/query ["SELECT * FROM accuracy_kpi_measurements WHERE kpi_id = ?"
                            kpi-id]))
          deserialize-row))
