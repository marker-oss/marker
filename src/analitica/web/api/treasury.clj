(ns analitica.web.api.treasury
  "HTTP handlers for spec 019 Treasury ledger endpoints.

   Routes (registered in server.clj — see StructuredOutput of this unit):
     GET  /api/v1/treasury/cashflow                    → ДДС matrix
     GET  /api/v1/treasury/categories                  → read-only taxonomy
     GET  /api/v1/treasury/operations                  → list + summary + pagination
     POST /api/v1/treasury/operations                  → create (cross-field validated)
     PUT  /api/v1/treasury/operations/:id              → update (manual category override)
     GET  /api/v1/treasury/accounts                    → list + derived balances
     POST /api/v1/treasury/accounts                    → create
     DELETE /api/v1/treasury/accounts/:id              → soft-archive if referenced
     GET  /api/v1/treasury/counterparties              → list
     POST /api/v1/treasury/counterparties              → create
     GET  /api/v1/treasury/obligations                 → list + derived status + pagination
     POST /api/v1/treasury/obligations                 → create
     POST /api/v1/treasury/obligations/:id/settle      → settle (partial/full)
     GET  /api/v1/treasury/obligations/summary         → dashboard summary
     GET  /api/v1/treasury/obligations/dynamics        → EXACTLY 12 points
     GET  /api/v1/treasury/auto-rules                  → list
     POST /api/v1/treasury/auto-rules                  → create
     POST /api/v1/treasury/auto-rules/classify         → run classifier (idempotent)

   Contracts: specs/019-treasury-ledger/contracts/cashflow-api.edn §1-§5 +
   obligations-api.edn §1-§4. Money is decimal-as-string (\"0.00\", FR-019):
   the domain layer returns strings/BigDecimal and these handlers emit strings
   verbatim — NEVER doubles. Ring transit/JSON middleware encodes the returned
   Clojure maps; handlers do not serialize themselves.

   All aggregation lives in the domain layer (FR-004); these handlers only parse
   request params, coerce string enums → keywords, and shape the response."
  (:require [clojure.string :as str]
            [analitica.domain.treasury.cashflow :as cashflow]
            [analitica.domain.treasury.operations :as ops]
            [analitica.domain.treasury.obligations :as obligations]
            [analitica.domain.treasury.autorules :as autorules]))

;; ---------------------------------------------------------------------------
;; Request helpers
;; ---------------------------------------------------------------------------

(defn- body-of
  "The parsed request body map (transit/JSON middleware), falling back to
   :params when the body is not a map."
  [req]
  (let [b (:body req)]
    (if (map? b) b (or (:params req) {}))))

(defn- params-of [req]
  (or (:params req) {}))

(defn- parse-id
  "Route/query :id as a Long, or nil when absent or non-numeric
   (nil lets handlers answer 4xx instead of a parseLong 500)."
  [req]
  (when-let [raw (or (get-in req [:params :id])
                     (get-in req [:route-params :id]))]
    (try (Long/parseLong raw) (catch NumberFormatException _ nil))))

(defn- ->kw
  "Coerce a value to a keyword if it is a non-blank string; pass keywords
   through; nil/blank → nil."
  [v]
  (cond
    (keyword? v) v
    (and (string? v) (not (str/blank? v))) (keyword v)
    :else nil))

(defn- ->long [v]
  (cond
    (integer? v) (long v)
    (and (string? v) (not (str/blank? v)))
    (try (Long/parseLong v) (catch NumberFormatException _ nil))
    :else nil))

(defn- ->bool
  "Coerce common truthy/falsey representations to boolean, or nil when absent."
  [v]
  (cond
    (boolean? v) v
    (= "true" v) true
    (= "false" v) false
    :else nil))

(defn- account-ids-of
  "Coerce an :account-ids param (vector of ints, or a comma-separated string,
   or a single value) to a vector of longs, or nil when absent."
  [v]
  (cond
    (nil? v) nil
    (sequential? v) (mapv ->long v)
    (and (string? v) (str/includes? v ",")) (mapv ->long (str/split v #","))
    :else (when-let [n (->long v)] [n])))

;; ---------------------------------------------------------------------------
;; §1 ДДС — GET /api/v1/treasury/cashflow
;; ---------------------------------------------------------------------------

(def ^:private iso-date-re #"\d{4}-\d{2}-\d{2}")

(defn get-cashflow
  "GET /api/v1/treasury/cashflow?from&to&group-by&account-ids&mode
   Returns the derived ДДС matrix (cashflow-api.edn §1). Cells are decimal-strings.
   from/to are required ISO dates with from <= to — a reversed range used to
   drive months-in-range into an unbounded loop (audit 2026-07-02 H1)."
  [req]
  (let [p    (params-of req)
        from (or (:from p) (get p "from"))
        to   (or (:to p) (get p "to"))]
    (cond
      (not (and (string? from) (re-matches iso-date-re from)
                (string? to)   (re-matches iso-date-re to)))
      {:status 422
       :body   {:ok false :error "from/to are required ISO dates (YYYY-MM-DD)"}}

      (pos? (compare from to))
      {:status 422
       :body   {:ok false :error "from must be <= to"}}

      :else
      (let [group-by (->kw (or (:group-by p) (get p "group-by")))
            mode     (->kw (or (:mode p) (get p "mode")))
            acc-ids  (account-ids-of (or (:account-ids p) (get p "account-ids")))
            opts     (cond-> {:from from :to to}
                       group-by (assoc :group-by group-by)
                       mode     (assoc :mode mode)
                       (seq acc-ids) (assoc :account-ids acc-ids))]
        {:status 200 :body (cashflow/report opts)}))))

;; ---------------------------------------------------------------------------
;; §5 Categories — GET /api/v1/treasury/categories (read-only taxonomy)
;; ---------------------------------------------------------------------------

(defn get-categories
  "GET /api/v1/treasury/categories → read-only taxonomy (shared with 015)."
  [_req]
  {:status 200 :body {:categories (vec cashflow/treasury-categories)}})

;; ---------------------------------------------------------------------------
;; §2 Operations — list / create / update
;; ---------------------------------------------------------------------------

(defn get-operations
  "GET /api/v1/treasury/operations → {:operations [...] :summary {...}
   :page :page-size :total}. Filters (all optional): from/to/account-id/
   counterparty-id/category/direction/planned/confirmed/regular/page/page-size."
  [req]
  (let [p   (params-of req)
        get* (fn [k] (or (get p k) (get p (name k))))
        ;; Gate each assoc on the COERCED value: ?page=abc coerces to nil and
        ;; must fall back to the default, not flow nil into list-ops (audit M1).
        flt (cond-> {}
              (get* :from)            (assoc :from (get* :from))
              (get* :to)              (assoc :to (get* :to))
              (->long (get* :account-id))      (assoc :account-id (->long (get* :account-id)))
              (->long (get* :counterparty-id)) (assoc :counterparty-id (->long (get* :counterparty-id)))
              (get* :category)        (assoc :category (get* :category))
              (->kw (get* :direction)) (assoc :direction (->kw (get* :direction)))
              (some? (->bool (get* :planned)))   (assoc :planned (->bool (get* :planned)))
              (some? (->bool (get* :confirmed))) (assoc :confirmed (->bool (get* :confirmed)))
              (some? (->bool (get* :regular)))   (assoc :regular (->bool (get* :regular)))
              (->long (get* :page))      (assoc :page (->long (get* :page)))
              (->long (get* :page-size)) (assoc :page-size (->long (get* :page-size))))]
    {:status 200 :body (ops/list-ops flt)}))

(defn post-operation
  "POST /api/v1/treasury/operations → {:ok true :id n} or {:ok false :error s}.
   Cross-field validation (R3/FR-022) is enforced by the domain schema."
  [req]
  (let [b   (body-of req)
        op  (cond-> b
              (contains? b :direction)       (update :direction ->kw)
              (contains? b :category-source) (update :category-source ->kw))]
    (try
      (let [{:keys [id]} (ops/create! op)]
        {:status 200 :body {:ok true :id id}})
      (catch clojure.lang.ExceptionInfo e
        ;; schema/validate! throws ex-info on cross-field / RUB-only failure
        {:status 422 :body {:ok false :error (.getMessage e)}})
      (catch Exception e
        {:status 500 :body {:ok false :error (.getMessage e)}}))))

(defn put-operation
  "PUT /api/v1/treasury/operations/:id → {:ok true}. A manual category edit
   passes :category-source :manual (manual-override-wins, R4)."
  [req]
  (if-let [id (parse-id req)]
    (let [b     (body-of req)
          patch (cond-> b
                  (contains? b :category-source) (update :category-source ->kw))]
      (try
        {:status 200 :body (ops/update! id patch)}
        (catch Exception e
          {:status 500 :body {:ok false :error (.getMessage e)}})))
    {:status 400 :body {:ok false :error "Missing or invalid id"}}))

;; ---------------------------------------------------------------------------
;; §3 Accounts — list / create / delete (soft-archive)
;; ---------------------------------------------------------------------------

(defn get-accounts
  "GET /api/v1/treasury/accounts → {:accounts [{… :balance}] :total-balance}.
   Balance is DERIVED (dsum of confirmed operations)."
  [req]
  (let [p   (params-of req)
        inc? (->bool (or (:include-archived p) (get p "include-archived")))]
    {:status 200 :body (ops/list-accounts {:include-archived (boolean inc?)})}))

(defn post-account
  "POST /api/v1/treasury/accounts → {:ok true :id n}. marketplace/kind coerced
   string → keyword."
  [req]
  (let [b   (body-of req)
        acc (cond-> b
              (contains? b :marketplace) (update :marketplace ->kw)
              (contains? b :kind)        (update :kind ->kw))]
    (try
      (let [{:keys [id]} (ops/create-account! acc)]
        {:status 200 :body {:ok true :id id}})
      (catch clojure.lang.ExceptionInfo e
        {:status 422 :body {:ok false :error (.getMessage e)}})
      (catch Exception e
        {:status 500 :body {:ok false :error (.getMessage e)}}))))

(defn delete-account
  "DELETE /api/v1/treasury/accounts/:id → {:ok true :archived bool}. Soft-archives
   (archived) when referenced by operations; hard-deletes otherwise (R11/FR-026)."
  [req]
  (if-let [id (parse-id req)]
    (try
      {:status 200 :body (ops/delete-account! id)}
      (catch Exception e
        {:status 500 :body {:ok false :error (.getMessage e)}}))
    {:status 400 :body {:ok false :error "Missing or invalid id"}}))

;; ---------------------------------------------------------------------------
;; §4 Counterparties — list / create
;; ---------------------------------------------------------------------------

(defn get-counterparties
  "GET /api/v1/treasury/counterparties → {:counterparties [{… :operation-count}]}."
  [req]
  (let [p   (params-of req)
        inc? (->bool (or (:include-archived p) (get p "include-archived")))]
    {:status 200 :body (ops/list-counterparties {:include-archived (boolean inc?)})}))

(defn post-counterparty
  "POST /api/v1/treasury/counterparties → {:ok true :id n}. kind coerced."
  [req]
  (let [b  (body-of req)
        cp (cond-> b
             (contains? b :kind) (update :kind ->kw))]
    (try
      (let [{:keys [id]} (ops/create-counterparty! cp)]
        {:status 200 :body {:ok true :id id}})
      (catch clojure.lang.ExceptionInfo e
        {:status 422 :body {:ok false :error (.getMessage e)}})
      (catch Exception e
        {:status 500 :body {:ok false :error (.getMessage e)}}))))

;; ---------------------------------------------------------------------------
;; Obligations — §1 summary / §2 dynamics / §3 list / create / settle
;; ---------------------------------------------------------------------------

(defn get-obligations-summary
  "GET /api/v1/treasury/obligations/summary?mode → dashboard summary (§1)."
  [req]
  (let [p    (params-of req)
        mode (->kw (or (:mode p) (get p "mode")))]
    {:status 200 :body (obligations/summary (cond-> {} mode (assoc :mode mode)))}))

(defn get-obligations-dynamics
  "GET /api/v1/treasury/obligations/dynamics?mode → EXACTLY 12 points (§2)."
  [req]
  (let [p    (params-of req)
        mode (->kw (or (:mode p) (get p "mode")))]
    {:status 200 :body (obligations/dynamics (cond-> {} mode (assoc :mode mode)))}))

(defn get-obligations
  "GET /api/v1/treasury/obligations → {:obligations [{… :status}] :page
   :page-size :total}. Filters: direction/status/mode/page/page-size."
  [req]
  (let [p    (params-of req)
        get* (fn [k] (or (get p k) (get p (name k))))
        flt  (cond-> {}
               (->kw (get* :direction)) (assoc :direction (->kw (get* :direction)))
               (->kw (get* :status))    (assoc :status (->kw (get* :status)))
               (->kw (get* :mode))      (assoc :mode (->kw (get* :mode)))
               (->long (get* :page))      (assoc :page (->long (get* :page)))
               (->long (get* :page-size)) (assoc :page-size (->long (get* :page-size))))]
    {:status 200 :body (obligations/list-obligations flt)}))

(defn post-obligation
  "POST /api/v1/treasury/obligations → {:ok true :id n}. direction coerced."
  [req]
  (let [b  (body-of req)
        ob (cond-> b
             (contains? b :direction) (update :direction ->kw))]
    (try
      (let [{:keys [id]} (obligations/create! ob)]
        {:status 200 :body {:ok true :id id}})
      (catch clojure.lang.ExceptionInfo e
        {:status 422 :body {:ok false :error (.getMessage e)}})
      (catch Exception e
        {:status 500 :body {:ok false :error (.getMessage e)}}))))

(defn settle-obligation
  "POST /api/v1/treasury/obligations/:id/settle → {:ok true :remaining-amount
   :status}. Overshoot (settle-amount > remaining) → 500 error."
  [req]
  (if-let [id (parse-id req)]
    (let [b (body-of req)
          settle {:settled-operation-id (:settled-operation-id b)
                  :settle-amount        (:settle-amount b)}]
      (try
        {:status 200 :body (obligations/settle! id settle)}
        (catch Exception e
          {:status 500 :body {:ok false :error (.getMessage e)}})))
    {:status 400 :body {:ok false :error "Missing or invalid id"}}))

