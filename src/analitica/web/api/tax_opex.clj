(ns analitica.web.api.tax-opex
  "HTTP handlers for tax/OPEX management endpoints.

   Routes (registered in server.clj):
     GET    /api/v1/settings/tax?year=YYYY   → 12-month tax config (fills gaps)
     PUT    /api/v1/settings/tax             → upsert tax config (pct→fraction)
     GET    /api/v1/opex?period=YYYY-MM      → opex rows + by-category + total
     POST   /api/v1/opex                     → create opex row
     DELETE /api/v1/opex/:id                 → delete opex row
     GET    /api/v1/opex/auto-rules          → list all auto-rules
     POST   /api/v1/opex/auto-rules          → create auto-rule
     DELETE /api/v1/opex/auto-rules/:id      → delete auto-rule

   Contracts: specs/015-management-taxes-opex/contracts/tax-opex-api.md §1/§2/§2b."
  (:require [analitica.domain.opex :as opex]
            [analitica.domain.tax :as tax]
            [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- body-of [req]
  (let [b (:body req)]
    (if (map? b) b (or (:params req) {}))))

(defn- parse-id
  "Route/query :id as a Long, or nil when absent or non-numeric
   (nil lets handlers answer 4xx instead of a parseLong 500)."
  [req]
  (when-let [raw (or (get-in req [:params :id])
                     (get-in req [:route-params :id]))]
    (try (Long/parseLong raw) (catch NumberFormatException _ nil))))

(defn- ->long [x]
  (cond
    (integer? x) (long x)
    (string? x)  (try (Long/parseLong x) (catch Exception _ nil))
    :else        nil))

(defn- ->keyword [x]
  (cond
    (keyword? x)          x
    (and (string? x)
         (seq x))         (keyword x)
    :else                 nil))

;; ---------------------------------------------------------------------------
;; Tax config handlers (§1 — GET/PUT /api/v1/settings/tax)
;; ---------------------------------------------------------------------------

(defn- default-month-row
  "Inert default row for an unconfigured month (FR-004)."
  [month]
  {:month               month
   :taxation-type       :none
   :usn-rate            0.0
   :vat-rate            0.0
   :official-cost-price true})

(defn- config-row->response
  "Project a persisted TaxConfigRow to the response shape (drops :year/:updated-at)."
  [{:keys [month taxation-type usn-rate vat-rate official-cost-price]}]
  {:month               (long month)
   :taxation-type       taxation-type
   :usn-rate            (double (or usn-rate 0.0))
   :vat-rate            (double (or vat-rate 0.0))
   :official-cost-price (boolean official-cost-price)})

(defn get-tax
  "GET /api/v1/settings/tax?year=YYYY
   Returns {:year n :months [12 rows]} — configured months merged over
   12 inert :none defaults so the FE always receives a full year."
  [req]
  (let [year     (or (->long (get-in req [:params :year]))
                     (+ 1900 (.getYear (java.time.LocalDate/now))))
        by-month (into {}
                       (map (juxt :month config-row->response))
                       (tax/fetch-config year))
        months   (mapv (fn [m] (get by-month m (default-month-row m)))
                       (range 1 13))]
    {:status 200
     :body   {:year   (long year)
              :months months}}))

(defn put-tax
  "PUT /api/v1/settings/tax
   Body: {:year n :months [rows with :usn-rate-pct/:vat-rate-pct percents]}.
   Server normalizes percents → fractions via tax/save-config!.
   Returns {:ok true :saved n} | {:ok false :error string}."
  [req]
  (let [body   (body-of req)
        year   (->long (:year body))
        months (->> (:months body)
                    (map (fn [m]
                           (cond-> (assoc m :year year)
                             (:taxation-type m)
                             (update :taxation-type ->keyword)))))]
    (if (nil? year)
      {:status 422 :body {:ok false :error "year is required"}}
      (try
        (let [{:keys [saved]} (tax/save-config! months)]
          {:status 200 :body {:ok true :saved saved}})
        (catch Exception e
          {:status 422 :body {:ok false :error (.getMessage e)}})))))

;; ---------------------------------------------------------------------------
;; OPEX rows handlers (§2 — GET/POST/DELETE /api/v1/opex)
;; ---------------------------------------------------------------------------

(defn get-opex
  "GET /api/v1/opex?period=YYYY-MM[&marketplace=wb|ozon|ym]
   Materializes active auto-rules for the period (idempotent) before aggregating,
   so an active rule contributes without a manual step (contract §2b read-path).
   Returns {:period :rows :by-category :total}."
  [req]
  (let [period (get-in req [:params :period])
        mp     (->keyword (get-in req [:params :marketplace]))]
    (if-not (and (string? period)
                 (re-matches #"\d{4}-(0[1-9]|1[0-2])" period))
      ;; Strict YYYY-MM guard BEFORE materialize-rules!: this GET performs a
      ;; write (read-path rule materialization, contract §2b) and a garbage
      ;; period used to insert orphan opex_rows that no read window would
      ;; ever match (audit 2026-07-02 H2).
      {:status 422 :body {:ok false :error "period is required (YYYY-MM)"}}
      (do
        ;; Read-path materialization (idempotent, override-safe).
        (opex/materialize-rules! period)
        (let [{:keys [total by-category rows]} (opex/sum-by-category period period mp)]
          {:status 200
           :body   {:period      period
                    :rows        (vec rows)
                    :by-category by-category
                    :total       total}})))))

(defn post-opex
  "POST /api/v1/opex
   Body: {:period-month :category :amount :marketplace :note}.
   Coerces marketplace string → keyword and integer amount → double.
   Returns {:ok true :id n} | {:ok false :error string} (amount ≤ 0)."
  [req]
  (let [body (body-of req)
        row  (cond-> body
               (contains? body :amount)
               (update :amount #(when (number? %) (double %)))
               (string? (:marketplace body))
               (update :marketplace #(when (seq %) (keyword %))))]
    (if (m/validate opex/OpexRow row)
      (try
        (let [{:keys [id]} (opex/save-row! row)]
          {:status 200 :body {:ok true :id id}})
        (catch Exception e
          {:status 500 :body {:ok false :error (.getMessage e)}}))
      {:status 422
       :body   {:ok    false
                :error (if (and (number? (:amount body))
                                (<= (double (:amount body)) 0))
                         "amount must be positive"
                         (let [ex (m/explain opex/OpexRow row)]
                           (str "Validation failed: "
                                (pr-str (mapv :message (:errors ex))))))}})))

(defn delete-opex
  "DELETE /api/v1/opex/:id
   Returns {:ok true} (idempotent) | {:ok false :error} (missing id)."
  [req]
  (if-let [id (parse-id req)]
    (do
      (opex/delete-row! id)
      {:status 200 :body {:ok true}})
    {:status 400 :body {:ok false :error "Missing or invalid id"}}))

;; ---------------------------------------------------------------------------
;; OPEX auto-rules handlers (US5 — T059/T060)
;; ---------------------------------------------------------------------------

(defn get-auto-rules
  "GET /api/v1/opex/auto-rules
   Returns {:rules [OpexAutoRule…]}"
  [_req]
  {:status 200
   :body   {:rules (vec (opex/fetch-rules))}})

(defn post-auto-rule
  "POST /api/v1/opex/auto-rules
   Body: OpexAutoRule map (without :id).
   Returns {:ok true :id n} or {:ok false :error string}."
  [req]
  (let [body (body-of req)
        ;; Coerce marketplace string → keyword if needed
        rule (cond-> body
               (string? (:marketplace body))
               (update :marketplace #(when (seq %) (keyword %)))
               (string? (:cadence body))
               (update :cadence keyword))]
    (if (m/validate opex/OpexAutoRule rule)
      (try
        (let [{:keys [id]} (opex/save-rule! rule)]
          {:status 200 :body {:ok true :id id}})
        (catch Exception e
          {:status 500 :body {:ok false :error (.getMessage e)}}))
      {:status 422
       :body   {:ok    false
                :error (let [ex (m/explain opex/OpexAutoRule rule)]
                         (str "Validation failed: "
                              (pr-str (mapv :message
                                           (:errors ex)))))}})))

(defn delete-auto-rule
  "DELETE /api/v1/opex/auto-rules/:id
   Returns {:ok true}."
  [req]
  (if-let [id (parse-id req)]
    (do
      (opex/delete-rule! id)
      {:status 200 :body {:ok true}})
    {:status 400 :body {:ok false :error "Missing or invalid id"}}))
