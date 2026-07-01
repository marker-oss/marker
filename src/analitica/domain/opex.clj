(ns analitica.domain.opex
  "Management-basis OPEX layer — manual and auto-rule operational expenses.

   This namespace owns:
     • opex_rows table (spec 015, data-model.md §1.2)
     • opex_auto_rules table (data-model.md §1.2b, FR-020/FR-021)
     • pure aggregation: sum-by-category

   ## Contracts (LOCKED spec 015 §0 / contracts/tax-opex-api.md)

   sum-by-category(from, to, mp):
     {:total double :by-category {string → double} :rows [...]}
     Rows are attributed by period_month (YYYY-MM); rows outside [from, to]
     window do NOT appear (event-date discipline — US3-AS5, data-model §1.2).

   Allocation rule R11 (data-model §1.2, contracts §4):
     marketplace nil  (blended) → ALL rows (NULL-mp + all tagged-mp rows)
     marketplace :wb/:ozon/:ym  → only rows tagged for that MP (NULL excluded)
     NULL-marketplace rows are unallocated — they go to blended only, never
     double-counted in per-MP queries.

   opex-rows.amount: strictly > 0 (Malli schema enforces [:and :double [:> 0]]).
   opex-rows.source: :manual (default) | :auto (set by materialize-rules!).
   opex-rows.rule_id: non-nil ⟺ source=:auto (FR-020)."
  (:require [analitica.db :as db]
            [analitica.util.math :as math]
            [malli.core :as m]
            [next.jdbc :as jdbc]))

;; ---------------------------------------------------------------------------
;; Expense category vocabulary (data-model.md §1.3, R6)
;; ---------------------------------------------------------------------------

(def opex-categories
  "Hint-list for UI; stored as free text in opex_rows.category."
  ["salary" "rent" "services" "marketing" "other"])

;; ---------------------------------------------------------------------------
;; Malli schemas (data-model.md §4)
;; ---------------------------------------------------------------------------

