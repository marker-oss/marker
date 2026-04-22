(ns analitica.audit.core
  "Orchestration for `audit reconcile`.

   Builds a ReconciliationContext from CLI options, filters registered rules,
   runs them, aggregates discrepancies into a ReconciliationReport, renders
   stdout, and (optionally) persists EDN.

   Returns a map {:report ReconciliationReport :exit-code int}:
     - exit-code follows contracts/cli-audit.md §Exit codes summary
     - report is always built (even on empty period — FR-008)."
  (:require [analitica.audit.rules :as rules]
            [analitica.audit.report :as report]
            [analitica.audit.rule-impl :as rule-impl]
            [analitica.db :as db]
            [clojure.string :as str])
  (:import [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Source availability detection
;; ---------------------------------------------------------------------------

(defn- data-present?
  "Return true if at least one row exists in `table` for the given period
   and marketplace (`:all` = no MP filter). Defensive — any SQL failure
   maps to false so a missing table does not blow up the pipeline."
  [table marketplace {:keys [from to]}]
  (try
    (let [date-col (case table
                     :finance "date_from"
                     (:sales :orders) "date")
          sql (if (= :all marketplace)
                (str "SELECT COUNT(*) AS c FROM " (name table)
                     " WHERE " date-col " <= ? AND "
                     (case table
                       :finance "date_to"
                       (:sales :orders) "date")
                     " >= ?")
                (str "SELECT COUNT(*) AS c FROM " (name table)
                     " WHERE " date-col " <= ? AND "
                     (case table
                       :finance "date_to"
                       (:sales :orders) "date")
                     " >= ? AND marketplace = ?"))
          params (if (= :all marketplace)
                   [to from]
                   [to from (name marketplace)])
          result (db/query (into [sql] params))]
      (pos? (long (or (:c (first result)) 0))))
    (catch Throwable _ false)))

(defn- compute-sources-available
  "Probe the DB / ctx to determine which sources are populated."
  [marketplace period ctx]
  (let [fin? (data-present? :finance marketplace period)
        sal? (data-present? :sales marketplace period)
        ord? (data-present? :orders marketplace period)
        bank? (some? (:ctx/bank-data ctx))]
    (cond-> #{}
      fin?  (conj :raw-finance :agg-finance)
      sal?  (conj :sales)
      ord?  (conj :orders)
      bank? (conj :bank-input))))

;; ---------------------------------------------------------------------------
;; Rule filtering
;; ---------------------------------------------------------------------------

(defn- select-rules
  "Pick the rule set to run:
     - filter to those applicable to `marketplace`
     - if `rule-ids` (seq of :kw) is given, further restrict to that set."
  [marketplace rule-ids]
  (let [applicable (rules/rules-for-marketplace marketplace)]
    (if (seq rule-ids)
      (let [rid-set (set rule-ids)]
        (filter #(contains? rid-set (:rule/id %)) applicable))
      applicable)))

;; ---------------------------------------------------------------------------
;; Exit code
;; ---------------------------------------------------------------------------

(defn- exit-code-for
  "Compute exit code from summary counts per contracts/cli-audit.md.
     0 — clean, no suspicious, no unclassified
     1 — suspicious > 0
     2 — unclassified > 0 (takes precedence over 1; new data type signals
         that developers need to update the known-operations set)."
  [{:keys [counts]}]
  (cond
    (pos? (:unclassified counts)) 2
    (pos? (:suspicious counts))   1
    :else                         0))

;; ---------------------------------------------------------------------------
;; Public entrypoint
;; ---------------------------------------------------------------------------

(defn- captured-at-now []
  (str (Instant/now)))

(defn- ensure-rules-registered!
  "If the registry is empty, load the default rule set. Callers can override
   by populating the registry themselves before calling `run-reconcile!`."
  []
  (when (empty? (rules/all-rules))
    (rule-impl/register-all!)))

(defn run-reconcile!
  "Run a reconciliation pass and return {:report R :exit-code N}.

   Required keys:
     :marketplace — :wb | :ozon | :ym | :all
     :period      — {:from \"YYYY-MM-DD\" :to \"YYYY-MM-DD\"}
     :tolerance   — {:rel N :abs N}

   Optional keys:
     :bank-input  — map with :sum (and optionally :by-date, :missing-dates)
                    forwarded into ctx/bank-data
     :rules       — seq of rule-ids to restrict execution to
     :top-n       — how many causes to include in summary (default 10)
     :sku-scope   — vec of articles (KPI-measurement scope; unused by MVP rules)"
  [{:keys [marketplace period tolerance bank-input rules top-n sku-scope]
    :or   {top-n 10}}]
  (ensure-rules-registered!)
  (let [ctx (rules/make-context
              {:period      period
               :marketplace marketplace
               :tolerance   tolerance
               :bank-data   bank-input
               :sku-scope   sku-scope
               :db          (db/ds)})
        sources (compute-sources-available marketplace period ctx)
        selected-rules (select-rules marketplace rules)
        ;; Run every selected rule; flatten to a single discrepancy seq.
        discrepancies (vec (mapcat #(rules/run-rule % ctx) selected-rules))
        rule-ids (mapv :rule/id selected-rules)
        report (report/make-report
                 {:marketplace        marketplace
                  :period             period
                  :rules-applied      rule-ids
                  :sources-available  sources
                  :discrepancies      discrepancies
                  :tolerance-snapshot tolerance
                  :top-n              top-n
                  :captured-at        (captured-at-now)})
        exit-code (exit-code-for (:report/summary report))]
    {:report     report
     :exit-code  exit-code
     :rendered   (report/render-stdout report top-n)}))
