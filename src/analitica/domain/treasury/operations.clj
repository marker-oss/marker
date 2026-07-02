(ns analitica.domain.treasury.operations
  "US2 — treasury registry CRUD: operations, accounts, counterparties.

   Contracts: cashflow-api.edn §2-§4, ledger-entities.edn §1/§2/§4.
   Money is decimal-as-string (\"0.00\", FR-019); all money arithmetic goes
   through util/math decimal helpers, never (reduce + …). Validation +
   cross-field rules (R3/R9/FR-022) live in schema.clj. Balance is DERIVED
   from ledger.clj (never a stored column). Soft-archive on delete when an
   account/counterparty has referencing operations (R11/FR-026)."
  (:require [clojure.string :as str]
            [analitica.db :as db]
            [analitica.util.math :as m]
            [analitica.domain.treasury.schema :as schema]
            [analitica.domain.treasury.ledger :as ledger]))

;; ---------------------------------------------------------------------------
;; Coercion helpers (DB strings/ints ↔ domain keywords/booleans).
;; ---------------------------------------------------------------------------

(defn- now-ts [] (str (java.time.Instant/now)))

(defn- ->kw [s] (when (and s (not (str/blank? (str s)))) (keyword s)))
(defn- ->bool [x] (= 1 (long x)))

(defn- row->account [r]
  {:id          (:id r)
   :name        (:name r)
   :marketplace (->kw (:marketplace r))
   :kind        (->kw (:kind r))
   :currency    (:currency r)
   :archived-at (:archived-at r)
   :created-at  (:created-at r)})

(defn- row->counterparty [r]
  {:id              (:id r)
   :name            (:name r)
   :kind            (->kw (:kind r))
   :archived-at     (:archived-at r)
   :operation-count (or (:operation-count r) 0)
   :created-at      (:created-at r)})

(defn- row->operation [r]
  {:id                  (:id r)
   :op-date             (:op-date r)
   :amount              (:amount r)             ; raw "0.00"-string (FR-019)
   :currency            (:currency r)
   :direction           (->kw (:direction r))
   :account-id          (:account-id r)
   :transfer-account-id (:transfer-account-id r)
   :counterparty-id     (:counterparty-id r)
   :category            (:category r)
   :category-source     (->kw (:category-source r))
   :applied-rule-id     (:applied-rule-id r)
   :confirmed           (->bool (:confirmed r))
   :regular             (->bool (:regular r))
   :description         (:description r)
   :source              (:source r)
   :created-at          (:created-at r)})

;; ---------------------------------------------------------------------------
;; Accounts
;; ---------------------------------------------------------------------------

(defn create-account!
  "Validate + insert an account. Returns {:id n}. Currency defaults to RUB."
  [{:keys [name marketplace kind currency] :or {currency "RUB"}}]
  (let [acct {:name name :marketplace marketplace :kind kind :currency currency}]
    (schema/validate! schema/Account acct "Account")
    {:id (db/treasury-insert-account!
           {:name        name
            :marketplace (when marketplace (clojure.core/name marketplace))
            :kind        (clojure.core/name kind)
            :currency    currency
            :created-at  (now-ts)})}))

(defn list-accounts
  "List accounts with DERIVED balances (dsum of confirmed operations, FR-010).
   Options: :include-archived (default false). Returns
   {:accounts [{… :balance \"0.00\"}] :total-balance \"0.00\"}."
  ([] (list-accounts {}))
  ([{:keys [include-archived]}]
   (let [rows (map row->account (db/treasury-list-accounts (boolean include-archived)))
         bal  (ledger/balances)
         accts (mapv (fn [a]
                       (assoc a :balance
                              (m/d->str (ledger/account-balance bal (:id a)))))
                     rows)]
     {:accounts       accts
      :total-balance  (m/d->str (m/dsum (map #(m/d (:balance %)) accts)))})))

(defn delete-account!
  "Delete account `id`. If it has referencing operations, soft-archive
   (set archived_at) instead of hard-deleting (R11/FR-026). Returns
   {:ok true :archived bool}."
  [id]
  (if (pos? (long (db/treasury-account-op-count id)))
    (do (db/treasury-archive-account! id (now-ts))
        {:ok true :archived true})
    (do (db/treasury-delete-account! id)
        {:ok true :archived false})))

;; ---------------------------------------------------------------------------
;; Counterparties
;; ---------------------------------------------------------------------------

(defn create-counterparty!
  "Validate + insert a counterparty. Returns {:id n}."
  [{:keys [name kind]}]
  (let [cp (cond-> {:name name} kind (assoc :kind kind))]
    (schema/validate! schema/Counterparty cp "Counterparty")
    {:id (db/treasury-insert-counterparty!
           {:name name :kind (when kind (clojure.core/name kind)) :created-at (now-ts)})}))

(defn list-counterparties
  "List counterparties with :operation-count linkage (US2 AC5).
   Options: :include-archived (default false)."
  ([] (list-counterparties {}))
  ([{:keys [include-archived]}]
   {:counterparties
    (mapv row->counterparty
          (db/treasury-list-counterparties (boolean include-archived)))}))

(defn delete-counterparty!
  "Delete counterparty `id`; soft-archive if it has referencing operations."
  [id]
  (if (pos? (long (db/treasury-counterparty-op-count id)))
    (do (db/treasury-archive-counterparty! id (now-ts))
        {:ok true :archived true})
    (do (db/treasury-delete-counterparty! id)
        {:ok true :archived false})))

;; ---------------------------------------------------------------------------
;; Operations
;; ---------------------------------------------------------------------------

(defn create!
  "Validate + insert an operation. `op` uses domain keys (kebab, keyword
   enums, \"0.00\"-string amount). Cross-field + RUB-only validation via
   schema.clj (throws on failure). Returns {:id n}."
  [{:keys [op-date amount currency direction account-id transfer-account-id
           counterparty-id category category-source applied-rule-id
           confirmed regular description source]
    :or   {currency "RUB"}
    :as   op}]
  (let [op* (-> op
                (assoc :currency currency)
                ;; drop nils so :maybe/optional keys validate cleanly
                (->> (remove (comp nil? val)) (into {})))]
    (schema/validate! schema/Operation op* "Operation")
    {:id (db/treasury-insert-operation!
           {:op-date             op-date
            :amount              amount
            :currency            currency
            :direction           (name direction)
            :account-id          account-id
            :transfer-account-id transfer-account-id
            :counterparty-id     counterparty-id
            :category            category
            :category-source     (when category-source (name category-source))
            :applied-rule-id     applied-rule-id
            :confirmed           (if confirmed 1 0)
            :regular             (if regular 1 0)
            :description         description
            :source              (or source "manual")
            :created-at          (now-ts)})}))

(defn get-op
  "Fetch a single operation by id (domain shape) or nil."
  [id]
  (some-> (db/treasury-get-operation id) row->operation))

(defn update!
  "Update an operation. Supported keys: :category, :category-source,
   :confirmed, :regular, :description, :counterparty-id. A manual category
   edit should pass :category-source :manual (manual-override-wins, R4).
   Returns {:ok true}."
  [id {:keys [category category-source confirmed regular description counterparty-id]
       :as   patch}]
  (let [set-map (cond-> {}
                  (contains? patch :category)        (assoc :category category)
                  (contains? patch :category-source) (assoc :category_source
                                                            (when category-source (name category-source)))
                  (contains? patch :confirmed)       (assoc :confirmed (if confirmed 1 0))
                  (contains? patch :regular)         (assoc :regular (if regular 1 0))
                  (contains? patch :description)     (assoc :description description)
                  (contains? patch :counterparty-id) (assoc :counterparty_id counterparty-id))]
    (db/treasury-update-operation! id set-map)
    {:ok true}))

;; ---------------------------------------------------------------------------
;; Registry listing — filters, pagination, summary
;; ---------------------------------------------------------------------------

(defn- filter->where
  "Build a (where-fragment, params) pair from a filter map. Empty fragment
   when no filters."
  [{:keys [from to account-id counterparty-id category direction
           planned confirmed regular]}]
  (let [clauses (cond-> []
                  from            (conj ["op_date >= ?" from])
                  to              (conj ["op_date <= ?" to])
                  account-id      (conj ["account_id = ?" account-id])
                  counterparty-id (conj ["counterparty_id = ?" counterparty-id])
                  category        (conj ["category = ?" category])
                  direction       (conj ["direction = ?" (name direction)])
                  ;; planned = confirmed=false; explicit :confirmed wins if both given
                  (some? planned) (conj ["confirmed = ?" (if planned 0 1)])
                  (some? confirmed) (conj ["confirmed = ?" (if confirmed 1 0)])
                  (some? regular) (conj ["regular = ?" (if regular 1 0)]))]
    [(str/join " AND " (map first clauses))
     (mapv second clauses)]))

(defn- summarise
  "Compute the summary row for a set of (domain-shaped) operations. Transfers
   are EXCLUDED from income/expense/balance (R3, SC-003). Expense is returned
   with sign preserved (negative). planned-count = |confirmed=false|."
  [ops]
  (let [income  (m/dsum (map #(m/d (:amount %))
                             (filter #(= :income (:direction %)) ops)))
        expense (m/dsum (map #(m/d (:amount %))
                             (filter #(= :expense (:direction %)) ops)))]
    {:total-income  (m/d->str income)
     :total-expense (m/d->str (m/d- (m/d "0.00") expense))  ; negative
     :balance       (m/d->str (m/d- income expense))
     :planned-count (count (remove :confirmed ops))}))

(defn list-ops
  "List operations for a filter with pagination + summary.
   Filter keys (all optional): :from :to :account-id :counterparty-id
   :category :direction :planned :confirmed :regular :page :page-size.
   Returns {:operations [...] :summary {...} :page :page-size :total}.
   Summary is over the FULL filtered set (not just the page)."
  [{:keys [page page-size] :or {page 1 page-size 50} :as flt}]
  (let [[where params] (filter->where flt)
        all  (mapv row->operation (db/treasury-query-operations where params))
        total (count all)
        pagev (->> all
                   (drop (* (dec page) page-size))
                   (take page-size)
                   vec)]
    {:operations pagev
     :summary    (summarise all)
     :page       page
     :page-size  page-size
     :total      total}))