(def OpexRow
  [:map
   [:id {:optional true} int?]
   [:period-month [:re #"^\d{4}-(0[1-9]|1[0-2])$"]]
   [:category :string]
   [:amount [:and :double [:> 0]]]
   [:marketplace {:optional true} [:maybe [:enum :wb :ozon :ym]]]
   [:note {:optional true} [:maybe :string]]
   [:source {:optional true} [:enum :manual :auto]]
   [:rule-id {:optional true} [:maybe int?]]])

(def Cadence [:enum :monthly])

(def OpexAutoRule
  [:map
   [:id {:optional true} int?]
   [:category :string]
   [:amount [:and :double [:> 0]]]
   [:marketplace {:optional true} [:maybe [:enum :wb :ozon :ym]]]
   [:cadence {:optional true} Cadence]
   [:effective-from [:re #"^\d{4}-(0[1-9]|1[0-2])$"]]
   [:effective-to {:optional true} [:maybe [:re #"^\d{4}-(0[1-9]|1[0-2])$"]]]
   [:note {:optional true} [:maybe :string]]])

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private last-rowid-key
  "SQLite returns the last inserted rowid under this key when :return-keys true."
  (keyword "last_insert_rowid()"))

(defn- period-month-from-date
  "Extract YYYY-MM from a YYYY-MM-DD date string.
   If already YYYY-MM (10-char), take the first 7 chars."
  [date-str]
  (subs date-str 0 7))

(defn- row->map
  "Coerce a JDBC kebab-map row to public OpexRow shape.
   Converts marketplace text to keyword; source text to keyword."
  [row]
  (cond-> row
    (:marketplace row) (update :marketplace keyword)
    (:source row)      (update :source keyword)))

;; ---------------------------------------------------------------------------
;; Store — opex_rows (T027)
;; ---------------------------------------------------------------------------

(defn save-row!
  "Validate and INSERT an OpexRow into opex_rows. Returns {:id n}.
   Throws ex-info on Malli validation failure."
  [{:keys [period-month category amount marketplace note source rule-id] :as row}]
  (when-not (m/validate OpexRow row)
    (throw (ex-info "Invalid OpexRow"
                    {:row row :errors (m/explain OpexRow row)})))
  (let [mp-str  (when marketplace (name marketplace))
        src-str (if source (name source) "manual")
        result  (jdbc/execute-one!
                  (db/ds)
                  ["INSERT INTO opex_rows
                      (period_month, category, amount, marketplace, note, source, rule_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)"
                   period-month category (double amount)
                   mp-str note src-str rule-id]
                  {:return-keys true})]
    {:id (get result last-rowid-key)}))

(defn fetch-rows
  "Return all opex_rows for period-month (YYYY-MM) as seq of maps."
  [period-month]
  (->> (db/query
         ["SELECT id, period_month, category, amount, marketplace, note,
                  source, rule_id, created_at
             FROM opex_rows
            WHERE period_month = ?"
          period-month])
       (map row->map)))

(defn delete-row!
  "Remove opex_rows row by id. No-op when absent."
  [id]
  (db/execute! ["DELETE FROM opex_rows WHERE id = ?" id]))

;; ---------------------------------------------------------------------------
;; Aggregation — sum-by-category (T028)
;; ---------------------------------------------------------------------------

(defn sum-by-category
  "Aggregate opex_rows for date window [from, to] and optional marketplace.

   from / to — YYYY-MM-DD or YYYY-MM strings; period_month is compared as
               YYYY-MM (first 7 chars of the window bounds).

   marketplace nil  → blended: ALL rows in window (NULL-mp + all tagged)
   marketplace :wb/:ozon/:ym → per-MP: only rows tagged for that MP (NULL excluded)

   Returns {:total double :by-category {category → double} :rows [...]}"
  [from to marketplace]
  (let [from-month (period-month-from-date from)
        to-month   (period-month-from-date to)
        rows       (if marketplace
                     ;; Per-MP query: only tagged rows for this marketplace
                     (db/query
                       ["SELECT id, period_month, category, amount, marketplace,
                                note, source, rule_id
                           FROM opex_rows
                          WHERE period_month >= ? AND period_month <= ?
                            AND marketplace = ?"
                        from-month to-month (name marketplace)])
                     ;; Blended query: ALL rows in window
                     (db/query
                       ["SELECT id, period_month, category, amount, marketplace,
                                note, source, rule_id
                           FROM opex_rows
                          WHERE period_month >= ? AND period_month <= ?"
                        from-month to-month]))
        rows       (map row->map rows)
        by-cat     (->> rows
                        (group-by :category)
                        (reduce-kv (fn [acc cat cat-rows]
                                     (assoc acc cat
                                            (math/round2
                                             (reduce + 0.0 (map :amount cat-rows)))))
                                   {}))
        total      (math/round2 (reduce + 0.0 (map :amount rows)))]
    {:total       total
     :by-category by-cat
     :rows        (vec rows)}))

;; ---------------------------------------------------------------------------
;; Store — opex_auto_rules (T056 / US5 scaffolded here)
;; ---------------------------------------------------------------------------

(defn save-rule!
  "Validate and INSERT an OpexAutoRule into opex_auto_rules. Returns {:id n}.
   Throws ex-info on Malli validation failure."
  [{:keys [category amount marketplace cadence effective-from effective-to note] :as rule}]
  (when-not (m/validate OpexAutoRule rule)
    (throw (ex-info "Invalid OpexAutoRule"
                    {:rule rule :errors (m/explain OpexAutoRule rule)})))
  (let [mp-str  (when marketplace (name marketplace))
        cad-str (if cadence (name cadence) "monthly")
        result  (jdbc/execute-one!
                  (db/ds)
                  ["INSERT INTO opex_auto_rules
                      (category, amount, marketplace, cadence, effective_from, effective_to, note)
                    VALUES (?, ?, ?, ?, ?, ?, ?)"
                   category (double amount) mp-str cad-str effective-from effective-to note]
                  {:return-keys true})]
    {:id (get result last-rowid-key)}))

(defn fetch-rules
  "Return all opex_auto_rules as seq of maps."
  []
  (->> (db/query
         ["SELECT id, category, amount, marketplace, cadence,
                  effective_from, effective_to, note, created_at
             FROM opex_auto_rules"])
       (map (fn [r]
              (cond-> r
                (:marketplace r)  (update :marketplace keyword)
                (:cadence r)      (update :cadence keyword))))))

(defn delete-rule!
  "Remove opex_auto_rules row by id. No-op when absent."
  [id]
  (db/execute! ["DELETE FROM opex_auto_rules WHERE id = ?" id]))

(defn materialize-rules!
  "For each active opex_auto_rules row covering period-month (YYYY-MM),
   INSERT OR IGNORE an opex_rows row with source='auto', rule_id set.
   Idempotent: the partial UNIQUE index idx_opex_rule_period on
   (rule_id, period_month) WHERE rule_id IS NOT NULL causes INSERT OR IGNORE
   to skip duplicate rows silently — no exception, no double-count.
   Override-safe: if the auto-row was already inserted (same rule_id+period),
   the second run produces 0 update-count, so a manually-overridden row is never
   clobbered."
  [period-month]
  (let [rules (db/query
                ["SELECT id, category, amount, marketplace, cadence,
                         effective_from, effective_to
                    FROM opex_auto_rules
                   WHERE effective_from <= ?
                     AND (effective_to IS NULL OR effective_to >= ?)"
                 period-month period-month])
        counts (doall
                (for [rule rules]
                  (let [res (db/execute!
                              ["INSERT OR IGNORE INTO opex_rows
                                  (period_month, category, amount, marketplace,
                                   source, rule_id)
                                VALUES (?, ?, ?, ?, 'auto', ?)"
                               period-month
                               (:category rule)
                               (double (:amount rule))
                               (:marketplace rule)
                               (:id rule)])]
                    (-> res first :next.jdbc/update-count (or 0)))))
        materialized (count (filter pos? counts))
        skipped      (- (count counts) materialized)]
    {:materialized materialized :skipped skipped}))
