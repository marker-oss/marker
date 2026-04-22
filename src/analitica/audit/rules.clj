(ns analitica.audit.rules
  "Reconciliation rule registry and classification for the audit tool.

   A rule is a plain map with these keys (see specs/002-calculation-audit/data-model.md):
     :rule/id           — unique keyword identifier
     :rule/description  — short human-readable text for reports/help
     :rule/marketplace  — :all or a set #{:wb :ozon :ym}
     :rule/sources      — vector of source keywords the rule compares, e.g. [:raw-finance :agg-finance]
     :rule/severity     — :critical | :informational
     :rule/fn           — (fn [ctx] → [discrepancy ...])
     :rule/classifier   — optional override; defaults to `classify`.

   Rules are registered via `register-rule!` and looked up via `all-rules` /
   `rules-for-marketplace`. The registry is a single atom — calling
   `register-rule!` with an existing `:rule/id` replaces the prior definition."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

(defn register-rule!
  "Register a rule map. Returns the rule-id for chaining in `doseq`/`run!`."
  [rule]
  (let [id (:rule/id rule)]
    (when-not id
      (throw (ex-info "Rule missing :rule/id" {:rule rule})))
    (swap! registry assoc id rule)
    id))

(defn unregister-rule!
  "Remove a rule by id. Primarily for tests that want a clean registry."
  [rule-id]
  (swap! registry dissoc rule-id)
  nil)

(defn clear-registry!
  "Remove all registered rules. Primarily for tests."
  []
  (reset! registry {}))

(defn all-rules
  "Return registered rules as a vector, sorted by :rule/id for deterministic order."
  []
  (->> (vals @registry)
       (sort-by :rule/id)
       vec))

(defn get-rule
  "Look up a rule by id. Returns nil if not registered."
  [rule-id]
  (get @registry rule-id))

(defn rules-for-marketplace
  "Rules applicable to the given marketplace keyword.
   A rule applies when :rule/marketplace is :all OR contains the marketplace."
  [marketplace]
  (->> (all-rules)
       (filter (fn [{mp :rule/marketplace}]
                 (or (= :all mp)
                     (and (set? mp) (contains? mp marketplace)))))
       vec))

;; ---------------------------------------------------------------------------
;; Reconciliation context
;; ---------------------------------------------------------------------------
;;
;; A ReconciliationContext is a plain map — no defrecord — so it serialises
;; cleanly to EDN for snapshot tests and stays easy to extend.
;;
;; Shape (keys match data-model.md §ReconciliationContext):
;;   :ctx/period       {:from "YYYY-MM-DD" :to "YYYY-MM-DD"}
;;   :ctx/marketplace  :wb | :ozon | :ym | :all
;;   :ctx/tolerance    {:rel 0.01 :abs 10.0}
;;   :ctx/bank-data    nil | {:sum N} | {:by-date {date amount} :missing-dates [...]}
;;   :ctx/sku-scope    nil | [article ...]
;;   :ctx/db           next.jdbc datasource

(defn make-context
  "Construct a ReconciliationContext map with validation of required keys."
  [{:keys [period marketplace tolerance bank-data sku-scope db]
    :or   {bank-data nil sku-scope nil}}]
  (when-not (and (map? period) (:from period) (:to period))
    (throw (ex-info "ctx/period must be {:from \"...\" :to \"...\"}"
                    {:period period})))
  (when-not (keyword? marketplace)
    (throw (ex-info "ctx/marketplace must be a keyword" {:marketplace marketplace})))
  (when-not (and (map? tolerance) (:rel tolerance) (:abs tolerance))
    (throw (ex-info "ctx/tolerance must be {:rel N :abs N}" {:tolerance tolerance})))
  {:ctx/period      period
   :ctx/marketplace marketplace
   :ctx/tolerance   tolerance
   :ctx/bank-data   bank-data
   :ctx/sku-scope   sku-scope
   :ctx/db          db})

;; ---------------------------------------------------------------------------
;; Classification
;; ---------------------------------------------------------------------------
;;
;; Given an absolute delta, relative delta, and tolerance thresholds, classify a
;; discrepancy as one of:
;;   :expected     — within tolerance (rounding, documented gap)
;;   :suspicious   — outside tolerance, known rule fired normally
;;   :unclassified — rule explicitly signalled "this input is outside the rule's
;;                    model" (e.g. unknown `operation` value). Callers indicate
;;                    this by passing `:unknown` as `delta-abs` or by calling
;;                    `classify-unclassified` directly.
;;
;; The rule from research.md §R5 is intentionally permissive (OR, not AND):
;; a discrepancy counts as :expected if EITHER threshold is met.

(defn classify
  "Classify a discrepancy by comparing |Δ| against tolerance thresholds.

   Arguments:
     delta-abs — absolute delta, non-negative number (required).
     delta-rel — relative delta, non-negative number (required).
     tolerance — {:abs X :rel Y} where X is ruble-amount and Y is fraction (e.g. 0.01 for 1%).

   Returns one of :expected | :suspicious.

   For :unclassified, call `classify-unclassified` — the three-way branch is
   driven by rule-level logic (unknown operation type, unsupported input),
   not by numerical thresholds."
  [delta-abs delta-rel {:keys [abs rel] :as _tolerance}]
  (when-not (and (number? delta-abs) (number? delta-rel))
    (throw (ex-info "classify requires numeric deltas"
                    {:delta-abs delta-abs :delta-rel delta-rel})))
  (when-not (and (number? abs) (number? rel))
    (throw (ex-info "classify requires :abs and :rel in tolerance"
                    {:tolerance _tolerance})))
  (let [a (Math/abs (double delta-abs))
        r (Math/abs (double delta-rel))]
    (if (or (<= a (double abs))
            (<= r (double rel)))
      :expected
      :suspicious)))

(defn classify-unclassified
  "Marker for discrepancies where the rule can't apply its usual model
   (e.g. an `operation` value not present in the rule's known-operations set).
   Returns the literal keyword :unclassified so discrepancy builders can write
   `(merge disc {:disc/classification (r/classify-unclassified)})`."
  []
  :unclassified)

;; ---------------------------------------------------------------------------
;; Running a rule
;; ---------------------------------------------------------------------------

(defn run-rule
  "Invoke a rule's :rule/fn with the given context and collect its discrepancies.

   The rule fn is expected to return a sequence of discrepancy maps (see
   data-model.md §Discrepancy). This wrapper adds error handling: if the
   rule fn throws, we capture the exception into a single synthetic
   discrepancy marked :unclassified so the overall reconciliation run
   doesn't abort mid-stream on a bug in one rule."
  [rule ctx]
  (try
    (vec ((:rule/fn rule) ctx))
    (catch Throwable t
      [{:disc/rule-id              (:rule/id rule)
        :disc/marketplace          (:ctx/marketplace ctx)
        :disc/period               (:ctx/period ctx)
        :disc/location             {:source-a nil :source-b nil
                                    :field    nil
                                    :article  nil
                                    :operation nil
                                    :row-id   nil}
        :disc/delta                {:a nil :b nil :abs 0 :rel 0 :unit :count}
        :disc/classification       :unclassified
        :disc/classification-reason (str "rule threw: "
                                         (.getName (class t))
                                         " "
                                         (or (.getMessage t) ""))
        :disc/evidence             {:exception-class (.getName (class t))
                                    :exception-msg   (.getMessage t)}}])))

(defn format-rule-summary
  "One-liner description of a rule, for CLI help output."
  [rule]
  (let [mp (:rule/marketplace rule)
        mp-str (if (= :all mp) "all" (str/join "," (map name (sort mp))))]
    (format "  %-30s [%s] %s"
            (name (:rule/id rule))
            mp-str
            (or (:rule/description rule) ""))))
