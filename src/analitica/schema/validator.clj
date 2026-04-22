(ns analitica.schema.validator
  "Malli-backed validator for marketplace API responses.

   Two kinds of violations:
     :critical — missing required fields, type mismatches, nested errors.
                 These throw ex-info from `validate!`.
     :warning  — fields present in response but absent from contract
                 (schema drift; logged via mulog, not thrown).

   Aggregation: if the same violation (same path, same kind) occurs more
   than `aggregation-threshold` times across a response (e.g. in every
   element of a large array), it is returned as one violation with
   :occurrences N and :sample (first 3 paths)."
  (:require [analitica.schema.registry :as reg]
            [clojure.set :as set]
            [com.brunobonacci.mulog :as mu]
            [malli.core :as m]))

(def ^:dynamic *aggregation-threshold*
  "If the same violation repeats more than this many times, it is folded
   into a single aggregated violation with :occurrences. Dynamic so tests
   can tweak it."
  5)

;; ---------------------------------------------------------------------------
;; Violation construction
;; ---------------------------------------------------------------------------

(defn- path-segments
  "Convert a Malli error path to a plain vec of keywords/ints. Malli gives
   paths like [:x 0 :y]; we pass them through untouched — they are already
   JSON-path-ish."
  [errpath]
  (vec errpath))

(defn- humanize-schema [schema]
  ;; Malli schemas pprint reasonably; for message display we stringify.
  (try (pr-str schema) (catch Throwable _ "<schema>")))

(defn- error->violation
  "Turn one Malli error entry into a critical Violation map."
  [{:keys [in schema value type] :as _err}]
  {:violation/kind       (cond
                           (= type ::m/missing-key)   :required-missing
                           (= type ::m/extra-key)     :extra-field
                           :else                      :type-mismatch)
   :violation/path       (path-segments in)
   :violation/expected   (humanize-schema schema)
   :violation/actual     (if (= type ::m/missing-key)
                           nil
                           (humanize-schema value))
   :violation/severity   :critical
   :violation/occurrences 1
   :violation/sample     [(path-segments in)]})

;; ---------------------------------------------------------------------------
;; Aggregation (FR-009)
;; ---------------------------------------------------------------------------

