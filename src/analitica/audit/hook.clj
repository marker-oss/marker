(ns analitica.audit.hook
  "Post-materialize audit hook (E-6, 2026-04-28).

   Replaces the original cron-based plan: instead of a daily scheduled run,
   the audit fires immediately after every user-triggered finance
   materialize. Catches data-quality and transform issues at the moment
   the seller has the freshest context, with no external scheduler needed.

   Triggers:
     - `analitica.cli/handle-materialize` for `:finance` / `:all` targets

   Skips silently when:
     - entity-type ∉ #{:finance :all} (other targets don't feed audit rules)
     - period is missing or malformed

   Failure mode: any error from the reconcile pass is caught and logged as
   a warning. The hook NEVER throws — a broken audit must not block a
   successful materialize."
  (:require [analitica.audit.core :as audit]))

(def ^:private default-tolerance
  "Mirror the CLI default. Per-rule overrides still apply via :rule/tolerance."
  {:abs 100.0 :rel 0.01})

(defn- audit-relevant?
  "Audit rules read from `finance`, so they only carry signal when finance
   was just rebuilt (`:finance`) or in the all-tables sweep (`:all`)."
  [entity-type]
  (contains? #{:finance :all} entity-type))

(defn- normalize-period
  "Accept {:from :to} map or [from to] 2-vector; return canonical map or nil
   for anything else (keywords like :last-30-days, missing values, etc.).
   Web sync layer commonly passes 2-vectors; CLI passes maps."
  [period]
  (cond
    (and (map? period) (:from period) (:to period))
    {:from (str (:from period)) :to (str (:to period))}

    (and (vector? period) (= 2 (count period)) (every? some? period))
    {:from (str (first period)) :to (str (second period))}))

(defn- valid-period? [period]
  (some? (normalize-period period)))

(defn- print-digest [marketplace period report]
  (let [counts  (-> report :report/summary :counts)
        discs   (:report/discrepancies report)
        flagged (filter #(#{:suspicious :unclassified} (:disc/classification %)) discs)]
    (println)
    (println "──────────────────────────────────────────────────────────────")
    (println (format "  Audit (post-materialize)  %s  %s → %s"
                     (name marketplace) (:from period) (:to period)))
    (println "──────────────────────────────────────────────────────────────")
    (println (format "  expected:     %5d" (or (:expected counts) 0)))
    (println (format "  suspicious:   %5d" (or (:suspicious counts) 0)))
    (println (format "  unclassified: %5d" (or (:unclassified counts) 0)))
    (when (seq flagged)
      (println)
      (println "  Findings (non-:expected):")
      (doseq [d flagged]
        (println (format "  • %-32s %s   %s"
                         (name (:disc/rule-id d))
                         (name (:disc/classification d))
                         (:disc/classification-reason d)))))
    (println)))

(defn audit-after-materialize!
  "Run audit reconcile against the freshly materialized period and print
   a one-screen digest. Returns nil unconditionally.

   Required keys:
     :entity-type — :finance | :all | other (other → no-op)
     :period      — {:from \"YYYY-MM-DD\" :to \"YYYY-MM-DD\"} or nil → no-op
     :marketplace — :wb | :ozon | :ym | :all

   Optional keys:
     :tolerance   — {:abs N :rel N}; defaults to {:abs 100.0 :rel 0.01}"
  [{:keys [entity-type period marketplace tolerance]
    :or   {tolerance default-tolerance}}]
  (cond
    (not (audit-relevant? entity-type))
    nil

    (not (valid-period? period))
    nil

    :else
    (try
      (let [period* (normalize-period period)
            {:keys [report]} (audit/run-reconcile!
                               {:marketplace (or marketplace :all)
                                :period      period*
                                :tolerance   tolerance})]
        (print-digest (or marketplace :all) period* report)
        nil)
      (catch Throwable t
        (binding [*out* *err*]
          (println (format "  WARN: post-materialize audit failed (%s: %s) — skipping"
                           (.getName (class t))
                           (or (.getMessage t) ""))))
        nil))))
