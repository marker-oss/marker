(ns analitica.domain.treasury.schema
  "Malli schemas for the six treasury ledger entities (spec 019).

   Contracts: specs/019-treasury-ledger/contracts/ledger-entities.edn +
   data-model.md §4. All monetary fields are decimal-as-string (\"0.00\",
   `:treasury/decimal-str` / `:treasury/positive-dec`) — NEVER :double
   (FR-019, R1, §3.D). The decimal-string primitive + the double analytics
   path live side-by-side in util/math.clj with NO bridge between them.

   Cross-field validation (R3, R9, FR-022) for operations:
     • direction=:transfer  ⇒ transfer-account-id ≠ nil AND ≠ account-id
     • direction≠:transfer  ⇒ transfer-account-id = nil
     • currency = \"RUB\"
   These are enforced by `valid-operation?` / `explain-operation` here (Malli
   :fn cross-field), used by domain/treasury/operations.clj on create!/update!."
  (:require [malli.core :as mc]
            [analitica.util.math :as m]))

;; ---------------------------------------------------------------------------
;; Shared primitives (verbatim from ledger-entities.edn :registry). Kept as a
;; local registry map rather than mutating the global default registry so the
;; schemas are self-contained and referentially transparent.
;; ---------------------------------------------------------------------------

(def registry
  "Local Malli registry for treasury shared types (ledger-entities.edn :registry)."
  {:treasury/decimal-str  [:re m/decimal-str-re]
   :treasury/positive-dec [:and [:re m/decimal-str-re]
                           [:fn {:error/message "amount must be ≥ 0.00"}
                            (fn [s] (not (clojure.string/starts-with? s "-")))]]
   :treasury/currency     [:= "RUB"]
   :treasury/iso-date     [:re #"^\d{4}-\d{2}-\d{2}$"]
   :treasury/marketplace  [:enum :wb :ozon :ym]})

;; ---------------------------------------------------------------------------
;; 1. Account
;; ---------------------------------------------------------------------------

(def Account
  [:map
   [:id          {:optional true} :int]
   [:name        :string]
   [:marketplace {:optional true} [:maybe :treasury/marketplace]]
   [:kind        [:enum :bank :wallet :mp-settlement]]
   [:currency    :treasury/currency]
   [:archived-at {:optional true} [:maybe :string]]
   [:created-at  {:optional true} :string]])

;; ---------------------------------------------------------------------------
;; 2. Counterparty
;; ---------------------------------------------------------------------------

(def Counterparty
  [:map
   [:id          {:optional true} :int]
   [:name        :string]
   [:kind        {:optional true} [:enum :supplier :contractor :marketplace :tax-authority :own]]
   [:archived-at {:optional true} [:maybe :string]]
   [:created-at  {:optional true} :string]])

;; ---------------------------------------------------------------------------
;; 4. Operation — cross-field validation via top-level :and [:fn …]
;; ---------------------------------------------------------------------------

(def OperationMap
  [:map
   [:id                  {:optional true} :int]
   [:op-date             :treasury/iso-date]
   [:amount              :treasury/positive-dec]
   [:currency            :treasury/currency]
   [:direction           [:enum :income :expense :transfer]]
   [:account-id          :int]
   [:transfer-account-id {:optional true} [:maybe :int]]
   [:counterparty-id     {:optional true} [:maybe :int]]
   [:category            {:optional true} [:maybe :string]]
   [:category-source     {:optional true} [:maybe [:enum :manual :rule :seed]]]
   [:applied-rule-id     {:optional true} [:maybe :int]]
   [:confirmed           :boolean]
   [:regular             :boolean]
   [:description         {:optional true} [:maybe :string]]
   [:source              {:optional true} [:maybe :string]]
   [:created-at          {:optional true} :string]])

(defn- transfer-cross-field-ok?
  "R3 cross-field rule: transfer ⇒ transfer-account-id present and distinct
   from account-id; non-transfer ⇒ transfer-account-id absent."
  [{:keys [direction account-id transfer-account-id]}]
  (if (= :transfer direction)
    (and (some? transfer-account-id) (not= transfer-account-id account-id))
    (nil? transfer-account-id)))

(def Operation
  [:and OperationMap
   [:fn {:error/message "transfer requires transfer-account-id ≠ nil and ≠ account-id; non-transfer forbids transfer-account-id"}
    transfer-cross-field-ok?]])

;; ---------------------------------------------------------------------------
;; 5. Auto-rule
;; ---------------------------------------------------------------------------

(def AutoRule
  [:map
   [:id          {:optional true} :int]
   [:match-field [:enum :counterparty :account :description]]
   [:match-op    [:enum :equals :contains]]
   [:match-value :string]
   [:category    :string]
   [:priority    :int]
   [:enabled     :boolean]
   [:created-at  {:optional true} :string]])

;; ---------------------------------------------------------------------------
;; 6. Obligation
;; ---------------------------------------------------------------------------

(def Obligation
  [:map
   [:id                   {:optional true} :int]
   [:direction            [:enum :receivable :payable]]
   [:amount               :treasury/positive-dec]
   [:remaining-amount     :treasury/positive-dec]
   [:currency             :treasury/currency]
   [:counterparty-id      {:optional true} [:maybe :int]]
   [:issue-date           {:optional true} [:maybe :treasury/iso-date]]
   [:due-date             :treasury/iso-date]
   [:settled-operation-id {:optional true} [:maybe :int]]
   [:confirmed            :boolean]
   [:created-at           {:optional true} :string]])

;; ---------------------------------------------------------------------------
;; Validation helpers — resolve schemas against the local registry.
;; ---------------------------------------------------------------------------

(defn- opts [] {:registry (merge (mc/default-schemas) registry)})

(defn valid?
  "True iff `value` conforms to `schema` (resolved against the treasury registry)."
  [schema value]
  (mc/validate schema value (opts)))

(defn explain
  "Malli explain for `value` against `schema` (registry-resolved)."
  [schema value]
  (mc/explain schema value (opts)))

(defn validate!
  "Throw ex-info if `value` does not conform to `schema`; else return `value`."
  [schema value label]
  (when-not (valid? schema value)
    (throw (ex-info (str "Invalid " label)
                    {:value value :errors (explain schema value)})))
  value)