(defn- aggregate-key
  "Two violations fold together if they share these attributes. We
   intentionally ignore numeric indices in `:path` so every
   `[0 :field]`, `[1 :field]` etc. collapse into one violation with
   path template `[:* :field]`."
  [{:keys [violation/kind violation/path violation/expected]}]
  [kind
   (mapv #(if (int? %) :* %) path)
   expected])

(defn aggregate-violations
  "Group identical violations by their structural key. If any group
   exceeds `*aggregation-threshold*`, emit a single aggregated violation;
   otherwise return each violation individually (for small counts the
   per-row detail is more useful than aggregation)."
  [violations]
  (->> (group-by aggregate-key violations)
       (mapcat (fn [[_ vs]]
                 (if (> (count vs) *aggregation-threshold*)
                   [(assoc (first vs)
                           :violation/occurrences (count vs)
                           :violation/sample (mapv :violation/path (take 3 vs))
                           :violation/path (:violation/path (first vs)))]
                   vs)))
       vec))

;; ---------------------------------------------------------------------------
;; Extra-field detection (top-level + one level of nesting)
;; ---------------------------------------------------------------------------

(defn- schema-map-keys
  "For a `[:map ...]` Malli schema, return the set of declared field keys.
   Returns nil if `schema` is not a map-type."
  [schema]
  (try
    (let [form (m/form schema)
          normalized (if (and (vector? form) (= :map (first form)))
                       form
                       nil)]
      (when normalized
        (->> (rest normalized)
             (remove map?) ;; drop the optional props map after :map
             (map (fn [entry]
                    (cond
                      (keyword? entry) entry
                      (vector? entry)  (first entry)
                      :else            nil)))
             (remove nil?)
             set)))
    (catch Throwable _ nil)))

(defn- detect-extras-at-path
  "If `expected` is a map-schema, compare `value`'s keys against the
   schema's declared keys; emit warning violations for extras. Returns
   a seq of violations."
  [expected-schema value parent-path]
  (when (map? value)
    (when-let [declared (schema-map-keys expected-schema)]
      (let [actual (set (keys value))
            extras (set/difference actual declared)]
        (mapv (fn [k]
                {:violation/kind       :extra-field
                 :violation/path       (conj parent-path k)
                 :violation/expected   "not declared in contract"
                 :violation/actual     (humanize-schema (get value k))
                 :violation/severity   :warning
                 :violation/occurrences 1
                 :violation/sample     [(conj parent-path k)]})
              extras)))))

(defn- extras-for-sequential-of-maps
  "For response shaped `[:sequential [:map ...]]`, look at the first
   element and check its keys against the inner map's keys. Assumes
   homogeneous arrays, which is the case for marketplace finance
   responses."
  [sequential-schema value]
  (when (and (sequential? value) (seq value))
    (let [form (m/form sequential-schema)]
      (when (and (vector? form) (= :sequential (first form)))
        (detect-extras-at-path (second form) (first value) [])))))

(defn detect-extras
  "Entry point: given a top-level schema and the full response value,
   emit warning violations for any extra keys observed. Handles two
   common shapes: `[:map …]` and `[:sequential [:map …]]`. Other
   shapes (nested :result wrappers) are currently not introspected —
   a future enhancement can add a walker."
  [schema response]
  (let [form (try (m/form schema) (catch Throwable _ nil))]
    (cond
      (and (vector? form) (= :map (first form)))
      (detect-extras-at-path schema response [])

      (and (vector? form) (= :sequential (first form)))
      (extras-for-sequential-of-maps schema response)

      :else nil)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn- compile-schema [contract]
  (let [schema (:contract/response-schema contract)]
    ;; `m/schema` compiles once; caching happens at the contract map level
    ;; (contracts are loaded once and not replaced during a process).
    (m/schema schema)))

(defn validate
  "Validate `response` against the contract's response-schema.

   Returns a ValidationResult map:
     {:result/endpoint-id :kw
      :result/status      :ok | :failed | :warned
      :result/violations  [Violation …]}

   `:ok`     — no violations of any kind.
   `:warned` — only warnings (extras); caller MAY log and continue.
   `:failed` — at least one critical violation; caller MUST stop."
  [contract response]
  (let [compiled (compile-schema contract)
        critical (when-let [err (m/explain compiled response)]
                   (mapv error->violation (:errors err)))
        warnings (detect-extras compiled response)
        all-viol (concat (or critical []) (or warnings []))
        aggregated (aggregate-violations all-viol)
        status (cond
                 (some #(= :critical (:violation/severity %)) aggregated) :failed
                 (seq aggregated) :warned
                 :else :ok)]
    {:result/endpoint-id (:endpoint/id contract)
     :result/status      status
     :result/violations  aggregated}))

(defn validate!
  "Validate and throw on critical failures. Log warnings via mulog
   without interrupting the flow. Returns the response on success
   (or warning-only result) — pipe-friendly."
  [endpoint-id response]
  (if-let [contract (reg/lookup endpoint-id)]
    (let [result (validate contract response)]
      (case (:result/status result)
        :ok      response
        :warned  (do (mu/log ::schema-drift
                             :endpoint-id endpoint-id
                             :violations  (:result/violations result))
                     response)
        :failed  (throw (ex-info (str "Schema validation failed for " endpoint-id)
                                 {:type       :schema-violation
                                  :endpoint-id endpoint-id
                                  :result     result}))))
    ;; No contract registered → no validation (FR-001: opt-in per endpoint).
    response))

;; ---------------------------------------------------------------------------
;; with-validation wrapper (T018 — part of Phase 3 impl)
;; ---------------------------------------------------------------------------

(defn with-validation
  "Perform `(fetch-fn)` to retrieve a response, persist it via
   `(save-raw-fn response)` BEFORE validating (FR-004 invariant),
   then `validate!` it against the contract registered for
   `endpoint-id`. Returns the validated response, or throws
   ex-info with :type :schema-violation on critical failures.

   If no contract is registered, behaves as a plain fetch + save.

   Usage:
     (with-validation :wb/report-detail-by-period
       #(wb-api/report-detail-by-period-all client from to)
       #(db/insert-raw! :wb :finance from to %))"
  [endpoint-id fetch-fn save-raw-fn]
  (let [response (fetch-fn)]
    (save-raw-fn response)
    (validate! endpoint-id response)))