;; ---------------------------------------------------------------------------
;; Auto-rules — §4 list / create / classify
;; ---------------------------------------------------------------------------

(defn get-auto-rules
  "GET /api/v1/treasury/auto-rules → {:auto-rules [...]} (priority ASC, id ASC)."
  [_req]
  {:status 200 :body (autorules/list-rules)})

(defn post-auto-rule
  "POST /api/v1/treasury/auto-rules → {:ok true :id n}. match-field/match-op
   coerced string → keyword."
  [req]
  (let [b    (body-of req)
        rule (cond-> b
               (contains? b :match-field) (update :match-field ->kw)
               (contains? b :match-op)    (update :match-op ->kw))]
    (try
      (let [{:keys [id]} (autorules/create! rule)]
        {:status 200 :body {:ok true :id id}})
      (catch clojure.lang.ExceptionInfo e
        {:status 422 :body {:ok false :error (.getMessage e)}})
      (catch Exception e
        {:status 500 :body {:ok false :error (.getMessage e)}}))))

(defn classify-auto-rules
  "POST /api/v1/treasury/auto-rules/classify → {:ok true :classified N
   :left-uncategorised N :manual-preserved N}. Idempotent; manual preserved."
  [_req]
  (try
    {:status 200 :body (assoc (autorules/classify!) :ok true)}
    (catch Exception e
      {:status 500 :body {:ok false :error (.getMessage e)}})))
