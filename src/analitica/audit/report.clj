(ns analitica.audit.report
  "Data layer + renderers for reconciliation reports.

   Document the shapes of Discrepancy and ReconciliationReport as plain maps
   (see specs/002-calculation-audit/data-model.md) and provide builders:

     make-discrepancy   — constructor with validation of required keys
     make-report        — aggregates discrepancies into a report with summary
                          (counts, total-abs-delta, top-N causes)
     stable-report-id   — deterministic sha256-derived id of the report's
                          configuration (marketplace + period + rules-applied +
                          tolerance-snapshot). Used for SC-006 reproducibility:
                          same inputs → same id, regardless of when the run
                          happened.
     render-stdout      — plain-text human-readable rendering (contract:
                          cli-audit.md §reconcile output).
     write-edn!         — machine-readable EDN serialisation to file."
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.java.io :as io])
  (:import [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; Discrepancy shape (reference; see data-model.md §Discrepancy)
;; ---------------------------------------------------------------------------
;;
;; {:disc/rule-id               :kw
;;  :disc/marketplace           :kw
;;  :disc/period                {:from "YYYY-MM-DD" :to "YYYY-MM-DD"}
;;  :disc/location              {:source-a :kw :source-b :kw :field :str-or-nil
;;                               :article :str-or-nil :operation :str-or-nil :row-id :str-or-nil}
;;  :disc/delta                 {:a N :b N :abs N :rel N :unit :rub|:qty|:count}
;;  :disc/classification        :expected | :suspicious | :unclassified
;;  :disc/classification-reason :str
;;  :disc/evidence              :any-or-nil}

(def ^:private classifications
  #{:expected :suspicious :unclassified})

(def ^:private delta-units
  #{:rub :qty :count})

(defn- default-location []
  {:source-a nil :source-b nil :field nil
   :article nil :operation nil :row-id nil})

(defn make-discrepancy
  "Build a discrepancy map, filling reasonable defaults and asserting required
   keys + invariants.

   Required inputs: :rule-id, :marketplace, :period, :delta (with :abs :rel :unit)
   and :classification. Everything else is optional.

   Invariants (see data-model.md §Discrepancy validation):
     - :delta.abs and :delta.rel must be non-negative.
     - :classification must be one of :expected / :suspicious / :unclassified.
     - If :classification is :expected or :unclassified, :classification-reason
       is required (non-blank string).
     - If :classification is :unclassified, :location.operation must be set."
  [{:keys [rule-id marketplace period location delta classification
           classification-reason evidence]}]
  (when-not (keyword? rule-id)
    (throw (ex-info "rule-id required (keyword)" {:rule-id rule-id})))
  (when-not (keyword? marketplace)
    (throw (ex-info "marketplace required (keyword)" {:marketplace marketplace})))
  (when-not (and (map? period) (:from period) (:to period))
    (throw (ex-info "period required {:from ... :to ...}" {:period period})))
  (when-not (map? delta)
    (throw (ex-info "delta required (map)" {:delta delta})))
  (let [{:keys [abs rel unit]} delta]
    (when-not (and (number? abs) (>= (double abs) 0))
      (throw (ex-info "delta.abs must be non-negative number" {:delta delta})))
    (when-not (and (number? rel) (>= (double rel) 0))
      (throw (ex-info "delta.rel must be non-negative number" {:delta delta})))
    (when-not (contains? delta-units unit)
      (throw (ex-info (str "delta.unit must be one of " delta-units)
                      {:delta delta}))))
  (when-not (contains? classifications classification)
    (throw (ex-info (str "classification must be one of " classifications)
                    {:classification classification})))
  (when (and (#{:expected :unclassified} classification)
             (or (nil? classification-reason) (str/blank? classification-reason)))
    (throw (ex-info "classification-reason required for :expected / :unclassified"
                    {:classification classification})))
  (let [loc (merge (default-location) location)]
    (when (and (= classification :unclassified)
               (str/blank? (or (:operation loc) "")))
      (throw (ex-info ":unclassified discrepancy must have location.operation"
                      {:location loc})))
    {:disc/rule-id               rule-id
     :disc/marketplace           marketplace
     :disc/period                period
     :disc/location              loc
     :disc/delta                 delta
     :disc/classification        classification
     :disc/classification-reason classification-reason
     :disc/evidence              evidence}))

;; ---------------------------------------------------------------------------
;; Report id — deterministic hash of configuration
;; ---------------------------------------------------------------------------

(defn- sha256-hex [s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes ^String s "UTF-8"))]
    (->> bs
         (map #(format "%02x" %))
         (apply str))))

(defn stable-report-id
  "Deterministic id of a reconciliation report, derived from its configuration.

   Two runs with the same marketplace, period, rules-applied, and tolerance
   produce the same id. `:captured-at` and the discrepancies themselves do NOT
   participate — per SC-006 the id identifies the configuration, not a
   particular run. Different data produces the same id but different body.

   Arguments:
     marketplace    — keyword, e.g. :wb, or :all
     period         — {:from \"YYYY-MM-DD\" :to \"YYYY-MM-DD\"}
     rules-applied  — collection of rule-id keywords; order does not matter
     tolerance      — {:rel N :abs N}"
  [marketplace period rules-applied tolerance]
  (let [rules-str (->> rules-applied
                       (map name)
                       sort
                       (str/join ","))
        input (str/join "|"
                        [(name marketplace)
                         (:from period)
                         (:to period)
                         rules-str
                         (format "%.6f" (double (:rel tolerance)))
                         (format "%.4f" (double (:abs tolerance)))])]
    (subs (sha256-hex input) 0 16)))

;; ---------------------------------------------------------------------------
;; Report construction
;; ---------------------------------------------------------------------------

(defn- summarise-discrepancies
  "Compute {:counts {...} :total-abs-delta {...} :top-causes [...]}."
  [discrepancies top-n]
  (let [by-class    (group-by :disc/classification discrepancies)
        counts      {:expected     (count (get by-class :expected []))
                     :suspicious   (count (get by-class :suspicious []))
                     :unclassified (count (get by-class :unclassified []))}
        sum-by-unit (fn [discs unit]
                      (->> discs
                           (filter #(= unit (get-in % [:disc/delta :unit])))
                           (map #(double (get-in % [:disc/delta :abs] 0)))
                           (reduce + 0.0)))
        total-abs   {:rub   (sum-by-unit discrepancies :rub)
                     :qty   (long (sum-by-unit discrepancies :qty))
                     :count (long (sum-by-unit discrepancies :count))}
        by-rule     (->> discrepancies
                         (group-by :disc/rule-id)
                         (map (fn [[rule-id discs]]
                                {:rule-id       rule-id
                                 :count         (count discs)
                                 :sum-abs-delta (reduce + 0.0
                                                        (map #(double (get-in % [:disc/delta :abs] 0))
                                                             discs))})))
        top         (->> by-rule
                         (sort-by (juxt #(- (:sum-abs-delta %)) #(- (:count %))))
                         (take top-n)
                         vec)]
    {:counts          counts
     :total-abs-delta total-abs
     :top-causes      top}))

(defn make-report
  "Aggregate discrepancies into a ReconciliationReport map.

   Inputs:
     :marketplace         — keyword
     :period              — {:from ... :to ...}
     :rules-applied       — seq of rule-id keywords
     :sources-available   — #{:raw-finance :agg-finance :sales :orders :bank-input ...}
     :discrepancies       — seq of discrepancy maps from make-discrepancy
     :tolerance-snapshot  — {:rel N :abs N}
     :top-n               — int (default 10) — how many top causes to include in summary
     :captured-at         — ISO-8601 timestamp string; NOT part of :report/id

   Invariants (see data-model.md):
     - sum(counts) == count(discrepancies)
     - :report/id is deterministic for the same marketplace/period/rules/tolerance."
  [{:keys [marketplace period rules-applied sources-available discrepancies
           tolerance-snapshot top-n captured-at]
    :or   {top-n 10}}]
  (let [rules-vec (vec (sort rules-applied))
        report-id (stable-report-id marketplace period rules-vec tolerance-snapshot)
        disc-vec  (vec discrepancies)
        summary   (summarise-discrepancies disc-vec top-n)]
    ;; Sanity: sum of counts matches discrepancies vec length.
    (let [total (+ (get-in summary [:counts :expected])
                   (get-in summary [:counts :suspicious])
                   (get-in summary [:counts :unclassified]))]
      (when (not= total (count disc-vec))
        (throw (ex-info (str "Report counts mismatch: sum=" total " vs " (count disc-vec))
                        {:summary summary :n (count disc-vec)}))))
    {:report/id                 report-id
     :report/marketplace        marketplace
     :report/period             period
     :report/rules-applied      rules-vec
     :report/sources-available  (set sources-available)
     :report/discrepancies      disc-vec
     :report/summary            summary
     :report/captured-at        captured-at
     :report/tolerance-snapshot tolerance-snapshot}))

;; ---------------------------------------------------------------------------
;; Rendering — stdout (plain text)
;; ---------------------------------------------------------------------------

(defn- fmt-rub [n] (format "%.2f₽" (double (or n 0))))

(defn- render-header
  [{:report/keys [marketplace period rules-applied sources-available
                  tolerance-snapshot captured-at]}]
  (str
    "Reconciliation report\n"
    "Period: " (:from period) " .. " (:to period) "\n"
    "Marketplace: " (name marketplace) "\n"
    "Rules applied: " (count rules-applied)
    "  | Sources available: "
    (if (empty? sources-available) "(none)"
        (str/join ", " (->> sources-available (map name) sort)))
    "\n"
    "Tolerance: " (format "%.1f%%" (* 100.0 (double (:rel tolerance-snapshot))))
    " or " (format "%.2f₽" (double (:abs tolerance-snapshot)))
    "\n"
    (when captured-at (str "Captured-at: " captured-at "\n"))))

(defn- render-counts
  [{{:keys [counts total-abs-delta]} :report/summary}]
  (let [{:keys [expected suspicious unclassified]} counts
        rub-delta (double (or (:rub total-abs-delta) 0))]
    (str
      "\nCounts:  expected=" expected
      "  suspicious=" suspicious
      "  unclassified=" unclassified
      "\nTotal delta: " (fmt-rub rub-delta) "\n")))

(defn- render-top-causes
  [{{:keys [top-causes]} :report/summary} top-n]
  (if (empty? top-causes)
    ""
    (let [take-n (take top-n top-causes)]
      (str "\nTop causes:\n"
           (str/join "\n"
                     (map-indexed
                       (fn [idx {:keys [rule-id count sum-abs-delta]}]
                         (format "  %d. %s (%d cases, %s)"
                                 (inc idx) (name rule-id) count
                                 (fmt-rub sum-abs-delta)))
                       take-n))
           "\n"))))

(defn- render-suspicious
  [{:report/keys [discrepancies]}]
  (let [susp (filter #(= :suspicious (:disc/classification %)) discrepancies)]
    (if (empty? susp)
      ""
      (str "\nSuspicious discrepancies:\n"
           (str/join "\n"
                     (map (fn [{:disc/keys [rule-id location delta classification-reason]}]
                            (let [{:keys [article operation source-a source-b field]} location
                                  {:keys [a b abs rel unit]} delta]
                              (format
                                "  [%s] %s%s%s a=%s b=%s Δ=%s (%.2f%%)\n    reason: %s"
                                (name rule-id)
                                (if article (str "article=" article) "")
                                (if operation (str " operation=" operation) "")
                                (if field (str " field=" field) "")
                                (str a) (str b)
                                (case unit :rub (fmt-rub abs) (format "%.2f" (double abs)))
                                (* 100.0 (double rel))
                                (or classification-reason ""))))
                          susp))
           "\n"))))

(defn- render-unclassified
  [{:report/keys [discrepancies]}]
  (let [uncls (filter #(= :unclassified (:disc/classification %)) discrepancies)]
    (if (empty? uncls)
      ""
      (str "\nUnclassified operations (new data types — require developer attention):\n"
           (str/join "\n"
                     (map (fn [d]
                            (format "  operation=%s rows=%s reason=%s"
                                    (get-in d [:disc/location :operation])
                                    (str (get-in d [:disc/delta :abs]))
                                    (:disc/classification-reason d)))
                          uncls))
           "\n"))))

(defn- render-verdict
  [{:report/keys [summary]}]
  (let [{:keys [counts]} summary
        s (:suspicious counts)
        u (:unclassified counts)]
    (cond
      (pos? u) (format "\nVerdict: %d unclassified operation types detected — add to known-operations or investigate." u)
      (pos? s) (format "\nVerdict: %d suspicious cases require investigation." s)
      :else    "\nVerdict: clean (no suspicious or unclassified discrepancies).")))

(defn render-stdout
  "Render a ReconciliationReport as a single string for the CLI.

   Honours `top-n` for the top-causes section. The result is deterministic
   for the same report (modulo the `:report/captured-at` line, which the
   caller can strip when comparing two runs)."
  [report top-n]
  (let [top-n (or top-n 10)
        {:report/keys [sources-available]} report
        ;; Empty-period heuristic: no data-dependent sources means no raw
        ;; data was found for the marketplace/period combination. Code-only
        ;; sources (:pnl-code from :tax-absence) do not count.
        data-sources (disj (set sources-available) :pnl-code)
        empty-period? (empty? data-sources)]
    (str
      (render-header report)
      (if empty-period?
        (str "\nNo data for marketplace=" (name (:report/marketplace report))
             " in period=" (:from (:report/period report))
             ".." (:to (:report/period report)) "\n")
        (str
          (render-counts report)
          (render-top-causes report top-n)
          (render-suspicious report)
          (render-unclassified report)
          (render-verdict report))))))

;; ---------------------------------------------------------------------------
;; write-edn! — machine-readable persistence
;; ---------------------------------------------------------------------------

(defn write-edn!
  "Serialise `report` as pretty-printed EDN to `path`. Overwrites if exists."
  [report ^String path]
  (let [parent (.getParentFile (io/file path))]
    (when (and parent (not (.exists parent)))
      (.mkdirs parent)))
  (with-open [w (io/writer path :encoding "UTF-8")]
    (binding [*out* w]
      (pp/pprint report)))
  path)
