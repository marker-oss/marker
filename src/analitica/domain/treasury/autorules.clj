(ns analitica.domain.treasury.autorules
  "US3 — auto-rules classification (spec 019, T040).

   Contracts: obligations-api.edn §4, ledger-entities.edn §5.
   SC-004: idempotent + manual-override-wins + deterministic precedence.

   Classifier contract (R4, SC-004):
     • processes ONLY operations with :category-source ≠ :manual
       (manual-override-wins, FR-012)
     • multiple matching rules → exactly one, chosen by (priority ASC, id ASC)
       (deterministic, no double-classify); rules already arrive in this order
       from db/treasury-list-auto-rules.
     • assigns :category + :category-source :rule + :applied-rule-id
     • re-run is idempotent: :rule-ops are re-evaluated, :manual ops untouched
     • disabled rules (enabled=0) are never applied"
  (:require [clojure.string :as str]
            [analitica.db :as db]
            [analitica.domain.treasury.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Coercion helpers
;; ---------------------------------------------------------------------------

(defn- now-ts [] (str (java.time.Instant/now)))

(defn- ->bool [x] (= 1 (long x)))
(defn- ->kw   [s] (when (and s (not (str/blank? (str s)))) (keyword s)))

(defn- row->rule [r]
  {:id          (:id r)
   :match-field (->kw (:match-field r))
   :match-op    (->kw (:match-op r))
   :match-value (:match-value r)
   :category    (:category r)
   :priority    (:priority r)
   :enabled     (->bool (:enabled r))
   :created-at  (:created-at r)})

;; ---------------------------------------------------------------------------
;; CRUD
;; ---------------------------------------------------------------------------

(defn create!
  "Validate + insert an auto-rule. Returns {:id n}.
   Keys: :match-field :match-op :match-value :category :priority :enabled."
  [{:keys [match-field match-op match-value category priority enabled]
    :or   {enabled true priority 100}}]
  (schema/validate!
    schema/AutoRule
    {:match-field match-field :match-op match-op :match-value match-value
     :category category :priority priority :enabled enabled}
    "AutoRule")
  {:id (db/treasury-insert-auto-rule!
         {:match-field  (name match-field)
          :match-op     (name match-op)
          :match-value  match-value
          :category     category
          :priority     priority
          :enabled      enabled
          :created-at   (now-ts)})})

(defn list-rules
  "Return all auto-rules, ordered by (priority ASC, id ASC)."
  []
  {:auto-rules (mapv row->rule (db/treasury-list-auto-rules))})

;; ---------------------------------------------------------------------------
;; Classifier helpers
;; ---------------------------------------------------------------------------

(defn- counterparty-names-map
  "Build id → name map for counterparties (for :counterparty match-field)."
  []
  (let [rows (db/query ["SELECT id, name FROM treasury_counterparties"])]
    (into {} (map (fn [r] [(:id r) (:name r)]) rows))))

(defn- account-names-map
  "Build id → name map for accounts (for :account match-field)."
  []
  (let [rows (db/query ["SELECT id, name FROM treasury_accounts"])]
    (into {} (map (fn [r] [(:id r) (:name r)]) rows))))

(defn- rule-matches?
  "True iff enabled `rule` matches `op`.
   `op` is a raw DB row (kebab-ified by next.jdbc).
   `cp-names` / `acc-names` are pre-built lookup maps."
  [rule op cp-names acc-names]
  (when (:enabled rule)
    (let [{:keys [match-field match-op match-value]} rule
          ;; raw DB rows use kebab column names from next.jdbc's as-unqualified-kebab-maps
          field-val (case match-field
                      :counterparty (get cp-names (:counterparty-id op))
                      :account      (get acc-names (:account-id op))
                      :description  (:description op)
                      nil)]
      (when (and field-val (string? field-val))
        (case match-op
          :equals   (= field-val match-value)
          :contains (str/includes? field-val match-value)
          false)))))

(defn- find-winning-rule
  "First rule (from the precedence-ordered seq) that matches `op`."
  [rules op cp-names acc-names]
  (first (filter #(rule-matches? % op cp-names acc-names) rules)))

;; ---------------------------------------------------------------------------
;; classify! — idempotent bulk classifier
;; ---------------------------------------------------------------------------

(defn classify!
  "Run classification over all operations where :category-source ≠ :manual.
   Returns {:classified n :left-uncategorised n :manual-preserved n}.

   Idempotent: :rule-ops are RE-evaluated on every call (rules may have changed).
   :manual ops are NEVER touched (manual-override-wins, FR-012, SC-004)."
  []
  (let [rules     (mapv row->rule (db/treasury-list-auto-rules))
        ;; Ops eligible for classification: all except category_source='manual'
        ;; Includes: nil (never classified), 'rule' (re-evaluate), 'seed' (re-evaluate)
        all-ops   (db/treasury-query-operations
                    "category_source IS NULL OR category_source != 'manual'" [])
        cp-names  (counterparty-names-map)
        acc-names (account-names-map)
        ;; Count manual-preserved from the other side
        manual-cnt (count (db/treasury-query-operations "category_source = 'manual'" []))
        classified  (atom 0)
        left-uncat  (atom 0)]
    (doseq [raw-op all-ops]
      (let [winning (find-winning-rule rules raw-op cp-names acc-names)]
        (if winning
          (do
            (db/treasury-update-operation!
              (:id raw-op)
              {:category        (:category winning)
               :category_source "rule"
               :applied_rule_id (:id winning)})
            (swap! classified inc))
          (do
            ;; If this op previously had a rule-assigned category but the rule
            ;; no longer matches (rule was deleted/changed), clear the stale assignment.
            (when (= "rule" (:category-source raw-op))
              (db/treasury-update-operation!
                (:id raw-op)
                {:category        nil
                 :category_source nil
                 :applied_rule_id nil}))
            (swap! left-uncat inc)))))
    {:classified         @classified
     :left-uncategorised @left-uncat
     :manual-preserved   manual-cnt}))
